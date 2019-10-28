package tech.libeufin.sandbox

import org.junit.Test
import org.w3c.dom.Element
import tech.libeufin.messages.ebics.keyrequest.EbicsUnsecuredRequest

class XsiTypeAttributeTest {

    @Test
    fun domToJaxb() {

        val processor = XMLUtil()
        val classLoader = ClassLoader.getSystemClassLoader()
        val ini = classLoader.getResource("ebics_ini_request_sample.xml")
        val iniDom = XMLUtil.parseStringIntoDom(ini.readText())
        val x: Element = iniDom.getElementsByTagName("OrderDetails")?.item(0) as Element

        x.setAttributeNS(
            "http://www.w3.org/2001/XMLSchema-instance",
            "type",
            "UnsecuredReqOrderDetailsType"
        )

        processor.convertDomToJaxb<EbicsUnsecuredRequest>(
            EbicsUnsecuredRequest::class.java,
            iniDom)
    }
}