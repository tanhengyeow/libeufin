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
        val ini = classLoader.getResource("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<SignaturePubKeyOrderData xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://www.ebics.org/S001 http://www.ebics.org/S001/ebics_signature.xsd\" xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\" xmlns=\"http://www.ebics.org/S001\">\n  <SignaturePubKeyInfo>\n    <PubKeyValue>\n      <ds:RSAKeyValue>\n        <ds:Modulus>s5ktpg3xGjbZZgVTYtW+0e6xsWg142UwvoM3mfuM+qrkIa5bPUGQLH6BRL9IejYosPhoA6jwMBSxO8LfaageyZJt2M5wHklJYz3fADtQrV1bk5R92OaY/9ZZdHxw3xY93tm5JfVrMDW9DEK5B1hUzYFdjuN/qu2/sdE9mwhx2YjYwwdSQzv6MhbtSK9OAJjPGo3fkxsht6maSmRCdgxplIOSO2mmP1wjUzbVUMcrRk9KDMvnb3kCxiTm+evvxX6J4wpY1bAWukolJbaALHlFtgTo1LnulUe/BxiKx9HpmuEAaPsk8kgduXsz5OqH2g/Vyw75x51aKVPxOTBPyP+4kQ==</ds:Modulus>\n        <ds:Exponent>AQAB</ds:Exponent>\n      </ds:RSAKeyValue>\n    </PubKeyValue>\n    <SignatureVersion>A006</SignatureVersion>\n  </SignaturePubKeyInfo>\n  <PartnerID>flo-kid</PartnerID>\n  <UserID>flo-uid</UserID>\n</SignaturePubKeyOrderData>")
        val jaxb = XMLUtil.convertStringToJaxb<SignatureTypes.SignaturePubKeyOrderData>(ini.readText())
        assertEquals("A006", jaxb.value.signaturePubKeyInfo.signatureVersion)
    }

    /**
     * Test string -> JAXB
     */
    @Test
    fun testStringToJaxb() {
        val classLoader = ClassLoader.getSystemClassLoader()
        val ini = classLoader.getResource("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<ebicsUnsecuredRequest Revision=\"1\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n                       xsi:schemaLocation=\"urn:org:ebics:H004 ebics_keymgmt_request_H004.xsd\" Version=\"H004\"\n                       xmlns=\"urn:org:ebics:H004\">\n    <header authenticate=\"true\">\n        <static>\n            <HostID>myhost</HostID>\n            <PartnerID>k1</PartnerID>\n            <UserID>u1</UserID>\n            <OrderDetails>\n                <OrderType>INI</OrderType>\n                <OrderAttribute>DZNNN</OrderAttribute>\n            </OrderDetails>\n            <SecurityMedium>0000</SecurityMedium>\n        </static>\n        <mutable/>\n    </header>\n    <body>\n        <DataTransfer>\n            <OrderData>\n                eJx9U1tzmkAUfu9M/wNDH524XIINjprBaOIFCopg8CWzyHK/GHaR1V9fhtg0bWrf9nzfdy57LoN7mqXMEZU4KvIhy3c5lkH5vvCiPBiyFfFv7tj70dcvAzMKckiqEhmVu0QnvfRQOYEEMo1/jvsUR0M2JOTQB6Cu624tdosyAALH8eBZU819iDJ4E+WYwHyPWKbR93ELqsUekjb5B3fkRnvcRjCbCMxVBrTmC/5VXJdij72U5OErFXGAk0Gj8Rq3bxf1f7KzzfcZ5u8GzHO/aImGekNsmFboAjWgh/trU/mEvzFa4VVphUdYSsghEOlT7O52gb1xyLbDoR7F24C/Faz6WGhi5lda57VM5lByDetppc5647Uqz1HsFNgIC6UX19rYpPqd6kMYoNNuQQRNqmdJunDOoq9MyKq0eTeR1rKgQwfIu503o7VIHVkkmbTw7VKbbOXJdCmN+dA6O49eXP0Ar5UAsDeVszqkghM7de2Zq/Oxp4UuMZeyrixi46kQ/YTikPQyaGbrBy+gh3Sum7qQZQZfx9bZtS1tX64TeTnRjrkrJg802mQddDzS597itj44vKtsq6RIFy5U1Fn6SIJNwat5lVoIjGm0pPLskFVTBRo4uUsCr3rGZ0l/nQkBsE/1d4lKPFzaBtU3Y+NkdG6T1XA4AB+a/Gfrp/RQ5CgnI2WljFvdO/I+O/DP4Q3A50H/Xgv77YZGCsf1BuAT3O4QuLZEAwOWJEflfDJK+CbPu9WSFm7fVcNcns1BgmsXOfoJ1l5CIg==\n            </OrderData>\n        </DataTransfer>\n    </body>\n</ebicsUnsecuredRequest>")
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
        val ini = classLoader.getResource("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<ebicsUnsecuredRequest Revision=\"1\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n                       xsi:schemaLocation=\"urn:org:ebics:H004 ebics_keymgmt_request_H004.xsd\" Version=\"H004\"\n                       xmlns=\"urn:org:ebics:H004\">\n    <header authenticate=\"true\">\n        <static>\n            <HostID>myhost</HostID>\n            <PartnerID>k1</PartnerID>\n            <UserID>u1</UserID>\n            <OrderDetails>\n                <OrderType>INI</OrderType>\n                <OrderAttribute>DZNNN</OrderAttribute>\n            </OrderDetails>\n            <SecurityMedium>0000</SecurityMedium>\n        </static>\n        <mutable/>\n    </header>\n    <body>\n        <DataTransfer>\n            <OrderData>\n                eJx9U1tzmkAUfu9M/wNDH524XIINjprBaOIFCopg8CWzyHK/GHaR1V9fhtg0bWrf9nzfdy57LoN7mqXMEZU4KvIhy3c5lkH5vvCiPBiyFfFv7tj70dcvAzMKckiqEhmVu0QnvfRQOYEEMo1/jvsUR0M2JOTQB6Cu624tdosyAALH8eBZU819iDJ4E+WYwHyPWKbR93ELqsUekjb5B3fkRnvcRjCbCMxVBrTmC/5VXJdij72U5OErFXGAk0Gj8Rq3bxf1f7KzzfcZ5u8GzHO/aImGekNsmFboAjWgh/trU/mEvzFa4VVphUdYSsghEOlT7O52gb1xyLbDoR7F24C/Faz6WGhi5lda57VM5lByDetppc5647Uqz1HsFNgIC6UX19rYpPqd6kMYoNNuQQRNqmdJunDOoq9MyKq0eTeR1rKgQwfIu503o7VIHVkkmbTw7VKbbOXJdCmN+dA6O49eXP0Ar5UAsDeVszqkghM7de2Zq/Oxp4UuMZeyrixi46kQ/YTikPQyaGbrBy+gh3Sum7qQZQZfx9bZtS1tX64TeTnRjrkrJg802mQddDzS597itj44vKtsq6RIFy5U1Fn6SIJNwat5lVoIjGm0pPLskFVTBRo4uUsCr3rGZ0l/nQkBsE/1d4lKPFzaBtU3Y+NkdG6T1XA4AB+a/Gfrp/RQ5CgnI2WljFvdO/I+O/DP4Q3A50H/Xgv77YZGCsf1BuAT3O4QuLZEAwOWJEflfDJK+CbPu9WSFm7fVcNcns1BgmsXOfoJ1l5CIg==\n            </OrderData>\n        </DataTransfer>\n    </body>\n</ebicsUnsecuredRequest>")!!
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
        val hia = classLoader.getResource("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<HIARequestOrderData xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"urn:org:ebics:H004 ebics_orders_H004.xsd\" xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\" xmlns=\"urn:org:ebics:H004\">\n    <AuthenticationPubKeyInfo>\n        <PubKeyValue>\n            <ds:RSAKeyValue>\n                <ds:Modulus>0Ekicvrcj2+8tsF+DZsWihl9W7AyVwtMLxq3qefSWagpfnV7BVsKYIJ/OhiWpvr3dz6K5lHSatzhG1x//jrZt6VHn5Wkkb0M0vayPUiZbe5s2aLabqfOTrt8TPnHwjZMChDHRmGoKI0OzLyQJ6MIfQrHZ5t61ccWubYO/bgbSnP9H39k8QEp0kmW4Tf4u+28GTLgueNAaaPTdCozZjrST4fH9nyhBUZ3nl+vZ+AiUNdl5UfV109CXhCm3safLboUus6ZcYLm6gTaiwJEdRX7HYbnAQZ5gcoXVz/oyxJqTkicVOLPrTAfi3UmFrnIVF8XBtOPdIXHzSpxZ3yT8gH4zQ==</ds:Modulus>\n                <ds:Exponent>AQAB</ds:Exponent>\n            </ds:RSAKeyValue>\n        </PubKeyValue>\n        <AuthenticationVersion>X002</AuthenticationVersion>\n    </AuthenticationPubKeyInfo>\n    <EncryptionPubKeyInfo>\n        <PubKeyValue>\n            <ds:RSAKeyValue>\n                <ds:Modulus>0Ekicvrcj2+8tsF+DZsWihl9W7AyVwtMLxq3qefSWagpfnV7BVsKYIJ/OhiWpvr3dz6K5lHSatzhG1x//jrZt6VHn5Wkkb0M0vayPUiZbe5s2aLabqfOTrt8TPnHwjZMChDHRmGoKI0OzLyQJ6MIfQrHZ5t61ccWubYO/bgbSnP9H39k8QEp0kmW4Tf4u+28GTLgueNAaaPTdCozZjrST4fH9nyhBUZ3nl+vZ+AiUNdl5UfV109CXhCm3safLboUus6ZcYLm6gTaiwJEdRX7HYbnAQZ5gcoXVz/oyxJqTkicVOLPrTAfi3UmFrnIVF8XBtOPdIXHzSpxZ3yT8gH4zQ==</ds:Modulus>\n                <ds:Exponent>AQAB</ds:Exponent>\n            </ds:RSAKeyValue>\n        </PubKeyValue>\n        <EncryptionVersion>E002</EncryptionVersion>\n    </EncryptionPubKeyInfo>\n    <PartnerID>PARTNER1</PartnerID>\n    <UserID>USER1</UserID>\n</HIARequestOrderData>")!!.readText()
        XMLUtil.convertStringToJaxb<HIARequestOrderData>(hia)
    }

    @Test
    fun testHiaLoad() {
        val classLoader = ClassLoader.getSystemClassLoader()
        val hia = classLoader.getResource("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ebicsUnsecuredRequest xmlns=\"urn:org:ebics:H004\"\n                       xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"\n                       xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n                       xsi:schemaLocation=\"urn:org:ebics:H004 ebics_keymgmt_request_H004.xsd\"\n                       Version=\"H004\"\n                       Revision=\"1\">\n  <header authenticate=\"true\">\n    <static>\n      <HostID>LIBEUFIN-SANDBOX</HostID>\n      <PartnerID>CUSTM001</PartnerID>\n      <UserID>u1</UserID>\n      <OrderDetails>\n      <OrderType>HIA</OrderType>\n      <OrderAttribute>DZNNN</OrderAttribute>\n      </OrderDetails>\n      <SecurityMedium>0000</SecurityMedium>\n    </static>\n    <mutable/>\n  </header>\n  <body>\n    <DataTransfer>\n      <OrderData>eJzNlsmyo0YWhvf1FBW3l0SZZBY3VHIwCwESM0g7ZpCYxAxPb/mWh7ZdrlUvmhX8Z/gzyPNl5P7npSo/T0nXF0399Q35Cbz9fPi0P8qMmTzHpB8uXZx0fDAEn1+Jdf8e91/f8mFo32F4nuefZuynpstgFAAAAxp+5cR9kf3n7dPn7z0fLb6+jV39/qp6T8Ii6t+PAOA/yn9PXh3/YvpR9+FrAYD8sHbpi39ZLwL7mmpFeVIFX4q6H4I6St4Or157ZhzypB6KKBheP0UfQyVZ5TptDh9G+2+CG5RjcvjNeh/376bF/F3+FtCaeCzH/sCccjF6CEpIHO9qNg7sk6vEJqcu5+zCjy3Te4YaE00r3WCcLidQGGEqJ4uoRMrcaQFlAnAK2xDVb1T2KGFMbW87hbMe9m5XzjCb6kuIareAuStQx66uzs+PUlk5oQim7B6eoNW2qo4Ljxu4jbFWLM4M7n4geL2ZNSiMnxum8+d+YTntqIqVV3eS06KTHYeBuOmqaXA1Ar/csDRAAFFNmr8cGc07u8ZC9TidnjhurtHURIQpvLBIf6Uj/5xQT2pGj62hFy2jbldl1wQtTkKbBKm0d2pWZIxX6Q6brViRT8Hl5UeW6FnsgIC1YQslccVX8AhjALHjgHF/ENYD8+3O7ZLOjPhJWvOeMniZ3ayU8F9rAxgdA9xMO1mN0ITDKKWuzZ23hRWH6P3ZKRn3FBhahVPVUOgmF1Br6mWEGkoWQ9h1eXcWlHsA5sGwVg6PurNwXCdWaw70kceyi3Gye6IjoQTPmksNZ5SAyYFlYQD2HLzenvaur+j7YOw20fN9ZEQK+yYyl0AYctO+tL7HmmVR6lw3hpVuLQpfJxvUcjJfsQ+pQCzmVCuiBpbg+DR1uktRiLu3+LHTSiTqQrWN+QF3a4lGdo/NMirzSlakYYUsCgekexluTTKjt4LKV65gMNMX5fIM3xBPu3PUGepNk19iM6yPa4K3z9m8X4KU3gVayt7E+jGR7DWwoZlcyj38X4P7l2kWlrapX3QcGINhP9L+UH6HAf4+DXu7qBJrCKr2gAJk9wVBvgDERpB3BLxjxG0P/xn/Bhr8D9L+hqb77dg6+ACge/j7sV+Bhn9E9F6oo25t/7eoqyBWNpkKhBnWdoKGuhmCBRJ82aiLXKO+c69WObdyykqpGrcg2A+PPp8it7LPdjNhK5BiMEIn6h6QgJHjEWsmk3Jz+9arzzIBzYNz3qj0mNT8xVTYUR8xVxXWklfOR9nz6FQ6z/gI7Gwkg6kTjx2OnisyF3DN823VzoSr4g5ryJwbHMquYT4NDSEM0PbsLwYsRRPq9lvpA5nnoqlXi9vTCYT4FkyoE7QDt5aBImu2fMdO5d0bBJftAd5cSFpop0IvJKXEH4KxYVdK8B7lsp0JfSmIiwiriH9a+plTZiGxZc8hezlARdah8KgLdshknHN6xLyi0SWHjCm6cYu2J4n6VkCYMjfdzMMxRs1sRCsAt9LXlrr01YT4jeM2RE95x8qg+zShwUrm7MyWCdonrmK+SL8U8gsGwbCZbECR3PP5ihjNrA6ryssn9bR6BnTlRnBkNYmdG6aEuzBLGnrk1WxY2VwBGH5S6tCp/VFGBobwT2FY2JSrPquMjHjlFnGFWDCutPVlcNIwljSFU34Ws5h4bdgq2eKUHV+jcHVQx5v74z2uToGp3xmRjGxpTkTEnnnaf7zQb+rdLpo0DlJL7ZwxCJ8OJIRM6iQvetZRo4sQoCTSbQp1HVovaZ95jUK/zljDup8ehRVrEBGCRGZmzukmaSZn61EXF/pRszUNjc8sVSdM9+Wuq1WA6f+vqP+J5e8oCx+Y/1P/QPzfKN7rQTfUSSfzB8HnjsxZEl5uf2i/Zjj9x6vNqIK5h3/7+rSHv3MJOnz6BXLJ7gw=</OrderData>\n    </DataTransfer>\n  </body>\n</ebicsUnsecuredRequest>\n")!!
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
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<SignaturePubKeyOrderData xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://www.ebics.org/S001 http://www.ebics.org/S001/ebics_signature.xsd\" xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\" xmlns=\"http://www.ebics.org/S001\">\n  <SignaturePubKeyInfo>\n    <PubKeyValue>\n      <ds:RSAKeyValue>\n        <ds:Modulus>s5ktpg3xGjbZZgVTYtW+0e6xsWg142UwvoM3mfuM+qrkIa5bPUGQLH6BRL9IejYosPhoA6jwMBSxO8LfaageyZJt2M5wHklJYz3fADtQrV1bk5R92OaY/9ZZdHxw3xY93tm5JfVrMDW9DEK5B1hUzYFdjuN/qu2/sdE9mwhx2YjYwwdSQzv6MhbtSK9OAJjPGo3fkxsht6maSmRCdgxplIOSO2mmP1wjUzbVUMcrRk9KDMvnb3kCxiTm+evvxX6J4wpY1bAWukolJbaALHlFtgTo1LnulUe/BxiKx9HpmuEAaPsk8kgduXsz5OqH2g/Vyw75x51aKVPxOTBPyP+4kQ==</ds:Modulus>\n        <ds:Exponent>AQAB</ds:Exponent>\n      </ds:RSAKeyValue>\n    </PubKeyValue>\n    <SignatureVersion>A006</SignatureVersion>\n  </SignaturePubKeyInfo>\n  <PartnerID>flo-kid</PartnerID>\n  <UserID>flo-uid</UserID>\n</SignaturePubKeyOrderData>"
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
        val text = classLoader.getResource("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<ebicsUnsecuredRequest Revision=\"1\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n                       xsi:schemaLocation=\"urn:org:ebics:H004 ebics_keymgmt_request_H004.xsd\" Version=\"H004\"\n                       xmlns=\"urn:org:ebics:H004\">\n    <header authenticate=\"true\">\n        <static>\n            <HostID>myhost</HostID>\n            <PartnerID>k1</PartnerID>\n            <UserID>u1</UserID>\n            <OrderDetails>\n                <OrderType>INI</OrderType>\n                <OrderAttribute>DZNNN</OrderAttribute>\n            </OrderDetails>\n            <SecurityMedium>0000</SecurityMedium>\n        </static>\n        <mutable/>\n    </header>\n    <body>\n        <DataTransfer>\n            <OrderData>\n                eJx9U1tzmkAUfu9M/wNDH524XIINjprBaOIFCopg8CWzyHK/GHaR1V9fhtg0bWrf9nzfdy57LoN7mqXMEZU4KvIhy3c5lkH5vvCiPBiyFfFv7tj70dcvAzMKckiqEhmVu0QnvfRQOYEEMo1/jvsUR0M2JOTQB6Cu624tdosyAALH8eBZU819iDJ4E+WYwHyPWKbR93ELqsUekjb5B3fkRnvcRjCbCMxVBrTmC/5VXJdij72U5OErFXGAk0Gj8Rq3bxf1f7KzzfcZ5u8GzHO/aImGekNsmFboAjWgh/trU/mEvzFa4VVphUdYSsghEOlT7O52gb1xyLbDoR7F24C/Faz6WGhi5lda57VM5lByDetppc5647Uqz1HsFNgIC6UX19rYpPqd6kMYoNNuQQRNqmdJunDOoq9MyKq0eTeR1rKgQwfIu503o7VIHVkkmbTw7VKbbOXJdCmN+dA6O49eXP0Ar5UAsDeVszqkghM7de2Zq/Oxp4UuMZeyrixi46kQ/YTikPQyaGbrBy+gh3Sum7qQZQZfx9bZtS1tX64TeTnRjrkrJg802mQddDzS597itj44vKtsq6RIFy5U1Fn6SIJNwat5lVoIjGm0pPLskFVTBRo4uUsCr3rGZ0l/nQkBsE/1d4lKPFzaBtU3Y+NkdG6T1XA4AB+a/Gfrp/RQ5CgnI2WljFvdO/I+O/DP4Q3A50H/Xgv77YZGCsf1BuAT3O4QuLZEAwOWJEflfDJK+CbPu9WSFm7fVcNcns1BgmsXOfoJ1l5CIg==\n            </OrderData>\n        </DataTransfer>\n    </body>\n</ebicsUnsecuredRequest>")!!.readText()
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
        val text = classLoader.getResource("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ebics:ebicsNoPubKeyDigestsRequest xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\" xmlns:ebics=\"urn:org:ebics:H004\" xmlns=\"http://www.w3.org/2001/XMLSchema\" Version=\"H004\" Revision=\"1\">\n  <ebics:header authenticate=\"true\">\n    <ebics:static>\n      <ebics:HostID>EBIXQUAL</ebics:HostID>\n      <ebics:Nonce>0749134D19E160DA4ACA366180113D44</ebics:Nonce>\n      <ebics:Timestamp>2018-11-01T11:10:35Z</ebics:Timestamp>\n      <ebics:PartnerID>EXCHANGE</ebics:PartnerID>\n      <ebics:UserID>TALER</ebics:UserID>\n      <ebics:OrderDetails>\n        <ebics:OrderType>HPB</ebics:OrderType>\n        <ebics:OrderAttribute>DZHNN</ebics:OrderAttribute>\n      </ebics:OrderDetails>\n      <ebics:SecurityMedium>0000</ebics:SecurityMedium>\n    </ebics:static>\n    <ebics:mutable/>\n  </ebics:header>\n  <ebics:AuthSignature>\n    <ds:SignedInfo>\n      <ds:CanonicalizationMethod Algorithm=\"http://www.w3.org/TR/2001/REC-xml-c14n-20010315\"/>\n      <ds:SignatureMethod Algorithm=\"http://www.w3.org/2001/04/xmldsig-more#rsa-sha256\"/>\n      <ds:Reference URI=\"#xpointer(//*[@authenticate='true'])\">\n        <ds:Transforms>\n          <ds:Transform Algorithm=\"http://www.w3.org/TR/2001/REC-xml-c14n-20010315\"/>\n        </ds:Transforms>\n        <ds:DigestMethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#sha256\"/>\n        <ds:DigestValue>yuwpcvmVrNrc1t58aulF6TiqKO+CNe7Dhwaa6V/cKws=</ds:DigestValue>\n      </ds:Reference>\n    </ds:SignedInfo>\n    <ds:SignatureValue>chhF4/yxSz2WltTR0/DPf01WyncNx3P1XJDr0SylVMVWWSY9S1dYyJKgGOW+g7C/\nHYzrGcFwKrejf79DH3F2Ek8NJLsAFzf/0oxff2eYEe0SlxjXmgsubeMOy5PKB9Ag\nZiQYMiNy9gaatqcW79E3n/r1nD/lwLsped/4jzWdY+Gfj3z6d18vymmGymbHqIaR\nhawk/Iu/tpMQ3dbvIFbzn9LLMmzfQzG2ZPy3BiQNVWr3aSLl9qG4U9zeK6OyH2/Z\ng1EEnjfJa/+pTCeJmyoDBwgaJWcuCRQmWjvxvbM4ckYnrFkhvLf24on2ydmUeipp\nsMl8q1khyWUC0P0h6otZhD1SUdf1rt14r16bdy1r0ieTVm6m3qXhcX5MXagvFci0\n0OE2mOgf/GXE2WiJLAbRh06s9OvAzHUq4QGQwbkprMBMFw4uxONyNzYl+F9aA5Ic\nAwebf7/dfDJIHZc8XkwY1jNJrmCTzRyTP5eDN4bDPoHqTotDo1CMFaHtnkygg/Lg\nqoirV8mHfqnO4XQBOCUiHZ6mzz81l+O7dYg65cYx9Z76q2cv1PxsMb7Eo4nvux5S\nQPuuid0G5lomHXM/uM3mu4vXcDluCoffgTDimxs0I9X+PB7a2vgSMezwYkj8dA69\nvszH1hwek7DRbRfKUo6HUxl49Gsk0XYG/K30M5fS5JE=</ds:SignatureValue>\n  </ebics:AuthSignature>\n  <ebics:body/>\n</ebics:ebicsNoPubKeyDigestsRequest>\n")!!.readText()
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