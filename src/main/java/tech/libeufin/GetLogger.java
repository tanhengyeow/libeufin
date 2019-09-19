package tech.libeufin;

import ch.qos.logback.classic.Level;
import org.slf4j.LoggerFactory;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

public class GetLogger {
    public static Logger getLogger() {
        FileAppender fa = new FileAppender<ILoggingEvent>();
        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        fa.setContext(lc);
        fa.setFile("server.log");
        Logger logger = (Logger) LoggerFactory.getLogger("libeufin-sandbox");
        logger.addAppender(fa);
        logger.setLevel(Level.DEBUG);
        return logger;
    }
}
