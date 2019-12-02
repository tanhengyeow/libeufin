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
            SchemaUtils.create(BalancesTable)
        }

        assertFailsWith<ExposedSQLException> {
            transaction {
                BalanceEntity.new {
                    value = 101
                    fraction = 101
                }
            }
        }

        transaction {
            BalanceEntity.new {
                value = 101
                fraction = 100
            }
        }
    }
}