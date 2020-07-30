/*
 * This file is part of LibEuFin.
 * Copyright (C) 2019 Stanisci and Dold.

 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.

 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.

 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

/**
 * This is the main "EBICS library interface".  Functions here are stateless helpers
 * used to implement both an EBICS server and EBICS client.
 */

package tech.libeufin.util

import io.ktor.http.HttpStatusCode
import tech.libeufin.util.ebics_h004.*
import tech.libeufin.util.ebics_hev.HEVRequest
import tech.libeufin.util.ebics_hev.HEVResponse
import tech.libeufin.util.ebics_s001.UserSignatureData
import java.math.BigInteger
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.time.ZonedDateTime
import java.util.*
import java.util.zip.DeflaterInputStream
import javax.xml.datatype.DatatypeFactory
import javax.xml.datatype.XMLGregorianCalendar

data class EbicsProtocolError(
    val httpStatusCode: HttpStatusCode,
    val reason: String
) : Exception(reason)

data class EbicsDateRange(val start: ZonedDateTime, val end: ZonedDateTime)

sealed class EbicsOrderParams

data class EbicsStandardOrderParams(
    val dateRange: EbicsDateRange? = null
) : EbicsOrderParams()

data class EbicsGenericOrderParams(
    val params: Map<String, String> = mapOf()
) : EbicsOrderParams()

enum class EbicsInitState {
    SENT, NOT_SENT, UNKNOWN
}

/**
 * This class is a mere container that keeps data found
 * in the database and that is further needed to sign / verify
 * / make messages.  And not all the values are needed all
 * the time.
 */
data class EbicsClientSubscriberDetails(
    val partnerId: String,
    val userId: String,
    var bankAuthPub: RSAPublicKey?,
    var bankEncPub: RSAPublicKey?,
    val ebicsUrl: String,
    val hostId: String,
    val customerEncPriv: RSAPrivateCrtKey,
    val customerAuthPriv: RSAPrivateCrtKey,
    val customerSignPriv: RSAPrivateCrtKey,
    val ebicsIniState: EbicsInitState,
    val ebicsHiaState: EbicsInitState
)

/**
 * @param size in bits
 */
private fun getNonce(size: Int): ByteArray {
    val sr = SecureRandom()
    val ret = ByteArray(size / 8)
    sr.nextBytes(ret)
    return ret
}

private fun getXmlDate(d: ZonedDateTime): XMLGregorianCalendar {
    return DatatypeFactory.newInstance()
        .newXMLGregorianCalendar(d.year, d.monthValue, d.dayOfMonth, 0, 0, 0, 0, d.offset.totalSeconds / 60)
}

private fun makeOrderParams(orderParams: EbicsOrderParams): EbicsRequest.OrderParams {
    return when (orderParams) {
        is EbicsStandardOrderParams -> {
            EbicsRequest.StandardOrderParams().apply {
                val r = orderParams.dateRange
                if (r != null) {
                    this.dateRange = EbicsRequest.DateRange().apply {
                        this.start = getXmlDate(r.start)
                        this.end = getXmlDate(r.end)
                    }
                }
            }
        }
        is EbicsGenericOrderParams -> {
            EbicsRequest.GenericOrderParams().apply {
                this.parameterList = orderParams.params.map { entry ->
                    EbicsTypes.Parameter().apply {
                        this.name = entry.key
                        this.value = entry.value
                        this.type = "string"
                    }
                }
            }
        }
    }
}


private fun signOrder(
    orderBlob: ByteArray,
    signKey: RSAPrivateCrtKey,
    partnerId: String,
    userId: String
): UserSignatureData {
    val ES_signature = CryptoUtil.signEbicsA006(
        CryptoUtil.digestEbicsOrderA006(orderBlob),
        signKey
    )
    val userSignatureData = UserSignatureData().apply {
        orderSignatureList = listOf(
            UserSignatureData.OrderSignatureData().apply {
                signatureVersion = "A006"
                signatureValue = ES_signature
                partnerID = partnerId
                userID = userId
            }
        )
    }
    return userSignatureData
}


fun createEbicsRequestForDownloadReceipt(
    subscriberDetails: EbicsClientSubscriberDetails,
    transactionID: String
): String {
    val req = EbicsRequest.createForDownloadReceiptPhase(transactionID, subscriberDetails.hostId)
    val doc = XMLUtil.convertJaxbToDocument(req)
    XMLUtil.signEbicsDocument(doc, subscriberDetails.customerAuthPriv)
    return XMLUtil.convertDomToString(doc)
}

