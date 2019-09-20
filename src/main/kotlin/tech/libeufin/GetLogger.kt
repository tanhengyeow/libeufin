package tech.libeufin;

import ch.qos.logback.classic.Level;
import org.slf4j.LoggerFactory;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

fun getLogger(): Logger {
    val fa = FileAppender<ILoggingEvent>()
    val lc = LoggerFactory.getILoggerFactory()
    fa.setContext(lc as LoggerContext)
    fa.setFile("server.log");
    val logger: Logger = LoggerFactory.getLogger("libeufin-sandbox") as Logger
    logger.addAppender(fa);
    logger.setLevel(Level.DEBUG);
    return logger;
    }
