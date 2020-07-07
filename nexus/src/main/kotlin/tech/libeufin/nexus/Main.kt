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
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import tech.libeufin.nexus.iso20022.parseCamtMessage
import tech.libeufin.util.XMLUtil
import tech.libeufin.util.setLogLevel
import java.io.File

val logger: Logger = LoggerFactory.getLogger("tech.libeufin.nexus")

class NexusCommand : CliktCommand() {
    override fun run() = Unit
}

class Serve : CliktCommand("Run nexus HTTP server") {
    init {
        context {
            helpFormatter = CliktHelpFormatter(showDefaultValues = true)
        }
    }
    private val dbName by option().default("libeufin-nexus.sqlite3")
    private val host by option().default("127.0.0.1")
    private val logLevel by option()
    override fun run() {
        setLogLevel(logLevel)
        serverMain(dbName, host)
    }
}

class ParseCamt : CliktCommand("Parse a camt file") {
    private val logLevel by option()
    private val filename by argument()
    override fun run() {
        setLogLevel(logLevel)
        val camtText = File(filename).readText(Charsets.UTF_8)
        val res = parseCamtMessage(XMLUtil.parseStringIntoDom(camtText))
        println(jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(res))
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
        .subcommands(Serve(), Superuser(), ParseCamt())
        .main(args)
}
