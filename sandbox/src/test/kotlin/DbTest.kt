package tech.libeufin.sandbox

import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import tech.libeufin.util.Amount
import tech.libeufin.util.BadAmount


class DbTest {

    @Before
    fun muteStderr() {
        System.setErr(PrintStream(ByteArrayOutputStream()))
    }

    @Before
    fun connectAndMakeTables() {
        Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction {
            SchemaUtils.create(BankTransactionsTable)
            SchemaUtils.create(BankCustomersTable)
        }
    }

    @Test
    fun customers() {
        transaction {
            BankCustomerEntity.new {
                customerName = "Test Name"
            }
            findCustomer("1")
        }
    }


    @Test
    fun goodAmount() {

        transaction {
            BankTransactionEntity.new {
                amount = Amount("1")
                counterpart = "IBAN"
                subject = "Salary"
                operationDate = DateTime.now().millis
                valueDate = DateTime.now().millis
                localCustomer = BankCustomerEntity.new {
                    customerName = "employee"
                }
            }

            BankTransactionEntity.new {
                amount = Amount("1.11")
                counterpart = "IBAN"
                subject = "Salary"
                operationDate = DateTime.now().millis
                valueDate = DateTime.now().millis
                localCustomer = BankCustomerEntity.new {
                    customerName = "employee"
                }
            }

            BankTransactionEntity.new {
                amount = Amount("1.110000000000") // BigDecimal does not crop the trailing zeros
                counterpart = "IBAN"
                subject = "Salary"
                operationDate = DateTime.now().millis
                valueDate = DateTime.now().millis
                localCustomer = BankCustomerEntity.new {
                    customerName = "employee"
                }
            }
        }
    }

    @Test
    fun badAmount() {
        assertFailsWith<BadAmount> {
            transaction {
                BankTransactionEntity.new {
                    amount = Amount("1.10001")
                    counterpart = "IBAN"
                    subject = "Salary"
                    operationDate = DateTime.now().millis
                    valueDate = DateTime.now().millis
                    localCustomer = BankCustomerEntity.new {
                        customerName = "employee"
                    }
                }
            }
        }
    }

    @Test
    fun timeBasedQuery() {


        val NQUERIES = 10

        transaction {

            for (i in 1..NQUERIES) {
                BankTransactionEntity.new {
                    amount = Amount("1")
                    counterpart = "IBAN"
                    subject = "Salary"
                    operationDate = DateTime.now().millis
                    valueDate = DateTime.now().millis
                    localCustomer = BankCustomerEntity.new {
                        customerName = "employee"
                    }
                }
            }

            assertEquals(
                NQUERIES,
                BankTransactionEntity.find {
                    BankTransactionsTable.valueDate.between(DateTime.parse("1970-01-01").millis, DateTime.parse("2999-12-31").millis)
                }.count()
            )
        }
    }
}