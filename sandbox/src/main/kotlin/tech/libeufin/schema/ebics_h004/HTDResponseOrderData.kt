package tech.libeufin.schema.ebics_h004

import javax.xml.bind.annotation.*

@XmlAccessorType(XmlAccessType.NONE)
@XmlType(name = "", propOrder = ["partnerInfo", "userInfo"])
@XmlRootElement(name = "HTDResponseOrderData")
class HTDResponseOrderData {
    @get:XmlElement(name = "PartnerInfo", required = true)
    lateinit var partnerInfo: PartnerInfo

    @get:XmlElement(name = "UserInfo", required = true)
    lateinit var userInfo: UserInfo

    @XmlAccessorType(XmlAccessType.NONE)
    class PartnerInfo {

    }

    @XmlAccessorType(XmlAccessType.NONE)
    class UserInfo {

        @get:XmlElement(name = "AddressInfo", required = true)
        lateinit var addressInfo: AddressInfo

        @get:XmlElement(name = "BankInfo", required = true)
        lateinit var bankInfo: BankInfo

        class AddressInfo
        class BankInfo

    }
}
