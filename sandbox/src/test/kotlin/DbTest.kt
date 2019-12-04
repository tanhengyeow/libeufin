package tech.libeufin.sandbox

import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DbTest {

    @Test
    fun valuesRange() {

        Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")

        transaction {

            SchemaUtils.create(BankCustomersTable)
            SchemaUtils.create(EbicsSubscribersTable)
            SchemaUtils.create(EbicsSubscriberPublicKeysTable)

            val customer = BankCustomerEntity.new {
                name = "username"
                balance = Float.MIN_VALUE
            }

            val row = EbicsSubscriberEntity.new {
                userId = "user id"
                partnerId = "partner id"
                nextOrderID = 0
                state = SubscriberState.NEW
                bankCustomer = customer
            }

            customer.balance = 100.toFloat()

            logger.info("${row.bankCustomer.balance}")
            assertTrue(row.bankCustomer.balance.equals(100.toFloat()))
        }
    }
}