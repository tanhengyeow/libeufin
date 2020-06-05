import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.IntIdTable
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
    val dbfile = "test-db.sqlite3"
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

object ContainedTableWithIntId : IntIdTable() {
    val column = text("column")
}
class ContainedEntityWithIntId(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ContainedEntityWithIntId>(ContainedTableWithIntId)
    var column by ContainedTableWithIntId.column
}

object ContainedTableWithStringId : IdTable<String>() {
    override val id = varchar("id", 10).entityId()
    override val primaryKey = PrimaryKey(id, name = "id")
    val column = text("column")

}
class ContainedEntityWithStringId(id: EntityID<String>) : Entity<String>(id) {
    companion object : EntityClass<String, ContainedEntityWithStringId>(ContainedTableWithStringId)
    var column by ContainedTableWithStringId.column
}

object ContainingTable : IdTable<String>() {
    override val id = varchar("id", 10).entityId()
    override val primaryKey = PrimaryKey(id, name = "id")
    val referenceStringId = reference("referenceStringId", ContainedTableWithStringId)
    val referenceIntId = reference("referenceIntId", ContainedTableWithIntId)
}
class ContainingEntity(id: EntityID<String>) : Entity<String>(id) {
    companion object : EntityClass<String, ContainingEntity>(ContainingTable)
    var referenceStringId by ContainedEntityWithStringId referencedOn ContainingTable.referenceStringId
    var referenceIntId by ContainedEntityWithIntId referencedOn ContainingTable.referenceIntId
}

class DBTest {
    @Test
    fun facadeConfigTest() {
        withTestDatabase {
            transaction {
                addLogger(StdOutSqlLogger)
                SchemaUtils.create(
                    ContainingTable,
                    ContainedTableWithIntId,
                    ContainedTableWithStringId
                )
                val entityWithIntId = ContainedEntityWithIntId.new {
                    column = "value"
                }
                entityWithIntId.flush()
                val entityWithStringId = ContainedEntityWithStringId.new("contained-id") {
                    column = "another value"
                }
                ContainingEntity.new("containing-id") {
                    referenceIntId = entityWithIntId
                    referenceStringId = entityWithStringId
                }
            }
        }
    }
}