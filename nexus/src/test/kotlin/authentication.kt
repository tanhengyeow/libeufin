package tech.libeufin.nexus

import org.junit.Test
import junit.framework.TestCase.assertEquals
import tech.libeufin.nexus.server.extractUserAndPassword

class AuthenticationTest {
    @Test
    fun basicAuthHeaderTest() {
        val pass = extractUserAndPassword(
            "Basic dXNlcm5hbWU6cGFzc3dvcmQ="
        ).second
        assertEquals("password", pass);
    }
}