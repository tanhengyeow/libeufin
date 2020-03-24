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


package tech.libeufin.sandbox

import io.ktor.application.ApplicationCall
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.request.receiveText
import io.ktor.response.respond
import io.ktor.response.respondText
import org.apache.xml.security.binding.xmldsig.RSAKeyValueType
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.w3c.dom.Document
import tech.libeufin.util.ebics_h004.*
import tech.libeufin.util.ebics_hev.HEVResponse
import tech.libeufin.util.ebics_hev.SystemReturnCodeType
import tech.libeufin.util.ebics_s001.SignatureTypes
import tech.libeufin.util.ebics_s001.UserSignatureData
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.EbicsOrderUtil
import tech.libeufin.util.XMLUtil
import tech.libeufin.util.*
import tech.libeufin.util.XMLUtil.Companion.signEbicsResponse
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.util.*
import java.util.zip.DeflaterInputStream
import java.util.zip.InflaterInputStream
import javax.sql.rowset.serial.SerialBlob
import javax.xml.datatype.DatatypeFactory
import org.apache.commons.compress.utils.IOUtils
import org.joda.time.DateTime
import org.joda.time.Instant
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.datatype.XMLGregorianCalendar


open class EbicsRequestError(errorText: String, errorCode: String) :
    Exception("EBICS request  error: $errorText ($errorCode)")


class EbicsInvalidRequestError : EbicsRequestError(
    "[EBICS_INVALID_REQUEST] Invalid request",
    "060102"
)

/**
 * This error is thrown whenever the Subscriber's state is not suitable
 * for the requested action.  For example, the subscriber sends a EbicsRequest
 * message without having first uploaded their keys (#5973).
 */
class EbicsSubscriberStateError : EbicsRequestError(
    "[EBICS_INVALID_USER_OR_USER_STATE] Subscriber unknown or subscriber state inadmissible",
    "091002"
)

open class EbicsKeyManagementError(val errorText: String, val errorCode: String) :
    Exception("EBICS key management error: $errorText ($errorCode)")

private class EbicsInvalidXmlError : EbicsKeyManagementError(
    "[EBICS_INVALID_XML]",
    "091010"
)

private class EbicsInvalidOrderType : EbicsRequestError(
    "[EBICS_UNSUPPORTED_ORDER_TYPE] Order type not supported",
    "091005"
)

private suspend fun ApplicationCall.respondEbicsKeyManagement(
    errorText: String,
    errorCode: String,
    bankReturnCode: String,
    dataTransfer: CryptoUtil.EncryptionResult? = null,
    orderId: String? = null
) {
    val responseXml = EbicsKeyManagementResponse().apply {
        version = "H004"
        header = EbicsKeyManagementResponse.Header().apply {
            authenticate = true
            mutable = EbicsKeyManagementResponse.MutableHeaderType().apply {
                reportText = errorText
                returnCode = errorCode
                if (orderId != null) {
                    this.orderID = orderId
                }
            }
            _static = EbicsKeyManagementResponse.EmptyStaticHeader()
        }
        body = EbicsKeyManagementResponse.Body().apply {
            this.returnCode = EbicsKeyManagementResponse.ReturnCode().apply {
                this.authenticate = true
                this.value = bankReturnCode
            }
            if (dataTransfer != null) {
                this.dataTransfer = EbicsKeyManagementResponse.DataTransfer().apply {
                    this.dataEncryptionInfo = EbicsTypes.DataEncryptionInfo().apply {
                        this.authenticate = true
                        this.transactionKey = dataTransfer.encryptedTransactionKey
                        this.encryptionPubKeyDigest = EbicsTypes.PubKeyDigest().apply {
                            this.algorithm = "http://www.w3.org/2001/04/xmlenc#sha256"
                            this.version = "E002"
                            this.value = dataTransfer.pubKeyDigest
                        }
                    }
                    this.orderData = EbicsKeyManagementResponse.OrderData().apply {
                        this.value = Base64.getEncoder().encodeToString(dataTransfer.encryptedData)
                    }
                }
            }
        }
    }
    val text = XMLUtil.convertJaxbToString(responseXml)
    LOGGER.info("responding with:\n${text}")
    respondText(text, ContentType.Application.Xml, HttpStatusCode.OK)
}

