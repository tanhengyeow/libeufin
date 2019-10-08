package tech.libeufin.sandbox

import org.junit.Assert
import org.junit.Test
import org.junit.Assert.*
import org.slf4j.LoggerFactory
import java.net.URLClassLoader

class LogTest {

    @Test
    fun logLine() {

        val cl = ClassLoader.getSystemClassLoader()
        val urls = (cl as URLClassLoader).urLs
        for (url in urls) {
            println(url)
        }
        val logger = LoggerFactory.getLogger("sandbox.log.test")
        logger.info("line")
    }
}

