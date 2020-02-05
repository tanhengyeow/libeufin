package tech.libeufin.nexus

import io.ktor.application.call
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.response.respondText
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.EbicsOrderUtil
import tech.libeufin.util.ebics_h004.EbicsRequest
import tech.libeufin.util.ebics_h004.EbicsResponse
import tech.libeufin.util.ebics_h004.EbicsTypes
import tech.libeufin.util.getGregorianCalendarNow
import java.lang.StringBuilder
import java.math.BigInteger
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.util.*
import java.util.zip.DeflaterInputStream
import javax.xml.datatype.XMLGregorianCalendar

/**
 * This class is a mere container that keeps data found
 * in the database and that is further needed to sign / verify
 * / make messages.  And not all the values are needed all
 * the time.
 */
data class EbicsSubscriberDetails(
    val partnerId: String,
    val userId: String,
    var bankAuthPub: RSAPublicKey?,
    var bankEncPub: RSAPublicKey?,
    // needed to send the message
    val ebicsUrl: String,
    // needed to craft further messages
    val hostId: String,
    // needed to decrypt data coming from the bank
    val customerEncPriv: RSAPrivateCrtKey,
    // needed to sign documents
    val customerAuthPriv: RSAPrivateCrtKey,
    val customerSignPriv: RSAPrivateCrtKey
)


fun createDownloadInitializationPhase(
    subscriberData: EbicsSubscriberDetails,
    orderType: String,
    nonce: ByteArray,
    date: XMLGregorianCalendar
): EbicsRequest {
    return EbicsRequest.createForDownloadInitializationPhase(
        subscriberData.userId,
        subscriberData.partnerId,
        subscriberData.hostId,
        nonce,
        date,
        subscriberData.bankEncPub ?: throw BankKeyMissing(
            HttpStatusCode.PreconditionFailed
        ),
        subscriberData.bankAuthPub ?: throw BankKeyMissing(
            HttpStatusCode.PreconditionFailed
        ),
        orderType
    )
}


fun createUploadInitializationPhase(
    subscriberData: EbicsSubscriberDetails,
    orderType: String,
    cryptoBundle: CryptoUtil.EncryptionResult
): EbicsRequest {
    return EbicsRequest.createForUploadInitializationPhase(
        cryptoBundle,
        subscriberData.hostId,
        getNonce(128),
        subscriberData.partnerId,
        subscriberData.userId,
        getGregorianCalendarNow(),
        subscriberData.bankAuthPub!!,
        subscriberData.bankEncPub!!,
        BigInteger.ONE,
        orderType
    )
}


/**
 * Wrapper around the lower decryption routine, that takes a EBICS response
 * object containing a encrypted payload, and return the plain version of it
 * (including decompression).
 */
fun decryptAndDecompressResponse(chunks: List<String>, transactionKey: ByteArray, privateKey: RSAPrivateCrtKey, pubDigest: ByteArray): ByteArray {
    val buf = StringBuilder()
    chunks.forEach { buf.append(it) }
    val decoded = Base64.getDecoder().decode(buf.toString())
    val er = CryptoUtil.EncryptionResult(
        transactionKey,
        pubDigest,
        decoded
    )
    val dataCompr = CryptoUtil.decryptEbicsE002(
        er,
        privateKey
    )
    return EbicsOrderUtil.decodeOrderData(dataCompr)
}


/**
 * Get the private key that matches the given public key digest.
 */
fun getDecryptionKey(subscriberDetails: EbicsSubscriberDetails, pubDigest: ByteArray): RSAPrivateCrtKey {
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
    throw Exception("no matching private key to decrypt response")
}


/**
 * Do an EBICS download transaction.  This includes the initialization phase, transaction phase
 * and receipt phase.
 */
