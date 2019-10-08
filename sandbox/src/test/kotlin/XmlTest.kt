package tech.libeufin

import org.junit.Assert
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import javax.xml.transform.Source
import javax.xml.transform.stream.StreamSource

class XmlTest {

    val processor = XML()

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