fun buildCamtString(history: SizedIterable<BankTransactionEntity>, type: Int): String {

    /**
     * Booking period: we keep two "lines" of booking periods; one for c52 and one for c53.
     * Each line's period ends when the user requests the c52/c53, and a new period is started.
     */

    /**
     * Checklist of data to be retrieved from the database.
     *
     * - IBAN(s): debitor and creditor's
     * - last IDs (of all kinds) ?
     */

    /**
     * What needs to be calculated before filling the document:
     *
     * - The balance _after_ all the transactions from the fresh
     *   booking period.
     */

    /**
     * ID types required:
     *
     * - Message Id
     * - Statement / Report Id
     * - Electronic sequence number
     * - Legal sequence number
     * - Entry Id by the Servicer
     * - Payment information Id
     * - Proprietary code of the bank transaction
     * - Id of the servicer (Issuer and Code)
     */

    val now = DateTime.now()

    return constructXml(indent = true) {
        root("Document") {
            attribute("xmlns", "urn:iso:std:iso:20022:tech:xsd:camt.053.001.08")
            attribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
            attribute("xmlns:schemaLocation", "urn:iso:std:iso:20022:tech:xsd:camt.053.001.02 camt.053.001.02.xsd")
            element("BkToCstmrStmt") {
                element("GrpHdr") {
                    element("MsgId") {
                        text("0")
                    }
                    element("CreDtTm") {
                        text(now.toZonedString())
                    }
                    element("MsgPgntn") {
                        element("PgNb") {
                            text("001")
                        }
                        element("LastPgInd") {
                            text("true")
                        }
                    }
                }
                element(if (type == 52) "Rpt" else "Stmt") {
                    element("Id")
                    element("ElctrncSeqNb")
                    element("LglSeqNb")
                    element("CreDtTm") {
                        text(now.toZonedString())
                    }

                    element("Acct") {
                        // mandatory account identifier
                        element("Id/IBAN") {
                            text("GB33BUKB20201555555555")
                        }
                        element("Ccy") {
                            text("EUR")
                        }
                        element("Ownr/Nm") {
                            text("Max Mustermann")
                        }
                        element("Svcr/FinInstn") {
                            element("BIC") {
                                text("XY")
                            }
                            element("Nm") {
                                text("Libeufin Bank")
                            }
                            element("Othr") {
                                element("Id") {
                                    text("0")
                                }
                                element("Issr") {
                                    text("XY")
                                }
                            }
                        }
                    }
                    element("Bal") {
                        element("Tp/CdOrPrtry/Cd") {
                            /* Balance type, in a coded format.  PRCD stands
                               for "Previously closed booked" and shows the
                               balance at the time _before_ all the entries
                               reported in this document were posted to the
                               involved bank account.  */
                            text("PRCD")
                        }
                        element("Amt") {
                            attribute("Ccy", "EUR")
                            text(Amount(1).toPlainString())
                        }
                        element("CdtDbtInd") {
                            text("DBIT")
                            // CRDT or DBIT here
                        }
                        element("Dt/Dt") {
                            // date of this balance
                            now.toDashedDate()
                        }
                    }
                    element("Bal") {
                        element("Tp/CdOrPrtry/Cd") {
                            /* CLBD stands for "Closing booked balance", and it
                               is calculated by summing the PRCD with all the
                               entries reported in this document */
                            text("CLBD")
                        }
                        element("Amt") {
                            attribute("Ccy", "EUR")
                            text(Amount(1).toPlainString())
                        }
                        element("CdtDbtInd") {
                            // CRDT or DBIT here
                            text("DBIT")
                        }
                        element("Dt/Dt") {
                            text(now.toDashedDate())
                        }
                    }
                    history.forEach {
                        element("Ntry") {
                            element("Amt")
                            element("CdtDbtInd") {
                                text("DBIT")
                            }
                            element("Sts") {
                                /* Status of the entry (see 2.4.2.15.5 from the ISO20022 reference document.)
                                 * From the original text:
                                 * "Status of an entry on the books of the account servicer" */
                                text("BOOK")
                            }
                            element("BookDt/Dt") {
                                text(now.toDashedDate())
                            } // date of the booking
                            element("ValDt/Dt") {
                                text(now.toDashedDate())
                            } // date of assets' actual (un)availability
                            element("AcctSvcrRef") {
                                text("0")
                            }
                            element("BkTxCd") {
                                /*  "Set of elements used to fully identify the type of underlying
                                 *   transaction resulting in an entry".  */
                                element("Domn") {
                                    element("Cd") {
                                        text("PMNT")
                                    }
                                    element("Fmly") {
                                        element("Cd") {
                                            text("ICDT")
                                        }
                                        element("SubFmlyCd") {
                                            text("ESCT")
                                        }
                                    }
                                    element("Prtry") {
                                        element("Cd") {
                                            text("0")
                                        }
                                        element("Issr") {
                                            text("XY")
                                        }
                                    }
                                }
                            }
                            element("NtryDtls/TxDtls") {
                                element("Refs") {
                                    element("MsgId") {
                                        text("0")
                                    }
                                    element("PmtInfId") {
                                        text("0")
                                    }
                                    element("EndToEndId") {
                                        text("NOTPROVIDED")
                                    }
                                }
                                element("AmtDtls/TxAmt/Amt") {
                                    attribute("Ccy", "EUR")
                                    text(Amount(1).toPlainString())
                                }
                                element("BkTxCd") {
                                    element("Domn") {
                                        element("Cd") {
                                            text("PMNT")
                                        }
                                        element("Fmly") {
                                            element("Cd") {
                                                text("ICDT")
                                            }
                                            element("SubFmlyCd") {
                                                text("ESCT")
                                            }
                                        }
                                    }
                                    element("Prtry") {
                                        element("Cd") {
                                            text("0")
                                        }
                                        element("Issr") {
                                            text("XY")
                                        }
                                    }
                                }
                                element("RltdPties") {
                                    element("Dbtr/Nm") {
                                        text("Max Mustermann")
                                    }
                                    element("DbtrAcct/Id/IBAN") {
                                        text("GB33BUKB20201555555555")
                                    }
                                    element("Cdtr/Nm") {
                                        text("Lina Musterfrau")
                                    }
                                    element("CdtrAcct/Id/IBAN") {
                                        text("DE75512108001245126199")
                                    }
                                }
                                element("RltdAgts") {
                                    element("CdtrAgt/FinInstnId/BIC") {
                                        text("SOGEDEFF")
                                    }
                                }
                                element("RmtInf/Ustrd") {
                                    text("made up subject")
                                }
                            }
                            element("AddtlNtryInf") {
                                text("additional information not given")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Builds CAMT response.
 *
 * @param history the list of all the history elements
 * @param type 52 or 53.
 */
private fun constructCamtResponse(type: Int, customerId: Int, header: EbicsRequest.Header): String {

    val dateRange = (header.static.orderDetails?.orderParams as EbicsRequest.StandardOrderParams).dateRange
    val (start, end) = if (dateRange != null) {
        Pair(DateTime(Instant(dateRange.start)), DateTime(Instant(dateRange.end)))
    } else Pair(DateTime(0), DateTime.now())
    val history = extractHistory(
        customerId,
        start,
        end
    )
    logger.debug("${history.count()} history elements found for account $customerId")
    return buildCamtString(history, type)
}


private fun handleEbicsTSD(requestContext: RequestContext): ByteArray {
    return "Hello World".toByteArray()
}

private fun handleEbicsPTK(requestContext: RequestContext): ByteArray {
    return "Hello I am a dummy PTK response.".toByteArray()
}

private fun handleEbicsC52(requestContext: RequestContext): ByteArray {
    val subscriber = requestContext.subscriber
    val camt = constructCamtResponse(
        52,
        subscriber.bankCustomer.id.value,
        requestContext.requestObject.header
    )
    return camt.toByteArray().zip()
}

private suspend fun ApplicationCall.handleEbicsHia(header: EbicsUnsecuredRequest.Header, orderData: ByteArray) {
    val plainOrderData = InflaterInputStream(orderData.inputStream()).use {
        it.readAllBytes()
    }
    println("hia order data: ${plainOrderData.toString(Charsets.UTF_8)}")

    val keyObject = EbicsOrderUtil.decodeOrderDataXml<HIARequestOrderData>(orderData)
    val encPubXml = keyObject.encryptionPubKeyInfo.pubKeyValue.rsaKeyValue
    val authPubXml = keyObject.authenticationPubKeyInfo.pubKeyValue.rsaKeyValue
    val encPub = CryptoUtil.loadRsaPublicKeyFromComponents(encPubXml.modulus, encPubXml.exponent)
    val authPub = CryptoUtil.loadRsaPublicKeyFromComponents(authPubXml.modulus, authPubXml.exponent)

    transaction {
        val ebicsSubscriber = findEbicsSubscriber(header.static.partnerID, header.static.userID, header.static.systemID)
        if (ebicsSubscriber == null) {
            LOGGER.warn("ebics subscriber not found")
            throw EbicsInvalidRequestError()
        }
        ebicsSubscriber.authenticationKey = EbicsSubscriberPublicKeyEntity.new {
            this.rsaPublicKey = SerialBlob(authPub.encoded)
            state = KeyState.NEW
        }
        ebicsSubscriber.encryptionKey = EbicsSubscriberPublicKeyEntity.new {
            this.rsaPublicKey = SerialBlob(encPub.encoded)
            state = KeyState.NEW
        }
        ebicsSubscriber.state = when (ebicsSubscriber.state) {
            SubscriberState.NEW -> SubscriberState.PARTIALLY_INITIALIZED_HIA
            SubscriberState.PARTIALLY_INITIALIZED_INI -> SubscriberState.INITIALIZED
            else -> ebicsSubscriber.state
        }
    }
    respondEbicsKeyManagement("[EBICS_OK]", "000000", "000000")
}


private suspend fun ApplicationCall.handleEbicsIni(header: EbicsUnsecuredRequest.Header, orderData: ByteArray) {
    val plainOrderData = InflaterInputStream(orderData.inputStream()).use {
        it.readAllBytes()
    }
    println("ini order data: ${plainOrderData.toString(Charsets.UTF_8)}")

    val keyObject = EbicsOrderUtil.decodeOrderDataXml<SignatureTypes.SignaturePubKeyOrderData>(orderData)
    val sigPubXml = keyObject.signaturePubKeyInfo.pubKeyValue.rsaKeyValue
    val sigPub = CryptoUtil.loadRsaPublicKeyFromComponents(sigPubXml.modulus, sigPubXml.exponent)

    transaction {
        val ebicsSubscriber =
            findEbicsSubscriber(header.static.partnerID, header.static.userID, header.static.systemID)
        if (ebicsSubscriber == null) {
            LOGGER.warn("ebics subscriber ('${header.static.partnerID}' / '${header.static.userID}' / '${header.static.systemID}') not found")
            throw EbicsInvalidRequestError()
        }
        ebicsSubscriber.signatureKey = EbicsSubscriberPublicKeyEntity.new {
            this.rsaPublicKey = SerialBlob(sigPub.encoded)
            state = KeyState.NEW
        }
        ebicsSubscriber.state = when (ebicsSubscriber.state) {
            SubscriberState.NEW -> SubscriberState.PARTIALLY_INITIALIZED_INI
            SubscriberState.PARTIALLY_INITIALIZED_HIA -> SubscriberState.INITIALIZED
            else -> ebicsSubscriber.state
        }
    }
    LOGGER.info("Signature key inserted in database _and_ subscriber state changed accordingly")
    respondEbicsKeyManagement("[EBICS_OK]", "000000", bankReturnCode = "000000", orderId = "OR01")
}

private suspend fun ApplicationCall.handleEbicsHpb(
    ebicsHostInfo: EbicsHostPublicInfo,
    requestDocument: Document,
    header: EbicsNpkdRequest.Header
) {
    val subscriberKeys = transaction {
        val ebicsSubscriber =
            findEbicsSubscriber(header.static.partnerID, header.static.userID, header.static.systemID)
        if (ebicsSubscriber == null) {
            throw EbicsInvalidRequestError()
        }
        if (ebicsSubscriber.state != SubscriberState.INITIALIZED) {
            throw EbicsSubscriberStateError()
        }
        val authPubBlob = ebicsSubscriber.authenticationKey!!.rsaPublicKey
        val encPubBlob = ebicsSubscriber.encryptionKey!!.rsaPublicKey
        val sigPubBlob = ebicsSubscriber.signatureKey!!.rsaPublicKey
        SubscriberKeys(
            CryptoUtil.loadRsaPublicKey(authPubBlob.toByteArray()),
            CryptoUtil.loadRsaPublicKey(encPubBlob.toByteArray()),
            CryptoUtil.loadRsaPublicKey(sigPubBlob.toByteArray())
        )
    }
    val validationResult =
        XMLUtil.verifyEbicsDocument(requestDocument, subscriberKeys.authenticationPublicKey)
    LOGGER.info("validationResult: $validationResult")
    if (!validationResult) {
        throw EbicsKeyManagementError("invalid signature", "90000");
    }
    val hpbRespondeData = HPBResponseOrderData().apply {
        this.authenticationPubKeyInfo = EbicsTypes.AuthenticationPubKeyInfoType().apply {
            this.authenticationVersion = "X002"
            this.pubKeyValue = EbicsTypes.PubKeyValueType().apply {
                this.rsaKeyValue = RSAKeyValueType().apply {
                    this.exponent = ebicsHostInfo.authenticationPublicKey.publicExponent.toByteArray()
                    this.modulus = ebicsHostInfo.authenticationPublicKey.modulus.toByteArray()
                }
            }
        }
        this.encryptionPubKeyInfo = EbicsTypes.EncryptionPubKeyInfoType().apply {
            this.encryptionVersion = "E002"
            this.pubKeyValue = EbicsTypes.PubKeyValueType().apply {
                this.rsaKeyValue = RSAKeyValueType().apply {
                    this.exponent = ebicsHostInfo.encryptionPublicKey.publicExponent.toByteArray()
                    this.modulus = ebicsHostInfo.encryptionPublicKey.modulus.toByteArray()
                }
            }
        }
        this.hostID = ebicsHostInfo.hostID
    }

    val compressedOrderData = EbicsOrderUtil.encodeOrderDataXml(hpbRespondeData)

    val encryptionResult = CryptoUtil.encryptEbicsE002(compressedOrderData, subscriberKeys.encryptionPublicKey)

    respondEbicsKeyManagement("[EBICS_OK]", "000000", "000000", encryptionResult, "OR01")
}

/**
 * Find the ebics host corresponding to the one specified in the header.
 */
private fun ApplicationCall.ensureEbicsHost(requestHostID: String): EbicsHostPublicInfo {
    return transaction {
        addLogger(StdOutSqlLogger)
        val ebicsHost =
            EbicsHostEntity.find { EbicsHostsTable.hostID.upperCase() eq requestHostID.toUpperCase() }.firstOrNull()
        if (ebicsHost == null) {
            LOGGER.warn("client requested unknown HostID ${requestHostID}")
            throw EbicsKeyManagementError("[EBICS_INVALID_HOST_ID]", "091011")
        }
        val encryptionPrivateKey = CryptoUtil.loadRsaPrivateKey(ebicsHost.encryptionPrivateKey.toByteArray())
        val authenticationPrivateKey = CryptoUtil.loadRsaPrivateKey(ebicsHost.authenticationPrivateKey.toByteArray())
        EbicsHostPublicInfo(
            requestHostID,
            CryptoUtil.getRsaPublicFromPrivate(encryptionPrivateKey),
            CryptoUtil.getRsaPublicFromPrivate(authenticationPrivateKey)
        )
    }
}


private suspend fun ApplicationCall.receiveEbicsXml(): Document {
    val body: String = receiveText()
    LOGGER.debug("Data received: $body")
    val requestDocument: Document? = XMLUtil.parseStringIntoDom(body)
    if (requestDocument == null || (!XMLUtil.validateFromDom(requestDocument))) {
        println("Problematic document was: $requestDocument")
        throw EbicsInvalidXmlError()
    }
    return requestDocument
}


fun handleEbicsHtd(): ByteArray {
    val htd = HTDResponseOrderData().apply {
        this.partnerInfo = EbicsTypes.PartnerInfo().apply {
            this.accountInfoList = listOf(
                EbicsTypes.AccountInfo().apply {
                    this.id = "acctid1"
                    this.accountHolder = "Mina Musterfrau"
                    this.accountNumberList = listOf(
                        EbicsTypes.GeneralAccountNumber().apply {
                            this.international = true
                            this.value = "DE21500105174751659277"
                        }
                    )
                    this.currency = "EUR"
                    this.description = "ACCT"
                    this.bankCodeList = listOf(
                        EbicsTypes.GeneralBankCode().apply {
                            this.international = true
                            this.value = "INGDDEFFXXX"
                        }
                    )
                },
                EbicsTypes.AccountInfo().apply {
                    this.id = "glsdemo"
                    this.accountHolder = "Mina Musterfrau"
                    this.accountNumberList = listOf(
                        EbicsTypes.GeneralAccountNumber().apply {
                            this.international = true
                            this.value = "DE91430609670123123123"
                        }
                    )
                    this.currency = "EUR"
                    this.description = "glsdemoacct"
                    this.bankCodeList = listOf(
                        EbicsTypes.GeneralBankCode().apply {
                            this.international = true
                            this.value = "GENODEM1GLS"
                        }
                    )
                }
            )
            this.addressInfo = EbicsTypes.AddressInfo().apply {
                this.name = "Foo"
            }
            this.bankInfo = EbicsTypes.BankInfo().apply {
                this.hostID = "host01"
            }
            this.orderInfoList = listOf(
                EbicsTypes.AuthOrderInfoType().apply {
                    this.description = "foo1"
                    this.orderType = "C53"
                    this.transferType = "Download"
                },
                EbicsTypes.AuthOrderInfoType().apply {
                    this.description = "foo2"
                    this.orderType = "C52"
                    this.transferType = "Download"
                },
                EbicsTypes.AuthOrderInfoType().apply {
                    this.description = "foo3"
                    this.orderType = "CCC"
                    this.transferType = "Upload"
                },
                EbicsTypes.AuthOrderInfoType().apply {
                    this.description = "foo4"
                    this.orderType = "VMK"
                    this.transferType = "Download"
                },
                EbicsTypes.AuthOrderInfoType().apply {
                    this.description = "foo5"
                    this.orderType = "STA"
                    this.transferType = "Download"
                }
            )
        }
        this.userInfo = EbicsTypes.UserInfo().apply {
            this.name = "Some User"
            this.userID = EbicsTypes.UserIDType().apply {
                this.status = 5
                this.value = "USER1"
            }
            this.permissionList = listOf(
                EbicsTypes.UserPermission().apply {
                    this.orderTypes = "C53 C52 CCC VMK STA"
                }
            )
        }
    }

    val str = XMLUtil.convertJaxbToString(htd)
    return str.toByteArray()
}


fun handleEbicsHkd(): ByteArray {
    val hkd = HKDResponseOrderData().apply {
        this.partnerInfo = EbicsTypes.PartnerInfo().apply {
            this.accountInfoList = listOf(
                EbicsTypes.AccountInfo().apply {
                    this.id = "acctid1"
                    this.accountHolder = "Mina Musterfrau"
                    this.accountNumberList = listOf(
                        EbicsTypes.GeneralAccountNumber().apply {
                            this.international = true
                            this.value = "DE21500105174751659277"
                        }
                    )
                    this.currency = "EUR"
                    this.description = "ACCT"
                    this.bankCodeList = listOf(
                        EbicsTypes.GeneralBankCode().apply {
                            this.international = true
                            this.value = "INGDDEFFXXX"
                        }
                    )
                },
                EbicsTypes.AccountInfo().apply {
                    this.id = "glsdemo"
                    this.accountHolder = "Mina Musterfrau"
                    this.accountNumberList = listOf(
                        EbicsTypes.GeneralAccountNumber().apply {
                            this.international = true
                            this.value = "DE91430609670123123123"
                        }
                    )
                    this.currency = "EUR"
                    this.description = "glsdemoacct"
                    this.bankCodeList = listOf(
                        EbicsTypes.GeneralBankCode().apply {
                            this.international = true
                            this.value = "GENODEM1GLS"
                        }
                    )
                }
            )
            this.addressInfo = EbicsTypes.AddressInfo().apply {
                this.name = "Foo"
            }
            this.bankInfo = EbicsTypes.BankInfo().apply {
                this.hostID = "host01"
            }
            this.orderInfoList = listOf(
                EbicsTypes.AuthOrderInfoType().apply {
                    this.description = "foo"
                    this.orderType = "C53"
                    this.transferType = "Download"
                },
                EbicsTypes.AuthOrderInfoType().apply {
                    this.description = "foo"
                    this.orderType = "C52"
                    this.transferType = "Download"
                },
                EbicsTypes.AuthOrderInfoType().apply {
                    this.description = "foo"
                    this.orderType = "CCC"
                    this.transferType = "Upload"
                }
            )
        }
        this.userInfoList = listOf(
            EbicsTypes.UserInfo().apply {
                this.name = "Some User"
                this.userID = EbicsTypes.UserIDType().apply {
                    this.status = 1
                    this.value = "USER1"
                }
                this.permissionList = listOf(
                    EbicsTypes.UserPermission().apply {
                        this.orderTypes = "C54 C53 C52 CCC"
                    }
                )
            })
    }

    val str = XMLUtil.convertJaxbToString(hkd)
    return str.toByteArray()
}


private data class RequestContext(
    val ebicsHost: EbicsHostEntity,
    val subscriber: EbicsSubscriberEntity,
    val clientEncPub: RSAPublicKey,
    val clientAuthPub: RSAPublicKey,
    val clientSigPub: RSAPublicKey,
    val hostEncPriv: RSAPrivateCrtKey,
    val hostAuthPriv: RSAPrivateCrtKey,
    val requestObject: EbicsRequest,
    val uploadTransaction: EbicsUploadTransactionEntity?,
    val downloadTransaction: EbicsDownloadTransactionEntity?
)


private fun handleEbicsDownloadTransactionInitialization(requestContext: RequestContext): EbicsResponse {
    val orderType =
        requestContext.requestObject.header.static.orderDetails?.orderType ?: throw EbicsInvalidRequestError()
    println("handling initialization for order type $orderType")
    val response = when (orderType) {
        "HTD" -> handleEbicsHtd()
        "HKD" -> handleEbicsHkd()
        /* Temporarily handling C52/C53 with same logic */
        "C52" -> handleEbicsC52(requestContext)
        "C53" -> handleEbicsC52(requestContext)
        "TSD" -> handleEbicsTSD(requestContext)
        "PTK" -> handleEbicsPTK(requestContext)
        else -> throw EbicsInvalidXmlError()
    }

    val transactionID = EbicsOrderUtil.generateTransactionId()

    val compressedResponse = DeflaterInputStream(response.inputStream()).use {
        it.readAllBytes()
    }

    val enc = CryptoUtil.encryptEbicsE002(compressedResponse, requestContext.clientEncPub)
    val encodedResponse = Base64.getEncoder().encodeToString(enc.encryptedData)

    val segmentSize = 4096
    val totalSize = encodedResponse.length
    val numSegments = ((totalSize + segmentSize - 1) / segmentSize)

    EbicsDownloadTransactionEntity.new(transactionID) {
        this.subscriber = requestContext.subscriber
        this.host = requestContext.ebicsHost
        this.orderType = orderType
        this.segmentSize = segmentSize
        this.transactionKeyEnc = SerialBlob(enc.encryptedTransactionKey)
        this.encodedResponse = encodedResponse
        this.numSegments = numSegments
        this.receiptReceived = false
    }
    return EbicsResponse.createForDownloadInitializationPhase(
        transactionID,
        numSegments,
        segmentSize,
        enc,
        encodedResponse
    )
}


private fun handleEbicsUploadTransactionInitialization(requestContext: RequestContext): EbicsResponse {
    val orderType =
        requestContext.requestObject.header.static.orderDetails?.orderType ?: throw EbicsInvalidRequestError()
    val transactionID = EbicsOrderUtil.generateTransactionId()
    val oidn = requestContext.subscriber.nextOrderID++
    if (EbicsOrderUtil.checkOrderIDOverflow(oidn)) throw NotImplementedError()
    val orderID = EbicsOrderUtil.computeOrderIDFromNumber(oidn)
    val numSegments =
        requestContext.requestObject.header.static.numSegments ?: throw EbicsInvalidRequestError()
    val transactionKeyEnc =
        requestContext.requestObject.body.dataTransfer?.dataEncryptionInfo?.transactionKey
            ?: throw EbicsInvalidRequestError()
    val encPubKeyDigest =
        requestContext.requestObject.body.dataTransfer?.dataEncryptionInfo?.encryptionPubKeyDigest?.value
            ?: throw EbicsInvalidRequestError()
    val encSigData = requestContext.requestObject.body.dataTransfer?.signatureData?.value
        ?: throw EbicsInvalidRequestError()
    val decryptedSignatureData = CryptoUtil.decryptEbicsE002(
        CryptoUtil.EncryptionResult(
            transactionKeyEnc,
            encPubKeyDigest,
            encSigData
        ), requestContext.hostEncPriv
    )
    val plainSigData = InflaterInputStream(decryptedSignatureData.inputStream()).use {
        it.readAllBytes()
    }

    println("creating upload transaction for transactionID $transactionID")
    EbicsUploadTransactionEntity.new(transactionID) {
        this.host = requestContext.ebicsHost
        this.subscriber = requestContext.subscriber
        this.lastSeenSegment = 0
        this.orderType = orderType
        this.orderID = orderID
        this.numSegments = numSegments.toInt()
        this.transactionKeyEnc = SerialBlob(transactionKeyEnc)
    }
    val sigObj = XMLUtil.convertStringToJaxb<UserSignatureData>(plainSigData.toString(Charsets.UTF_8))
    println("got UserSignatureData: ${plainSigData.toString(Charsets.UTF_8)}")
    for (sig in sigObj.value.orderSignatureList ?: listOf()) {
        println("inserting order signature for orderID $orderID and orderType $orderType")
        EbicsOrderSignatureEntity.new {
            this.orderID = orderID
            this.orderType = orderType
            this.partnerID = sig.partnerID
            this.userID = sig.userID
            this.signatureAlgorithm = sig.signatureVersion
            this.signatureValue = SerialBlob(sig.signatureValue)
        }
    }

    return EbicsResponse.createForUploadInitializationPhase(transactionID, orderID)
}


private fun handleEbicsUploadTransactionTransmission(requestContext: RequestContext): EbicsResponse {
    val uploadTransaction = requestContext.uploadTransaction ?: throw EbicsInvalidRequestError()
    val requestObject = requestContext.requestObject
    val requestSegmentNumber =
        requestContext.requestObject.header.mutable.segmentNumber?.value?.toInt() ?: throw EbicsInvalidRequestError()
    val requestTransactionID = requestObject.header.static.transactionID ?: throw EbicsInvalidRequestError()
    if (requestSegmentNumber == 1 && uploadTransaction.numSegments == 1) {
        val encOrderData =
            requestObject.body.dataTransfer?.orderData ?: throw EbicsInvalidRequestError()
        val zippedData = CryptoUtil.decryptEbicsE002(
            uploadTransaction.transactionKeyEnc.toByteArray(),
            Base64.getDecoder().decode(encOrderData),
            requestContext.hostEncPriv
        )
        val unzippedData =
            InflaterInputStream(zippedData.inputStream()).use { it.readAllBytes() }
        logger.debug("got upload data: ${unzippedData.toString(Charsets.UTF_8)}")

        val sigs  = EbicsOrderSignatureEntity.find {
            (EbicsOrderSignaturesTable.orderID eq uploadTransaction.orderID) and
                    (EbicsOrderSignaturesTable.orderType eq uploadTransaction.orderType)
        }

        if (sigs.count() == 0) {
            throw EbicsInvalidRequestError()
        }

        for (sig in sigs) {
            if (sig.signatureAlgorithm == "A006") {

                val signedData = CryptoUtil.digestEbicsOrderA006(unzippedData)
                val res1 = CryptoUtil.verifyEbicsA006(sig.signatureValue.toByteArray(), signedData, requestContext.clientSigPub)

                if (!res1) {
                    throw EbicsInvalidRequestError()
                }

            } else {
                throw NotImplementedError()
            }
        }

        return EbicsResponse.createForUploadTransferPhase(
            requestTransactionID,
            requestSegmentNumber,
            true,
            uploadTransaction.orderID
        )
    } else {
        throw NotImplementedError()
    }
}

private fun makeReqestContext(requestObject: EbicsRequest): RequestContext {
    val staticHeader = requestObject.header.static
    val requestedHostId = staticHeader.hostID
    val ebicsHost =
        EbicsHostEntity.find { EbicsHostsTable.hostID.upperCase() eq requestedHostId.toUpperCase() }
            .firstOrNull()
    val requestTransactionID = requestObject.header.static.transactionID
    var downloadTransaction: EbicsDownloadTransactionEntity? = null
    var uploadTransaction: EbicsUploadTransactionEntity? = null
    val subscriber = if (requestTransactionID != null) {
        println("finding subscriber by transactionID $requestTransactionID")
        downloadTransaction = EbicsDownloadTransactionEntity.findById(requestTransactionID.toUpperCase())
        if (downloadTransaction != null) {
            downloadTransaction.subscriber
        } else {
            uploadTransaction = EbicsUploadTransactionEntity.findById(requestTransactionID)
            uploadTransaction?.subscriber
        }
    } else {
        val partnerID = staticHeader.partnerID ?: throw EbicsInvalidRequestError()
        val userID = staticHeader.userID ?: throw EbicsInvalidRequestError()
        findEbicsSubscriber(partnerID, userID, staticHeader.systemID)
    }

    if (ebicsHost == null) throw EbicsInvalidRequestError()

    /**
     * NOTE: production logic must check against READY state (the
     * one activated after the subscriber confirms their keys via post)
     */
    if (subscriber == null || subscriber.state != SubscriberState.INITIALIZED)
        throw EbicsSubscriberStateError()

    val hostAuthPriv = CryptoUtil.loadRsaPrivateKey(
        ebicsHost.authenticationPrivateKey
            .toByteArray()
    )
    val hostEncPriv = CryptoUtil.loadRsaPrivateKey(
        ebicsHost.encryptionPrivateKey
            .toByteArray()
    )
    val clientAuthPub =
        CryptoUtil.loadRsaPublicKey(subscriber.authenticationKey!!.rsaPublicKey.toByteArray())
    val clientEncPub =
        CryptoUtil.loadRsaPublicKey(subscriber.encryptionKey!!.rsaPublicKey.toByteArray())
    val clientSigPub =
        CryptoUtil.loadRsaPublicKey(subscriber.signatureKey!!.rsaPublicKey.toByteArray())

    return RequestContext(
        hostAuthPriv = hostAuthPriv,
        hostEncPriv = hostEncPriv,
        clientAuthPub = clientAuthPub,
        clientEncPub = clientEncPub,
        clientSigPub = clientSigPub,
        ebicsHost = ebicsHost,
        requestObject = requestObject,
        subscriber = subscriber,
        downloadTransaction = downloadTransaction,
        uploadTransaction = uploadTransaction
    )
}


suspend fun ApplicationCall.ebicsweb() {
    val requestDocument = receiveEbicsXml()

    LOGGER.info("Processing ${requestDocument.documentElement.localName}")

    when (requestDocument.documentElement.localName) {
        "ebicsUnsecuredRequest" -> {
            val requestObject = requestDocument.toObject<EbicsUnsecuredRequest>()
            LOGGER.info("Serving a ${requestObject.header.static.orderDetails.orderType} request")

            val orderData = requestObject.body.dataTransfer.orderData.value
            val header = requestObject.header

            when (header.static.orderDetails.orderType) {
                "INI" -> handleEbicsIni(header, orderData)
                "HIA" -> handleEbicsHia(header, orderData)
                else -> throw EbicsInvalidXmlError()
            }
        }
        "ebicsHEVRequest" -> {
            val hevResponse = HEVResponse().apply {
                this.systemReturnCode = SystemReturnCodeType().apply {
                    this.reportText = "[EBICS_OK]"
                    this.returnCode = "000000"
                }
                this.versionNumber = listOf(HEVResponse.VersionNumber.create("H004", "02.50"))
            }

            val strResp = XMLUtil.convertJaxbToString(hevResponse)
            LOGGER.debug("HEV response: $strResp")
            respondText(strResp, ContentType.Application.Xml, HttpStatusCode.OK)
        }
        "ebicsNoPubKeyDigestsRequest" -> {
            val requestObject = requestDocument.toObject<EbicsNpkdRequest>()
            val hostInfo = ensureEbicsHost(requestObject.header.static.hostID)
            when (requestObject.header.static.orderDetails.orderType) {
                "HPB" -> handleEbicsHpb(hostInfo, requestDocument, requestObject.header)
                else -> throw EbicsInvalidXmlError()
            }
        }
        "ebicsRequest" -> {
            println("ebicsRequest ${XMLUtil.convertDomToString(requestDocument)}")
            val requestObject = requestDocument.toObject<EbicsRequest>()

            val responseXmlStr = transaction {
                // Step 1 of 3:  Get information about the host and subscriber

                val requestContext = makeReqestContext(requestObject)

                // Step 2 of 3:  Validate the signature
                val verifyResult = XMLUtil.verifyEbicsDocument(requestDocument, requestContext.clientAuthPub)
                if (!verifyResult) {
                    throw EbicsInvalidRequestError()
                }

                // Step 3 of 3:  Generate response
                val ebicsResponse: EbicsResponse = when (requestObject.header.mutable.transactionPhase) {
                    EbicsTypes.TransactionPhaseType.INITIALISATION -> {
                        if (requestObject.header.static.numSegments == null) {
                            handleEbicsDownloadTransactionInitialization(requestContext)
                        } else {
                            handleEbicsUploadTransactionInitialization(requestContext)
                        }
                    }
                    EbicsTypes.TransactionPhaseType.TRANSFER -> {
                        if (requestContext.uploadTransaction != null) {
                            handleEbicsUploadTransactionTransmission(requestContext)
                        } else if (requestContext.downloadTransaction != null) {
                            throw NotImplementedError()
                        } else {
                            throw AssertionError()
                        }
                    }
                    EbicsTypes.TransactionPhaseType.RECEIPT -> {
                        val requestTransactionID = requestObject.header.static.transactionID ?: throw EbicsInvalidRequestError()
                        if (requestContext.downloadTransaction == null)
                            throw EbicsInvalidRequestError()
                        val receiptCode =
                            requestObject.body.transferReceipt?.receiptCode ?: throw EbicsInvalidRequestError()
                        EbicsResponse.createForDownloadReceiptPhase(requestTransactionID, receiptCode == 0)
                    }
                }
                signEbicsResponse(ebicsResponse, requestContext.hostAuthPriv)
            }
            respondText(responseXmlStr, ContentType.Application.Xml, HttpStatusCode.OK)
        }
        else -> {
            /* Log to console and return "unknown type" */
            LOGGER.info("Unknown message, just logging it!")
            respond(
                HttpStatusCode.NotImplemented,
                SandboxError("Not Implemented")
            )
        }
    }
}
