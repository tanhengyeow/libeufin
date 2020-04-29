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

package tech.libeufin.nexus

import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.client.HttpClient
import io.ktor.features.*
import io.ktor.gson.gson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.request.ApplicationReceivePipeline
import io.ktor.request.ApplicationReceiveRequest
import io.ktor.request.receive
import io.ktor.request.uri
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.io.ByteReadChannel
import kotlinx.coroutines.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.io.jvm.javaio.toInputStream
import kotlinx.io.core.ExperimentalIoApi
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import tech.libeufin.util.*
import tech.libeufin.util.ebics_h004.HTDResponseOrderData
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.InflaterInputStream
import javax.crypto.EncryptedPrivateKeyInfo
import javax.sql.rowset.serial.SerialBlob


data class NexusError(val statusCode: HttpStatusCode, val reason: String) : Exception()

val logger: Logger = LoggerFactory.getLogger("tech.libeufin.nexus")

fun isProduction(): Boolean {
    return System.getenv("NEXUS_PRODUCTION") != null
}

@ExperimentalIoApi
@KtorExperimentalAPI
fun main() {
    dbCreateTables()
    val client = HttpClient() {
        expectSuccess = false // this way, it does not throw exceptions on != 200 responses.
    }
    val server = embeddedServer(Netty, port = 5001) {

        install(CallLogging) {
            this.level = Level.DEBUG
            this.logger = tech.libeufin.nexus.logger
        }
        install(ContentNegotiation) {
            gson {
                setDateFormat(DateFormat.LONG)
                setPrettyPrinting()
            }
        }
        install(StatusPages) {
            exception<NexusError> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText(
                    cause.reason,
                    ContentType.Text.Plain,
                    cause.statusCode
                )
            }
            exception<UtilError> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText(
                    cause.reason,
                    ContentType.Text.Plain,
                    cause.statusCode
                )
            }
            exception<Exception> { cause ->
                logger.error("Uncaught exception while handling '${call.request.uri}'", cause)
                logger.error(cause.toString())
                call.respondText(
                    "Internal server error",
                    ContentType.Text.Plain,
                    HttpStatusCode.InternalServerError
                )
            }
        }

        intercept(ApplicationCallPipeline.Fallback) {
            if (this.call.response.status() == null) {
                call.respondText("Not found (no route matched).\n", ContentType.Text.Plain, HttpStatusCode.NotFound)
                return@intercept finish()
            }
        }

        receivePipeline.intercept(ApplicationReceivePipeline.Before) {
            if (this.context.request.headers["Content-Encoding"] == "deflate") {
                logger.debug("About to inflate received data")
                val deflated = this.subject.value as ByteReadChannel
                val inflated = InflaterInputStream(deflated.toInputStream())
                proceedWith(ApplicationReceiveRequest(this.subject.typeInfo, inflated.toByteReadChannel()))
                return@intercept
            }
            proceed()
            return@intercept
        }

        routing {

            /** General / debug endpoints */

            get("/") {
                call.respondText("Hello by Nexus!\n")
                return@get
            }

            get("/taler/test-auth") {
                authenticateRequest(call.request.headers["Authorization"])
                call.respondText("Authenticated!", ContentType.Text.Plain, HttpStatusCode.OK)
                return@get
            }

            /** USER endpoints (no EBICS) */

            /** Lists the users known to this system */
            get("/users") {
                val ret = NexusUsers()
                transaction {
                    NexusUserEntity.all().forEach {
                        val nexusUser = NexusUser(userID = it.id.value)
                        val ebicsSubscriber = it.ebicsSubscriber
                        if (ebicsSubscriber != null) {
                            nexusUser.transports.add(
                                EbicsSubscriber(
                                    userID = ebicsSubscriber.userID,
                                    ebicsURL = ebicsSubscriber.ebicsURL,
                                    hostID = ebicsSubscriber.hostID,
                                    partnerID = ebicsSubscriber.partnerID,
                                    systemID = ebicsSubscriber.systemID
                                )
                            )
                        }
                        if (it.testSubscriber != null) {
                            nexusUser.transports.add(TestSubscriber())
                        }
                    }
                }
                call.respond(ret)
                return@get
            }

            /** Get all the details associated with a NEXUS user */
            get("/user/{id}") {
                val response = transaction {
                    val nexusUser = extractNexusUser(call.parameters["id"])
                    NexusUser(
                        userID = nexusUser.id.value
                    )
                }
                call.respond(HttpStatusCode.OK, response)
                return@get
            }

            /** Make a new NEXUS user in the system */
            post("/users/{id}") {
                val newUserId = expectId(call.parameters["id"])
                val body = call.receive<NexusUserRequest>()
                transaction {
                    NexusUserEntity.new(id = newUserId) {
                        password = if (body.password != null) {
                            SerialBlob(CryptoUtil.hashStringSHA256(body.password))
                        } else {
                            logger.debug("No password set for $newUserId")
                            null
                        }
                    }
                }
                call.respondText(
                    "New NEXUS user registered. ID: $newUserId",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
                return@post
            }

            /** List all the bank accounts associated with a given NEXUS user.  */
            get("/users/{id}/accounts") {
                // this information is only avaiable *after* HTD or HKD has been called
                val id = expectId(call.parameters["id"])
                val ret = BankAccountsInfoResponse()
                transaction {
                    BankAccountEntity.find {
                        UserToBankAccountsTable.nexusUser eq id
                    }.forEach {
                        ret.accounts.add(
                            BankAccountInfoElement(
                                accountHolderName = it.accountHolder,
                                iban = it.iban,
                                bankCode = it.bankCode,
                                accountId = it.id.value
                            )
                        )
                    }
                }
                call.respond(
                    HttpStatusCode.OK,
                    ret
                )
                return@get
            }
            /** Show list of payments prepared by calling user.  */
            get("/users/{id}/payments") {
                val nexusUserId = expectId(call.parameters["id"])
                val ret = PaymentsInfo()
                transaction {
                    val nexusUser = extractNexusUser(nexusUserId)
                    val bankAccountsMap = UserToBankAccountEntity.find {
                        UserToBankAccountsTable.nexusUser eq nexusUser.id
                    }
                    bankAccountsMap.forEach {
                        Pain001Entity.find {
                            Pain001Table.debtorAccount eq it.bankAccount.iban
                        }.forEach {
                            ret.payments.add(
                                PaymentInfoElement(
                                    debtorAccount = it.debtorAccount,
                                    creditorIban = it.creditorIban,
                                    creditorBic = it.creditorBic,
                                    creditorName = it.creditorName,
                                    subject = it.subject,
                                    sum = it.sum,
                                    submitted = it.submitted // whether Nexus processed and sent to the bank
                                )
                            )
                        }
                    }
                }
                call.respond(ret)
                return@get
            }
            post("/users/{id}/accounts/{acctid}/prepare-payment") {
                val acctid = transaction {
                    val accountInfo = expectAcctidTransaction(call.parameters["acctid"])
                    val nexusUser = extractNexusUser(call.parameters["id"])
                    if (!userHasRights(nexusUser, accountInfo)) {
                        throw NexusError(
                            HttpStatusCode.BadRequest,
                            "Claimed bank account '${accountInfo.id}' doesn't belong to user '${nexusUser.id.value}'!"
                        )
                    }
                    accountInfo.id.value
                }
                val pain001data = call.receive<Pain001Data>()
                createPain001entity(pain001data, acctid)
                call.respondText(
                    "Payment instructions persisted in DB",
                    ContentType.Text.Plain, HttpStatusCode.OK
                )
                return@post
            }
            get("/users/{id}/raw-payments") {
                val nexusUser = extractNexusUser(call.parameters["id"])
                var ret = RawPayments()
                transaction {
                    RawBankTransactionEntity.find {
                        RawBankTransactionsTable.nexusUser eq nexusUser.id.value
                    }.forEach {
                        ret.payments.add(
                            RawPayment(
                                creditorIban = it.creditorIban,
                                debitorIban = it.debitorIban,
                                subject = it.unstructuredRemittanceInformation,
                                date = DateTime(it.bookingDate).toDashedDate(),
                                amount = it.amount + " " + it.currency
                            )
                        )
                    }
                }
                call.respond(
                    HttpStatusCode.OK,
                    ret
                )
                return@get
            }

            /** Associate a EBICS subscriber to the existing user */
            post("/ebics/subscribers/{id}") {
                val nexusUser = extractNexusUser(call.parameters["id"])
                val body = call.receive<EbicsSubscriber>()
                val pairA = CryptoUtil.generateRsaKeyPair(2048)
                val pairB = CryptoUtil.generateRsaKeyPair(2048)
                val pairC = CryptoUtil.generateRsaKeyPair(2048)
                transaction {
                    val newEbicsSubscriber = EbicsSubscriberEntity.new {
                        ebicsURL = body.ebicsURL
                        hostID = body.hostID
                        partnerID = body.partnerID
                        userID = body.userID
                        systemID = body.systemID
                        signaturePrivateKey = SerialBlob(pairA.private.encoded)
                        encryptionPrivateKey = SerialBlob(pairB.private.encoded)
                        authenticationPrivateKey = SerialBlob(pairC.private.encoded)
                    }
                    nexusUser.ebicsSubscriber = newEbicsSubscriber
                }
                call.respondText(
                    "EBICS user successfully created",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
                return@post
            }
            post("/ebics/subscribers/{id}/restoreBackup") {
                val body = call.receive<EbicsKeysBackupJson>()
                val nexusId = expectId(call.parameters["id"])
                val subscriber = transaction {
                    NexusUserEntity.findById(nexusId)
                }
                if (subscriber != null) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        NexusErrorJson("ID exists, please choose a new one")
                    )
                    return@post
                }
                val (authKey, encKey, sigKey) = try {
                    Triple(
                        CryptoUtil.decryptKey(
                            EncryptedPrivateKeyInfo(base64ToBytes(body.authBlob)), body.passphrase!!
                        ),
                        CryptoUtil.decryptKey(
                            EncryptedPrivateKeyInfo(base64ToBytes(body.encBlob)), body.passphrase
                        ),
                        CryptoUtil.decryptKey(
                            EncryptedPrivateKeyInfo(base64ToBytes(body.sigBlob)), body.passphrase
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    logger.info("Restoring keys failed, probably due to wrong passphrase")
                    throw NexusError(HttpStatusCode.BadRequest, reason = "Bad backup given")
                }
                logger.info("Restoring keys, creating new user: $nexusId")
                try {
                    transaction {
                        NexusUserEntity.new(id = nexusId) {
                            ebicsSubscriber = EbicsSubscriberEntity.new {
                                ebicsURL = body.ebicsURL
                                hostID = body.hostID
                                partnerID = body.partnerID
                                userID = body.userID
                                signaturePrivateKey = SerialBlob(sigKey.encoded)
                                encryptionPrivateKey = SerialBlob(encKey.encoded)
                                authenticationPrivateKey = SerialBlob(authKey.encoded)
                            }
                        }
                    }
                } catch (e: Exception) {
                    print(e)
                    call.respond(NexusErrorJson("Could not store the new account into database"))
                    return@post
                }
                call.respondText(
                    "Keys successfully restored",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
                return@post
            }

            /** EBICS CONVENIENCE */

            get("/ebics/subscribers/{id}/pubkeys") {
                val nexusUser = extractNexusUser(call.parameters["id"])
                val response = transaction {
                    val subscriber = getEbicsSubscriberFromUser(nexusUser)
                    val authPriv = CryptoUtil.loadRsaPrivateKey(subscriber.authenticationPrivateKey.toByteArray())
                    val authPub = CryptoUtil.getRsaPublicFromPrivate(authPriv)
                    val encPriv = CryptoUtil.loadRsaPrivateKey(subscriber.encryptionPrivateKey.toByteArray())
                    val encPub = CryptoUtil.getRsaPublicFromPrivate(encPriv)
                    val sigPriv = CryptoUtil.loadRsaPrivateKey(subscriber.signaturePrivateKey.toByteArray())
                    val sigPub = CryptoUtil.getRsaPublicFromPrivate(sigPriv)
                    EbicsPubKeyInfo(
                        bytesToBase64(authPub.encoded),
                        bytesToBase64(encPub.encoded),
                        bytesToBase64(sigPub.encoded)
                    )
                }
                call.respond(
                    HttpStatusCode.OK,
                    response
                )
            }
            get("/ebics/subscribers/{id}/keyletter") {
                val nexusUserId = expectId(call.parameters["id"])
                var usernameLine = "TODO"
                var recipientLine = "TODO"
                val customerIdLine = "TODO"
                var userIdLine = ""
                var esExponentLine = ""
                var esModulusLine = ""
                var authExponentLine = ""
                var authModulusLine = ""
                var encExponentLine = ""
                var encModulusLine = ""
                var esKeyHashLine = ""
                var encKeyHashLine = ""
                var authKeyHashLine = ""
                val esVersionLine = "A006"
                val authVersionLine = "X002"
                val encVersionLine = "E002"
                val now = Date()
                val dateFormat = SimpleDateFormat("DD.MM.YYYY")
                val timeFormat = SimpleDateFormat("HH:mm:ss")
                val dateLine = dateFormat.format(now)
                val timeLine = timeFormat.format(now)
                var hostID = ""
                transaction {
                    val nexusUser = extractNexusUser(nexusUserId)
                    val subscriber = getEbicsSubscriberFromUser(nexusUser)
                    val signPubTmp = CryptoUtil.getRsaPublicFromPrivate(
                        CryptoUtil.loadRsaPrivateKey(subscriber.signaturePrivateKey.toByteArray())
                    )
                    val authPubTmp = CryptoUtil.getRsaPublicFromPrivate(
                        CryptoUtil.loadRsaPrivateKey(subscriber.authenticationPrivateKey.toByteArray())
                    )
                    val encPubTmp = CryptoUtil.getRsaPublicFromPrivate(
                        CryptoUtil.loadRsaPrivateKey(subscriber.encryptionPrivateKey.toByteArray())
                    )
                    hostID = subscriber.hostID
                    userIdLine = subscriber.userID
                    esExponentLine = signPubTmp.publicExponent.toUnsignedHexString()
                    esModulusLine = signPubTmp.modulus.toUnsignedHexString()
                    encExponentLine = encPubTmp.publicExponent.toUnsignedHexString()
                    encModulusLine = encPubTmp.modulus.toUnsignedHexString()
                    authExponentLine = authPubTmp.publicExponent.toUnsignedHexString()
                    authModulusLine = authPubTmp.modulus.toUnsignedHexString()
                    esKeyHashLine = CryptoUtil.getEbicsPublicKeyHash(signPubTmp).toHexString()
                    encKeyHashLine = CryptoUtil.getEbicsPublicKeyHash(encPubTmp).toHexString()
                    authKeyHashLine = CryptoUtil.getEbicsPublicKeyHash(authPubTmp).toHexString()
                }
                val iniLetter = """
                    |Name: ${usernameLine}
                    |Date: ${dateLine}
                    |Time: ${timeLine}
                    |Recipient: ${recipientLine}
                    |Host ID: ${hostID}
                    |User ID: ${userIdLine}
                    |Partner ID: ${customerIdLine}
                    |ES version: ${esVersionLine}
                    
                    |Public key for the electronic signature:
                    
                    |Exponent:
                    |${chunkString(esExponentLine)}
                    
                    |Modulus:
                    |${chunkString(esModulusLine)}
                    
                    |SHA-256 hash:
                    |${chunkString(esKeyHashLine)}
                    
                    |I hereby confirm the above public keys for my electronic signature.
                    
                    |__________
                    |Place/date
                    
                    |__________
                    |Signature
                    |
                """.trimMargin()

                val hiaLetter = """
                    |Name: ${usernameLine}
                    |Date: ${dateLine}
                    |Time: ${timeLine}
                    |Recipient: ${recipientLine}
                    |Host ID: ${hostID}
                    |User ID: ${userIdLine}
                    |Partner ID: ${customerIdLine}
                    |Identification and authentication signature version: ${authVersionLine}
                    |Encryption version: ${encVersionLine}
                    
                    |Public key for the identification and authentication signature:
                    
                    |Exponent:
                    |${chunkString(authExponentLine)}
                    
                    |Modulus:
                    |${chunkString(authModulusLine)}
                    
                    |SHA-256 hash:
                    |${chunkString(authKeyHashLine)}
                    
                    |Public encryption key:
                    
                    |Exponent:
                    |${chunkString(encExponentLine)}
                    
                    |Modulus:
                    |${chunkString(encModulusLine)}
                    
                    |SHA-256 hash:
                    |${chunkString(encKeyHashLine)}              

                    |I hereby confirm the above public keys for my electronic signature.
                    
                    |__________
                    |Place/date
                    
                    |__________
                    |Signature
                    |
                """.trimMargin()

                call.respondText(
                    "####INI####:\n${iniLetter}\n\n\n####HIA####:\n${hiaLetter}",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
            }


            /** STATE CHANGES VIA EBICS */

            post("/ebics/admin/execute-payments") {
                val (paymentRowId, painDoc: String, debtorAccount) = transaction {
                    val entity = Pain001Entity.find {
                        (Pain001Table.submitted eq false) and (Pain001Table.invalid eq false)
                    }.firstOrNull() ?: throw NexusError(HttpStatusCode.Accepted, reason = "No ready payments found")
                    Triple(entity.id, createPain001document(entity), entity.debtorAccount)
                }
                logger.debug("Uploading PAIN.001: ${painDoc}")
                val subscriberDetails = getSubscriberDetailsFromBankAccount(debtorAccount)
                doEbicsUploadTransaction(
                    client,
                    subscriberDetails,
                    "CCT",
                    painDoc.toByteArray(Charsets.UTF_8),
                    EbicsStandardOrderParams()
                )
                /* flow here == no errors occurred */
                transaction {
                    val payment = Pain001Entity.findById(paymentRowId) ?: throw NexusError(
                        HttpStatusCode.InternalServerError,
                        "Severe internal error: could not find payment in DB after having submitted it to the bank"
                    )
                    payment.submitted = true
                }
                call.respondText(
                    "CCT message submitted to the bank",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
                return@post
            }
            post("/ebics/admin/execute-payments-ccc") {
                val (paymentRowId, painDoc: String, debtorAccount) = transaction {
                    val entity = Pain001Entity.find {
                        (Pain001Table.submitted eq false) and (Pain001Table.invalid eq false)
                    }.firstOrNull() ?: throw NexusError(HttpStatusCode.Accepted, reason = "No ready payments found")
                    Triple(entity.id, createPain001document(entity), entity.debtorAccount)
                }
                logger.debug("Uploading PAIN.001 via CCC: ${painDoc}")
                val subscriberDetails = getSubscriberDetailsFromBankAccount(debtorAccount)
                doEbicsUploadTransaction(
                    client,
                    subscriberDetails,
                    "CCC",
                    painDoc.toByteArray(Charsets.UTF_8).zip(),
                    EbicsStandardOrderParams()
                )
                /* flow here == no errors occurred */
                transaction {
                    val payment = Pain001Entity.findById(paymentRowId) ?: throw NexusError(
                        HttpStatusCode.InternalServerError,
                        "Severe internal error: could not find payment in DB after having submitted it to the bank"
                    )
                    payment.submitted = true
                }
                call.respondText(
                    "CCC message submitted to the bank",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
                return@post
            }
            /** exports keys backup copy */
            post("/ebics/subscribers/{id}/backup") {
                val body = call.receive<EbicsBackupRequestJson>()
                val response = transaction {
                    val nexusUser = extractNexusUser(call.parameters["id"])
                    val subscriber = getEbicsSubscriberFromUser(nexusUser)
                    EbicsKeysBackupJson(
                        userID = subscriber.userID,
                        hostID = subscriber.hostID,
                        partnerID = subscriber.partnerID,
                        ebicsURL = subscriber.ebicsURL,
                        authBlob = bytesToBase64(
                            CryptoUtil.encryptKey(
                                subscriber.authenticationPrivateKey.toByteArray(),
                                body.passphrase
                            )
                        ),
                        encBlob = bytesToBase64(
                            CryptoUtil.encryptKey(
                                subscriber.encryptionPrivateKey.toByteArray(),
                                body.passphrase
                            )
                        ),
                        sigBlob = bytesToBase64(
                            CryptoUtil.encryptKey(
                                subscriber.signaturePrivateKey.toByteArray(),
                                body.passphrase
                            )
                        )
                    )
                }
                call.response.headers.append("Content-Disposition", "attachment")
                call.respond(
                    HttpStatusCode.OK,
                    response
                )
            }
            /** Download keys from bank */
            post("/ebics/subscribers/{id}/sync") {
                val nexusUser = extractNexusUser(call.parameters["id"])
                val subscriberDetails = getSubscriberDetailsFromNexusUserId(nexusUser.id.value)
                val hpbRequest = makeEbicsHpbRequest(subscriberDetails)
                val responseStr = client.postToBank(subscriberDetails.ebicsUrl, hpbRequest)
                val response = parseAndDecryptEbicsKeyManagementResponse(subscriberDetails, responseStr)
                val orderData = response.orderData ?: throw NexusError(
                    HttpStatusCode.InternalServerError,
                    "HPB response has no order data"
                )
                val hpbData = parseEbicsHpbOrder(orderData)
                // put bank's keys into database.
                transaction {
                    val ebicsSubscriber = getEbicsSubscriberFromUser(nexusUser)
                    ebicsSubscriber.bankAuthenticationPublicKey = SerialBlob(hpbData.authenticationPubKey.encoded)
                    ebicsSubscriber.bankEncryptionPublicKey = SerialBlob(hpbData.encryptionPubKey.encoded)
                }
                call.respondText("Bank keys stored in database\n", ContentType.Text.Plain, HttpStatusCode.OK)
                return@post
            }
            post("/ebics/subscribers/{id}/fetch-payment-status") {
                val id = expectId(call.parameters["id"])
                val paramsJson = call.receive<EbicsStandardOrderParamsJson>()
                val orderParams = paramsJson.toOrderParams()
                val subscriberData = getSubscriberDetailsFromNexusUserId(id)
                val response = doEbicsDownloadTransaction(
                    client,
                    subscriberData,
                    "CRZ",
                    orderParams
                )
                when (response) {
                    is EbicsDownloadSuccessResult ->
                        call.respondText(
                            response.orderData.toString(Charsets.UTF_8),
                            ContentType.Text.Plain,
                            HttpStatusCode.OK
                        )
                    /**
                     * NOTE: flow gets here when the bank-technical return code is
                     * different from 000000.  This happens also for 090005 (no data available)
                     */
                    else -> call.respond(NexusErrorJson("Could not download any PAIN.002"))
                }
                return@post
            }
            post("/ebics/subscribers/{id}/collect-transactions-c53") {
                val id = expectId(call.parameters["id"])
                val paramsJson = call.receive<EbicsStandardOrderParamsJson>()
                val orderParams = paramsJson.toOrderParams()
                val subscriberData = getSubscriberDetailsFromNexusUserId(id)
                when (val response = doEbicsDownloadTransaction(client, subscriberData, "C53", orderParams)) {
                    is EbicsDownloadSuccessResult -> {
                        /**
                         * The current code is _heavily_ dependent on the way GLS returns
                         * data.  For example, GLS makes one ZIP entry for each "Ntry" element
                         * (a bank transfer), but per the specifications one bank can choose to
                         * return all the "Ntry" elements into one single ZIP entry, or even unzipped
                         * at all.
                         */
                        response.orderData.unzipWithLoop {
                            val fileName = it.first
                            val camt53doc = XMLUtil.parseStringIntoDom(it.second)
                            transaction {
                                RawBankTransactionEntity.new {
                                    sourceFileName = fileName
                                    unstructuredRemittanceInformation = camt53doc.pickString("//*[local-name()='Ntry']//*[local-name()='Ustrd']")
                                    transactionType = camt53doc.pickString("//*[local-name()='Ntry']//*[local-name()='CdtDbtInd']")
                                    currency = camt53doc.pickString("//*[local-name()='Ntry']//*[local-name()='Amt']/@Ccy")
                                    amount = camt53doc.pickString("//*[local-name()='Ntry']//*[local-name()='Amt']")
                                    status = camt53doc.pickString("//*[local-name()='Ntry']//*[local-name()='Sts']")
                                    bookingDate = parseDate(camt53doc.pickString("//*[local-name()='BookgDt']//*[local-name()='Dt']")).millis
                                    nexusUser = extractNexusUser(id)
                                    creditorName = camt53doc.pickString("//*[local-name()='RltdPties']//*[local-name()='Dbtr']//*[local-name()='Nm']")
                                    creditorIban = camt53doc.pickString("//*[local-name()='CdtrAcct']//*[local-name()='IBAN']")
                                    debitorName = camt53doc.pickString("//*[local-name()='RltdPties']//*[local-name()='Dbtr']//*[local-name()='Nm']")
                                    debitorIban = camt53doc.pickString("//*[local-name()='DbtrAcct']//*[local-name()='IBAN']")
                                    counterpartBic = camt53doc.pickString("//*[local-name()='RltdAgts']//*[local-name()='BIC']")
                                }
                            }
                        }
                        call.respondText(
                            "C53 data persisted into the database (WIP).",
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
                return@post
            }
            post("/ebics/subscribers/{id}/collect-transactions-c54") {
                // FIXME(florian): Download C54 and store the result in the right database table
            }
            /**
             * This endpoint downloads bank account information associated with the
             * calling EBICS subscriber.
             */
            post("/ebics/subscribers/{id}/fetch-accounts") {
                val nexusUser = extractNexusUser((call.parameters["id"]))
                val paramsJson = call.receive<EbicsStandardOrderParamsJson>()
                val orderParams = paramsJson.toOrderParams()
                val subscriberData = getSubscriberDetailsFromNexusUserId(nexusUser.id.value)
                val response = doEbicsDownloadTransaction(client, subscriberData, "HTD", orderParams)
                when (response) {
                    is EbicsDownloadSuccessResult -> {
                        val payload = XMLUtil.convertStringToJaxb<HTDResponseOrderData>(response.orderData.toString(Charsets.UTF_8))
                        transaction {
                            payload.value.partnerInfo.accountInfoList?.forEach {
                                val bankAccount = BankAccountEntity.new(id = it.id) {
                                    accountHolder = it.accountHolder
                                    iban = extractFirstIban(it.accountNumberList) ?: throw NexusError(HttpStatusCode.NotFound, reason = "bank gave no IBAN")
                                    bankCode = extractFirstBic(it.bankCodeList) ?: throw NexusError(HttpStatusCode.NotFound, reason = "bank gave no BIC")
                                }
                                EbicsToBankAccountEntity.new {
                                    ebicsSubscriber = getEbicsSubscriberFromUser(nexusUser)
                                    this.bankAccount = bankAccount
                                }
                            }

                        }
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
                return@post
            }

            /** EBICS MESSAGES / DEBUG */

            // FIXME: some messages include a ZIPped payload.
            post("/ebics/subscribers/{id}/send{MSG}") {
                val id = expectId(call.parameters["id"])
                val MSG = expectId(call.parameters["MSG"])
                val paramsJson = call.receive<EbicsStandardOrderParamsJson>()
                val orderParams = paramsJson.toOrderParams()
                println("$MSG order params: $orderParams")
                val subscriberData = getSubscriberDetailsFromNexusUserId(id)
                val response = doEbicsDownloadTransaction(
                    client,
                    subscriberData,
                    MSG,
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
                return@post
            }
            get("/ebics/{id}/sendHEV") {
                val id = expectId(call.parameters["id"])
                val subscriberData = getSubscriberDetailsFromNexusUserId(id)
                val request = makeEbicsHEVRequest(subscriberData)
                val response = client.postToBank(subscriberData.ebicsUrl, request)
                val versionDetails = parseEbicsHEVResponse(response)
                call.respond(
                    HttpStatusCode.OK,
                    EbicsHevResponseJson(versionDetails.versions.map { ebicsVersionSpec ->
                        ProtocolAndVersionJson(
                            ebicsVersionSpec.protocol,
                            ebicsVersionSpec.version
                        )
                    })
                )
                return@get
            }
            post("/ebics/subscribers/{id}/sendINI") {
                val id = expectId(call.parameters["id"])
                val subscriberData = getSubscriberDetailsFromNexusUserId(id)
                val iniRequest = makeEbicsIniRequest(subscriberData)
                val responseStr = client.postToBank(
                    subscriberData.ebicsUrl,
                    iniRequest
                )
                val resp = parseAndDecryptEbicsKeyManagementResponse(subscriberData, responseStr)
                if (resp.technicalReturnCode != EbicsReturnCode.EBICS_OK) {
                    throw NexusError(HttpStatusCode.InternalServerError,"Unexpected INI response code: ${resp.technicalReturnCode}")
                }
                call.respondText(
                    "Bank accepted signature key\n",
                    ContentType.Text.Plain, HttpStatusCode.OK
                )
                return@post
            }

            post("/ebics/subscribers/{id}/sendHIA") {
                val id = expectId(call.parameters["id"])
                val subscriberData = getSubscriberDetailsFromNexusUserId(id)
                val hiaRequest = makeEbicsHiaRequest(subscriberData)
                val responseStr = client.postToBank(
                    subscriberData.ebicsUrl,
                    hiaRequest
                )
                val resp = parseAndDecryptEbicsKeyManagementResponse(subscriberData, responseStr)
                if (resp.technicalReturnCode != EbicsReturnCode.EBICS_OK) {
                    throw NexusError(HttpStatusCode.InternalServerError,"Unexpected HIA response code: ${resp.technicalReturnCode}")
                }
                call.respondText(
                    "Bank accepted authentication and encryption keys\n",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
                return@post
            }

            /** PLUGINS */
            /* Taler class will initialize all the relevant handlers.  */
            Taler(this)
        }
    }
    logger.info("Up and running")
    server.start(wait = true)
}