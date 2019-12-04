package tech.libeufin.sandbox

import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DbTest {

    @Test
    fun valuesRange() {

        Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")

        transaction {

            SchemaUtils.create(BankTransactionsTable)
            SchemaUtils.create(BankCustomersTable)

            val customer = BankCustomerEntity.new {
                name = "employee"
            }

            val ledgerEntry = BankTransactionEntity.new {
                amountSign = 1
                amountValue = 5
                amountFraction = 0
                counterpart = "IBAN"
                subject = "Salary"
                date = DateTime.now()
                localCustomer = customer
            }
        }
    }
}