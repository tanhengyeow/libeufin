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
 * Parse and generate ISO 20022 messages
 */
package tech.libeufin.nexus

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import org.w3c.dom.Document
import tech.libeufin.nexus.server.CurrencyAmount
import tech.libeufin.util.*
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

enum class CreditDebitIndicator {
    DBIT, CRDT
}

enum class EntryStatus {
    /**
     * Booked
     */
    BOOK,

    /**
     * Pending
     */
    PDNG,

    /**
     * Informational
     */
    INFO,
}

enum class CashManagementResponseType(@get:JsonValue val jsonName: String) {
    Report("report"), Statement("statement"), Notification("notification")
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CamtReport(
    val account: CashAccount,
    val entries: List<CamtBankAccountEntry>
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GenericId(
    val id: String,
    val schemeName: String?,
    val proprietarySchemeName: String?,
    val issuer: String?
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CashAccount(
    val name: String?,
    val currency: String?,
    val iban: String?,
    val otherId: GenericId?
)

data class Balance(
    val type: String?,
    val subtype: String?,
    val proprietaryType: String?,
    val proprietarySubtype: String?,
    val date: String,
    val creditDebitIndicator: CreditDebitIndicator,
    val amount: CurrencyAmount
)

data class CamtParseResult(
    val reports: List<CamtReport>,
    val balances: List<Balance>,
    val messageId: String,
    /**
     * Message type in form of the ISO 20022 message name.
     */
    val messageType: CashManagementResponseType,
    val creationDateTime: String
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PrivateIdentification(
    val birthDate: String?,
    val provinceOfBirth: String?,
    val cityOfBirth: String?,
    val countryOfBirth: String?
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class OrganizationIdentification(
    val bic: String?,
    val lei: String?
)

/**
 * Identification of a party, which can be a private party
 * or an organiation.
 *
 * Mapping of ISO 20022 PartyIdentification135.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PartyIdentification(
    val name: String?,
    val countryOfResidence: String?,
    val privateId: PrivateIdentification?,
    val organizationId: OrganizationIdentification?,

    /**
     * Identification that applies to both private parties and organizations.
     */
    val otherId: GenericId?
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AgentIdentification(
    val name: String?,
    val bic: String?,
    val otherId: GenericId?
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CurrencyExchange(
    val sourceCurrency: String,
    val targetCurrency: String,
    val unitCurrency: String?,
    val exchangeRate: String,
    val contractId: String?,
    val quotationDate: String?
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class TransactionInfo(
    val batchPaymentInformationId: String?,
    val batchMessageId: String?,

    val debtor: PartyIdentification?,
    val debtorAccount: CashAccount?,
    val debtorAgent: AgentIdentification?,
    val creditor: PartyIdentification?,
    val creditorAccount: CashAccount?,
    val creditorAgent: AgentIdentification?,

    val endToEndId: String? = null,
    val paymentInformationId: String? = null,
    val messageId: String? = null,

    val amount: CurrencyAmount,
    val creditDebitIndicator: CreditDebitIndicator,

    val instructedAmount: CurrencyAmount?,
    val transactionAmount: CurrencyAmount?,

    val instructedAmountCurrencyExchange: CurrencyExchange?,
    val transactionAmountCurrencyExchange: CurrencyExchange?,

    /**
     * Unstructured remittance information (=subject line) of the transaction,
     * or the empty string if missing.
     */
    val unstructuredRemittanceInformation: String,
    val returnInfo: ReturnInfo?
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ReturnInfo(
    val originalBankTransactionCode: BankTransactionCode?,
    val originator: PartyIdentification?,
    val reason: String?,
    val proprietaryReason: String?,
    val additionalInfo: String?
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CamtBankAccountEntry(
    val entryAmount: CurrencyAmount,

    /**
     * Is this entry debiting or crediting the account
     * it is reported for?
     */
    val creditDebitIndicator: CreditDebitIndicator,

    /**
     * Booked, pending, etc.
     */
    val status: EntryStatus,

    /**
     * Code that describes the type of bank transaction
     * in more detail
     */

    val bankTransactionCode: BankTransactionCode,
    /**
     * Transaction details, if this entry contains a single transaction.
     */
    val transactionInfos: List<TransactionInfo>,
    val valueDate: String?,
    val bookingDate: String?,
    val accountServicerRef: String?,
    val entryRef: String?
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class BankTransactionCode(
    val domain: String?,
    val family: String?,
    val subfamily: String?,
    val proprietaryCode: String?,
    val proprietaryIssuer: String?
)

class CamtParsingError(msg: String) : Exception(msg)

/**
 * Data that the LibEuFin nexus uses for payment initiation.
 * Subset of what ISO 20022 allows.
 */
data class NexusPaymentInitiationData(
    val debtorIban: String,
    val debtorBic: String?,
    val debtorName: String,
    val messageId: String,
    val paymentInformationId: String,
    val endToEndId: String?,
    val amount: String,
    val currency: String,
    val subject: String,
    val preparationTimestamp: Long,
    val creditorName: String,
    val creditorIban: String,
    val instructionId: String?
)

/**
 * Create a PAIN.001 XML document according to the input data.
 * Needs to be called within a transaction block.
 */
fun createPain001document(paymentData: NexusPaymentInitiationData): String {
    // Every PAIN.001 document contains at least three IDs:
    //
    // 1) MsgId: a unique id for the message itself
    // 2) PmtInfId: the unique id for the payment's set of information
    // 3) EndToEndId: a unique id to be shared between the debtor and
    //    creditor that uniquely identifies the transaction
    //
    // For now and for simplicity, since every PAIN entry in the database
    // has a unique ID, and the three values aren't required to be mutually different,
    // we'll assign the SAME id (= the row id) to all the three aforementioned
    // PAIN id types.

    val s = constructXml(indent = true) {
        root("Document") {
            attribute("xmlns", "urn:iso:std:iso:20022:tech:xsd:pain.001.001.03")
            attribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
            attribute(
                "xsi:schemaLocation",
                "urn:iso:std:iso:20022:tech:xsd:pain.001.001.03 pain.001.001.03.xsd"
            )
            element("CstmrCdtTrfInitn") {
                element("GrpHdr") {
                    element("MsgId") {
                        text(paymentData.messageId)
                    }
                    element("CreDtTm") {
                        val dateMillis = paymentData.preparationTimestamp
                        val dateFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
                        val instant = Instant.ofEpochSecond(dateMillis / 1000)
                        val zoned = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault())
                        text(dateFormatter.format(zoned))
                    }
                    element("NbOfTxs") {
                        text("1")
                    }
                    element("CtrlSum") {
                        text(paymentData.amount)
                    }
                    element("InitgPty/Nm") {
                        text(paymentData.debtorName)
                    }
                }
                element("PmtInf") {
                    element("PmtInfId") {
                        text(paymentData.paymentInformationId)
                    }
                    element("PmtMtd") {
                        text("TRF")
                    }
                    element("BtchBookg") {
                        text("true")
                    }
                    element("NbOfTxs") {
                        text("1")
                    }
                    element("CtrlSum") {
                        text(paymentData.amount)
                    }
                    element("PmtTpInf/SvcLvl/Cd") {
                        text("SEPA")
                    }
                    element("ReqdExctnDt") {
                        val dateMillis = paymentData.preparationTimestamp
                        text(importDateFromMillis(dateMillis).toDashedDate())
                    }
                    element("Dbtr/Nm") {
                        text(paymentData.debtorName)
                    }
                    element("DbtrAcct/Id/IBAN") {
                        text(paymentData.debtorIban)
                    }
                    when (val b = paymentData.debtorBic) {
                        null -> element("DbtrAgt/FinInstnId/Othr/Id") { text("NOTPROVIDED") }
                        else -> element("DbtrAgt/FinInstnId/BIC") { text(b) }
                    }
                    element("ChrgBr") {
                        text("SLEV")
                    }
                    element("CdtTrfTxInf") {
                        element("PmtId") {
                            paymentData.instructionId?.let {
                                element("InstrId") { text(it) }
                            }
                            when (val eeid = paymentData.endToEndId) {
                                null -> element("EndToEndId") { text("NOTPROVIDED") }
                                else -> element("EndToEndId") { text(eeid) }
                            }
                        }
                        element("Amt/InstdAmt") {
                            attribute("Ccy", paymentData.currency)
                            text(paymentData.amount)
                        }
                        element("Cdtr/Nm") {
                            text(paymentData.creditorName)
                        }
                        element("CdtrAcct/Id/IBAN") {
                            text(paymentData.creditorIban)
                        }
                        element("RmtInf/Ustrd") {
                            text(paymentData.subject)
                        }
                    }
                }
            }
        }
    }
    return s
}

private fun XmlElementDestructor.extractDateOrDateTime(): String {
    return requireOnlyChild {
        when (it.localName) {
            "Dt" -> e.textContent
            "DtTm" -> e.textContent
            else -> throw Exception("Invalid date / time: ${e.localName}")
        }
    }
}

private fun XmlElementDestructor.extractAgent(): AgentIdentification {
    return AgentIdentification(
        name = maybeUniqueChildNamed("FinInstnId") {
            maybeUniqueChildNamed("Nm") {
                it.textContent
            }
        },
        bic = requireUniqueChildNamed("FinInstnId") {
            requireUniqueChildNamed("BIC") {
                it.textContent
            }
        },
        otherId = null
    )
}

private fun XmlElementDestructor.extractGenericId(): GenericId {
    return GenericId(
        id = requireUniqueChildNamed("Id") { it.textContent },
        schemeName = maybeUniqueChildNamed("SchmeNm") {
            maybeUniqueChildNamed("Cd") { it.textContent }
        },
        issuer = maybeUniqueChildNamed("Issr") { it.textContent },
        proprietarySchemeName = maybeUniqueChildNamed("SchmeNm") {
            maybeUniqueChildNamed("Prtry") { it.textContent }
        }
    )
}

private fun XmlElementDestructor.extractAccount(): CashAccount {
    var iban: String? = null
    var otherId: GenericId? = null
    val currency: String? = maybeUniqueChildNamed("Ccy") { it.textContent }
    val name: String? = maybeUniqueChildNamed("Nm") { it.textContent }
    requireUniqueChildNamed("Id") {
        requireOnlyChild {
            when (it.localName) {
                "IBAN" -> {
                    iban = it.textContent
                }
                "Othr" -> {
                    otherId = extractGenericId()
                }
                else -> throw Error("invalid account identification")
            }
        }
    }
    return CashAccount(name, currency, iban, otherId)
}

private fun XmlElementDestructor.extractParty(): PartyIdentification {
    val otherId: GenericId? = maybeUniqueChildNamed("Id") {
        (maybeUniqueChildNamed("PrvtId") { it } ?: maybeUniqueChildNamed("OrgId") { it })?.run {
            maybeUniqueChildNamed("Othr") {
                extractGenericId()
            }
        }
    }

    val privateId = maybeUniqueChildNamed("Id") {
        maybeUniqueChildNamed("PrvtId") {
            maybeUniqueChildNamed("DtAndPlcOfBirth") {
                PrivateIdentification(
                    birthDate = maybeUniqueChildNamed("BirthDt") { it.textContent},
                    cityOfBirth = maybeUniqueChildNamed("CityOfBirth") { it.textContent},
                    countryOfBirth = maybeUniqueChildNamed("CtryOfBirth") { it.textContent},
                    provinceOfBirth = maybeUniqueChildNamed("PrvcOfBirth") { it.textContent}
                )
            }
        }
    }

    val organizationId = maybeUniqueChildNamed("Id") {
        maybeUniqueChildNamed("OrgId") {
            OrganizationIdentification(
                bic = maybeUniqueChildNamed("BICOrBEI") { it.textContent} ?: maybeUniqueChildNamed("AnyBIC") { it.textContent},
                lei = maybeUniqueChildNamed("LEI") { it.textContent}
            )
        }
    }


    return PartyIdentification(
        name = maybeUniqueChildNamed("Nm") { it.textContent },
        otherId = otherId,
        privateId = privateId,
        organizationId = organizationId,
        countryOfResidence = maybeUniqueChildNamed("CtryOfRes") { it.textContent }
    )
}

private fun XmlElementDestructor.extractCurrencyAmount(): CurrencyAmount {
    return CurrencyAmount(
        amount = requireUniqueChildNamed("Amt") { it.textContent },
        currency = requireUniqueChildNamed("Amt") { it.getAttribute("Ccy") }
    )
}

private fun XmlElementDestructor.maybeExtractCurrencyAmount(): CurrencyAmount? {
    return maybeUniqueChildNamed("Amt") {
        CurrencyAmount(
            it.textContent,
            it.getAttribute("Ccy")
        )
    }
}

private fun XmlElementDestructor.extractMaybeCurrencyExchange(): CurrencyExchange? {
    return maybeUniqueChildNamed("CcyXchg") {
        CurrencyExchange(
            sourceCurrency = requireUniqueChildNamed("SrcCcy") { it.textContent },
            targetCurrency = requireUniqueChildNamed("TgtCcy") { it.textContent },
            contractId = maybeUniqueChildNamed("CtrctId") { it.textContent },
            exchangeRate = requireUniqueChildNamed("XchgRate") { it.textContent },
            quotationDate = maybeUniqueChildNamed("QtnDt") { it.textContent },
            unitCurrency = maybeUniqueChildNamed("UnitCcy") { it.textContent }
        )
    }
}


private fun XmlElementDestructor.extractTransactionInfos(
    outerAmount: CurrencyAmount,
    outerCreditDebitIndicator: CreditDebitIndicator
): List<TransactionInfo> {

    val numTxDtls = requireUniqueChildNamed("NtryDtls") {
        mapEachChildNamed("TxDtls") { Unit }
    }.count()

    return requireUniqueChildNamed("NtryDtls") {
        mapEachChildNamed("TxDtls") {

            val instructedAmount = maybeUniqueChildNamed("AmtDtls") {
                maybeUniqueChildNamed("InstrAmt") { extractCurrencyAmount() }
            }

            val transactionAmount = maybeUniqueChildNamed("AmtDtls") {
                maybeUniqueChildNamed("TxAmt") { extractCurrencyAmount() }
            }

            var amount = maybeExtractCurrencyAmount()
            var creditDebitIndicator = maybeUniqueChildNamed("CdtDbtInd") { it.textContent }?.let {
                CreditDebitIndicator.valueOf(it)
            }
            if (amount == null) {
                when {
                    numTxDtls == 1 -> {
                        amount = outerAmount
                        creditDebitIndicator = outerCreditDebitIndicator
                    }
                    transactionAmount?.currency == outerAmount.currency -> {
                        amount = transactionAmount
                        creditDebitIndicator = outerCreditDebitIndicator
                    }
                    instructedAmount?.currency == outerAmount.currency -> {
                        amount = instructedAmount
                        creditDebitIndicator = outerCreditDebitIndicator
                    }
                    else -> {
                        throw Error("invalid camt, no amount for transaction details of entry details")
                    }
                }
            }

            if (creditDebitIndicator == null) {
                throw Error("invalid camt, no credit/debit indicator for transaction details of entry details")
            }

            TransactionInfo(
                batchMessageId = null,
                batchPaymentInformationId = null,
                amount = amount,
                creditDebitIndicator = creditDebitIndicator,
                instructedAmount = instructedAmount,
                instructedAmountCurrencyExchange = maybeUniqueChildNamed("AmtDtls") {
                    maybeUniqueChildNamed("InstrAmt") { extractMaybeCurrencyExchange() }
                },
                transactionAmount = transactionAmount,
                transactionAmountCurrencyExchange = maybeUniqueChildNamed("AmtDtls") {
                    maybeUniqueChildNamed("TxAmt") { extractMaybeCurrencyExchange() }
                },
                endToEndId = maybeUniqueChildNamed("Refs") {
                    maybeUniqueChildNamed("EndToEndId") { it.textContent }
                },
                messageId = maybeUniqueChildNamed("Refs") {
                    maybeUniqueChildNamed("MsgId") { it.textContent }
                },
                paymentInformationId = maybeUniqueChildNamed("Refs") {
                    maybeUniqueChildNamed("PmtInfId") { it.textContent }
                },
                unstructuredRemittanceInformation = maybeUniqueChildNamed("RmtInf") {
                    val chunks = mapEachChildNamed("Ustrd", { it.textContent })
                    if (chunks.isEmpty()) {
                        null
                    } else {
                        chunks.joinToString(separator = "")
                    }
                } ?: "",
                creditorAgent = maybeUniqueChildNamed("CdtrAgt") { extractAgent() },
                debtorAgent = maybeUniqueChildNamed("DbtrAgt") { extractAgent() },
                debtorAccount = maybeUniqueChildNamed("DbtrAgt") { extractAccount() },
                creditorAccount = maybeUniqueChildNamed("CdtrAgt") { extractAccount() },
                debtor = maybeUniqueChildNamed("Dbtr") { extractParty() },
                creditor = maybeUniqueChildNamed("Cdtr") { extractParty() },
                returnInfo = maybeUniqueChildNamed("RtrInf") {
                    ReturnInfo(
                        originalBankTransactionCode = maybeUniqueChildNamed("OrgnlBkTxCd") {
                            extractInnerBkTxCd()
                        },
                        originator = maybeUniqueChildNamed("Orgtr") { extractParty() },
                        reason = maybeUniqueChildNamed("Rsn") { maybeUniqueChildNamed("Cd") { it.textContent } },
                        proprietaryReason = maybeUniqueChildNamed("Rsn") { maybeUniqueChildNamed("Prtry") { it.textContent } },
                        additionalInfo = maybeUniqueChildNamed("AddtlInf") { it.textContent }
                    )
                }
            )
        }
    }
}

private fun XmlElementDestructor.extractInnerBkTxCd(): BankTransactionCode {
    return BankTransactionCode(
        domain = maybeUniqueChildNamed("Domn") { maybeUniqueChildNamed("Cd") { it.textContent } },
        family = maybeUniqueChildNamed("Domn") {
            maybeUniqueChildNamed("Fmly") {
                maybeUniqueChildNamed("Cd") { it.textContent }
            }
        },
        subfamily = maybeUniqueChildNamed("Domn") {
            maybeUniqueChildNamed("Fmly") {
                maybeUniqueChildNamed("SubFmlyCd") { it.textContent }
            }
        },
        proprietaryCode = maybeUniqueChildNamed("Prtry") {
            maybeUniqueChildNamed("Cd") { it.textContent }
        },
        proprietaryIssuer = maybeUniqueChildNamed("Prtry") {
            maybeUniqueChildNamed("Issr") { it.textContent }
        }
    )
}

private fun XmlElementDestructor.extractInnerTransactions(): CamtReport {
    val account = requireUniqueChildNamed("Acct") { extractAccount() }
    val entries = mapEachChildNamed("Ntry") {
        val amount = requireUniqueChildNamed("Amt") { it.textContent }
        val currency = requireUniqueChildNamed("Amt") { it.getAttribute("Ccy") }
        val status = requireUniqueChildNamed("Sts") { it.textContent }.let {
            EntryStatus.valueOf(it)
        }
        val creditDebitIndicator = requireUniqueChildNamed("CdtDbtInd") { it.textContent }.let {
            CreditDebitIndicator.valueOf(it)
        }
        val btc = requireUniqueChildNamed("BkTxCd") {
            extractInnerBkTxCd()
        }
        val acctSvcrRef = maybeUniqueChildNamed("AcctSvcrRef") { it.textContent }
        val entryRef = maybeUniqueChildNamed("NtryRef") { it.textContent }
        // For now, only support account servicer reference as id
        val transactionInfos = extractTransactionInfos(CurrencyAmount(currency, amount), creditDebitIndicator)
        CamtBankAccountEntry(
            entryAmount = CurrencyAmount(currency, amount),
            status = status,
            creditDebitIndicator = creditDebitIndicator,
            bankTransactionCode = btc,
            transactionInfos = transactionInfos,
            bookingDate = maybeUniqueChildNamed("BookgDt") { extractDateOrDateTime() },
            valueDate = maybeUniqueChildNamed("ValDt") { extractDateOrDateTime() },
            accountServicerRef = acctSvcrRef,
            entryRef = entryRef
        )
    }
    return CamtReport(account, entries)
}

/**
 * Extract a list of transactions from an ISO20022 camt.052 / camt.053 message.
 */
fun parseCamtMessage(doc: Document): CamtParseResult {
    return destructXml(doc) {
        requireRootElement("Document") {
            // Either bank to customer statement or report
            val reports = requireOnlyChild {
                when (it.localName) {
                    "BkToCstmrAcctRpt" -> {
                        mapEachChildNamed("Rpt") {
                            extractInnerTransactions()
                        }
                    }
                    "BkToCstmrStmt" -> {
                        mapEachChildNamed("Stmt") {
                            extractInnerTransactions()
                        }
                    }
                    else -> {
                        throw CamtParsingError("expected statement or report")
                    }
                }
            }

            val balances = requireOnlyChild {
                mapEachChildNamed("Bal") {
                    Balance(
                        type = maybeUniqueChildNamed("Tp") { maybeUniqueChildNamed("Cd") { it.textContent } },
                        proprietaryType = maybeUniqueChildNamed("Tp") { maybeUniqueChildNamed("Prtry") { it.textContent } },
                        date = extractDateOrDateTime(),
                        creditDebitIndicator = requireUniqueChildNamed("CdtDbtInd") { it.textContent }.let {
                            CreditDebitIndicator.valueOf(it)
                        },
                        subtype = maybeUniqueChildNamed("SubTp") { maybeUniqueChildNamed("Cd") { it.textContent } },
                        proprietarySubtype = maybeUniqueChildNamed("SubTp") { maybeUniqueChildNamed("Prtry") { it.textContent } },
                        amount = extractCurrencyAmount()
                    )
                }
            }

            val messageId = requireOnlyChild {
                requireUniqueChildNamed("GrpHdr") {
                    requireUniqueChildNamed("MsgId") { it.textContent }
                }
            }
            val creationDateTime = requireOnlyChild {
                requireUniqueChildNamed("GrpHdr") {
                    requireUniqueChildNamed("CreDtTm") { it.textContent }
                }
            }
            val messageType = requireOnlyChild {
                when (it.localName) {
                    "BkToCstmrAcctRpt" -> CashManagementResponseType.Report
                    "BkToCstmrStmt" -> CashManagementResponseType.Statement
                    else -> {
                        throw CamtParsingError("expected statement or report")
                    }
                }
            }
            CamtParseResult(reports, balances, messageId, messageType, creationDateTime)
        }
    }
}
