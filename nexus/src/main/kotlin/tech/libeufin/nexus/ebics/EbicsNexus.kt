/*
 * This file is part of LibEuFin.
 * Copyright (C) 2020 Taler Systems S.A.
 *
 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.
 *
 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

/**
 * Handlers for EBICS-related endpoints offered by the nexus for EBICS
 * connections.
 */
package tech.libeufin.nexus.ebics

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.application.Application
import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.request.receiveOrNull
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.Route
import io.ktor.routing.Routing
import io.ktor.routing.post
import io.ktor.util.pipeline.PipelineContext
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.nexus.*
import tech.libeufin.nexus.logger
import tech.libeufin.util.*
import tech.libeufin.util.ebics_h004.HTDResponseOrderData
import java.util.*
import javax.crypto.EncryptedPrivateKeyInfo


private data class EbicsFetchSpec(
    val orderType: String,
    val orderParams: EbicsOrderParams
)

suspend fun fetchEbicsBySpec(fetchSpec: FetchSpecJson, client: HttpClient, bankConnectionId: String) {
    val subscriberDetails = transaction { getEbicsSubscriberDetails(bankConnectionId) }
    val specs = mutableListOf<EbicsFetchSpec>()
    when (fetchSpec) {
        is FetchSpecLatestJson -> {
            val p = EbicsStandardOrderParams()
            when (fetchSpec.level) {
                FetchLevel.ALL -> {
                    specs.add(EbicsFetchSpec("C52", p))
                    specs.add(EbicsFetchSpec("C53", p))
                }
                FetchLevel.REPORT -> {
                    specs.add(EbicsFetchSpec("C52", p))
                }
                FetchLevel.STATEMENT -> {
                    specs.add(EbicsFetchSpec("C53", p))
                }
            }
        }
    }
    for (spec in specs) {
        fetchEbicsC5x(spec.orderType, client, bankConnectionId, spec.orderParams, subscriberDetails)
    }
}

/**
 * Fetch EBICS C5x and store it locally, but do not update bank accounts.
 */
private suspend fun fetchEbicsC5x(
    historyType: String,
    client: HttpClient,
    bankConnectionId: String,
    orderParams: EbicsOrderParams,
    subscriberDetails: EbicsClientSubscriberDetails
) {
    val response = doEbicsDownloadTransaction(
        client,
        subscriberDetails,
        historyType,
        orderParams
    )
    when (historyType) {
        "C52" -> {
        }
        "C53" -> {
        }
        else -> {
            throw NexusError(HttpStatusCode.BadRequest, "history type '$historyType' not supported")
        }
    }
    when (response) {
        is EbicsDownloadSuccessResult -> {
            response.orderData.unzipWithLambda {
                logger.debug("Camt entry: ${it.second}")
                val camt53doc = XMLUtil.parseStringIntoDom(it.second)
                val msgId = camt53doc.pickStringWithRootNs("/*[1]/*[1]/root:GrpHdr/root:MsgId")
                logger.info("msg id $msgId")
                transaction {
                    val conn = NexusBankConnectionEntity.findById(bankConnectionId)
                    if (conn == null) {
                        throw NexusError(HttpStatusCode.InternalServerError, "bank connection missing")
                    }
                    val oldMsg = NexusBankMessageEntity.find { NexusBankMessagesTable.messageId eq msgId }.firstOrNull()
                    if (oldMsg == null) {
                        NexusBankMessageEntity.new {
                            this.bankConnection = conn
                            this.code = historyType
                            this.messageId = msgId
                            this.message = ExposedBlob(it.second.toByteArray(Charsets.UTF_8))
                        }
                    }
                }
            }
        }
        is EbicsDownloadBankErrorResult -> {
            throw NexusError(
                HttpStatusCode.BadGateway,
                response.returnCode.errorCode
            )
        }
    }
}


