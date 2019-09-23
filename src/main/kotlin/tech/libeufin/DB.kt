package tech.libeufin.tech.libeufin

import org.jetbrains.exposed.dao.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

object SignKeys: IntIdTable(){
    val pub = binary("pub", 50)
}

fun db() {
    Database.connect("jdbc:h2:mem:test", driver = "org.h2.Driver")
    transaction {
        addLogger(StdOutSqlLogger)
        SchemaUtils.create(SignKeys)
    }
}