package tech.libeufin.nexus

import org.junit.Before
import org.junit.Test
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SchemaUtils
import org.joda.time.DateTime
import tech.libeufin.util.Amount



class PainTest {
    @Before
    fun prepare() {
        Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction {
            SchemaUtils.create(BankAccountsTable)
            SchemaUtils.create(Pain001Table)
            SchemaUtils.create(NexusUsersTable)
            BankAccountEntity.new(id = "acctid") {
                accountHolder = "Account Holder"
                iban = "DEBIT IBAN"
                bankCode = "DEBIT BIC"
            }
        }
    }

    @Test
    fun testPain001document() {
        transaction {
            val nu = NexusUserEntity.new(id = "mock") { }
            val pain001Entity = Pain001Entity.new {
                sum = Amount(1)
                debitorIban = "DEBIT IBAN"
                debitorBic = "DEBIT BIC"
                debitorName = "DEBIT NAME"
                subject = "subject line"
                creditorIban = "CREDIT IBAN"
                creditorBic = "CREDIT BIC"
                creditorName = "CREDIT NAME"
                paymentId = 1
                msgId = 1
                endToEndId = 1
                date = DateTime.now().millis
                nexusUser = nu
            }
            val s = createPain001document(pain001Entity)
            println(s)
        }
    }
}