fun createEbicsBankConnectionFromBackup(
    bankConnectionName: String,
    user: NexusUserEntity,
    passphrase: String?,
    backup: JsonNode
) {
    if (passphrase === null) {
        throw NexusError(HttpStatusCode.BadRequest, "EBICS backup needs passphrase")
    }
    val bankConn = NexusBankConnectionEntity.new(bankConnectionName) {
        owner = user
        type = "ebics"
    }
    val ebicsBackup = jacksonObjectMapper().treeToValue(backup, EbicsKeysBackupJson::class.java)
    val (authKey, encKey, sigKey) = try {
        Triple(
            CryptoUtil.decryptKey(
                EncryptedPrivateKeyInfo(base64ToBytes(ebicsBackup.authBlob)),
                passphrase
            ),
            CryptoUtil.decryptKey(
                EncryptedPrivateKeyInfo(base64ToBytes(ebicsBackup.encBlob)),
                passphrase
            ),
            CryptoUtil.decryptKey(
                EncryptedPrivateKeyInfo(base64ToBytes(ebicsBackup.sigBlob)),
                passphrase
            )
        )
    } catch (e: Exception) {
        e.printStackTrace()
        logger.info("Restoring keys failed, probably due to wrong passphrase")
        throw NexusError(
            HttpStatusCode.BadRequest,
            "Bad backup given"
        )
    }
    try {
        EbicsSubscriberEntity.new {
            ebicsURL = ebicsBackup.ebicsURL
            hostID = ebicsBackup.hostID
            partnerID = ebicsBackup.partnerID
            userID = ebicsBackup.userID
            signaturePrivateKey = ExposedBlob(sigKey.encoded)
            encryptionPrivateKey = ExposedBlob((encKey.encoded))
            authenticationPrivateKey = ExposedBlob((authKey.encoded))
            nexusBankConnection = bankConn
            ebicsIniState = EbicsInitState.UNKNOWN
            ebicsHiaState = EbicsInitState.UNKNOWN
        }
    } catch (e: Exception) {
        throw NexusError(
            HttpStatusCode.BadRequest,
            "exception: $e"
        )
    }
    return
}

/**
 * Retrieve Ebics subscriber details given a bank connection.
 */
private fun getEbicsSubscriberDetails(bankConnectionId: String): EbicsClientSubscriberDetails {
    val transport = NexusBankConnectionEntity.findById(bankConnectionId)
    if (transport == null) {
        throw NexusError(HttpStatusCode.NotFound, "transport not found")
    }
    val subscriber = EbicsSubscriberEntity.find { EbicsSubscribersTable.nexusBankConnection eq transport.id }.first()
    // transport exists and belongs to caller.
    return getEbicsSubscriberDetailsInternal(subscriber)
}

fun Route.ebicsBankProtocolRoutes(client: HttpClient) {
    post("test-host") {
        val r = call.receiveJson<EbicsHostTestRequest>()
        val qr = doEbicsHostVersionQuery(client, r.ebicsBaseUrl, r.ebicsHostId)
        call.respond(HttpStatusCode.OK, qr)
        return@post
    }
}

