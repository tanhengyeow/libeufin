package tech.libeufin.sandbox

import org.junit.Test
import org.w3c.dom.Element
import tech.libeufin.messages.ebics.keyrequest.OrderDetailsType
import tech.libeufin.messages.ebics.keyrequest.UnsecuredReqOrderDetailsType

class DomToJaxbTest {

    @Test
    fun domToJaxb() {

        val processor = XML()
        val classLoader = ClassLoader.getSystemClassLoader()
        val ini = classLoader.getResource("ebics_ini_request_sample.xml")
        val iniDom = processor.parseStringIntoDom(ini.readText())
        val x: Element = iniDom?.getElementsByTagName("OrderDetails")?.item(0) as Element

        x.setAttributeNS(
            "http://www.w3.org/2001/XMLSchema-instance",
            "type",
            "UnsecuredReqOrderDetailsType"
        )

        processor.convertDomToJaxb(
            "tech.libeufin.messages.ebics.keyrequest",
            iniDom)
    }
}