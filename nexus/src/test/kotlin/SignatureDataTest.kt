package tech.libeufin.nexus

import tech.libeufin.util.XMLUtil
import org.apache.xml.security.binding.xmldsig.SignatureType
import org.junit.Test
import tech.libeufin.nexus.getNonce
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.ebics_h004.EbicsRequest
import tech.libeufin.util.ebics_h004.EbicsTypes
import java.math.BigInteger
import tech.libeufin.util.getGregorianDate

class SignatureDataTest {

    @Test
    fun makeSignatureData() {

        val pair = CryptoUtil.generateRsaKeyPair(1024)

        val tmp = EbicsRequest().apply {
            header = EbicsRequest.Header().apply {
                version = "H004"
                revision = 1
                authenticate = true
                static = EbicsRequest.StaticHeaderType().apply {
                    hostID = "some host ID"
                    nonce = getNonce(128)
                    timestamp = getGregorianDate()
                    partnerID = "some partner ID"
                    userID = "some user ID"
                    orderDetails = EbicsRequest.OrderDetails().apply {
                        orderType = "TST"
                        orderAttribute = "OZHNN"
                    }
                    bankPubKeyDigests = EbicsRequest.BankPubKeyDigests().apply {
                        authentication = EbicsTypes.PubKeyDigest().apply {
                            algorithm = "http://www.w3.org/2001/04/xmlenc#sha256"
                            version = "X002"
                            value = CryptoUtil.getEbicsPublicKeyHash(pair.public)
                        }
                        encryption = EbicsTypes.PubKeyDigest().apply {
                            algorithm = "http://www.w3.org/2001/04/xmlenc#sha256"
                            version = "E002"
                            value = CryptoUtil.getEbicsPublicKeyHash(pair.public)
                        }
                    }
                    securityMedium = "0000"
                    numSegments = BigInteger.ONE

                    authSignature = SignatureType()
                }
                mutable = EbicsRequest.MutableHeader().apply {
                    transactionPhase = EbicsTypes.TransactionPhaseType.INITIALISATION
                }
                body = EbicsRequest.Body().apply {
                    dataTransfer = EbicsRequest.DataTransfer().apply {
                        signatureData = EbicsRequest.SignatureData().apply {
                            authenticate = true
                            value = "to byte array".toByteArray()
                        }
                        dataEncryptionInfo = EbicsTypes.DataEncryptionInfo().apply {
                            transactionKey = "mock".toByteArray()
                            authenticate = true
                            encryptionPubKeyDigest = EbicsTypes.PubKeyDigest().apply {
                                algorithm = "http://www.w3.org/2001/04/xmlenc#sha256"
                                version = "E002"
                                value = CryptoUtil.getEbicsPublicKeyHash(pair.public)
                            }
                        }
                        hostId = "a host ID"
                    }
                }
            }
        }

        println(XMLUtil.convertJaxbToString(tmp))
    }
}