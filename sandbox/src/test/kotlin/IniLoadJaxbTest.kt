package tech.libeufin.sandbox

import kotlinx.coroutines.io.jvm.javaio.toByteReadChannel
import org.junit.Assert
import org.junit.Test
import org.junit.Assert.*
import tech.libeufin.messages.ebics.keyrequest.SignaturePubKeyOrderDataType
import java.io.File
import javax.xml.transform.Source
import javax.xml.transform.stream.StreamSource

class IniKeyMaterialTest {

    val processor = tech.libeufin.sandbox.XML()

    @Test
    fun importKey(){
        val classLoader = ClassLoader.getSystemClassLoader()
        val ini = classLoader.getResource("ebics_ini_inner_key.xml")
        val obj = processor.convertStringToJaxb<SignaturePubKeyOrderDataType>(
            "tech.libeufin.messages.ebics.keyrequest",
            ini.readText()
        )

    }
}