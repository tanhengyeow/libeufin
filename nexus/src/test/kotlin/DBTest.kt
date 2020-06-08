package tech.libeufin.nexus

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import java.io.File

/**
 * Run a block after connecting to the test database.
 * Cleans up the DB file afterwards.
 */
fun withTestDatabase(f: () -> Unit) {
    val dbfile = "nexus-test.sqlite3"
    File(dbfile).also {
        if (it.exists()) {
            it.delete()
        }
    }
    Database.connect("jdbc:sqlite:$dbfile", "org.sqlite.JDBC")
    try {
        f()
    }
    finally {
        File(dbfile).also {
            if (it.exists()) {
                it.delete()
            }
        }
    }
}


class DBTest {
    @Test
    fun facadeConfigTest() {
        withTestDatabase {
            transaction {
                addLogger(StdOutSqlLogger)
                SchemaUtils.create(
                    FacadesTable,
                    TalerFacadeStatesTable,
                    NexusUsersTable
                )
                val user = NexusUserEntity.new("u") {
                    passwordHash = "x"
                    superuser = true
                }
                val facade = FacadeEntity.new("my-id") {
                    type = "any"
                    creator = user
                }
                TalerFacadeStateEntity.new {
                    bankAccount = "b"
                    bankConnection = "b"
                    reserveTransferLevel = "any"
                    intervalIncrement = "any"
                    this.facade = facade
                }
            }
        }
    }
}