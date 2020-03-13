/*
 * This file is part of LibEuFin.
 * Copyright (C) 2019 Stanisci and Dold.

 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.

 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.

 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

package tech.libeufin.util.ebics_h004

import org.apache.xml.security.binding.xmldsig.RSAKeyValueType
import org.w3c.dom.Element
import java.math.BigInteger
import java.util.*
import javax.xml.bind.annotation.*
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter
import javax.xml.bind.annotation.adapters.NormalizedStringAdapter
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter
import javax.xml.datatype.XMLGregorianCalendar


/**
 * EBICS type definitions that are shared between other requests / responses / order types.
 */
object EbicsTypes {
    /**
     * EBICS client product.  Identifies the software that accesses the EBICS host.
     */
    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "Product", propOrder = ["value"])
    class Product {
        @get:XmlValue
        @get:XmlJavaTypeAdapter(NormalizedStringAdapter::class)
        lateinit var value: String

        @get:XmlAttribute(name = "Language", required = true)
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        lateinit var language: String

        @get:XmlAttribute(name = "InstituteID")
        @get:XmlJavaTypeAdapter(NormalizedStringAdapter::class)
        var instituteID: String? = null
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["value"])
    class SegmentNumber {
        @XmlValue
        lateinit var value: BigInteger

        @XmlAttribute(name = "lastSegment")
        var lastSegment: Boolean? = null
    }


    @XmlType(name = "", propOrder = ["encryptionPubKeyDigest", "transactionKey"])
    @XmlAccessorType(XmlAccessType.NONE)
    class DataEncryptionInfo {
        @get:XmlAttribute(name = "authenticate", required = true)
        var authenticate: Boolean = false

        @get:XmlElement(name = "EncryptionPubKeyDigest", required = true)
        lateinit var encryptionPubKeyDigest: PubKeyDigest

        @get:XmlElement(name = "TransactionKey", required = true)
        lateinit var transactionKey: ByteArray
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["value"])
    class PubKeyDigest {
        /**
         * Version of the *digest* of the public key.
         */
        @get:XmlAttribute(name = "Version", required = true)
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        lateinit var version: String

        @XmlAttribute(name = "Algorithm", required = true)
        @XmlSchemaType(name = "anyURI")
        lateinit var algorithm: String

        @get:XmlValue
        lateinit var value: ByteArray
    }

    @Suppress("UNUSED_PARAMETER")
    enum class TransactionPhaseType(value: String) {
        @XmlEnumValue("Initialisation")
        INITIALISATION("Initialisation"),

        /**
         * Auftragsdatentransfer
         *
         */
        @XmlEnumValue("Transfer")
        TRANSFER("Transfer"),

        /**
         * Quittungstransfer
         *
         */
        @XmlEnumValue("Receipt")
        RECEIPT("Receipt");
    }


    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "")
    class TimestampBankParameter {
        @get:XmlValue
        lateinit var value: XMLGregorianCalendar

        @get:XmlAttribute(name = "authenticate", required = true)
        var authenticate: Boolean = false
    }



    @XmlType(
        name = "PubKeyValueType", propOrder = [
            "rsaKeyValue",
            "timeStamp"
        ]
    )
    @XmlAccessorType(XmlAccessType.NONE)
    class PubKeyValueType {
        @get:XmlElement(name = "RSAKeyValue", namespace = "http://www.w3.org/2000/09/xmldsig#", required = true)
        lateinit var rsaKeyValue: RSAKeyValueType

        @get:XmlElement(name = "TimeStamp", required = false)
        @get:XmlSchemaType(name = "dateTime")
        var timeStamp: XMLGregorianCalendar? = null
    }


    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(
        name = "AuthenticationPubKeyInfoType", propOrder = [
            "x509Data",
            "pubKeyValue",
            "authenticationVersion"
        ]
    )
    class AuthenticationPubKeyInfoType {
        @get:XmlAnyElement()
        var x509Data: Element? = null

        @get:XmlElement(name = "PubKeyValue", required = true)
        lateinit var pubKeyValue: PubKeyValueType

        @get:XmlElement(name = "AuthenticationVersion", required = true)
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        @get:XmlSchemaType(name = "token")
        lateinit var authenticationVersion: String
    }


    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(
        name = "EncryptionPubKeyInfoType", propOrder = [
            "x509Data",
            "pubKeyValue",
            "encryptionVersion"
        ]
    )
    class EncryptionPubKeyInfoType {
        @get:XmlAnyElement()
        var x509Data: Element? = null

        @get:XmlElement(name = "PubKeyValue", required = true)
        lateinit var pubKeyValue: PubKeyValueType

        @get:XmlElement(name = "EncryptionVersion", required = true)
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        @get:XmlSchemaType(name = "token")
        lateinit var encryptionVersion: String
    }

    @XmlAccessorType(XmlAccessType.NONE)
    class FileFormatType {
        @get:XmlAttribute(name = "CountryCode")
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        lateinit var language: String

        @get:XmlValue
        @get:XmlJavaTypeAdapter(NormalizedStringAdapter::class)
        lateinit var value: String
    }

    /**
     * Generic key-value pair.
     */
    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["name", "value"])
    class Parameter {
        @get:XmlAttribute(name = "Type", required = true)
        lateinit var type: String

        @get:XmlElement(name = "Name", required = true)
        lateinit var name: String

        @get:XmlElement(name = "Value", required = true)
        lateinit var value: String
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["addressInfo", "bankInfo", "accountInfoList", "orderInfoList"])
    class PartnerInfo {
        @get:XmlElement(name = "AddressInfo", required = true)
        lateinit var addressInfo: AddressInfo

        @get:XmlElement(name = "BankInfo", required = true)
        lateinit var bankInfo: BankInfo

        @get:XmlElement(name = "AccountInfo", type = AccountInfo::class)
        var accountInfoList: List<AccountInfo>? = LinkedList<AccountInfo>()

        @get:XmlElement(name = "OrderInfo", type = AuthOrderInfoType::class)
        var orderInfoList: List<AuthOrderInfoType> = LinkedList<AuthOrderInfoType>()
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
        val fileFormat: FileFormatType? = null

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
        val fileFormat: FileFormatType? = null

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

        @get:XmlElement(type = Parameter::class)
        var parameters: List<Parameter>? = null
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
        var accountNumberList: List<AbstractAccountNumber>? = LinkedList<AbstractAccountNumber>()

        @get:XmlElements(
            XmlElement(name = "BankCode", type = GeneralBankCode::class),
            XmlElement(name = "NationalBankCode", type = NationalBankCode::class)
        )
        var bankCodeList: List<AbstractBankCode>? = LinkedList<AbstractBankCode>()

        @get:XmlElement(name = "AccountHolder")
        var accountHolder: String? = null
    }

    interface AbstractAccountNumber

    @XmlAccessorType(XmlAccessType.NONE)
    class GeneralAccountNumber : AbstractAccountNumber {
        @get:XmlAttribute(name = "international")
        var international: Boolean = true

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
        var international: Boolean = true

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