suspend fun doEbicsDownloadTransaction(
    client: HttpClient,
    subscriberDetails: EbicsSubscriberDetails,
    orderType: String
): ByteArray {
    val initDownloadRequest = createDownloadInitializationPhase(
        subscriberDetails,
        orderType,
        getNonce(128),
        getGregorianCalendarNow()
    )
    val payloadChunks = LinkedList<String>();
    val initResponse = client.postToBankSigned<EbicsRequest, EbicsResponse>(
        subscriberDetails.ebicsUrl,
        initDownloadRequest,
        subscriberDetails.customerAuthPriv
    )
    if (initResponse.value.body.returnCode.value != "000000") {
        throw EbicsError(initResponse.value.body.returnCode.value)
    }
    val initDataTransfer = initResponse.value.body.dataTransfer
        ?: throw ProtocolViolationError("initial response for download transaction does not contain data transfer")
    val dataEncryptionInfo = initDataTransfer.dataEncryptionInfo
        ?: throw ProtocolViolationError("initial response for download transaction does not contain date encryption info")
    val initOrderData = initDataTransfer.orderData.value
    // FIXME: Also verify that algorithm matches!
    val decryptionKey = getDecryptionKey(subscriberDetails, dataEncryptionInfo.encryptionPubKeyDigest.value)
    payloadChunks.add(initOrderData)
    val respPayload = decryptAndDecompressResponse(
        payloadChunks,
        dataEncryptionInfo.transactionKey,
        decryptionKey,
        dataEncryptionInfo.encryptionPubKeyDigest.value
    )
    val ackRequest = EbicsRequest.createForDownloadReceiptPhase(
        initResponse.value.header._static.transactionID ?: throw BankInvalidResponse(
            HttpStatusCode.ExpectationFailed
        ),
        subscriberDetails.hostId
    )
    val ackResponse = client.postToBankSignedAndVerify<EbicsRequest, EbicsResponse>(
        subscriberDetails.ebicsUrl,
        ackRequest,
        subscriberDetails.bankAuthPub ?: throw BankKeyMissing(
            HttpStatusCode.PreconditionFailed
        ),
        subscriberDetails.customerAuthPriv
    )
    if (ackResponse.value.body.returnCode.value != "000000") {
        throw EbicsError(ackResponse.value.body.returnCode.value)
    }
    return respPayload
}


suspend fun doEbicsUploadTransaction(
    client: HttpClient,
    subscriberDetails: EbicsSubscriberDetails,
    orderType: String,
    payload: ByteArray
) {
    if (subscriberDetails.bankEncPub == null) {
        throw InvalidSubscriberStateError("bank encryption key unknown, request HPB first")
    }
    val usd_encrypted = CryptoUtil.encryptEbicsE002(
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
    val response = client.postToBankSignedAndVerify<EbicsRequest, EbicsResponse>(
        subscriberDetails.ebicsUrl,
        createUploadInitializationPhase(
            subscriberDetails,
            orderType,
            usd_encrypted
        ),
        subscriberDetails.bankAuthPub!!,
        subscriberDetails.customerAuthPriv
    )
    if (response.value.header.mutable.returnCode != "000000") {
        throw EbicsError(response.value.header.mutable.returnCode)
    }
    if (response.value.body.returnCode.value != "000000") {
        throw EbicsError(response.value.body.returnCode.value)
    }
    logger.debug("INIT phase passed!")
    /* now send actual payload */
    val compressedInnerPayload = DeflaterInputStream(
        payload.inputStream()
    ).use { it.readAllBytes() }
    val encryptedPayload = CryptoUtil.encryptEbicsE002withTransactionKey(
        compressedInnerPayload,
        subscriberDetails.bankEncPub!!,
        usd_encrypted.plainTransactionKey!!
    )
    val tmp = EbicsRequest.createForUploadTransferPhase(
        subscriberDetails.hostId,
        response.value.header._static.transactionID!!,
        BigInteger.ONE,
        encryptedPayload.encryptedData
    )
    val responseTransaction = client.postToBankSignedAndVerify<EbicsRequest, EbicsResponse>(
        subscriberDetails.ebicsUrl,
        tmp,
        subscriberDetails.bankAuthPub!!,
        subscriberDetails.customerAuthPriv
    )
    if (responseTransaction.value.body.returnCode.value != "000000") {
        throw EbicsError(response.value.body.returnCode.value)
    }
}