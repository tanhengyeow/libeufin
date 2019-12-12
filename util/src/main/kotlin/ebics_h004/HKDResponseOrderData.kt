package tech.libeufin.util.ebics_h004

import java.security.Permission
import javax.xml.bind.annotation.*

@XmlAccessorType(XmlAccessType.NONE)
@XmlType(name = "", propOrder = ["partnerInfo", "userInfoList"])
@XmlRootElement(name = "HTDResponseOrderData")
class HKDResponseOrderData {
    @get:XmlElement(name = "PartnerInfo", required = true)
    lateinit var partnerInfo: EbicsTypes.PartnerInfo

    @get:XmlElement(name = "UserInfo", type = EbicsTypes.UserInfo::class, required = true)
    lateinit var userInfoList: List<EbicsTypes.UserInfo>
}
