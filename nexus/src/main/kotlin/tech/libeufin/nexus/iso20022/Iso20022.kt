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
package tech.libeufin.nexus.iso20022

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import org.w3c.dom.Document
import tech.libeufin.nexus.server.CurrencyAmount
import tech.libeufin.util.*
import java.math.BigDecimal
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
    val id: String,
    val creationDateTime: String?,
    val legalSequenceNumber: Int?,
    val electronicSequenceNumber: Int?,
    val fromDate: String?,
    val toDate: String?,
    val reportingSource: String?,
    val proprietaryReportingSource: String?,
    val account: CashAccount,
    val balances: List<Balance>,
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

@JsonInclude(JsonInclude.Include.NON_NULL)
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
    /**
     * Message type in form of the ISO 20022 message name.
     */
    val messageType: CashManagementResponseType,
    val messageId: String,
    val creationDateTime: String,
    val reports: List<CamtReport>
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
 * or an organization.
 *
 * Mapping of ISO 20022 PartyIdentification135.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PartyIdentification(
    val name: String?,
    val countryOfResidence: String?,
    val privateId: PrivateIdentification?,
    val organizationId: OrganizationIdentification?,
    val postalAddress: PostalAddress?,

    /**
     * Identification that applies to both private parties and organizations.
     */
    val otherId: GenericId?
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PostalAddress(
    val addressCode: String?,
    val addressProprietaryId: String?,
    val addressProprietarySchemeName: String?,
    val addressProprietaryIssuer: String?,
    val department: String?,
    val subDepartment: String?,
    val streetName: String?,
    val buildingNumber: String?,
    val buildingName: String?,
    val floor: String?,
    val postBox: String?,
    val room: String?,
    val postCode: String?,
    val townName: String?,
    val townLocationName: String?,
    val districtName: String?,
    val countrySubDivision: String?,
    val country: String?,
    val addressLines: List<String>
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AgentIdentification(
    val name: String?,

    val bic: String?,

    /**
     * Legal entity identification.
     */
    val lei: String?,

    val clearingSystemMemberId: String?,

    val clearingSystemCode: String?,

    val proprietaryClearingSystemCode: String?,

    val postalAddress: PostalAddress?,

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
data class TransactionDetails(
    val debtor: PartyIdentification?,
    val debtorAccount: CashAccount?,
    val debtorAgent: AgentIdentification?,
    val creditor: PartyIdentification?,
    val creditorAccount: CashAccount?,
    val creditorAgent: AgentIdentification?,
    val ultimateCreditor: PartyIdentification?,
    val ultimateDebtor: PartyIdentification?,

    val endToEndId: String? = null,
    val paymentInformationId: String? = null,
    val messageId: String? = null,

    val purpose: String?,
    val proprietaryPurpose: String?,

    /**
     * Currency exchange information for the transaction's amount.
     */
    val currencyExchange: CurrencyExchange?,

    /**
     * Amount as given in the payment initiation.
     * Can be same or different currency as account currency.
     */
    val instructedAmount: CurrencyAmount?,

    /**
     * Raw amount used for currency exchange, before extra charges.
     * Can be same or different currency as account currency.
     */
    val counterValueAmount: CurrencyAmount?,

    /**
     * Money that was moved between banks.
     *
     * For CH, we use the "TxAmt".
     * For EPC, this amount is either blank or taken
     * from the "IBC" proprietary amount.
     */
    val interBankSettlementAmount: CurrencyAmount?,

    /**
     * Unstructured remittance information (=subject line) of the transaction,
     * or the empty string if missing.
     */
    val unstructuredRemittanceInformation: String,
    val returnInfo: ReturnInfo?
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ReturnInfo(
    val originalBankTransactionCode: String?,
    val originator: PartyIdentification?,
    val reason: String?,
    val proprietaryReason: String?,
    val additionalInfo: String?
)

data class BatchTransaction(
    val amount: CurrencyAmount,

    /**
     * Is this entry debiting or crediting the account
     * it is reported for?
     */
    val creditDebitIndicator: CreditDebitIndicator,

    val details: TransactionDetails
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Batch(
    val messageId: String?,
    val paymentInformationId: String?,
    val batchTransactions: List<BatchTransaction>
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CamtBankAccountEntry(
    val amount: CurrencyAmount,

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
    val bankTransactionCode: String,

    val valueDate: String?,

    val bookingDate: String?,

    val accountServicerRef: String?,

    val entryRef: String?,

    /**
     * Currency exchange information for the entry's amount.
     * Only present if currency exchange happened at the entry level.
     */
    val currencyExchange: CurrencyExchange?,

    /**
     * Value before/after currency exchange before charges have been applied.
     * Only present if currency exchange happened at the entry level.
     */
    val counterValueAmount: CurrencyAmount?,

    /**
     * Instructed amount.
     * Only present if currency exchange happens at the entry level.
     */
    val instructedAmount: CurrencyAmount?,

    /**
     * Details of the underlying transaction for type=Simple.
     */
    val details: TransactionDetails?,
    val batches: List<Batch>?
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
        when (focusElement.localName) {
            "Dt" -> focusElement.textContent
            "DtTm" -> focusElement.textContent
            else -> throw Exception("Invalid date / time: ${focusElement.localName}")
        }
    }
}

private fun XmlElementDestructor.extractInnerPostalAddress(): PostalAddress {
    return PostalAddress(
        addressCode = maybeUniqueChildNamed("AdrTp") { maybeUniqueChildNamed("Cd") { focusElement.textContent } },
        addressProprietaryIssuer = maybeUniqueChildNamed("AdrTp") {
            maybeUniqueChildNamed("Prtry") {
                maybeUniqueChildNamed("Issr") { focusElement.textContent }
            }
        },
        addressProprietarySchemeName = maybeUniqueChildNamed("AdrTp") {
            maybeUniqueChildNamed("Prtry") {
                maybeUniqueChildNamed("SchmeNm") { focusElement.textContent }
            }
        },
        addressProprietaryId = maybeUniqueChildNamed("AdrTp") {
            maybeUniqueChildNamed("Prtry") {
                maybeUniqueChildNamed("Id") { focusElement.textContent }
            }
        },
        buildingName = maybeUniqueChildNamed("BldgNm") { focusElement.textContent },
        buildingNumber = maybeUniqueChildNamed("BldgNb") { focusElement.textContent },
        country = maybeUniqueChildNamed("Ctry") { focusElement.textContent },
        countrySubDivision = maybeUniqueChildNamed("CtrySubDvsn") { focusElement.textContent },
        department = maybeUniqueChildNamed("Dept") { focusElement.textContent },
        districtName = maybeUniqueChildNamed("DstrctNm") { focusElement.textContent },
        floor = maybeUniqueChildNamed("Flr") { focusElement.textContent },
        postBox = maybeUniqueChildNamed("PstBx") { focusElement.textContent },
        postCode = maybeUniqueChildNamed("PstCd") { focusElement.textContent },
        room = maybeUniqueChildNamed("Room") { focusElement.textContent },
        streetName = maybeUniqueChildNamed("StrtNm") { focusElement.textContent },
        subDepartment = maybeUniqueChildNamed("SubDept") { focusElement.textContent },
        townLocationName = maybeUniqueChildNamed("TwnLctnNm") { focusElement.textContent },
        townName = maybeUniqueChildNamed("TwnNm") { focusElement.textContent },
        addressLines = mapEachChildNamed("AdrLine") { focusElement.textContent }
    )
}

private fun XmlElementDestructor.extractAgent(): AgentIdentification {
    return AgentIdentification(
        name = maybeUniqueChildNamed("FinInstnId") {
            maybeUniqueChildNamed("Nm") { focusElement.textContent }
        },
        bic = requireUniqueChildNamed("FinInstnId") {
            maybeUniqueChildNamed("BIC") { focusElement.textContent }
        },
        lei = requireUniqueChildNamed("FinInstnId") {
            maybeUniqueChildNamed("LEI") { focusElement.textContent }
        },
        clearingSystemCode = requireUniqueChildNamed("FinInstnId") {
            maybeUniqueChildNamed("ClrSysMmbId") {
                maybeUniqueChildNamed("ClrSysId") {
                    maybeUniqueChildNamed("Cd") { focusElement.textContent }
                }
            }
        },
        proprietaryClearingSystemCode = requireUniqueChildNamed("FinInstnId") {
            maybeUniqueChildNamed("ClrSysMmbId") {
                maybeUniqueChildNamed("ClrSysId") {
                    maybeUniqueChildNamed("Prtry") { focusElement.textContent }
                }
            }
        },
        clearingSystemMemberId = requireUniqueChildNamed("FinInstnId") {
            maybeUniqueChildNamed("ClrSysMmbId") {
                maybeUniqueChildNamed("MmbId") { focusElement.textContent }
            }
        },
        otherId = requireUniqueChildNamed("FinInstnId") { maybeUniqueChildNamed("Othr") { extractGenericId() } },
        postalAddress = requireUniqueChildNamed("FinInstnId") { maybeUniqueChildNamed("PstlAdr") { extractInnerPostalAddress() } }
    )
}

private fun XmlElementDestructor.extractGenericId(): GenericId {
    return GenericId(
        id = requireUniqueChildNamed("Id") { focusElement.textContent },
        schemeName = maybeUniqueChildNamed("SchmeNm") {
            maybeUniqueChildNamed("Cd") { focusElement.textContent }
        },
        issuer = maybeUniqueChildNamed("Issr") { focusElement.textContent },
        proprietarySchemeName = maybeUniqueChildNamed("SchmeNm") {
            maybeUniqueChildNamed("Prtry") { focusElement.textContent }
        }
    )
}

private fun XmlElementDestructor.extractAccount(): CashAccount {
    var iban: String? = null
    var otherId: GenericId? = null
    val currency: String? = maybeUniqueChildNamed("Ccy") { focusElement.textContent }
    val name: String? = maybeUniqueChildNamed("Nm") { focusElement.textContent }
    requireUniqueChildNamed("Id") {
        requireOnlyChild {
            when (focusElement.localName) {
                "IBAN" -> {
                    iban = focusElement.textContent
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
        (maybeUniqueChildNamed("PrvtId") { focusElement } ?: maybeUniqueChildNamed("OrgId") { focusElement })?.run {
            maybeUniqueChildNamed("Othr") {
                extractGenericId()
            }
        }
    }

    val privateId = maybeUniqueChildNamed("Id") {
        maybeUniqueChildNamed("PrvtId") {
            maybeUniqueChildNamed("DtAndPlcOfBirth") {
                PrivateIdentification(
                    birthDate = maybeUniqueChildNamed("BirthDt") { focusElement.textContent },
                    cityOfBirth = maybeUniqueChildNamed("CityOfBirth") { focusElement.textContent },
                    countryOfBirth = maybeUniqueChildNamed("CtryOfBirth") { focusElement.textContent },
                    provinceOfBirth = maybeUniqueChildNamed("PrvcOfBirth") { focusElement.textContent }
                )
            }
        }
    }

    val organizationId = maybeUniqueChildNamed("Id") {
        maybeUniqueChildNamed("OrgId") {
            OrganizationIdentification(
                bic = maybeUniqueChildNamed("BICOrBEI") { focusElement.textContent }
                    ?: maybeUniqueChildNamed("AnyBIC") { focusElement.textContent },
                lei = maybeUniqueChildNamed("LEI") { focusElement.textContent }
            )
        }
    }

    return PartyIdentification(
        name = maybeUniqueChildNamed("Nm") { focusElement.textContent },
        otherId = otherId,
        privateId = privateId,
        organizationId = organizationId,
        countryOfResidence = maybeUniqueChildNamed("CtryOfRes") { focusElement.textContent },
        postalAddress = maybeUniqueChildNamed("PstlAdr") { extractInnerPostalAddress() }
    )
}

private fun XmlElementDestructor.extractCurrencyAmount(): CurrencyAmount {
    return CurrencyAmount(
        value = BigDecimal(requireUniqueChildNamed("Amt") { focusElement.textContent }),
        currency = requireUniqueChildNamed("Amt") { focusElement.getAttribute("Ccy") }
    )
}

private fun XmlElementDestructor.maybeExtractCurrencyAmount(): CurrencyAmount? {
    return maybeUniqueChildNamed("Amt") {
        CurrencyAmount(
            focusElement.getAttribute("Ccy"),
            BigDecimal(focusElement.textContent)
        )
    }
}

private fun XmlElementDestructor.extractMaybeCurrencyExchange(): CurrencyExchange? {
    return maybeUniqueChildNamed("CcyXchg") {
        CurrencyExchange(
            sourceCurrency = requireUniqueChildNamed("SrcCcy") { focusElement.textContent },
            targetCurrency = requireUniqueChildNamed("TrgtCcy") { focusElement.textContent },
            contractId = maybeUniqueChildNamed("CtrctId") { focusElement.textContent },
            exchangeRate = requireUniqueChildNamed("XchgRate") { focusElement.textContent },
            quotationDate = maybeUniqueChildNamed("QtnDt") { focusElement.textContent },
            unitCurrency = maybeUniqueChildNamed("UnitCcy") { focusElement.textContent }
        )
    }
}

private fun XmlElementDestructor.extractBatches(
    outerAmount: CurrencyAmount,
    outerCreditDebitIndicator: CreditDebitIndicator
): List<Batch> {
    return mapEachChildNamed("NtryDtls") {
        val numDtls = mapEachChildNamed("TxDtls") { Unit }.count()
        var amount = maybeExtractCurrencyAmount()
        var creditDebitIndicator = maybeExtractCreditDebitIndicator()

        val ttlAmt = maybeUniqueChildNamed("Btch") {
            maybeUniqueChildNamed("TtlAmt") {
                CurrencyAmount(
                    value =  BigDecimal(focusElement.textContent),
                    currency = focusElement.getAttribute("Ccy")
                )
            }
        }

        val ttlCreditDebitIndicator = maybeUniqueChildNamed("Btch") {
            maybeExtractCreditDebitIndicator()
        }

        if (amount == null && ttlAmt != null && ttlCreditDebitIndicator != null) {
            amount = ttlAmt
            creditDebitIndicator = ttlCreditDebitIndicator
        } else if (amount == null && numDtls == 1) {
            amount = outerAmount
            creditDebitIndicator = outerCreditDebitIndicator
        }

        if (amount == null || creditDebitIndicator == null) {
            throw Error("no amount for inner transaction")
        }
        val txs = mapEachChildNamed("TxDtls") {
            val details = extractTransactionDetails(outerAmount, outerCreditDebitIndicator, false)
            BatchTransaction(amount, creditDebitIndicator, details)
        }
        Batch(null, null, txs)
    }
}

private fun XmlElementDestructor.maybeExtractCreditDebitIndicator(): CreditDebitIndicator? {
    return maybeUniqueChildNamed("CdtDbtInd") { focusElement.textContent }?.let {
        CreditDebitIndicator.valueOf(it)
    }
}

private fun XmlElementDestructor.extractTransactionDetails(
    outerAmount: CurrencyAmount,
    outerCreditDebitIndicator: CreditDebitIndicator,
    batch: Boolean
): TransactionDetails {
    val instructedAmount = maybeUniqueChildNamed("AmtDtls") {
        maybeUniqueChildNamed("InstdAmt") { extractCurrencyAmount() }
    }

    val creditDebitIndicator = maybeExtractCreditDebitIndicator() ?: outerCreditDebitIndicator

    val currencyExchange = maybeUniqueChildNamed("AmtDtls") {
        val cxCntrVal = maybeUniqueChildNamed("CntrValAmt") { extractMaybeCurrencyExchange() }
        val cxTx = maybeUniqueChildNamed("TxAmt") { extractMaybeCurrencyExchange() }
        val cxInstr = maybeUniqueChildNamed("InstdAmt") { extractMaybeCurrencyExchange() }
        cxCntrVal ?: cxTx ?: cxInstr
    }

    return TransactionDetails(
        instructedAmount = instructedAmount,
        counterValueAmount = maybeUniqueChildNamed("AmtDtls") {
            maybeUniqueChildNamed("CntrValAmt") { extractCurrencyAmount() }
        },
        currencyExchange = currencyExchange,
        // FIXME: implement
        interBankSettlementAmount = null,
        endToEndId = maybeUniqueChildNamed("Refs") {
            maybeUniqueChildNamed("EndToEndId") { focusElement.textContent }
        },
        messageId = maybeUniqueChildNamed("Refs") {
            maybeUniqueChildNamed("MsgId") { focusElement.textContent }
        },
        paymentInformationId = maybeUniqueChildNamed("Refs") {
            maybeUniqueChildNamed("PmtInfId") { focusElement.textContent }
        },
        unstructuredRemittanceInformation = maybeUniqueChildNamed("RmtInf") {
            val chunks = mapEachChildNamed("Ustrd") { focusElement.textContent }
            if (chunks.isEmpty()) {
                null
            } else {
                chunks.joinToString(separator = "")
            }
        } ?: "",
        creditorAgent = maybeUniqueChildNamed("RltdAgts") { maybeUniqueChildNamed("CdtrAgt") { extractAgent() } },
        debtorAgent = maybeUniqueChildNamed("RltdAgts") { maybeUniqueChildNamed("DbtrAgt") { extractAgent() } },
        debtorAccount = maybeUniqueChildNamed("RltdPties") { maybeUniqueChildNamed("DbtrAcct") { extractAccount() } },
        creditorAccount = maybeUniqueChildNamed("RltdPties") { maybeUniqueChildNamed("CdtrAcct") { extractAccount() } },
        debtor = maybeUniqueChildNamed("RltdPties") { maybeUniqueChildNamed("Dbtr") { extractParty() } },
        creditor = maybeUniqueChildNamed("RltdPties") { maybeUniqueChildNamed("Cdtr") { extractParty() } },
        proprietaryPurpose = maybeUniqueChildNamed("Purp") { maybeUniqueChildNamed("Prtry") { focusElement.textContent } },
        purpose = maybeUniqueChildNamed("Purp") { maybeUniqueChildNamed("Cd") { focusElement.textContent } },
        ultimateCreditor = maybeUniqueChildNamed("RltdPties") { maybeUniqueChildNamed("UltmtCdtr") { extractParty() } },
        ultimateDebtor = maybeUniqueChildNamed("RltdPties") { maybeUniqueChildNamed("UltmtDbtr") { extractParty() } },
        returnInfo = maybeUniqueChildNamed("RtrInf") {
            ReturnInfo(
                originalBankTransactionCode = maybeUniqueChildNamed("OrgnlBkTxCd") {
                    extractInnerBkTxCd(
                        when (creditDebitIndicator) {
                            CreditDebitIndicator.DBIT -> CreditDebitIndicator.CRDT
                            CreditDebitIndicator.CRDT -> CreditDebitIndicator.DBIT
                        }
                    )
                },
                originator = maybeUniqueChildNamed("Orgtr") { extractParty() },
                reason = maybeUniqueChildNamed("Rsn") { maybeUniqueChildNamed("Cd") { focusElement.textContent } },
                proprietaryReason = maybeUniqueChildNamed("Rsn") { maybeUniqueChildNamed("Prtry") { focusElement.textContent } },
                additionalInfo = maybeUniqueChildNamed("AddtlInf") { focusElement.textContent }
            )
        }
    )
}

private fun XmlElementDestructor.extractSingleDetails(
    outerAmount: CurrencyAmount,
    outerCreditDebitIndicator: CreditDebitIndicator
): TransactionDetails {
    return requireUniqueChildNamed("NtryDtls") {
        requireUniqueChildNamed("TxDtls") {
            extractTransactionDetails(outerAmount, outerCreditDebitIndicator, false)
        }
    }
}

private fun XmlElementDestructor.extractInnerBkTxCd(creditDebitIndicator: CreditDebitIndicator): String {

    val domain = maybeUniqueChildNamed("Domn") { maybeUniqueChildNamed("Cd") { focusElement.textContent } }
    val family = maybeUniqueChildNamed("Domn") {
        maybeUniqueChildNamed("Fmly") {
            maybeUniqueChildNamed("Cd") { focusElement.textContent }
        }
    }
    val subfamily = maybeUniqueChildNamed("Domn") {
        maybeUniqueChildNamed("Fmly") {
            maybeUniqueChildNamed("SubFmlyCd") { focusElement.textContent }
        }
    }
    val proprietaryCode = maybeUniqueChildNamed("Prtry") {
        maybeUniqueChildNamed("Cd") { focusElement.textContent }
    }
    val proprietaryIssuer = maybeUniqueChildNamed("Prtry") {
        maybeUniqueChildNamed("Issr") { focusElement.textContent }
    }

    if (domain != null && family != null && subfamily != null) {
        return "$domain-$family-$subfamily"
    }
    if (proprietaryIssuer == "DK" && proprietaryCode != null) {
        val components = proprietaryCode.split("+")
        if (components.size == 1) {
            return GbicRules.getBtcFromGvc(creditDebitIndicator, components[0])
        } else {
            return GbicRules.getBtcFromGvc(creditDebitIndicator, components[1])
        }
    }
    // FIXME: log/raise this somewhere?
    return "XTND-NTAV-NTAV"
}


private fun XmlElementDestructor.extractInnerTransactions(): CamtReport {
    val account = requireUniqueChildNamed("Acct") { extractAccount() }

    val balances = mapEachChildNamed("Bal") {
        Balance(
            type = maybeUniqueChildNamed("Tp") {
                maybeUniqueChildNamed("CdOrPrtry") {
                    maybeUniqueChildNamed("Cd") { focusElement.textContent }
                }
            },
            proprietaryType = maybeUniqueChildNamed("Tp") {
                maybeUniqueChildNamed("CdOrPrtry") {
                    maybeUniqueChildNamed("Prtry") { focusElement.textContent }
                }
            },
            date = requireUniqueChildNamed("Dt") { extractDateOrDateTime() },
            creditDebitIndicator = requireUniqueChildNamed("CdtDbtInd") { focusElement.textContent }.let {
                CreditDebitIndicator.valueOf(it)
            },
            subtype = maybeUniqueChildNamed("Tp") {
                maybeUniqueChildNamed("SubTp") { maybeUniqueChildNamed("Cd") { focusElement.textContent } }
            },
            proprietarySubtype = maybeUniqueChildNamed("Tp") {
                maybeUniqueChildNamed("SubTp") { maybeUniqueChildNamed("Prtry") { focusElement.textContent } }
            },
            amount = extractCurrencyAmount()
        )
    }

    val entries = mapEachChildNamed("Ntry") {
        val amount = extractCurrencyAmount()
        val status = requireUniqueChildNamed("Sts") { focusElement.textContent }.let {
            EntryStatus.valueOf(it)
        }
        val creditDebitIndicator = requireUniqueChildNamed("CdtDbtInd") { focusElement.textContent }.let {
            CreditDebitIndicator.valueOf(it)
        }
        val btc = requireUniqueChildNamed("BkTxCd") {
            extractInnerBkTxCd(creditDebitIndicator)
        }
        val acctSvcrRef = maybeUniqueChildNamed("AcctSvcrRef") { focusElement.textContent }
        val entryRef = maybeUniqueChildNamed("NtryRef") { focusElement.textContent }

        val numInnerTxs = mapEachChildNamed("NtryDtls") {
            mapEachChildNamed("TxDtls") { Unit }
        }.flatten().count()

        val numBatches = mapEachChildNamed("NtryDtls") {
            mapEachChildNamed("Btch") { Unit }
        }.flatten().count()

        val isBatch = numBatches > 0 || numInnerTxs > 1

        val currencyExchange = maybeUniqueChildNamed("AmtDtls") {
            val cxCntrVal = maybeUniqueChildNamed("CntrValAmt") { extractMaybeCurrencyExchange() }
            val cxTx = maybeUniqueChildNamed("TxAmt") { extractMaybeCurrencyExchange() }
            val cxInstr = maybeUniqueChildNamed("InstrAmt") { extractMaybeCurrencyExchange() }
            cxCntrVal ?: cxTx ?: cxInstr
        }

        val counterValueAmount = maybeUniqueChildNamed("AmtDtls") {
            maybeUniqueChildNamed("CntrValAmt") { extractCurrencyAmount() }
        }

        val instructedAmount = maybeUniqueChildNamed("AmtDtls") {
            maybeUniqueChildNamed("InstdAmt") { extractCurrencyAmount() }
        }

        // For now, only support account servicer reference as id

        CamtBankAccountEntry(
            amount = amount,
            status = status,
            currencyExchange = currencyExchange,
            counterValueAmount = counterValueAmount,
            instructedAmount = instructedAmount,
            creditDebitIndicator = creditDebitIndicator,
            bankTransactionCode = btc,
            details = if (isBatch) {
                null
            } else {
                extractSingleDetails(amount, creditDebitIndicator)
            },
            batches = if (isBatch) {
                extractBatches(amount, creditDebitIndicator)
            } else {
                null
            },
            bookingDate = maybeUniqueChildNamed("BookgDt") { extractDateOrDateTime() },
            valueDate = maybeUniqueChildNamed("ValDt") { extractDateOrDateTime() },
            accountServicerRef = acctSvcrRef,
            entryRef = entryRef
        )
    }
    return CamtReport(
        account = account,
        entries = entries,
        creationDateTime = maybeUniqueChildNamed("CreDtTm") { focusElement.textContent },
        balances = balances,
        electronicSequenceNumber = maybeUniqueChildNamed("ElctrncSeqNb") { focusElement.textContent.toInt() },
        legalSequenceNumber = maybeUniqueChildNamed("LglSeqNb") { focusElement.textContent.toInt() },
        fromDate = maybeUniqueChildNamed("FrToDt") { maybeUniqueChildNamed("FrDtTm") { focusElement.textContent } },
        toDate = maybeUniqueChildNamed("FrToDt") { maybeUniqueChildNamed("ToDtTm") { focusElement.textContent } },
        id = requireUniqueChildNamed("Id") { focusElement.textContent },
        proprietaryReportingSource = maybeUniqueChildNamed("RptgSrc") { maybeUniqueChildNamed("Prtry") { focusElement.textContent } },
        reportingSource = maybeUniqueChildNamed("RptgSrc") { maybeUniqueChildNamed("Cd") { focusElement.textContent } }
    )
}

/**
 * Extract a list of transactions from an ISO20022 camt.052 / camt.053 message.
 */
fun parseCamtMessage(doc: Document): CamtParseResult {
    return destructXml(doc) {
        requireRootElement("Document") {
            // Either bank to customer statement or report
            val reports = requireOnlyChild {
                when (focusElement.localName) {
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

            val messageId = requireOnlyChild {
                requireUniqueChildNamed("GrpHdr") {
                    requireUniqueChildNamed("MsgId") { focusElement.textContent }
                }
            }
            val creationDateTime = requireOnlyChild {
                requireUniqueChildNamed("GrpHdr") {
                    requireUniqueChildNamed("CreDtTm") { focusElement.textContent }
                }
            }
            val messageType = requireOnlyChild {
                when (focusElement.localName) {
                    "BkToCstmrAcctRpt" -> CashManagementResponseType.Report
                    "BkToCstmrStmt" -> CashManagementResponseType.Statement
                    else -> {
                        throw CamtParsingError("expected statement or report")
                    }
                }
            }
            CamtParseResult(
                reports = reports,
                messageId = messageId,
                messageType = messageType,
                creationDateTime = creationDateTime
            )
        }
    }
}
