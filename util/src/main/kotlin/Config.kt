package tech.libeufin.util

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.util.ContextInitializer
import ch.qos.logback.core.util.Loader
import org.slf4j.LoggerFactory

/**
 * Set system properties to wanted values, and load logback configuration after.
 * While it can set any system property, it is used only to set the log file name.
 *
 * @param logFile filename of logfile.  If null, then no logfile will be produced.
 * @param logFileNameAsProperty property that indicates the logfile name in logback configuration.
 * @param configFileName name of logback's config file.  Typically something different
 * from "logback.xml" (otherwise logback will load it by itself upon startup.)
 */
fun setLogFile(logFile: String?, logFileNameAsProperty: String, configFileName: String) {
    if (logFile != null) System.setProperty(logFileNameAsProperty, logFile)
    val configFilePath = Loader.getResource(configFileName, ClassLoader.getSystemClassLoader())
    if (configFilePath == null) {
        println("Warning: could not find log config file")
    }
    System.setProperty(ContextInitializer.CONFIG_FILE_PROPERTY, configFilePath.toString())
}

/**
 * Set level of any logger belonging to LibEuFin (= has "libeufin" in name)
 * _and_ found under the calling classpath (= obeying to the same logback.xml)
 */
fun setLogLevel(logLevel: String?) {
    when (val immutable = logLevel) {
        is String -> {
            val ctx = LoggerFactory.getILoggerFactory() as LoggerContext
            val loggers: List<ch.qos.logback.classic.Logger> = ctx.loggerList
            loggers.forEach {
                if (it.name.contains("libeufin")) {
                    it.level = Level.toLevel(immutable)
                }
            }
        }
    }
}