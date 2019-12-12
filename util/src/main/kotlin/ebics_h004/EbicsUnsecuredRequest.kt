package tech.libeufin.util.ebics_h004

import org.apache.xml.security.binding.xmldsig.RSAKeyValueType
import tech.libeufin.util.EbicsOrderUtil
import tech.libeufin.util.ebics_s001.SignatureTypes
import java.security.interfaces.RSAPrivateCrtKey
import javax.xml.bind.annotation.*
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter

@XmlAccessorType(XmlAccessType.NONE)
@XmlType(name = "", propOrder = ["header", "body"])
@XmlRootElement(name = "ebicsUnsecuredRequest")
class EbicsUnsecuredRequest {
    @get:XmlAttribute(name = "Version", required = true)
    @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
    lateinit var version: String

    @get:XmlAttribute(name = "Revision")
    var revision: Int? = null

    @get:XmlElement(name = "header", required = true)
    lateinit var header: Header

    @get:XmlElement(required = true)
    lateinit var body: Body

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["static", "mutable"])
    class Header {
        @XmlAccessorType(XmlAccessType.NONE)
        @XmlType(name = "")
        class EmptyMutableHeader

        @get:XmlElement(name = "static", required = true)
        lateinit var static: StaticHeaderType

        @get:XmlElement(required = true)
        lateinit var mutable: EmptyMutableHeader

        @get:XmlAttribute(name = "authenticate", required = true)
        var authenticate: Boolean = false
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["dataTransfer"])
    class Body {
        @get:XmlElement(name = "DataTransfer", required = true)
        lateinit var dataTransfer: UnsecuredDataTransfer
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["orderData"])
    class UnsecuredDataTransfer {
        @get:XmlElement(name = "OrderData", required = true)
        lateinit var orderData: OrderData
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "")
    class OrderData {
        @get:XmlValue
        lateinit var value: ByteArray
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(
        name = "",
        propOrder = ["hostID", "partnerID", "userID", "systemID", "product", "orderDetails", "securityMedium"]
    )
    class StaticHeaderType {
        @get:XmlElement(name = "HostID", required = true)
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        lateinit var hostID: String

        @get:XmlElement(name = "PartnerID", required = true)
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        lateinit var partnerID: String

        @get:XmlElement(name = "UserID", required = true)
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        lateinit var userID: String

        @get:XmlElement(name = "SystemID")
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        var systemID: String? = null

        @get:XmlElement(name = "Product")
        val product: EbicsTypes.Product? = null

        @get:XmlElement(name = "OrderDetails", required = true)
        lateinit var orderDetails: OrderDetails

        @get:XmlElement(name = "SecurityMedium", required = true)
        lateinit var securityMedium: String
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["orderType", "orderAttribute"])
    class OrderDetails {
        @get:XmlElement(name = "OrderType", required = true)
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        lateinit var orderType: String

        @get:XmlElement(name = "OrderAttribute", required = true)
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        lateinit var orderAttribute: String
    }

    companion object {

        fun createHia(
            hostId: String,
            userId: String,
            partnerId: String,
            authKey: RSAPrivateCrtKey,
            encKey: RSAPrivateCrtKey

        ): EbicsUnsecuredRequest {

            return EbicsUnsecuredRequest().apply {

                version = "H004"
                revision = 1
                header = Header().apply {
                    authenticate = true
                    static = StaticHeaderType().apply {
                        orderDetails = OrderDetails().apply {
                            orderAttribute = "DZNNN"
                            orderType = "HIA"
                            securityMedium = "0000"
                            hostID = hostId
                            userID = userId
                            partnerID = partnerId
                        }
                    }
                    mutable = Header.EmptyMutableHeader()
                }
                body = Body().apply {
                    dataTransfer = UnsecuredDataTransfer().apply {
                        orderData = OrderData().apply {
                            value = EbicsOrderUtil.encodeOrderDataXml(
                                HIARequestOrderData().apply {
                                    authenticationPubKeyInfo = EbicsTypes.AuthenticationPubKeyInfoType()
                                        .apply {
                                        pubKeyValue = EbicsTypes.PubKeyValueType().apply {
                                            rsaKeyValue = RSAKeyValueType().apply {
                                                exponent = authKey.publicExponent.toByteArray()
                                                modulus = authKey.modulus.toByteArray()
                                            }
                                        }
                                        authenticationVersion = "X002"
                                    }
                                    encryptionPubKeyInfo = EbicsTypes.EncryptionPubKeyInfoType()
                                        .apply {
                                        pubKeyValue = EbicsTypes.PubKeyValueType().apply {
                                            rsaKeyValue = RSAKeyValueType().apply {
                                                exponent = encKey.publicExponent.toByteArray()
                                                modulus = encKey.modulus.toByteArray()
                                            }
                                        }
                                        encryptionVersion = "E002"

                                    }
                                    partnerID = partnerId
                                    userID = userId
                                }
                            )
                        }
                    }
                }
            }
        }

        fun createIni(
            hostId: String,
            userId: String,
            partnerId: String,
            signKey: RSAPrivateCrtKey

        ): EbicsUnsecuredRequest {
            return EbicsUnsecuredRequest().apply {
                version = "H004"
                revision = 1
                header = Header().apply {
                    authenticate = true
                    static = StaticHeaderType().apply {
                        orderDetails = OrderDetails().apply {
                            orderAttribute = "DZNNN"
                            orderType = "INI"
                            securityMedium = "0000"
                            hostID = hostId
                            userID = userId
                            partnerID = partnerId
                        }
                    }
                    mutable = Header.EmptyMutableHeader()
                }
                body = Body().apply {
                    dataTransfer = UnsecuredDataTransfer().apply {
                        orderData = OrderData().apply {
                            value = EbicsOrderUtil.encodeOrderDataXml(
                                SignatureTypes.SignaturePubKeyOrderData().apply {
                                    signaturePubKeyInfo = SignatureTypes.SignaturePubKeyInfoType().apply {
                                        signatureVersion = "A006"
                                        pubKeyValue = SignatureTypes.PubKeyValueType().apply {
                                            rsaKeyValue = org.apache.xml.security.binding.xmldsig.RSAKeyValueType().apply {
                                                exponent = signKey.publicExponent.toByteArray()
                                                modulus = signKey.modulus.toByteArray()
                                            }
                                        }
                                    }
                                    userID = userId
                                    partnerID = partnerId
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
