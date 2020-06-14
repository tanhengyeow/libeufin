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

package tech.libeufin.nexus

/**
 * Parse ISO 20022 messages
 */

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.w3c.dom.Document
import tech.libeufin.util.XmlElementDestructor
import tech.libeufin.util.destructXml

enum class CreditDebitIndicator {
    DBIT, CRDT
}

enum class TransactionStatus {
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

/**
 * Schemes to identify a transaction within an account.
 * An identifier from such a scheme will be used to reconcile transactions
 * from multiple sources.
 * (LibEuFin-specific, not defined by ISO 20022)
 */
enum class TransactionIdentifierSchemes {
    /**
     * Reconcile based on the account servicer reference.
     */
    AcctSvcrRef
}

data class TransactionDetails(
    /**
     * Related parties as JSON.
     */
    val relatedParties: RelatedParties,
    val amountDetails: AmountDetails,
    val references: References,
    /**
     * Unstructured remittance information (=subject line) of the transaction,
     * or the empty string if missing.
     */
    val unstructuredRemittanceInformation: String
)

abstract class AccountIdentification : TypedEntity()

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AccountIdentificationIban(
    val iban: String
) : AccountIdentification()

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AccountIdentificationGeneric(
    val identification: String,
    val issuer: String?,
    val code: String?,
    val proprietary: String?
) : AccountIdentification()

data class BankTransaction(
    val account: AccountIdentification,
    /**
     * Identifier for the transaction that should be unique within an account.
     * Prefix by the identifier scheme name followed by a colon.
     */
    val transactionIdentifier: String,
    /**
     * Scheme used for the account identifier.
     */
    val currency: String,
    val amount: String,
    /**
     * Booked, pending, etc.
     */
    val status: TransactionStatus,
    /**
     * Is this transaction debiting or crediting the account
     * it is reported for?
     */
    val creditDebitIndicator: CreditDebitIndicator,
    /**
     * Code that describes the type of bank transaction
     * in more detail
     */
    val bankTransactionCode: BankTransactionCode,
    /**
     * Is this a batch booking?
     */
    val isBatch: Boolean,
    val details: List<TransactionDetails>,
    val valueDate: DateOrDateTime?,
    val bookingDate: DateOrDateTime?,
    val accountServicerReference: String?
)

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = Agent::class, name = "agent"),
    JsonSubTypes.Type(value = Party::class, name = "party"),
    JsonSubTypes.Type(value = Date::class, name = "date"),
    JsonSubTypes.Type(value = DateTime::class, name = "datetime"),
    JsonSubTypes.Type(value = AccountIdentificationIban::class, name = "account-identification-iban"),
    JsonSubTypes.Type(value = AccountIdentificationGeneric::class, name = "account-identification-generic")
)
abstract class TypedEntity

@JsonInclude(JsonInclude.Include.NON_NULL)
class Agent(
    val name: String?,
    val bic: String
) : TypedEntity()

@JsonInclude(JsonInclude.Include.NON_NULL)
class Party(
    val name: String?
) : TypedEntity()

abstract class DateOrDateTime : TypedEntity()

class Date(
    val date: String
) : DateOrDateTime()

class DateTime(
    val date: String
) : DateOrDateTime()


@JsonInclude(JsonInclude.Include.NON_NULL)
data class BankTransactionCode(
    /**
     * Standardized bank transaction code, as "$domain/$family/$subfamily"
     */
    val iso: String?,

    /**
     * Proprietary code, as "$issuer/$code".
     */
    val proprietary: String?
)

