package tech.libeufin.sandbox

import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction

fun getBankAccountFromSubscriber(subscriber: EbicsSubscriberEntity): BankAccountEntity {
    return transaction {
        BankAccountEntity.find(BankAccountsTable.subscriber eq subscriber.id)
    }.firstOrNull() ?: throw SandboxError(
        HttpStatusCode.NotFound,
        "Subscriber doesn't have any bank account"
    )
}
fun getEbicsSubscriberFromDetails(userID: String, partnerID: String, hostID: String): EbicsSubscriberEntity {
    return transaction {
        EbicsSubscriberEntity.find {
            (EbicsSubscribersTable.userId eq userID) and (EbicsSubscribersTable.partnerId eq partnerID) and
                    (EbicsSubscribersTable.hostId eq hostID)
        }.firstOrNull() ?: throw SandboxError(
            HttpStatusCode.NotFound,
            "Ebics subscriber not found"
        )
    }
}