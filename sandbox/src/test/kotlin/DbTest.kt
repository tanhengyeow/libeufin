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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue


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
    fun goodAmount() {

        transaction {
            BankTransactionEntity.new {
                amount = Amount("1")
                counterpart = "IBAN"
                subject = "Salary"
                date = DateTime.now()
                localCustomer = BankCustomerEntity.new {
                    name = "employee"
                }
            }

            BankTransactionEntity.new {
                amount = Amount("1.11")
                counterpart = "IBAN"
                subject = "Salary"
                date = DateTime.now()
                localCustomer = BankCustomerEntity.new {
                    name = "employee"
                }
            }

            val x = BankTransactionEntity.new {
                amount = Amount("1.110000000000") // BigDecimal does not crop the trailing zeros
                counterpart = "IBAN"
                subject = "Salary"
                date = DateTime.now()
                localCustomer = BankCustomerEntity.new {
                    name = "employee"
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
                    date = DateTime.now()
                    localCustomer = BankCustomerEntity.new {
                        name = "employee"
                    }
                }
            }
        }
    }
}