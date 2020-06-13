/*
 * This file is part of LibEuFin.
 * Copyright (C) 2020 Taler Systems S.A.
 *
 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.
 *
 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

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
import java.io.File
import java.sql.Connection
import java.time.Instant
import java.time.LocalDateTime

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
    fun exist() {
        println("x")
    }

    @Test
    fun betweenDates() {
        withTestDatabase {
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
                    currency = "EUR"
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
}