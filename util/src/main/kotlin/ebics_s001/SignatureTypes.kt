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

package tech.libeufin.util.ebics_s001

import org.apache.xml.security.binding.xmldsig.RSAKeyValueType
import org.apache.xml.security.binding.xmldsig.X509DataType
import javax.xml.bind.annotation.*
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter
import javax.xml.datatype.XMLGregorianCalendar


object SignatureTypes {

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(
        name = "PubKeyValueType", namespace = "http://www.ebics.org/S001", propOrder = [
            "rsaKeyValue",
            "timeStamp"
        ]
    )
    class PubKeyValueType {
        @get:XmlElement(name = "RSAKeyValue", namespace = "http://www.w3.org/2000/09/xmldsig#", required = true)
        lateinit var rsaKeyValue: RSAKeyValueType

        @get:XmlElement(name = "TimeStamp")
        @get:XmlSchemaType(name = "dateTime")
        var timeStamp: XMLGregorianCalendar? = null
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(
        name = "",
        propOrder = [
            "x509Data",
            "pubKeyValue",
            "signatureVersion"
        ]
    )
    class SignaturePubKeyInfoType {
        @get:XmlElement(name = "X509Data")
        var x509Data: X509DataType? = null

        @get:XmlElement(name = "PubKeyValue", required = true)
        lateinit var pubKeyValue: PubKeyValueType

        @get:XmlElement(name = "SignatureVersion", required = true)
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        lateinit var signatureVersion: String
    }

    /**
     * EBICS INI payload.
     */
    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(
        name = "",
        propOrder = ["signaturePubKeyInfo", "partnerID", "userID"]
    )
    @XmlRootElement(name = "SignaturePubKeyOrderData")
    class SignaturePubKeyOrderData {
        @get:XmlElement(name = "SignaturePubKeyInfo", required = true)
        lateinit var signaturePubKeyInfo: SignaturePubKeyInfoType

        @get:XmlElement(name = "PartnerID", required = true)
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        @get:XmlSchemaType(name = "token")
        lateinit var partnerID: String

        @get:XmlElement(name = "UserID", required = true)
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        @get:XmlSchemaType(name = "token")
        lateinit var userID: String
    }
}