package tech.libeufin.schema.ebics_h004

import java.security.Permission
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
    @XmlType(name = "", propOrder = ["addressInfo", "bankInfo", "accountInfoList", "orderInfoList"])
    class PartnerInfo {
        @get:XmlElement(name = "AddressInfo", required = true)
        lateinit var addressInfo: AddressInfo

        @get:XmlElement(name = "BankInfo", required = true)
        lateinit var bankInfo: BankInfo

        @get:XmlElement(name = "AccountInfo", required = true)
        var accountInfoList: List<AccountInfo>? = null

        @get:XmlElement(name = "OrderInfo")
        lateinit var orderInfoList: List<AuthOrderInfoType>
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(
        name = "",
        propOrder = ["orderType", "fileFormat", "transferType", "orderFormat", "description", "numSigRequired"]
    )
    class AuthOrderInfoType {
        @get:XmlElement(name = "OrderType")
        lateinit var orderType: String

        @get:XmlElement(name = "FileFormat")
        val fileFormat: EbicsTypes.FileFormatType? = null

        @get:XmlElement(name = "TransferType")
        lateinit var transferType: String

        @get:XmlElement(name = "OrderFormat", required = false)
        var orderFormat: String? = null

        @get:XmlElement(name = "Description")
        lateinit var description: String

        @get:XmlElement(name = "NumSigRequired")
        var numSigRequired: Int? = null

    }

    @XmlAccessorType(XmlAccessType.NONE)
    class UserIDType {
        @get:XmlValue
        lateinit var value: String;

        @get:XmlAttribute(name = "Status")
        var status: Int? = null
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["userID", "name", "permissionList"])
    class UserInfo {
        @get:XmlElement(name = "UserID", required = true)
        lateinit var userID: UserIDType

        @get:XmlElement(name = "Name")
        var name: String? = null

        @get:XmlElement(name = "Permission", type = UserPermission::class)
        var permissionList: List<UserPermission>? = null
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["orderTypes", "fileFormat", "accountID", "maxAmount"])
    class UserPermission {
        @get:XmlAttribute(name = "AuthorizationLevel")
        var authorizationLevel: String? = null

        @get:XmlElement(name = "OrderTypes")
        var orderTypes: String? = null

        @get:XmlElement(name = "FileFormat")
        val fileFormat: EbicsTypes.FileFormatType? = null

        @get:XmlElement(name = "AccountID")
        val accountID: String? = null

        @get:XmlElement(name = "MaxAmount")
        val maxAmount: String? = null
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["name", "street", "postCode", "city", "region", "country"])
    class AddressInfo {
        @get:XmlElement(name = "Name")
        var name: String? = null

        @get:XmlElement(name = "Street")
        var street: String? = null

        @get:XmlElement(name = "PostCode")
        var postCode: String? = null

        @get:XmlElement(name = "City")
        var city: String? = null

        @get:XmlElement(name = "Region")
        var region: String? = null

        @get:XmlElement(name = "Country")
        var country: String? = null
    }


    @XmlAccessorType(XmlAccessType.NONE)
    class BankInfo {
        @get:XmlElement(name = "HostID")
        lateinit var hostID: String

        @get:XmlElement(type = EbicsTypes.Parameter::class)
        var parameters: List<EbicsTypes.Parameter>? = null
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["accountNumberList", "bankCodeList", "accountHolder"])
    class AccountInfo {
        @get:XmlAttribute(name = "Currency")
        var currency: String? = null

        @get:XmlAttribute(name = "ID")
        lateinit var id: String

        @get:XmlAttribute(name = "Description")
        var description: String? = null

        @get:XmlElements(
            XmlElement(name = "AccountNumber", type = GeneralAccountNumber::class),
            XmlElement(name = "NationalAccountNumber", type = NationalAccountNumber::class)
        )
        var accountNumberList: List<AbstractAccountNumber>? = null

        @get:XmlElements(
            XmlElement(name = "BankCode", type = GeneralBankCode::class),
            XmlElement(name = "NationalBankCode", type = NationalBankCode::class)
        )
        var bankCodeList: List<AbstractBankCode>? = null

        @get:XmlElement(name = "AccountHolder")
        var accountHolder: String? = null
    }

    interface AbstractAccountNumber

    @XmlAccessorType(XmlAccessType.NONE)
    class GeneralAccountNumber : AbstractAccountNumber {
        @get:XmlAttribute(name = "international")
        var international: Boolean = false

        @get:XmlValue
        lateinit var value: String
    }

    @XmlAccessorType(XmlAccessType.NONE)
    class NationalAccountNumber : AbstractAccountNumber {
        @get:XmlAttribute(name = "format")
        lateinit var format: String

        @get:XmlValue
        lateinit var value: String
    }

    interface AbstractBankCode

    @XmlAccessorType(XmlAccessType.NONE)
    class GeneralBankCode : AbstractBankCode {
        @get:XmlAttribute(name = "prefix")
        var prefix: String? = null

        @get:XmlAttribute(name = "international")
        var international: Boolean = false

        @get:XmlValue
        lateinit var value: String
    }

    @XmlAccessorType(XmlAccessType.NONE)
    class NationalBankCode : AbstractBankCode {
        @get:XmlValue
        lateinit var value: String

        @get:XmlAttribute(name = "format")
        lateinit var format: String
    }
}