data class AmountAndCurrencyExchangeDetails(
    val amount: String,
    val currency: String
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AmountDetails(
    val instructedAmount: AmountAndCurrencyExchangeDetails?,
    val transactionAmount: AmountAndCurrencyExchangeDetails?
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class References(
    val endToEndIdentification: String?
)

/**
 * This structure captures both "TransactionParties6" and "TransactionAgents5"
 * of ISO 20022.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class RelatedParties(
    val debtor: Party?,
    val debtorAccount: AccountIdentification?,
    val debtorAgent: Agent?,
    val creditor: Party?,
    val creditorAccount: AccountIdentification?,
    val creditorAgent: Agent?
)

class CamtParsingError(msg: String) : Exception(msg)

private fun XmlElementDestructor.extractDateOrDateTime(): DateOrDateTime {
    return requireOnlyChild {
        when (it.localName) {
            "Dt" -> Date(e.textContent)
            "DtTm" -> DateTime(e.textContent)
            else -> throw Exception("Invalid date / time: ${e.localName}")
        }
    }
}

private fun XmlElementDestructor.extractAgent(): Agent {
    return Agent(
        name = maybeUniqueChildNamed("FinInstnId") {
            maybeUniqueChildNamed("Nm") {
                it.textContent
            }
        },
        bic = requireUniqueChildNamed("FinInstnId") {
            requireUniqueChildNamed("BIC") {
                it.textContent
            }
        }
    )
}

private fun XmlElementDestructor.extractAccount(): AccountIdentification {
    return requireUniqueChildNamed("Id") {
        requireOnlyChild {
            when (it.localName) {
                "IBAN" -> AccountIdentificationIban(it.textContent)
                "Othr" -> AccountIdentificationGeneric(
                    identification = requireUniqueChildNamed("Id") { it.textContent },
                    proprietary = maybeUniqueChildNamed("Prtry") { it.textContent },
                    code = maybeUniqueChildNamed("Cd") { it.textContent },
                    issuer = maybeUniqueChildNamed("Issr") { it.textContent }
                )
                else -> throw Error("invalid account identification")
            }
        }
    }
}

private fun XmlElementDestructor.extractParty(): Party {
    return Party(
        name = maybeUniqueChildNamed("Nm") { it.textContent }
    )
}

private fun XmlElementDestructor.extractPartiesAndAgents(): RelatedParties {
    return RelatedParties(
        debtor = maybeUniqueChildNamed("RltdPties") {
            maybeUniqueChildNamed("Dbtr") {
                extractParty()
            }
        },
        creditor = maybeUniqueChildNamed("RltdPties") {
            maybeUniqueChildNamed("Cdtr") {
                extractParty()
            }
        },
        creditorAccount = maybeUniqueChildNamed("RltdPties") {
            maybeUniqueChildNamed("CdtrAcct") {
                extractAccount()
            }
        },
        debtorAccount = maybeUniqueChildNamed("RltdPties") {
            maybeUniqueChildNamed("DbtrAcct") {
                extractAccount()
            }
        },
        creditorAgent = maybeUniqueChildNamed("RltdAgts") {
            maybeUniqueChildNamed("CdtrAgt") {
                extractAgent()
            }
        },
        debtorAgent = maybeUniqueChildNamed("RltdAgts") {
            maybeUniqueChildNamed("DbtrAgt") {
                extractAgent()
            }
        }
    )
}

private fun XmlElementDestructor.extractAmountAndCurrencyExchangeDetails(): AmountAndCurrencyExchangeDetails {
    return AmountAndCurrencyExchangeDetails(
        amount = requireUniqueChildNamed("Amt") { it.textContent },
        currency = requireUniqueChildNamed("Amt") { it.getAttribute("Ccy") }
    )
}

private fun XmlElementDestructor.extractTransactionDetails(): List<TransactionDetails> {
    return requireUniqueChildNamed("NtryDtls") {
        mapEachChildNamed("TxDtls") {
            TransactionDetails(
                relatedParties = extractPartiesAndAgents(),
                amountDetails = maybeUniqueChildNamed("AmtDtls") {
                    AmountDetails(
                        instructedAmount = maybeUniqueChildNamed("InstrAmt") { extractAmountAndCurrencyExchangeDetails() },
                        transactionAmount = maybeUniqueChildNamed("TxAmt") { extractAmountAndCurrencyExchangeDetails() }
                    )
                } ?: AmountDetails(null, null),
                references = maybeUniqueChildNamed("Refs") {
                    References(
                        endToEndIdentification = maybeUniqueChildNamed("EndToEndId") { it.textContent }
                    )
                } ?: References(null),
                unstructuredRemittanceInformation = maybeUniqueChildNamed("RmtInf") {
                    requireUniqueChildNamed("Ustrd") { it.textContent }
                } ?: ""
            )
        }
    }
}

private fun XmlElementDestructor.extractInnerTransactions(): List<BankTransaction> {
    val account = requireUniqueChildNamed("Acct") { extractAccount() }
    return mapEachChildNamed("Ntry") {
        val amount = requireUniqueChildNamed("Amt") { it.textContent }
        val currency = requireUniqueChildNamed("Amt") { it.getAttribute("Ccy") }
        val status = requireUniqueChildNamed("Sts") { it.textContent }.let {
            TransactionStatus.valueOf(it)
        }
        val creditDebitIndicator = requireUniqueChildNamed("CdtDbtInd") { it.textContent }.let {
            CreditDebitIndicator.valueOf(it)
        }
        val btc = requireUniqueChildNamed("BkTxCd") {
            BankTransactionCode(
                proprietary = maybeUniqueChildNamed("Prtry") {
                    val cd = requireUniqueChildNamed("Cd") { it.textContent }
                    val issr = requireUniqueChildNamed("Issr") { it.textContent }
                    "$issr/$cd"
                },
                iso = maybeUniqueChildNamed("Domn") {
                    val cd = requireUniqueChildNamed("Cd") { it.textContent }
                    val r = requireUniqueChildNamed("Fmly") {
                        object {
                            val fmlyCd = requireUniqueChildNamed("Cd") { it.textContent }
                            val subFmlyCd = requireUniqueChildNamed("SubFmlyCd") { it.textContent }
                        }
                    }
                    "$cd/${r.fmlyCd}/${r.subFmlyCd}"
                }
            )
        }
        val acctSvcrRef = maybeUniqueChildNamed("AcctSvcrRef") { it.textContent }
        // For now, only support account servicer reference as id
        val txId = "AcctSvcrRef:" + (acctSvcrRef ?: throw Exception("currently, AcctSvcrRef is mandatory in LibEuFin"))
        val details = extractTransactionDetails()
        BankTransaction(
            account = account,
            amount = amount,
            currency = currency,
            status = status,
            creditDebitIndicator = creditDebitIndicator,
            bankTransactionCode = btc,
            details = details,
            isBatch = details.size > 1,
            bookingDate = maybeUniqueChildNamed("BookgDt") { extractDateOrDateTime() },
            valueDate = maybeUniqueChildNamed("ValDt") { extractDateOrDateTime() },
            accountServicerReference = acctSvcrRef,
            transactionIdentifier = txId
        )
    }
}

/**
 * Extract a list of transactions from an ISO20022 camt.052 / camt.053 message.
 */
fun getTransactions(doc: Document): List<BankTransaction> {
    return destructXml(doc) {
        requireRootElement("Document") {
            // Either bank to customer statement or report
            requireOnlyChild {
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
        }
    }.flatten()
}