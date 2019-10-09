package tech.libeufin.sandbox

import org.junit.Assert
import org.junit.Test
import org.junit.Assert.*
import org.slf4j.LoggerFactory
import java.net.URLClassLoader

class LogTest {

    @Test
    fun logLine() {

        val loggerSandbox = LoggerFactory.getLogger("tech.libeufin.sandbox")
        val loggerNexus = LoggerFactory.getLogger("tech.libeufin.nexus")
        loggerSandbox.info("line")
        loggerNexus.trace("other line")
    }
}

