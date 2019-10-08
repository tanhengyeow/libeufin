package tech.libeufin.sandbox

import ch.qos.logback.classic.Level
import org.slf4j.LoggerFactory
import ch.qos.logback.core.FileAppender
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.LoggerContext

fun getLogger(): Logger {
    val lc: LoggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
    val logger: Logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    val fa = FileAppender<ILoggingEvent>()
    val le = PatternLayoutEncoder()

    // appender setup
    fa.context = lc
    fa.name = "libeufin"
    fa.isAppend = true
    fa.file = "server.log"

    // encoder setup
    le.context = lc
    le.pattern = "%date [%level]: %msg\n"

    // link && start
    le.start()
    fa.encoder = le
    fa.start()
    logger.addAppender(fa)

    logger.level = Level.DEBUG
    return logger
}
