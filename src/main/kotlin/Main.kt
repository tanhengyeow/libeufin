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
import tech.libeufin.messages.HEVResponse
import tech.libeufin.messages.HEVResponseDataType
import javax.swing.text.Document
import javax.xml.bind.JAXBElement

fun main(args: Array<String>) {
    var xmlProcess = XMLManagement();
    var logger = getLogger()

    val server = embeddedServer(Netty, port = 5000) {
        routing {
            get("/") {
                logger.debug("GET: not implemented")
                call.respondText("Hello LibEuFin!", ContentType.Text.Plain)
            }
            post("/") {
                val body: String = call.receiveText()
                logger.debug("Body: $body")

                val isValid = xmlProcess.validateFromString(body as java.lang.String)

                if (!isValid) {
                    logger.error("Invalid request received")
                    call.respondText(contentType = ContentType.Application.Xml,
                                     status = HttpStatusCode.BadRequest) {"Bad request"};
                    return@post
                }

                val bodyDocument = xmlProcess.parseStringIntoDom(body) as org.w3c.dom.Document
                if (null == bodyDocument)
                {
                    /* Should never happen.  */
                    logger.error("A valid document failed to parse into DOM!")
                    call.respondText(contentType = ContentType.Application.Xml,
                        status = HttpStatusCode.InternalServerError) {"Internal server error"};
                    return@post
                }
                logger.info(bodyDocument.documentElement.localName)

                if ("ebicsHEVRequest" == bodyDocument.documentElement.localName)
                {
                    /* known type, and already valid here! */
                    val hevResponse: HEVResponse = HEVResponse("rc", "rt")
                    val jaxbHEV: JAXBElement<HEVResponseDataType> = hevResponse.makeHEVResponse()

                    val responseText: String? = xmlProcess.getStringFromJaxb(jaxbHEV)
                    // FIXME: check if String is actually non-NULL!
                    call.respondText(contentType = ContentType.Application.Xml,
                        status = HttpStatusCode.OK) {responseText.toString()};
                    return@post
                }

                /* Log to console and return "unknown type" */
                logger.info("Unknown message, just logging it!")
                call.respondText(contentType = ContentType.Application.Xml,
                    status = HttpStatusCode.NotFound) {"Not found"};
                return@post

            }
        }
    }
    server.start(wait = true)
}
