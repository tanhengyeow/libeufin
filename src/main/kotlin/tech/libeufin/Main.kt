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

package tech.libeufin

import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.CallLogging
import io.ktor.features.ContentNegotiation
import io.ktor.gson.gson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.request.receiveText
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.jetbrains.exposed.sql.transactions.transaction
import org.w3c.dom.Document
import tech.libeufin.messages.HEVResponseDataType
import java.text.DateFormat
import javax.xml.bind.JAXBElement

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
                        ebicsInfo = CustomerEbicsInfo(
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

            post("/admin/customers/{id}/ebics/keyletter") {
                val body = try {
                    call.receive<IniHiaLetters>()
                } catch (e: Exception) {
                    e.printStackTrace()
                    call.respond(
                        HttpStatusCode.BadRequest,
                        SandboxError(e.message.toString())
                    )
                    return@post
                }
                logger.info(body.toString())

                /**********************************************/

                // Extract keys and compare them to what was
                // received via the INI and HIA orders.

                /**********************************************/

            }

            post("/ebicsweb") {
                val body: String = call.receiveText()
                logger.debug("Body: $body")
                val bodyDocument: Document? = xmlProcess.parseStringIntoDom(body)

                if (bodyDocument == null) {
                    call.respondText(
                        contentType = ContentType.Application.Xml,
                        status = HttpStatusCode.BadRequest
                    ) { "Bad request / Could not parse the body" }
                    return@post

                }

                if (!xmlProcess.validateFromDom(bodyDocument)) {
                    logger.error("Invalid request received")
                    call.respondText(
                        contentType = ContentType.Application.Xml,
                        status = HttpStatusCode.BadRequest
                    ) { "Bad request / invalid document" }
                    return@post
                }

                logger.info("Processing", bodyDocument.documentElement.localName)

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
