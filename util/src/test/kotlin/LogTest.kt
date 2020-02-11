import org.junit.Test
import org.slf4j.LoggerFactory


class LogTest {
    @Test
    fun logLine() {
        val loggerSandbox = LoggerFactory.getLogger("tech.libeufin.sandbox")
        val loggerNexus = LoggerFactory.getLogger("tech.libeufin.nexus")
        loggerSandbox.info("line")
        loggerNexus.trace("other line")
    }
}
