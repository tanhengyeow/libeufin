package tech.libeufin.nexus

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test

class DBTest {
    @Test
    fun facadeConfigTest() {
        Database.connect("jdbc:sqlite:on-the-fly-db.sqlite3", "org.sqlite.JDBC")
        val talerConfig = transaction {
            addLogger(StdOutSqlLogger)
            SchemaUtils.create(
                FacadesTable,
                TalerFacadeConfigsTable,
                NexusUsersTable
            )
            TalerFacadeConfigEntity.new {
                bankAccount = "b"
                bankConnection = "b"
                reserveTransferLevel = "any"
                intervalIncrement = "any"
            }
        }
        transaction {
            val user = NexusUserEntity.new("u") {
                passwordHash = "x"
                superuser = true
            }
            FacadeEntity.new("my-id") {
                type = "any"
                creator = user
                config = talerConfig
                highestSeenMsgID = 0
            }
        }
    }
}