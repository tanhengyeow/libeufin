package tech.libeufin;

import ch.qos.logback.classic.Level;
import org.slf4j.LoggerFactory;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

fun getLogger(): Logger {
    val lc = LoggerFactory.getILoggerFactory() as LoggerContext
    val logger: Logger = LoggerFactory.getLogger("libeufin-sandbox") as Logger
    val fa = FileAppender<ILoggingEvent>()

    fa.context = lc
    fa.file = "server.log"
    fa.start()
    logger.addAppender(fa)
    logger.level = Level.DEBUG
    return logger
}