fun Route.ebicsBankConnectionRoutes(client: HttpClient) {
    post("/send-ini") {
        val subscriber = transaction {
            val user = authenticateRequest(call.request)
            val conn = requireBankConnection(call, "connid")
            if (conn.type != "ebics") {
                throw NexusError(
                    HttpStatusCode.BadRequest,
                    "bank connection is not of type 'ebics' (but '${conn.type}')"
                )
            }
            getEbicsSubscriberDetails(conn.id.value)
        }
        val resp = doEbicsIniRequest(client, subscriber)
        call.respond(resp)
    }

    post("/send-hia") {
        val subscriber = transaction {
            val user = authenticateRequest(call.request)
            val conn = requireBankConnection(call, "connid")
            if (conn.type != "ebics") {
                throw NexusError(HttpStatusCode.BadRequest, "bank connection is not of type 'ebics'")
            }
            getEbicsSubscriberDetails(conn.id.value)
        }
        val resp = doEbicsHiaRequest(client, subscriber)
        call.respond(resp)
    }

    post("/send-hev") {
        val subscriber = transaction {
            val user = authenticateRequest(call.request)
            val conn = requireBankConnection(call, "connid")
            if (conn.type != "ebics") {
                throw NexusError(HttpStatusCode.BadRequest, "bank connection is not of type 'ebics'")
            }
            getEbicsSubscriberDetails(conn.id.value)
        }
        val resp = doEbicsHostVersionQuery(client, subscriber.ebicsUrl, subscriber.hostId)
        call.respond(resp)
    }

    post("/send-hpb") {
        val subscriberDetails = transaction {
            val user = authenticateRequest(call.request)
            val conn = requireBankConnection(call, "connid")
            if (conn.type != "ebics") {
                throw NexusError(HttpStatusCode.BadRequest, "bank connection is not of type 'ebics'")
            }
            getEbicsSubscriberDetails(conn.id.value)
        }
        val hpbData = doEbicsHpbRequest(client, subscriberDetails)
        transaction {
            val conn = requireBankConnection(call, "connid")
            val subscriber =
                EbicsSubscriberEntity.find { EbicsSubscribersTable.nexusBankConnection eq conn.id }.first()
            subscriber.bankAuthenticationPublicKey = ExposedBlob((hpbData.authenticationPubKey.encoded))
            subscriber.bankEncryptionPublicKey = ExposedBlob((hpbData.encryptionPubKey.encoded))
        }
        call.respond(object {})
    }

    /**
     * Directly import accounts.  Used for testing.
     */
    post("/import-accounts") {
        val subscriberDetails = transaction {
            authenticateRequest(call.request)
            val conn = requireBankConnection(call, "connid")
            if (conn.type != "ebics") {
                throw NexusError(HttpStatusCode.BadRequest, "bank connection is not of type 'ebics'")
            }
            getEbicsSubscriberDetails(conn.id.value)
        }
        val response = doEbicsDownloadTransaction(
            client, subscriberDetails, "HTD", EbicsStandardOrderParams()
        )
        when (response) {
            is EbicsDownloadBankErrorResult -> {
                throw NexusError(
                    HttpStatusCode.BadGateway,
                    response.returnCode.errorCode
                )
            }
            is EbicsDownloadSuccessResult -> {
                val payload = XMLUtil.convertStringToJaxb<HTDResponseOrderData>(
                    response.orderData.toString(Charsets.UTF_8)
                )
                transaction {
                    val conn = requireBankConnection(call, "connid")
                    payload.value.partnerInfo.accountInfoList?.forEach {
                        val bankAccount = NexusBankAccountEntity.new(id = it.id) {
                            accountHolder = it.accountHolder ?: "NOT-GIVEN"
                            iban = extractFirstIban(it.accountNumberList)
                                ?: throw NexusError(HttpStatusCode.NotFound, reason = "bank gave no IBAN")
                            bankCode = extractFirstBic(it.bankCodeList) ?: throw NexusError(
                                HttpStatusCode.NotFound,
                                reason = "bank gave no BIC"
                            )
                            defaultBankConnection = conn
                            highestSeenBankMessageId = 0
                        }
                    }
                }
                response.orderData.toString(Charsets.UTF_8)
            }
        }
        call.respond(object {})
    }

    post("/download/{msgtype}") {
        val orderType = requireNotNull(call.parameters["msgtype"]).toUpperCase(Locale.ROOT)
        if (orderType.length != 3) {
            throw NexusError(HttpStatusCode.BadRequest, "ebics order type must be three characters")
        }
        val paramsJson = call.receiveOrNull<EbicsStandardOrderParamsDateJson>()
        val orderParams = if (paramsJson == null) {
            EbicsStandardOrderParams()
        } else {
            paramsJson.toOrderParams()
        }
        val subscriberDetails = transaction {
            val user = authenticateRequest(call.request)
            val conn = requireBankConnection(call, "connid")
            if (conn.type != "ebics") {
                throw NexusError(HttpStatusCode.BadRequest, "bank connection is not of type 'ebics'")
            }
            getEbicsSubscriberDetails(conn.id.value)
        }
        val response = doEbicsDownloadTransaction(
            client,
            subscriberDetails,
            orderType,
            orderParams
        )
        when (response) {
            is EbicsDownloadSuccessResult -> {
                call.respondText(
                    response.orderData.toString(Charsets.UTF_8),
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
            }
            is EbicsDownloadBankErrorResult -> {
                call.respond(
                    HttpStatusCode.BadGateway,
                    EbicsErrorJson(EbicsErrorDetailJson("bankError", response.returnCode.errorCode))
                )
            }
        }
    }
}

fun exportEbicsKeyBackup(bankConnectionId: String, passphrase: String): Any {
    val subscriber = transaction { getEbicsSubscriberDetails(bankConnectionId) }
    return EbicsKeysBackupJson(
        type = "ebics",
        userID = subscriber.userId,
        hostID = subscriber.hostId,
        partnerID = subscriber.partnerId,
        ebicsURL = subscriber.ebicsUrl,
        authBlob = bytesToBase64(
            CryptoUtil.encryptKey(
                subscriber.customerAuthPriv.encoded,
                passphrase
            )
        ),
        encBlob = bytesToBase64(
            CryptoUtil.encryptKey(
                subscriber.customerEncPriv.encoded,
                passphrase
            )
        ),
        sigBlob = bytesToBase64(
            CryptoUtil.encryptKey(
                subscriber.customerSignPriv.encoded,
                passphrase
            )
        )
    )
}

suspend fun submitEbicsPaymentInitiation(client: HttpClient, connId: String, pain001Document: String) {
    val ebicsSubscriberDetails = transaction { getEbicsSubscriberDetails(connId) }
    logger.debug("Uploading PAIN.001: ${pain001Document}")
    doEbicsUploadTransaction(
        client,
        ebicsSubscriberDetails,
        "CCT",
        pain001Document.toByteArray(Charsets.UTF_8),
        EbicsStandardOrderParams()
    )
}

fun getEbicsConnectionDetails(conn: NexusBankConnectionEntity): Any {
    val ebicsSubscriber = transaction { getEbicsSubscriberDetails(conn.id.value) }
    val mapper = ObjectMapper()
    val details = mapper.createObjectNode()
    details.put("ebicsUrl", ebicsSubscriber.ebicsUrl)
    details.put("ebicsHostId", ebicsSubscriber.hostId)
    details.put("partnerId", ebicsSubscriber.partnerId)
    details.put("userId", ebicsSubscriber.userId)
    val node = mapper.createObjectNode()
    node.put("type", conn.type)
    node.put("owner", conn.owner.id.value)
    node.set<JsonNode>("details", details)
    return node
}

suspend fun connectEbics(client: HttpClient, connId: String) {
    val subscriber = transaction { getEbicsSubscriberDetails(connId) }
    if (subscriber.bankAuthPub != null && subscriber.bankEncPub != null) {
        return
    }
    val iniDone = when (subscriber.ebicsIniState) {
        EbicsInitState.NOT_SENT, EbicsInitState.UNKNOWN -> {
            val iniResp = doEbicsIniRequest(client, subscriber)
            iniResp.bankReturnCode == EbicsReturnCode.EBICS_OK && iniResp.technicalReturnCode == EbicsReturnCode.EBICS_OK
        }
        else -> {
            false
        }
    }
    val hiaDone = when (subscriber.ebicsHiaState) {
        EbicsInitState.NOT_SENT, EbicsInitState.UNKNOWN -> {
            val hiaResp = doEbicsHiaRequest(client, subscriber)
            hiaResp.bankReturnCode == EbicsReturnCode.EBICS_OK && hiaResp.technicalReturnCode == EbicsReturnCode.EBICS_OK
        }
        else -> {
            false
        }
    }
    val hpbData = try {
        doEbicsHpbRequest(client, subscriber)
    } catch (e: EbicsProtocolError) {
        logger.warn("failed hpb request", e)
        null
    }
    transaction {
        val conn = NexusBankConnectionEntity.findById(connId)
        if (conn == null) {
            throw NexusError(HttpStatusCode.NotFound, "bank connection '$connId' not found")
        }
        val subscriberEntity =
            EbicsSubscriberEntity.find { EbicsSubscribersTable.nexusBankConnection eq conn.id }.first()
        if (iniDone) {
            subscriberEntity.ebicsIniState = EbicsInitState.SENT
        }
        if (hiaDone) {
            subscriberEntity.ebicsHiaState = EbicsInitState.SENT
        }
        if (hpbData != null) {
            subscriberEntity.bankAuthenticationPublicKey =
                ExposedBlob((hpbData.authenticationPubKey.encoded))
            subscriberEntity.bankEncryptionPublicKey = ExposedBlob((hpbData.encryptionPubKey.encoded))
        }
    }
}