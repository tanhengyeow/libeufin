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

import io.ktor.gson.*
import io.ktor.application.*
import io.ktor.features.CallLogging
import io.ktor.http.*
import io.ktor.request.receive
import io.ktor.request.receiveText
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import org.w3c.dom.Document
import tech.libeufin.messages.HEVResponseDataType
import javax.xml.bind.JAXBElement
import io.ktor.features.*
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.tech.libeufin.*
import java.lang.NumberFormatException
import java.text.*

fun main() {

    val xmlProcess = XMLTransform()
    val logger = getLogger()
    dbCreateTables()

    val server = embeddedServer(Netty, port = 5000) {

        install(CallLogging)
        install(ContentNegotiation) {
            gson {
                setDateFormat(DateFormat.LONG)
                setPrettyPrinting()
            }
        }
        routing {
            get("/") {
                logger.debug("GET: not implemented")
                call.respondText("Hello LibEuFin!", ContentType.Text.Plain)
                return@get
            }

            post("/admin/customers") {
                val body = try {
                    call.receive<CustomerRequest>()
                } catch (e: Exception) {
                    e.printStackTrace()
                    call.respond(
                        HttpStatusCode.BadRequest,
                        SandboxError(e.message.toString())
                    )
                    return@post
                }
                logger.info(body.toString())

                val returnId = transaction {
                    val myUserId = EbicsUser.new { }
                    val myPartnerId = EbicsPartner.new { }
                    val mySystemId = EbicsSystem.new { }
                    val subscriber = EbicsSubscriber.new {
                        userId = myUserId
                        partnerId = myPartnerId
                        systemId = mySystemId
                        state = SubscriberStates.NEW
                    }
                    println("subscriber ID: ${subscriber.id.value}")
                    val customer = BankCustomer.new {
                        name = body.name
                        ebicsSubscriber = subscriber
                    }
                    println("name: ${customer.name}")
                    return@transaction customer.id.value
                }

                call.respond(
                    HttpStatusCode.OK,
                    CustomerResponse(id = returnId)
                )

                return@post
            }

            get("/admin/customers/{id}") {

                val id: Int = try {
                    call.parameters["id"]!!.toInt()
                } catch (e: NumberFormatException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        SandboxError(e.message.toString())
                    )
                    return@get
                }

                logger.info("Querying ID: $id")

                val customerInfo = transaction {
                    val customer = BankCustomer.findById(id) ?: return@transaction null
                    CustomerInfo(
                        customer.name,
                        customerEbicsInfo = CustomerEbicsInfo(
                            customer.ebicsSubscriber.userId.id.value
                        )
                    )
                }

                if (null == customerInfo) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        SandboxError("id $id not found")
                    )
                    return@get
                }

                call.respond(HttpStatusCode.OK, customerInfo)
            }

            post("/ebicsweb") {
                val body: String = call.receiveText()
                logger.debug("Body: $body")

                val isValid = xmlProcess.validateFromString(body)

                if (!isValid) {
                    logger.error("Invalid request received")
                    call.respondText(
                        contentType = ContentType.Application.Xml,
                        status = HttpStatusCode.BadRequest
                    ) { "Bad request" }
                    return@post
                }

                val bodyDocument: Document? = xmlProcess.parseStringIntoDom(body)
                if (null == bodyDocument) {
                    /* Should never happen.  */
                    logger.error("A valid document failed to parse into DOM!")
                    call.respondText(
                        contentType = ContentType.Application.Xml,
                        status = HttpStatusCode.InternalServerError
                    ) { "Internal server error" }
                    return@post
                }
                logger.info(bodyDocument.documentElement.localName)

                when (bodyDocument.documentElement.localName) {
                    "ebicsHEVRequest" -> {
                        val hevResponse = HEVResponse(
                            "000000",
                            "EBICS_OK",
                            arrayOf(
                                ProtocolAndVersion("H003", "02.40"),
                                ProtocolAndVersion("H004", "02.50")
                            )
                        )

                        val jaxbHEV: JAXBElement<HEVResponseDataType> = hevResponse.makeHEVResponse()
                        val responseText: String? = xmlProcess.getStringFromJaxb(jaxbHEV)
                        // FIXME: check if String is actually non-NULL!
                        call.respondText(
                            contentType = ContentType.Application.Xml,
                            status = HttpStatusCode.OK
                        ) { responseText.toString() }
                        return@post
                    }
                    else -> {
                        /* Log to console and return "unknown type" */
                        logger.info("Unknown message, just logging it!")
                        call.respondText(
                            contentType = ContentType.Application.Xml,
                            status = HttpStatusCode.NotFound
                        ) { "Not found" }
                        return@post
                    }
                }
            }
        }
    }
    server.start(wait = true)
}