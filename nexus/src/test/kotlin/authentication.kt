package tech.libeufin.nexus

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import junit.framework.TestCase.assertEquals
import tech.libeufin.util.CryptoUtil
import javax.sql.rowset.serial.SerialBlob

class AuthenticationTest {
    @Test
    fun basicAuthHeaderTest() {
        val pass = extractUserAndHashedPassword(
            "Basic dXNlcm5hbWU6cGFzc3dvcmQ="
        ).second
        assertEquals("password", pass);
    }
}