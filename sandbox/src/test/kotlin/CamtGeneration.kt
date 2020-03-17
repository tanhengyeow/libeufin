package tech.libeufin.sandbox

import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.junit.Test
import org.junit.Before
import tech.libeufin.util.Amount
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Database

class CamtGeneration {

    @Before
    fun connectAndMakeTables() {
        Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction {
            SchemaUtils.create(BankCustomersTable)
            SchemaUtils.create(BankTransactionsTable)
        }
    }

    @Test
    fun generateCamt() {
        transaction {
            val customer = BankCustomerEntity.new {
                customerName = "payer"
            }
            for (iter in 1..5) {
                BankTransactionEntity.new {
                    localCustomer = customer
                    counterpart = "IBAN${iter}"
                    subject = "subject #${iter}"
                    operationDate = DateTime.parse("3000-01-01").millis
                    valueDate = DateTime.parse("3000-01-02").millis
                    amount = Amount("${iter}.0")
                }
            }
            val string = buildCamtString(
                BankTransactionEntity.all(),
                52
            )
            println(string)
        }
    }
}