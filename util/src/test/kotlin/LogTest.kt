import org.junit.Assert
import org.junit.Test
import org.junit.Assert.*
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import tech.libeufin.util.LOGGER as utilLogger


class LogTest {

    @Test
    fun logLine() {
        val loggerSandbox = LoggerFactory.getLogger("tech.libeufin.sandbox")
        val loggerNexus = LoggerFactory.getLogger("tech.libeufin.nexus")
        loggerSandbox.info("line")
        loggerNexus.trace("other line")
    }

    @Test
    fun logFromUtil() {
        /* This log should show up with 'util' name but without this latter owning a logback.xml */
        utilLogger.trace("shown")
    }
}

