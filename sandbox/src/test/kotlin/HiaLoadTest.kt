package tech.libeufin.sandbox

import org.junit.Test
import org.w3c.dom.Element
import tech.libeufin.schema.ebics_h004.EbicsUnsecuredRequest

class HiaLoadTest {

    @Test
    fun hiaLoad() {

        val processor = XMLUtil()
        val classLoader = ClassLoader.getSystemClassLoader()
        val hia = classLoader.getResource("HIA.xml")
        val hiaDom = XMLUtil.parseStringIntoDom(hia.readText())
        val x: Element = hiaDom.getElementsByTagNameNS(
            "urn:org:ebics:H004",
            "OrderDetails"
        )?.item(0) as Element

        x.setAttributeNS(
            "http://www.w3.org/2001/XMLSchema-instance",
            "type",
            "UnsecuredReqOrderDetailsType"
        )

        XMLUtil.convertDomToJaxb<EbicsUnsecuredRequest>(
            EbicsUnsecuredRequest::class.java,
            hiaDom
        )
    }
}