data class PreparedUploadData(
    val transactionKey: ByteArray,
    val userSignatureDataEncrypted: ByteArray,
    val encryptedPayloadChunks: List<String>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PreparedUploadData

        if (!transactionKey.contentEquals(other.transactionKey)) return false
        if (!userSignatureDataEncrypted.contentEquals(other.userSignatureDataEncrypted)) return false
        if (encryptedPayloadChunks != other.encryptedPayloadChunks) return false

        return true
    }

    override fun hashCode(): Int {
        var result = transactionKey.contentHashCode()
        result = 31 * result + userSignatureDataEncrypted.contentHashCode()
        result = 31 * result + encryptedPayloadChunks.hashCode()
        return result
    }
}

fun prepareUploadPayload(subscriberDetails: EbicsClientSubscriberDetails, payload: ByteArray): PreparedUploadData {
    val userSignatureDataEncrypted = CryptoUtil.encryptEbicsE002(
        EbicsOrderUtil.encodeOrderDataXml(
            signOrder(
                payload,
                subscriberDetails.customerSignPriv,
                subscriberDetails.partnerId,
                subscriberDetails.userId
            )
        ),
        subscriberDetails.bankEncPub!!
    )
    val compressedInnerPayload = DeflaterInputStream(
        payload.inputStream()
    ).use { it.readAllBytes() }
    val encryptedPayload = CryptoUtil.encryptEbicsE002withTransactionKey(
        compressedInnerPayload,
        subscriberDetails.bankEncPub!!,
        userSignatureDataEncrypted.plainTransactionKey!!
    )
    val encodedEncryptedPayload = Base64.getEncoder().encodeToString(encryptedPayload.encryptedData)
    return PreparedUploadData(
        userSignatureDataEncrypted.encryptedTransactionKey,
        userSignatureDataEncrypted.encryptedData,
        listOf(encodedEncryptedPayload)
    )
}

/**
 * Create an EBICS request for the initialization phase of an upload EBICS transaction.
 *
 * The payload is only passed to generate the signature.
 */
fun createEbicsRequestForUploadInitialization(
    subscriberDetails: EbicsClientSubscriberDetails,
    orderType: String,
    orderParams: EbicsOrderParams,
    preparedUploadData: PreparedUploadData
): String {
    val req = EbicsRequest.createForUploadInitializationPhase(
        preparedUploadData.transactionKey,
        preparedUploadData.userSignatureDataEncrypted,
        subscriberDetails.hostId,
        getNonce(128),
        subscriberDetails.partnerId,
        subscriberDetails.userId,
        DatatypeFactory.newInstance().newXMLGregorianCalendar(GregorianCalendar()),
        subscriberDetails.bankAuthPub!!,
        subscriberDetails.bankEncPub!!,
        BigInteger.ONE,
        orderType,
        makeOrderParams(orderParams)
    )
    val doc = XMLUtil.convertJaxbToDocument(req)
    XMLUtil.signEbicsDocument(doc, subscriberDetails.customerAuthPriv)
    return XMLUtil.convertDomToString(doc)
}


fun createEbicsRequestForDownloadInitialization(
    subscriberDetails: EbicsClientSubscriberDetails,
    orderType: String,
    orderParams: EbicsOrderParams
): String {
    val req = EbicsRequest.createForDownloadInitializationPhase(
        subscriberDetails.userId,
        subscriberDetails.partnerId,
        subscriberDetails.hostId,
        getNonce(128),
        DatatypeFactory.newInstance().newXMLGregorianCalendar(GregorianCalendar()),
        subscriberDetails.bankEncPub ?: throw EbicsProtocolError(
            HttpStatusCode.BadRequest,
            "Invalid subscriber state 'bankEncPub' missing, please send HPB first"
        ),
        subscriberDetails.bankAuthPub ?: throw EbicsProtocolError(
            HttpStatusCode.BadRequest,
            "Invalid subscriber state 'bankAuthPub' missing, please send HPB first"
        ),
        orderType,
        makeOrderParams(orderParams)
    )
    val doc = XMLUtil.convertJaxbToDocument(req)
    XMLUtil.signEbicsDocument(doc, subscriberDetails.customerAuthPriv)
    return XMLUtil.convertDomToString(doc)
}

