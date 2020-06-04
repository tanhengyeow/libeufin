import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import tech.libeufin.sandbox.PaymentEntity
import tech.libeufin.sandbox.PaymentsTable
import tech.libeufin.util.millis
import tech.libeufin.util.parseDashedDate
import java.sql.Connection
import java.time.Instant
import java.time.LocalDateTime

class DBTest {
    @Test
    fun exist() {
        println("x")
    }

    @Test
    fun betweenDates() {
        Database.connect("jdbc:sqlite:sandbox-test.sqlite3", "org.sqlite.JDBC")
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
        transaction {
            SchemaUtils.create(PaymentsTable)
            PaymentEntity.new {
                creditorIban = "earns"
                creditorBic = "BIC"
                creditorName = "Creditor Name"
                debitorIban = "spends"
                debitorBic = "BIC"
                debitorName = "Debitor Name"
                subject = "deal"
                amount = "EUR:1"
                date = LocalDateTime.now().millis()
            }
        }
        val result = transaction {
            addLogger(StdOutSqlLogger)
            PaymentEntity.find {
                PaymentsTable.date.between(
                    parseDashedDate(
                        "1970-01-01"
                    ).millis(),
                    LocalDateTime.now().millis()
                )
            }.firstOrNull()
        }
        assert(result != null)
    }
}