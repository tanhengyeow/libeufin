package tech.libeufin

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*

fun main(args: Array<String>) {
    val server = embeddedServer(Netty, port = 5000) {
        routing {
            get("/") {
                call.respondText("Hello LibEuFin!", ContentType.Text.Plain)
            }
        }
    }
    server.start(wait = true)
}