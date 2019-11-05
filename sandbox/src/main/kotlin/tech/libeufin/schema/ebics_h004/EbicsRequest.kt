package tech.libeufin.schema.ebics_h004

import org.apache.xml.security.binding.xmldsig.SignatureType
import javax.xml.bind.annotation.*

@XmlAccessorType(XmlAccessType.NONE)
@XmlType(name = "", propOrder = ["header", "authSignature", "body"])
@XmlRootElement(name = "ebicsRequest")
class EbicsRequest {
    @get:XmlElement(name = "header", required = true)
    lateinit var header: Header

    @get:XmlElement(name = "AuthSignature", required = true)
    lateinit var authSignature: SignatureType

    @get:XmlElement(name = "body")
    lateinit var body: Body

    @XmlAccessorType(XmlAccessType.NONE)
    class Body {

    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["static", "mutable"])
    class Header {
        @get:XmlElement(name = "static", required = true)
        lateinit var static: StaticHeaderType

        @get:XmlElement(required = true)
        lateinit var mutable: MutableHeader

        @get:XmlAttribute(name = "authenticate", required = true)
        var authenticate: Boolean = false

    }

    class StaticHeaderType {
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "")
    class MutableHeader {

    }
}
