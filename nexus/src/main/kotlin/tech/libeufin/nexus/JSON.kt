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

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.databind.JsonNode
import tech.libeufin.util.*
import java.time.LocalDate
import java.time.LocalDateTime

data class BackupRequestJson(
    val passphrase: String
)

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "paramType"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = EbicsStandardOrderParamsDateJson::class, name = "standard-date-range"),
    JsonSubTypes.Type(value = EbicsStandardOrderParamsEmptyJson::class, name = "standard-empty"),
    JsonSubTypes.Type(value = EbicsGenericOrderParamsJson::class, name = "generic")
)
abstract class EbicsOrderParamsJson {
    abstract fun toOrderParams(): EbicsOrderParams
}

@JsonTypeName("generic")
class EbicsGenericOrderParamsJson(
    val params: Map<String, String>
) : EbicsOrderParamsJson() {
    override fun toOrderParams(): EbicsOrderParams {
        return EbicsGenericOrderParams(params)
    }
}

@JsonTypeName("standard-empty")
class EbicsStandardOrderParamsEmptyJson : EbicsOrderParamsJson() {
    override fun toOrderParams(): EbicsOrderParams {
        return EbicsStandardOrderParams(null)
    }
}

@JsonTypeName("standard-date-range")
class EbicsStandardOrderParamsDateJson(
    val start: String,
    val end: String
) : EbicsOrderParamsJson() {
    override fun toOrderParams(): EbicsOrderParams {
        val dateRange: EbicsDateRange? =
            EbicsDateRange(
                LocalDate.parse(this.start),
                LocalDate.parse(this.end)
            )
        return EbicsStandardOrderParams(dateRange)
    }
}

data class EbicsErrorDetailJson(
    val type: String,
    val ebicsReturnCode: String
)

data class EbicsErrorJson(
    val error: EbicsErrorDetailJson
)

data class BankConnectionInfo(
    val name: String,
    val type: String
)

data class BankConnectionsList(
    val bankConnections: List<BankConnectionInfo>
)

data class EbicsHostTestRequest(
    val ebicsBaseUrl: String,
    val ebicsHostId: String
)

/**
 * This object is used twice: as a response to the backup request,
 * and as a request to the backup restore.  Note: in the second case
 * the client must provide the passphrase.
 */
data class EbicsKeysBackupJson(
    // Always "ebics"
    val type: String,
    val userID: String,
    val partnerID: String,
    val hostID: String,
    val ebicsURL: String,
    val authBlob: String,
    val encBlob: String,
    val sigBlob: String
)

enum class FetchLevel(@get:JsonValue val jsonName: String) {
    REPORT("report"), STATEMENT("statement"), ALL("all");
}

/**
 * Instructions on what range to fetch from the bank,
 * and which source(s) to use.
 *
 * Intended to be convenient to specify.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "rangeType"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = FetchSpecLatestJson::class, name = "latest"),
    JsonSubTypes.Type(value = FetchSpecAllJson::class, name = "all"),
    JsonSubTypes.Type(value = FetchSpecPreviousDaysJson::class, name = "previous-days") ,
    JsonSubTypes.Type(value = FetchSpecSinceLastJson::class, name = "since-last")
)
abstract class FetchSpecJson(
    val level: FetchLevel,
    val bankConnection: String?
)

@JsonTypeName("latest")
class FetchSpecLatestJson(level: FetchLevel, bankConnection: String?) : FetchSpecJson(level, bankConnection)
@JsonTypeName("all")
class FetchSpecAllJson(level: FetchLevel, bankConnection: String?) : FetchSpecJson(level, bankConnection)
@JsonTypeName("since-last")
class FetchSpecSinceLastJson(level: FetchLevel, bankConnection: String?) : FetchSpecJson(level, bankConnection)
@JsonTypeName("previous-days")
class FetchSpecPreviousDaysJson(level: FetchLevel, bankConnection: String?, val number: Int) :
    FetchSpecJson(level, bankConnection)

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "source"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = CreateBankConnectionFromBackupRequestJson::class, name = "backup"),
    JsonSubTypes.Type(value = CreateBankConnectionFromNewRequestJson::class, name = "new")
)
abstract class CreateBankConnectionRequestJson(
    val name: String
)

@JsonTypeName("backup")
class CreateBankConnectionFromBackupRequestJson(
    name: String,
    val passphrase: String?,
    val data: JsonNode
) : CreateBankConnectionRequestJson(name)

@JsonTypeName("new")
class CreateBankConnectionFromNewRequestJson(
    name: String,
    val type: String,
    val data: JsonNode
) : CreateBankConnectionRequestJson(name)

data class EbicsNewTransport(
    val userID: String,
    val partnerID: String,
    val hostID: String,
    val ebicsURL: String,
    val systemID: String?
)

/** Response type of "GET /prepared-payments/{uuid}" */
data class PaymentStatus(
    val uuid: String,
    val submitted: Boolean,
    val creditorIban: String,
    val creditorBic: String,
    val creditorName: String,
    val amount: String,
    val subject: String,
    val submissionDate: String?,
    val preparationDate: String
)

data class Transactions(
    val transactions: MutableList<BankTransaction> = mutableListOf()
)

data class BankProtocolsResponse(
    val protocols: List<String>
)

/** Request type of "POST /prepared-payments" */
data class PreparedPaymentRequest(
    val iban: String,
    val bic: String?,
    val name: String,
    val amount: String,
    val subject: String
)

/** Response type of "POST /prepared-payments" */
data class PreparedPaymentResponse(
    val uuid: String
)

/** Response type of "GET /user" */
data class UserResponse(
    val username: String,
    val superuser: Boolean
)

/** Request type of "POST /users" */
data class User(
    val username: String,
    val password: String
)

data class UserInfo(
    val username: String,
    val superuser: Boolean
)

data class UsersResponse(
    val users: List<UserInfo>
)

/** Response (list's element) type of "GET /bank-accounts" */
data class BankAccount(
    var holder: String,
    var iban: String,
    var bic: String,
    var account: String
)

/** Response type of "GET /bank-accounts" */
data class BankAccounts(
    var accounts: MutableList<BankAccount> = mutableListOf()
)

data class BankMessageList(
    val bankMessages: MutableList<BankMessageInfo> = mutableListOf()
)

data class BankMessageInfo(
    val messageId: String,
    val code: String,
    val length: Long
)

data class FacadeInfo(
    val name: String,
    val type: String,
    val creator: String,
    val bankAccountsRead: MutableList<String>? = mutableListOf(),
    val bankAccountsWrite: MutableList<String>? = mutableListOf(),
    val bankConnectionsRead: MutableList<String>? = mutableListOf(),
    val bankConnectionsWrite: MutableList<String>? = mutableListOf(),
    val config: TalerWireGatewayFacadeConfig /* To be abstracted to Any! */
)

data class TalerWireGatewayFacadeConfig(
    val bankAccount: String,
    val bankConnection: String,
    val reserveTransferLevel: String,
    val intervalIncremental: String
)

/**********************************************************************
 * Convenience types (ONLY used to gather data together in one place) *
 **********************************************************************/

data class Pain001Data(
    val creditorIban: String,
    val creditorBic: String?,
    val creditorName: String,
    val sum: Amount,
    val currency: String,
    val subject: String
)


