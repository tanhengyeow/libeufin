package tech.libeufin.util.ebics_h004

import javax.xml.bind.annotation.*
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter


@XmlAccessorType(XmlAccessType.NONE)
@XmlType(name = "", propOrder = ["authenticationPubKeyInfo", "encryptionPubKeyInfo", "hostID"])
@XmlRootElement(name = "HPBResponseOrderData")
class HPBResponseOrderData {
    @get:XmlElement(name = "AuthenticationPubKeyInfo", required = true)
    lateinit var authenticationPubKeyInfo: EbicsTypes.AuthenticationPubKeyInfoType

    @get:XmlElement(name = "EncryptionPubKeyInfo", required = true)
    lateinit var encryptionPubKeyInfo: EbicsTypes.EncryptionPubKeyInfoType

    @get:XmlElement(name = "HostID", required = true)
    @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
    lateinit var hostID: String
}