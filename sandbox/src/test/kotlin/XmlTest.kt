package tech.libeufin.sandbox

import org.junit.Test
import org.junit.Assert.*
import javax.xml.transform.stream.StreamSource

class XmlTest {

    val processor = tech.libeufin.sandbox.XMLUtil()

    @Test
    fun hevValidation(){
        val classLoader = ClassLoader.getSystemClassLoader()
        val hev = classLoader.getResourceAsStream("ebics_hev.xml")
        assertTrue(processor.validate(StreamSource(hev)))
    }

    @Test
    fun iniValidation(){
        val classLoader = ClassLoader.getSystemClassLoader()
        val ini = classLoader.getResourceAsStream("ebics_ini_request_sample.xml")
        assertTrue(processor.validate(StreamSource(ini)))
    }
}