fun createEbicsRequestForDownloadTransferPhase(
    subscriberDetails: EbicsClientSubscriberDetails,
    transactionID: String,
    segmentNumber: Int,
    numSegments: Int
): String {
    val req = EbicsRequest.createForDownloadTransferPhase(
        subscriberDetails.hostId,
        transactionID,
        segmentNumber,
        numSegments
    )
    val doc = XMLUtil.convertJaxbToDocument(req)
    XMLUtil.signEbicsDocument(doc, subscriberDetails.customerAuthPriv)
    return XMLUtil.convertDomToString(doc)
}


fun createEbicsRequestForUploadTransferPhase(
    subscriberDetails: EbicsClientSubscriberDetails,
    transactionID: String,
    preparedUploadData: PreparedUploadData,
    chunkIndex: Int
): String {
    val req = EbicsRequest.createForUploadTransferPhase(
        subscriberDetails.hostId,
        transactionID,
        // chunks are 1-indexed
        BigInteger.valueOf(chunkIndex.toLong() + 1),
        preparedUploadData.encryptedPayloadChunks[chunkIndex]
    )
    val doc = XMLUtil.convertJaxbToDocument(req)
    XMLUtil.signEbicsDocument(doc, subscriberDetails.customerAuthPriv)
    return XMLUtil.convertDomToString(doc)
}

data class DataEncryptionInfo(
    val transactionKey: ByteArray,
    val bankPubDigest: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DataEncryptionInfo

        if (!transactionKey.contentEquals(other.transactionKey)) return false
        if (!bankPubDigest.contentEquals(other.bankPubDigest)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = transactionKey.contentHashCode()
        result = 31 * result + bankPubDigest.contentHashCode()
        return result
    }
}


@Suppress("SpellCheckingInspection")
enum class EbicsReturnCode(val errorCode: String) {
    EBICS_OK("000000"),
    EBICS_DOWNLOAD_POSTPROCESS_DONE("011000"),
    EBICS_DOWNLOAD_POSTPROCESS_SKIPPED("011001"),
    EBICS_TX_SEGMENT_NUMBER_UNDERRUN("011101"),
    EBICS_INVALID_USER_OR_USER_STATE("091002"),
    EBICS_PROCESSING_ERROR("091116"),
    EBICS_NO_DOWNLOAD_DATA_AVAILABLE("090005");

    companion object {
        fun lookup(errorCode: String): EbicsReturnCode {
            for (x in values()) {
                if (x.errorCode == errorCode) {
                    return x
                }
            }
            throw EbicsProtocolError(HttpStatusCode.InternalServerError, "Unknown EBICS status code: $errorCode")
        }
    }
}

data class EbicsResponseContent(
    val transactionID: String?,
    val dataEncryptionInfo: DataEncryptionInfo?,
    val orderDataEncChunk: String?,
    val technicalReturnCode: EbicsReturnCode,
    val bankReturnCode: EbicsReturnCode,
    val segmentNumber: Int?,
    // Only present in init phase
    val numSegments: Int?
)

data class EbicsKeyManagementResponseContent(
    val technicalReturnCode: EbicsReturnCode,
    val bankReturnCode: EbicsReturnCode?,
    val orderData: ByteArray?
)

fun parseAndDecryptEbicsKeyManagementResponse(
    subscriberDetails: EbicsClientSubscriberDetails,
    responseStr: String
): EbicsKeyManagementResponseContent {
    val resp = try {
        XMLUtil.convertStringToJaxb<EbicsKeyManagementResponse>(responseStr)
    } catch (e: Exception) {
        throw EbicsProtocolError(HttpStatusCode.InternalServerError, "Invalid XML received from bank")
    }
    val retCode = EbicsReturnCode.lookup(resp.value.header.mutable.returnCode)

    val daeXml = resp.value.body.dataTransfer?.dataEncryptionInfo
    val orderData = if (daeXml != null) {
        val dae = DataEncryptionInfo(daeXml.transactionKey, daeXml.encryptionPubKeyDigest.value)
        val encOrderData = resp.value.body.dataTransfer?.orderData?.value ?: throw EbicsProtocolError(
            HttpStatusCode.InternalServerError, "Invalid XML/orderData received from bank"
        )
        decryptAndDecompressResponse(subscriberDetails, dae, listOf(encOrderData))
    } else {
        null
    }

    val bankReturnCodeStr = resp.value.body.returnCode.value
    val bankReturnCode = EbicsReturnCode.lookup(bankReturnCodeStr)

    return EbicsKeyManagementResponseContent(retCode, bankReturnCode, orderData)
}

