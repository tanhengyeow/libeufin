/*
 * This file is part of LibEuFin.
 * Copyright (C) 2019 Stanisci and Dold.

 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.

 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.

 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

package tech.libeufin.nexus

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.CliktHelpFormatter
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.nexus.server.serverMain
import tech.libeufin.util.CryptoUtil.hashpw
import tech.libeufin.util.*


lateinit var logger: Logger

class NexusCommand : CliktCommand() {
    override fun run() = Unit
}

class Serve : CliktCommand("Run nexus HTTP server") {
    init {
        context {
            helpFormatter = CliktHelpFormatter(showDefaultValues = true)
        }
    }
    private val logFile by option()
    private val dbName by option().default("libeufin-nexus.sqlite3")
    private val host by option().default("127.0.0.1")
    override fun run() {
        setLogFile(logFile, "nexusLogFile","late-logback.xml")
        logger = LoggerFactory.getLogger("tech.libeufin.nexus")
        serverMain(dbName, host)
    }
}


class Superuser : CliktCommand("Add superuser or change pw") {
    private val dbName by option().default("libeufin-nexus.sqlite3")
    private val username by argument()
    private val password by option().prompt(requireConfirmation = true, hideInput = true)
    override fun run() {
        dbCreateTables(dbName)
        transaction {
            val hashedPw = hashpw(password)
            val user = NexusUserEntity.findById(username)
            if (user == null) {
                NexusUserEntity.new(username) {
                    this.passwordHash = hashedPw
                    this.superuser = true
                }
            } else {
                if (!user.superuser) {
                    println("Can only change password for superuser with this command.")
                    throw ProgramResult(1)
                }
                user.passwordHash = hashedPw
            }
        }
    }
}

fun main(args: Array<String>) {
    NexusCommand()
        .subcommands(Serve(), Superuser())
        .main(args)
}
