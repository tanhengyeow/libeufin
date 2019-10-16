package tech.libeufin.sandbox

import kotlinx.coroutines.io.jvm.javaio.toByteReadChannel
import org.junit.Assert
import org.junit.Test
import org.junit.Assert.*
import org.xml.sax.InputSource
import tech.libeufin.messages.ebics.keyrequest.SignaturePubKeyOrderDataType
import java.io.File
import java.io.InputStream
import java.io.StringReader
import javax.xml.bind.JAXBContext
import javax.xml.bind.JAXBElement
import javax.xml.transform.Source
import javax.xml.transform.stream.StreamSource

class IniKeyMaterialTest {

    val processor = tech.libeufin.sandbox.XML()

    @Test
    fun importKey(){
        val classLoader = ClassLoader.getSystemClassLoader()
        val ini = classLoader.getResource(
            "ebics_ini_inner_key.xml"
        )

        // manual unmarshalling now.
        val jc = JAXBContext.newInstance(
            "tech.libeufin.messages.ebics.keyrequest"
        )

        /* Marshalling the object into the document.  */
        val u = jc.createUnmarshaller()

        val js = u.unmarshal(
            StreamSource(((StringReader(ini.readText())))),
            SignaturePubKeyOrderDataType::class.java)

    }
}