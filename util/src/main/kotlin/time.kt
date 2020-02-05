package tech.libeufin.util

import java.util.*
import javax.xml.datatype.DatatypeFactory
import javax.xml.datatype.XMLGregorianCalendar

/* now */
fun getGregorianCalendarNow(): XMLGregorianCalendar {
    val gregorianCalendar = GregorianCalendar()
    val datatypeFactory = DatatypeFactory.newInstance()
    return datatypeFactory.newXMLGregorianCalendar(gregorianCalendar)
}

/* explicit point in time */
fun getGregorianCalendar(year: Int, month: Int, day: Int): XMLGregorianCalendar {
    val gregorianCalendar = GregorianCalendar(year, month, day)
    val datatypeFactory = DatatypeFactory.newInstance()
    return datatypeFactory.newXMLGregorianCalendar(gregorianCalendar)
}