package tech.libeufin.nexus

import org.apache.commons.compress.utils.IOUtils
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.junit.Test
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.toByteArray
import tech.libeufin.util.toHexString
import java.sql.Blob
import javax.sql.rowset.serial.SerialBlob

class AuthenticationTest {

    @Before
    fun connectAndMakeTables() {
        Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction {
            SchemaUtils.create(EbicsSubscribersTable)
            EbicsSubscriberEntity.new(id = "username") {
                password = SerialBlob(CryptoUtil.hashStringSHA256("password"))
                ebicsURL = "ebics url"
                hostID = "host"
                partnerID = "partner"
                userID = "user"
                systemID = "system"
                signaturePrivateKey = SerialBlob("signturePrivateKey".toByteArray())
                authenticationPrivateKey = SerialBlob("authenticationPrivateKey".toByteArray())
                encryptionPrivateKey = SerialBlob("encryptionPrivateKey".toByteArray())
            }
        }
    }

    @Test
    fun manualMethod() {
        // base64 of "username:password" == "dXNlcm5hbWU6cGFzc3dvcmQ="
        val (username: String, hashedPass: ByteArray) = extractUserAndHashedPassword("Basic dXNlcm5hbWU6cGFzc3dvcmQ=")
        val result = transaction {
            val row = EbicsSubscriberEntity.find {
                EbicsSubscribersTable.id eq username and (EbicsSubscribersTable.password eq SerialBlob(hashedPass))
            }.firstOrNull()
            assert(row != null)
        }
    }

    @Test
    fun testExtractor() {
        val (username: String, hashedPass: ByteArray) = extractUserAndHashedPassword("Basic dXNlcm5hbWU6cGFzc3dvcmQ=")
        assert(CryptoUtil.hashStringSHA256("password").contentEquals(hashedPass))
    }
}