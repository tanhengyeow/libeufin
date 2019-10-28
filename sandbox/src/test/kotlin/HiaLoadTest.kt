package tech.libeufin.sandbox

import org.junit.Test
import org.w3c.dom.Element
import tech.libeufin.messages.ebics.keyrequest.EbicsUnsecuredRequest

class HiaLoadTest {

    @Test
    fun hiaLoad() {

        val processor = XML()
        val classLoader = ClassLoader.getSystemClassLoader()
        val hia = classLoader.getResource("HIA.xml")
        val hiaDom = XML.parseStringIntoDom(hia.readText())
        val x: Element = hiaDom.getElementsByTagNameNS(
            "urn:org:ebics:H004",
            "OrderDetails"
        )?.item(0) as Element

        x.setAttributeNS(
            "http://www.w3.org/2001/XMLSchema-instance",
            "type",
            "UnsecuredReqOrderDetailsType"
        )

        processor.convertDomToJaxb<EbicsUnsecuredRequest>(
            EbicsUnsecuredRequest::class.java,
            hiaDom
        )
    }
}