class HpbResponseData(
    val hostID: String,
    val encryptionPubKey: RSAPublicKey,
    val encryptionVersion: String,
    val authenticationPubKey: RSAPublicKey,
    val authenticationVersion: String
)

fun parseEbicsHpbOrder(orderDataRaw: ByteArray): HpbResponseData {
    val resp = try {
        XMLUtil.convertStringToJaxb<HPBResponseOrderData>(orderDataRaw.toString(Charsets.UTF_8))
    } catch (e: Exception) {
        throw EbicsProtocolError(HttpStatusCode.InternalServerError, "Invalid XML (as HPB response) received from bank")
    }
    val encPubKey = CryptoUtil.loadRsaPublicKeyFromComponents(
        resp.value.encryptionPubKeyInfo.pubKeyValue.rsaKeyValue.modulus,
        resp.value.encryptionPubKeyInfo.pubKeyValue.rsaKeyValue.exponent
    )
    val authPubKey = CryptoUtil.loadRsaPublicKeyFromComponents(
        resp.value.authenticationPubKeyInfo.pubKeyValue.rsaKeyValue.modulus,
        resp.value.authenticationPubKeyInfo.pubKeyValue.rsaKeyValue.exponent
    )
    return HpbResponseData(
        hostID = resp.value.hostID,
        encryptionPubKey = encPubKey,
        encryptionVersion = resp.value.encryptionPubKeyInfo.encryptionVersion,
        authenticationPubKey = authPubKey,
        authenticationVersion = resp.value.authenticationPubKeyInfo.authenticationVersion
    )
}

fun parseAndValidateEbicsResponse(
    subscriberDetails: EbicsClientSubscriberDetails,
    responseStr: String
): EbicsResponseContent {
    val responseDocument = try {
        XMLUtil.parseStringIntoDom(responseStr)
    } catch (e: Exception) {
        throw EbicsProtocolError(
            HttpStatusCode.InternalServerError,
            "Invalid XML (as EbicsResponse) received from bank: $responseStr"
        )
    }

    if (!XMLUtil.verifyEbicsDocument(
            responseDocument,
            subscriberDetails.bankAuthPub ?: throw EbicsProtocolError(
                HttpStatusCode.BadRequest,
                "Invalid subscriber state: bankAuthPub missing, please send HPB first"
            )
        )
    ) {
        throw EbicsProtocolError(HttpStatusCode.InternalServerError, "Bank's signature validation failed")
    }
    val resp = try {
        XMLUtil.convertStringToJaxb<EbicsResponse>(responseStr)
    } catch (e: Exception) {
        throw EbicsProtocolError(
            HttpStatusCode.InternalServerError,
            "Could not transform string-response from bank into JAXB"
        )
    }

    val bankReturnCodeStr = resp.value.body.returnCode.value
    val bankReturnCode = EbicsReturnCode.lookup(bankReturnCodeStr)

    val techReturnCodeStr = resp.value.header.mutable.returnCode
    val techReturnCode = EbicsReturnCode.lookup(techReturnCodeStr)

    val daeXml = resp.value.body.dataTransfer?.dataEncryptionInfo
    val dataEncryptionInfo = if (daeXml == null) {
        null
    } else {
        DataEncryptionInfo(daeXml.transactionKey, daeXml.encryptionPubKeyDigest.value)
    }

    return EbicsResponseContent(
        transactionID = resp.value.header._static.transactionID,
        bankReturnCode = bankReturnCode,
        technicalReturnCode = techReturnCode,
        orderDataEncChunk = resp.value.body.dataTransfer?.orderData?.value,
        dataEncryptionInfo = dataEncryptionInfo,
        numSegments = resp.value.header._static.numSegments?.toInt(),
        segmentNumber = resp.value.header.mutable.segmentNumber?.value?.toInt()
    )
}

/**
 * Get the private key that matches the given public key digest.
 */
