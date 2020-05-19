package tech.libeufin.sandbox

import junit.framework.TestCase.assertEquals
import org.apache.xml.security.binding.xmldsig.SignatureType
import org.junit.Test
import org.w3c.dom.Element
import tech.libeufin.util.ebics_h004.*
import tech.libeufin.util.ebics_hev.HEVResponse
import tech.libeufin.util.ebics_hev.SystemReturnCodeType
import tech.libeufin.util.ebics_s001.SignatureTypes
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.XMLUtil
import javax.xml.datatype.DatatypeFactory
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EbicsMessagesTest {
    /**
     * Tests the JAXB instantiation of non-XmlRootElement documents,
     * as notably are the inner XML strings carrying keys in INI/HIA
     * messages.
     */
    @Test
    fun testImportNonRoot() {
        val classLoader = ClassLoader.getSystemClassLoader()
        val ini = classLoader.getResource("ebics_ini_inner_key.xml")
        val jaxb = XMLUtil.convertStringToJaxb<SignatureTypes.SignaturePubKeyOrderData>(ini.readText())
        assertEquals("A006", jaxb.value.signaturePubKeyInfo.signatureVersion)
    }

    /**
     * Test string -> JAXB
     */
    @Test
    fun testStringToJaxb() {
        val classLoader = ClassLoader.getSystemClassLoader()
        val ini = classLoader.getResource("ebics_ini_request_sample.xml")
        val jaxb = XMLUtil.convertStringToJaxb<EbicsUnsecuredRequest>(ini.readText())
        println("jaxb loaded")
        assertEquals(
            "INI",
            jaxb.value.header.static.orderDetails.orderType
        )
    }

    /**
     * Test JAXB -> string
     */
    @Test
    fun testJaxbToString() {
        val hevResponseJaxb = HEVResponse().apply {
            this.systemReturnCode = SystemReturnCodeType().apply {
                this.reportText = "[EBICS_OK]"
                this.returnCode = "000000"
            }
            this.versionNumber = listOf(HEVResponse.VersionNumber.create("H004", "02.50"))
        }
        XMLUtil.convertJaxbToString(hevResponseJaxb)
    }

    /**
     * Test DOM -> JAXB
     */
    @Test
    fun testDomToJaxb() {
        val classLoader = ClassLoader.getSystemClassLoader()
        val ini = classLoader.getResource("ebics_ini_request_sample.xml")!!
        val iniDom = XMLUtil.parseStringIntoDom(ini.readText())
        XMLUtil.convertDomToJaxb<EbicsUnsecuredRequest>(
            EbicsUnsecuredRequest::class.java,
            iniDom
        )
    }

    @Test
    fun testKeyMgmgResponse() {
        val responseXml = EbicsKeyManagementResponse().apply {
            header = EbicsKeyManagementResponse.Header().apply {
                mutable = EbicsKeyManagementResponse.MutableHeaderType().apply {
                    reportText = "foo"
                    returnCode = "bar"
                }
                _static = EbicsKeyManagementResponse.EmptyStaticHeader()
            }
            version = "H004"
            body = EbicsKeyManagementResponse.Body().apply {
                returnCode = EbicsKeyManagementResponse.ReturnCode().apply {
                    authenticate = true
                    value = "000000"
                }
            }
        }
        val text = XMLUtil.convertJaxbToString(responseXml)
        assertTrue(text.isNotEmpty())
    }

    @Test
    fun testParseHiaRequestOrderData() {
        val classLoader = ClassLoader.getSystemClassLoader()
        val hia = classLoader.getResource("hia_request_order_data.xml")!!.readText()
        XMLUtil.convertStringToJaxb<HIARequestOrderData>(hia)
    }

    @Test
    fun testHiaLoad() {
        val classLoader = ClassLoader.getSystemClassLoader()
        val hia = classLoader.getResource("hia_request.xml")!!
        val hiaDom = XMLUtil.parseStringIntoDom(hia.readText())
        val x: Element = hiaDom.getElementsByTagNameNS(
            "urn:org:ebics:H004",
            "OrderDetails"
        )?.item(0) as Element

        x.setAttributeNS(
            "http://www.w3.org/2001/XMLSchema-instance",
            "type",
            "UnsecuredReqOrderDetailsType"
        )

        XMLUtil.convertDomToJaxb<EbicsUnsecuredRequest>(
            EbicsUnsecuredRequest::class.java,
            hiaDom
        )
    }

    @Test
    fun testLoadInnerKey() {
        val jaxbKey = run {
            val classLoader = ClassLoader.getSystemClassLoader()
            val file = classLoader.getResource(
                "ebics_ini_inner_key.xml"
            )
            assertNotNull(file)
            XMLUtil.convertStringToJaxb<SignatureTypes.SignaturePubKeyOrderData>(file.readText())
        }

        val modulus = jaxbKey.value.signaturePubKeyInfo.pubKeyValue.rsaKeyValue.modulus
        val exponent = jaxbKey.value.signaturePubKeyInfo.pubKeyValue.rsaKeyValue.exponent
        CryptoUtil.loadRsaPublicKeyFromComponents(modulus, exponent)
    }

    @Test
    fun testLoadIniMessage() {
        val classLoader = ClassLoader.getSystemClassLoader()
        val text = classLoader.getResource("ebics_ini_request_sample.xml")!!.readText()
        XMLUtil.convertStringToJaxb<EbicsUnsecuredRequest>(text)
    }

    @Test
    fun testLoadResponse() {
        val response = EbicsResponse().apply {
            version = "H004"
            header = EbicsResponse.Header().apply {
                _static = EbicsResponse.StaticHeaderType()
                mutable = EbicsResponse.MutableHeaderType().apply {
                    this.reportText = "foo"
                    this.returnCode = "bar"
                    this.transactionPhase = EbicsTypes.TransactionPhaseType.INITIALISATION
                }
            }
            authSignature = SignatureType()
            body = EbicsResponse.Body().apply {
                returnCode = EbicsResponse.ReturnCode().apply {
                    authenticate = true
                    value = "asdf"
                }
            }
        }
        print(XMLUtil.convertJaxbToString(response))
    }

    @Test
    fun testLoadHpb() {
        val classLoader = ClassLoader.getSystemClassLoader()
        val text = classLoader.getResource("hpb_request.xml")!!.readText()
        XMLUtil.convertStringToJaxb<EbicsNpkdRequest>(text)
    }

    @Test
    fun testHtd() {
        val htd = HTDResponseOrderData().apply {
            this.partnerInfo = EbicsTypes.PartnerInfo().apply {
                this.accountInfoList = listOf(
                    EbicsTypes.AccountInfo().apply {
                        this.id = "acctid1"
                        this.accountHolder = "Mina Musterfrau"
                        this.accountNumberList = listOf(
                            EbicsTypes.GeneralAccountNumber().apply {
                                this.international = true
                                this.value = "AT411100000237571500"
                            }
                        )
                        this.currency = "EUR"
                        this.description = "some account"
                        this.bankCodeList = listOf(
                            EbicsTypes.GeneralBankCode().apply {
                                this.international = true
                                this.value = "ABAGATWWXXX"
                            }
                        )
                    }
                )
                this.addressInfo = EbicsTypes.AddressInfo().apply {
                    this.name = "Foo"
                }
                this.bankInfo = EbicsTypes.BankInfo().apply {
                    this.hostID = "MYHOST"
                }
                this.orderInfoList = listOf(
                    EbicsTypes.AuthOrderInfoType().apply {
                        this.description = "foo"
                        this.orderType = "CCC"
                        this.orderFormat = "foo"
                        this.transferType = "Upload"
                    }
                )
            }
            this.userInfo = EbicsTypes.UserInfo().apply {
                this.name = "Some User"
                this.userID = EbicsTypes.UserIDType().apply {
                    this.status = 2
                    this.value = "myuserid"
                }
                this.permissionList = listOf(
                    EbicsTypes.UserPermission().apply {
                        this.orderTypes = "CCC ABC"
                    }
                )
            }
        }

        val str = XMLUtil.convertJaxbToString(htd)
        println(str)
        assert(XMLUtil.validateFromString(str))
    }


    @Test
    fun testHkd() {
        val hkd = HKDResponseOrderData().apply {
            this.partnerInfo = EbicsTypes.PartnerInfo().apply {
                this.accountInfoList = listOf(
                    EbicsTypes.AccountInfo().apply {
                        this.id = "acctid1"
                        this.accountHolder = "Mina Musterfrau"
                        this.accountNumberList = listOf(
                            EbicsTypes.GeneralAccountNumber().apply {
                                this.international = true
                                this.value = "AT411100000237571500"
                            }
                        )
                        this.currency = "EUR"
                        this.description = "some account"
                        this.bankCodeList = listOf(
                            EbicsTypes.GeneralBankCode().apply {
                                this.international = true
                                this.value = "ABAGATWWXXX"
                            }
                        )
                    }
                )
                this.addressInfo = EbicsTypes.AddressInfo().apply {
                    this.name = "Foo"
                }
                this.bankInfo = EbicsTypes.BankInfo().apply {
                    this.hostID = "MYHOST"
                }
                this.orderInfoList = listOf(
                    EbicsTypes.AuthOrderInfoType().apply {
                        this.description = "foo"
                        this.orderType = "CCC"
                        this.orderFormat = "foo"
                        this.transferType = "Upload"
                    }
                )
            }
            this.userInfoList = listOf(
                EbicsTypes.UserInfo().apply {
                    this.name = "Some User"
                    this.userID = EbicsTypes.UserIDType().apply {
                        this.status = 2
                        this.value = "myuserid"
                    }
                    this.permissionList = listOf(
                        EbicsTypes.UserPermission().apply {
                            this.orderTypes = "CCC ABC"
                        }
                    )
                })
        }

        val str = XMLUtil.convertJaxbToString(hkd)
        println(str)
        assert(XMLUtil.validateFromString(str))
    }

    @Test
    fun testEbicsRequestInitializationPhase() {
        val ebicsRequestObj = EbicsRequest().apply {
            this.version = "H004"
            this.revision = 1
            this.authSignature = SignatureType()
            this.header = EbicsRequest.Header().apply {
                this.authenticate = true
                this.mutable = EbicsRequest.MutableHeader().apply {
                    this.transactionPhase = EbicsTypes.TransactionPhaseType.INITIALISATION
                }
                this.static = EbicsRequest.StaticHeaderType().apply {
                    this.hostID = "myhost"
                    this.nonce = ByteArray(16)
                    this.timestamp =
                        DatatypeFactory.newDefaultInstance().newXMLGregorianCalendar(2019, 5, 5, 5, 5, 5, 0, 0)
                    this.partnerID = "mypid01"
                    this.userID = "myusr01"
                    this.product = EbicsTypes.Product().apply {
                        this.instituteID = "test"
                        this.language = "en"
                        this.value = "test"
                    }
                    this.orderDetails = EbicsRequest.OrderDetails().apply {
                        this.orderAttribute = "DZHNN"
                        this.orderID = "OR01"
                        this.orderType = "BLA"
                        this.orderParams = EbicsRequest.StandardOrderParams()
                    }
                    this.bankPubKeyDigests = EbicsRequest.BankPubKeyDigests().apply {
                        this.authentication = EbicsTypes.PubKeyDigest().apply {
                            this.algorithm = "foo"
                            this.value = ByteArray(32)
                            this.version = "X002"
                        }
                        this.encryption = EbicsTypes.PubKeyDigest().apply {
                            this.algorithm = "foo"
                            this.value = ByteArray(32)
                            this.version = "E002"
                        }
                    }
                    this.securityMedium = "0000"
                }
            }
            this.body = EbicsRequest.Body().apply {
            }
        }

        val str = XMLUtil.convertJaxbToString(ebicsRequestObj)
        val doc = XMLUtil.parseStringIntoDom(str)
        val pair = CryptoUtil.generateRsaKeyPair(1024)
        XMLUtil.signEbicsDocument(doc, pair.private)
        val finalStr = XMLUtil.convertDomToString(doc)
        assert(XMLUtil.validateFromString(finalStr))
    }
}