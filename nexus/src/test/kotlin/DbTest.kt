package tech.libeufin.nexus

import org.jetbrains.exposed.dao.EntityID
import org.junit.Before
import org.junit.Test

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SchemaUtils
import javax.sql.rowset.serial.SerialBlob


class DbTest {

    @Before
    fun connectAndMakeTables() {
        Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction {
            SchemaUtils.create(EbicsSubscribersTable)
        }
    }

    @Test
    fun makeCustomer() {
        transaction {
            EbicsSubscriberEntity.new(id = "123asdf") {
                ebicsURL = "ebics url"
                hostID = "host"
                partnerID = "partner"
                userID = "user"
                systemID = "system"
                signaturePrivateKey = SerialBlob("signturePrivateKey".toByteArray())
                authenticationPrivateKey = SerialBlob("authenticationPrivateKey".toByteArray())
                encryptionPrivateKey = SerialBlob("encryptionPrivateKey".toByteArray())
            }
            assert(EbicsSubscriberEntity.findById("123asdf") != null)
        }
    }
}