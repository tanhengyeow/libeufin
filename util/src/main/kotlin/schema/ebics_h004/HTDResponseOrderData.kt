package tech.libeufin.util.schema.ebics_h004

import java.security.Permission
import javax.xml.bind.annotation.*

@XmlAccessorType(XmlAccessType.NONE)
@XmlType(name = "", propOrder = ["partnerInfo", "userInfo"])
@XmlRootElement(name = "HTDResponseOrderData")
class HTDResponseOrderData {
    @get:XmlElement(name = "PartnerInfo", required = true)
    lateinit var partnerInfo: EbicsTypes.PartnerInfo

    @get:XmlElement(name = "UserInfo", required = true)
    lateinit var userInfo: EbicsTypes.UserInfo
}
