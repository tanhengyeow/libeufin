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

import io.ktor.application.call
import io.ktor.client.*
import io.ktor.client.features.ServerResponseException
import io.ktor.client.request.get
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory

fun main() {

    val logger = LoggerFactory.getLogger("tech.libeufin.nexus")

    val server = embeddedServer(Netty, port = 5001) {

        routing {
            get("/") {
                call.respondText("Hello by Nexus!\n")
                return@get
            }

            post("/nexus") {
                val client = HttpClient()
                val content = try {
                    client.get<ByteArray>(
                        "https://ebicstest1.libeufin.tech/"
                    )
                } catch (e: ServerResponseException) {
                    logger.info("Request ended bad (${e.response.status}).")
                }

                call.respondText("Not implemented!\n")
                return@post
            }
        }
    }

    logger.info("Up and running")
    server.start(wait = true)

}
