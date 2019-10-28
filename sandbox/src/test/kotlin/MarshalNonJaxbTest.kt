package tech.libeufin.sandbox

import org.junit.Test
import tech.libeufin.messages.ebics.keyresponse.EbicsKeyManagementResponse
import tech.libeufin.messages.ebics.keyresponse.ObjectFactory
import java.io.StringWriter
import javax.xml.bind.JAXBContext
import javax.xml.bind.Marshaller

class MarshalNonJaxbTest {

    @Test
    fun marshalNonJaxbNative() {

        val jc = JAXBContext.newInstance(EbicsKeyManagementResponse::class.java)
        val proc = jc.createMarshaller()
        val sw = StringWriter()
        val obj = ObjectFactory().createEbicsKeyManagementResponse()

        proc.marshal(obj, sw)
        proc.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true)

        println(sw.toString())
    }

    @Test
    fun marshalArtificialJaxb() {

        val obj = KeyManagementResponse(
            "H004",
            1,
            "000000",
            "fake",
            "EBICS_NON_OK"
        )

        val proc = XMLUtil()
        println(proc.convertJaxbToString(obj.get()))
    }
}