/**
 * This file is part of LIBEUFIN.
 * Copyright (C) 2019 Stanisci and Dold.

 * LIBEUFIN is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.

 * LIBEUFIN is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.

 * You should have received a copy of the GNU Affero General Public
 * License along with LIBEUFIN; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

package tech.libeufin

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.request.receiveText
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import tech.libeufin.XMLManagement;
import java.io.ByteArrayInputStream
import java.io.InputStream


fun main(args: Array<String>) {
    var xmlProcess = XMLManagement();
    val server = embeddedServer(Netty, port = 5000) {
        routing {
            get("/") {
                call.respondText("Hello LibEuFin!", ContentType.Text.Plain)
            }
            post("/") {
                val body: String = call.receiveText()
                println("Body: $body")
                val isValid = xmlProcess.validate(body)
                call.response.header("Content-Type", "application/xml")

                /*if (!isValid) {
                    /* Return "invalid request" */
                }

                if (!knownType) {

                    /* Log to console and return "unknown type" */
                }

                if (isValid){
                    call.respond(HttpStatusCode.OK, xmlResponseObject)
                }
                else {
                    call.respond(HttpStatusCode.BadRequest, xmlResponseObject)
                }*/
            }
        }
    }
    server.start(wait = true)
}