fun getDecryptionKey(subscriberDetails: EbicsClientSubscriberDetails, pubDigest: ByteArray): RSAPrivateCrtKey {
    val authPub = CryptoUtil.getRsaPublicFromPrivate(subscriberDetails.customerAuthPriv)
    val encPub = CryptoUtil.getRsaPublicFromPrivate(subscriberDetails.customerEncPriv)
    val authPubDigest = CryptoUtil.getEbicsPublicKeyHash(authPub)
    val encPubDigest = CryptoUtil.getEbicsPublicKeyHash(encPub)
    if (pubDigest.contentEquals(authPubDigest)) {
        return subscriberDetails.customerAuthPriv
    }
    if (pubDigest.contentEquals(encPubDigest)) {
        return subscriberDetails.customerEncPriv
    }
    throw EbicsProtocolError(HttpStatusCode.NotFound, "Could not find customer's public key")
}

/**
 * Wrapper around the lower decryption routine, that takes a EBICS response
 * object containing a encrypted payload, and return the plain version of it
 * (including decompression).
 */
fun decryptAndDecompressResponse(
    subscriberDetails: EbicsClientSubscriberDetails,
    encryptionInfo: DataEncryptionInfo,
    chunks: List<String>
): ByteArray {
    val privateKey = getDecryptionKey(subscriberDetails, encryptionInfo.bankPubDigest)
    val buf = StringBuilder()
    chunks.forEach { buf.append(it) }
    val decoded = Base64.getDecoder().decode(buf.toString())
    val er = CryptoUtil.EncryptionResult(
        encryptionInfo.transactionKey,
        encryptionInfo.bankPubDigest,
        decoded
    )
    val dataCompr = CryptoUtil.decryptEbicsE002(
        er,
        privateKey
    )
    return EbicsOrderUtil.decodeOrderData(dataCompr)
}

data class EbicsVersionSpec(
    val protocol: String,
    val version: String
)

data class EbicsHevDetails(
    val versions: List<EbicsVersionSpec>
)

fun makeEbicsHEVRequest(subscriberDetails: EbicsClientSubscriberDetails): String {
    val req = HEVRequest().apply {
        hostId = subscriberDetails.hostId
    }
    val doc = XMLUtil.convertJaxbToDocument(req)
    return XMLUtil.convertDomToString(doc)
}

fun makeEbicsHEVRequestRaw(hostID: String): String {
    val h = hostID
    val req = HEVRequest().apply {
        hostId = h
    }
    val doc = XMLUtil.convertJaxbToDocument(req)
    return XMLUtil.convertDomToString(doc)
}

fun parseEbicsHEVResponse(respStr: String): EbicsHevDetails {
    val resp = try {
        XMLUtil.convertStringToJaxb<HEVResponse>(respStr)
    } catch (e: Exception) {
        logger.error("Exception while parsing HEV response", e)
        throw EbicsProtocolError(HttpStatusCode.InternalServerError, "Invalid HEV received from bank")
    }
    val versions = resp.value.versionNumber.map { versionNumber ->
        EbicsVersionSpec(versionNumber.protocolVersion, versionNumber.value)
    }
    return EbicsHevDetails(versions)
}

fun makeEbicsIniRequest(subscriberDetails: EbicsClientSubscriberDetails): String {
    val iniRequest = EbicsUnsecuredRequest.createIni(
        subscriberDetails.hostId,
        subscriberDetails.userId,
        subscriberDetails.partnerId,
        subscriberDetails.customerSignPriv
    )
    val doc = XMLUtil.convertJaxbToDocument(iniRequest)
    return XMLUtil.convertDomToString(doc)
}

fun makeEbicsHiaRequest(subscriberDetails: EbicsClientSubscriberDetails): String {
    val hiaRequest = EbicsUnsecuredRequest.createHia(
        subscriberDetails.hostId,
        subscriberDetails.userId,
        subscriberDetails.partnerId,
        subscriberDetails.customerAuthPriv,
        subscriberDetails.customerEncPriv
    )
    val doc = XMLUtil.convertJaxbToDocument(hiaRequest)
    return XMLUtil.convertDomToString(doc)
}

fun makeEbicsHpbRequest(subscriberDetails: EbicsClientSubscriberDetails): String {
    val hpbRequest = EbicsNpkdRequest.createRequest(
        subscriberDetails.hostId,
        subscriberDetails.partnerId,
        subscriberDetails.userId,
        getNonce(128),
        DatatypeFactory.newInstance().newXMLGregorianCalendar(GregorianCalendar())
    )
    val doc = XMLUtil.convertJaxbToDocument(hpbRequest)
    XMLUtil.signEbicsDocument(doc, subscriberDetails.customerAuthPriv)
    return XMLUtil.convertDomToString(doc)
}