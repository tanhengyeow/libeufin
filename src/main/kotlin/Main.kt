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
import org.w3c.dom.Document;
import kotlin.reflect.jvm.internal.impl.load.kotlin.JvmType

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

                if (!isValid) {
                    call.respond(HttpStatusCode(400, "Invalid request"));
                }

                val bodyDocument = XMLManagement.parseStringIntoDom(body)
                if (null == bodyDocument)
                {
                    /* Should never happen.  */
                    System.out.println("A valid document failed to parse into DOM!")
                    call.respond(HttpStatusCode(500, "Internal server error"));
                }

                /* FIXME: Check if that's a known type! */
                if (true) {
                    /* Log to console and return "unknown type" */
                    System.out.println("Unknown message, just logging it!")
                    call.respond(HttpStatusCode(400, "Not found"));
                }

                /* Generate response here.  */


            }
        }
    }
    server.start(wait = true)
}
