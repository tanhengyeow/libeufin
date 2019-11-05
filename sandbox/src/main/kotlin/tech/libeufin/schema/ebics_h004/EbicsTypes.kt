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

package tech.libeufin.schema.ebics_h004

import org.apache.xml.security.binding.xmldsig.RSAKeyValueType
import org.w3c.dom.Element
import java.math.BigInteger
import javax.xml.bind.annotation.*
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter
import javax.xml.bind.annotation.adapters.HexBinaryAdapter
import javax.xml.bind.annotation.adapters.NormalizedStringAdapter
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter
import javax.xml.datatype.XMLGregorianCalendar


/**
 * EBICS type definitions that are shared between other requests / responses / order types.
 */
class EbicsTypes private constructor() {
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
    class DataEncryptionInfo {
        @get:XmlAttribute(name = "authenticate", required = true)
        var authenticate: Boolean = false

        @get:XmlElement(name = "EncryptionPubKeyDigest", required = true)
        lateinit var encryptionPubKeyDigest: EncryptionPubKeyDigest

        @get:XmlElement(name = "TransactionKey", required = true)
        lateinit var transactionKey: ByteArray

        @get:XmlAnyElement(lax = true)
        var any: List<Any>? = null

        @XmlAccessorType(XmlAccessType.NONE)
        class EncryptionPubKeyDigest {
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
}