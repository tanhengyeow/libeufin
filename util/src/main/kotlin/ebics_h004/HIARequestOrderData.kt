package tech.libeufin.util.ebics_h004

import javax.xml.bind.annotation.*
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter


@XmlAccessorType(XmlAccessType.NONE)
@XmlType(
    name = "HIARequestOrderDataType",
    propOrder = ["authenticationPubKeyInfo", "encryptionPubKeyInfo", "partnerID", "userID", "any"]
)
@XmlRootElement(name = "HIARequestOrderData")
class HIARequestOrderData {
    @get:XmlElement(name = "AuthenticationPubKeyInfo", required = true)
    lateinit var authenticationPubKeyInfo: EbicsTypes.AuthenticationPubKeyInfoType

    @get:XmlElement(name = "EncryptionPubKeyInfo", required = true)
    lateinit var encryptionPubKeyInfo: EbicsTypes.EncryptionPubKeyInfoType

    @get:XmlElement(name = "PartnerID", required = true)
    @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
    @get:XmlSchemaType(name = "token")
    lateinit var partnerID: String

    @get:XmlElement(name = "UserID", required = true)
    @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
    @get:XmlSchemaType(name = "token")
    lateinit var userID: String

    @get:XmlAnyElement(lax = true)
    var any: List<Any>? = null
}