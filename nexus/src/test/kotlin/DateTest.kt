package tech.libeufin.nexus

import org.joda.time.DateTime
import org.junit.Test
import tech.libeufin.util.toDashedDate
import tech.libeufin.util.parseDashedDate

class DateTest {
    @Test
    fun dashedDateParsing() {
        val parseddate = parseDashedDate("2020-04-30")
        println("Parsed value: " + parseddate.toLocalDate())
        println("To dashed value: " + parseddate.toDashedDate())
        println("System now(): " + DateTime.now().toLocalDate())
    }
}