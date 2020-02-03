import org.junit.Test
import tech.libeufin.util.EbicsOrderUtil
import tech.libeufin.util.XMLUtil
import tech.libeufin.util.ebics_h004.HTDResponseOrderData
import kotlin.test.assertEquals


class EbicsOrderUtilTest {

    @Test
    fun testComputeOrderIDFromNumber() {
        assertEquals("OR01", EbicsOrderUtil.computeOrderIDFromNumber(1))
        assertEquals("OR0A", EbicsOrderUtil.computeOrderIDFromNumber(10))
        assertEquals("OR10", EbicsOrderUtil.computeOrderIDFromNumber(36))
        assertEquals("OR11", EbicsOrderUtil.computeOrderIDFromNumber(37))
    }

    @Test
    fun testDecodeOrderData() {
        val orderDataXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <HTDResponseOrderData xmlns="urn:org:ebics:H004" xmlns:ds="http://www.w3.org/2000/09/xmldsig#" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="urn:org:ebics:H004 ebics_orders_H004.xsd">
                <PartnerInfo>
                    <AddressInfo>
                        <Name>Mr Anybody</Name>
                        <Street>CENSORED</Street>
                        <PostCode>12345</PostCode>
                        <City>Footown</City>
                    </AddressInfo>
                    <BankInfo>
                        <HostID>BLABLUBLA</HostID>
                    </BankInfo>
                    <AccountInfo ID="accid000000001" Currency="EUR">
                        <AccountNumber international="false">12345667</AccountNumber>
                        <AccountNumber international="true">DE54430609999999999999</AccountNumber>
                        <BankCode international="false">43060967</BankCode>
                        <BankCode international="true">GENODEM1GLS</BankCode>
                        <AccountHolder>Mr Anybody</AccountHolder>
                    </AccountInfo>
                    <OrderInfo>
                        <OrderType>C52</OrderType>
                        <TransferType>Download</TransferType>
                        <OrderFormat>CAMT052</OrderFormat>
                        <Description>Abholen Vormerkposten</Description>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>C53</OrderType>
                        <TransferType>Download</TransferType>
                        <OrderFormat>CAMT053</OrderFormat>
                        <Description>Abholen Kontoauszuege</Description>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>C54</OrderType>
                        <TransferType>Download</TransferType>
                        <OrderFormat>CAMT054</OrderFormat>
                        <Description>Abholen Nachricht Sammelbuchungsdatei, Soll-, Haben-Avis</Description>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>CDZ</OrderType>
                        <TransferType>Download</TransferType>
                        <OrderFormat>XMLBIN</OrderFormat>
                        <Description>Abholen Payment Status Report for Direct Debit</Description>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>CRZ</OrderType>
                        <TransferType>Download</TransferType>
                        <OrderFormat>XMLBIN</OrderFormat>
                        <Description>Abholen Payment Status Report for Credit Transfer</Description>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>HAA</OrderType>
                        <TransferType>Download</TransferType>
                        <OrderFormat>MISC</OrderFormat>
                        <Description>Abrufbare Auftragsarten abholen</Description>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>HAC</OrderType>
                        <TransferType>Download</TransferType>
                        <OrderFormat>HAC</OrderFormat>
                        <Description>Kundenprotokoll (XML-Format) abholen</Description>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>HKD</OrderType>
                        <TransferType>Download</TransferType>
                        <OrderFormat>MISC</OrderFormat>
                        <Description>Kunden- und Teilnehmerdaten abholen</Description>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>HPB</OrderType>
                        <TransferType>Download</TransferType>
                        <OrderFormat>MISC</OrderFormat>
                        <Description>Public Keys der Bank abholen</Description>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>HPD</OrderType>
                        <TransferType>Download</TransferType>
                        <OrderFormat>MISC</OrderFormat>
                        <Description>Bankparameter abholen</Description>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>HTD</OrderType>
                        <TransferType>Download</TransferType>
                        <OrderFormat>MISC</OrderFormat>
                        <Description>Kunden- und Teilnehmerdaten abholen</Description>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>HVD</OrderType>
                        <TransferType>Download</TransferType>
                        <OrderFormat>MISC</OrderFormat>
                        <Description>VEU-Status abrufen</Description>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>HVT</OrderType>
                        <TransferType>Download</TransferType>
                        <OrderFormat>MISC</OrderFormat>
                        <Description>VEU-Transaktionsdetails abrufen</Description>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>HVU</OrderType>
                        <TransferType>Download</TransferType>
                        <OrderFormat>MISC</OrderFormat>
                        <Description>VEU-Uebersicht abholen</Description>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>HVZ</OrderType>
                        <TransferType>Download</TransferType>
                        <OrderFormat>MISC</OrderFormat>
                        <Description>VEU-Uebersicht mit Zusatzinformationen abholen</Description>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>PTK</OrderType>
                        <TransferType>Download</TransferType>
                        <OrderFormat>PTK</OrderFormat>
                        <Description>Protokolldatei abholen</Description>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>STA</OrderType>
                        <TransferType>Download</TransferType>
                        <OrderFormat>MT940</OrderFormat>
                        <Description>Swift-Tagesauszuege abholen</Description>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>VMK</OrderType>
                        <TransferType>Download</TransferType>
                        <OrderFormat>MT942</OrderFormat>
                        <Description>Abholen kurzfristige Vormerkposten</Description>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>AZV</OrderType>
                        <TransferType>Upload</TransferType>
                        <OrderFormat>DTAZVJS</OrderFormat>
                        <Description>AZV im Diskettenformat senden</Description>
                        <NumSigRequired>0</NumSigRequired>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>C1C</OrderType>
                        <TransferType>Upload</TransferType>
                        <OrderFormat>P8CCOR1</OrderFormat>
                        <Description>Einreichen von Lastschriften D-1-Option in einem Container</Description>
                        <NumSigRequired>0</NumSigRequired>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>C2C</OrderType>
                        <TransferType>Upload</TransferType>
                        <OrderFormat>PN8CONCS</OrderFormat>
                        <Description>Einreichen von Firmenlastschriften in einem Container</Description>
                        <NumSigRequired>0</NumSigRequired>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>CCC</OrderType>
                        <TransferType>Upload</TransferType>
                        <OrderFormat>PN1CONCS</OrderFormat>
                        <Description>Ueberweisungen im SEPA-Container</Description>
                        <NumSigRequired>0</NumSigRequired>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>CCT</OrderType>
                        <TransferType>Upload</TransferType>
                        <OrderFormat>PN1GOCS</OrderFormat>
                        <Description>Überweisungen im ZKA-Format</Description>
                        <NumSigRequired>0</NumSigRequired>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>CCU</OrderType>
                        <TransferType>Upload</TransferType>
                        <OrderFormat>P1URGCS</OrderFormat>
                        <Description>Einreichen von Eilueberweisungen</Description>
                        <NumSigRequired>0</NumSigRequired>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>CDB</OrderType>
                        <TransferType>Upload</TransferType>
                        <OrderFormat>PAIN8CS</OrderFormat>
                        <Description>Einreichen von Firmenlastschriften</Description>
                        <NumSigRequired>0</NumSigRequired>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>CDC</OrderType>
                        <TransferType>Upload</TransferType>
                        <OrderFormat>PN8CONCS</OrderFormat>
                        <Description>Einreichen von Lastschriften in einem Container</Description>
                        <NumSigRequired>0</NumSigRequired>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>CDD</OrderType>
                        <TransferType>Upload</TransferType>
                        <OrderFormat>PN8GOCS</OrderFormat>
                        <Description>Einreichen von Lastschriften</Description>
                        <NumSigRequired>0</NumSigRequired>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>HCA</OrderType>
                        <TransferType>Upload</TransferType>
                        <OrderFormat>MISC</OrderFormat>
                        <Description>Public Key senden</Description>
                        <NumSigRequired>0</NumSigRequired>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>HCS</OrderType>
                        <TransferType>Upload</TransferType>
                        <OrderFormat>MISC</OrderFormat>
                        <Description>Teilnehmerschluessel EU und EBICS aendern</Description>
                        <NumSigRequired>0</NumSigRequired>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>HIA</OrderType>
                        <TransferType>Upload</TransferType>
                        <OrderFormat>MISC</OrderFormat>
                        <Description>Initiales Senden Public Keys</Description>
                        <NumSigRequired>0</NumSigRequired>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>HVE</OrderType>
                        <TransferType>Upload</TransferType>
                        <OrderFormat>MISC</OrderFormat>
                        <Description>VEU-Unterschrift hinzufuegen</Description>
                        <NumSigRequired>0</NumSigRequired>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>HVS</OrderType>
                        <TransferType>Upload</TransferType>
                        <OrderFormat>MISC</OrderFormat>
                        <Description>VEU-Storno</Description>
                        <NumSigRequired>0</NumSigRequired>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>INI</OrderType>
                        <TransferType>Upload</TransferType>
                        <OrderFormat>MISC</OrderFormat>
                        <Description>Passwort-Initialisierung</Description>
                        <NumSigRequired>0</NumSigRequired>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>PUB</OrderType>
                        <TransferType>Upload</TransferType>
                        <OrderFormat>MISC</OrderFormat>
                        <Description>Public-Key senden</Description>
                        <NumSigRequired>0</NumSigRequired>
                    </OrderInfo>
                    <OrderInfo>
                        <OrderType>SPR</OrderType>
                        <TransferType>Upload</TransferType>
                        <OrderFormat>MISC</OrderFormat>
                        <Description>Sperrung der Zugangsberechtigung</Description>
                        <NumSigRequired>0</NumSigRequired>
                    </OrderInfo>
                </PartnerInfo>
                <UserInfo>
                    <UserID Status="1">ANYBOMR</UserID>
                    <Name>Mr Anybody</Name>
                    <Permission>
                        <OrderTypes>C52 C53 C54 CDZ CRZ HAA HAC HKD HPB HPD HTD HVD HVT HVU HVZ PTK</OrderTypes>
                    </Permission>
                    <Permission>
                        <OrderTypes></OrderTypes>
                        <AccountID>accid000000001</AccountID>
                    </Permission>
                    <Permission AuthorisationLevel="E">
                        <OrderTypes>AZV CCC CCT CCU</OrderTypes>
                    </Permission>
                    <Permission AuthorisationLevel="T">
                        <OrderTypes>HCA HCS HIA HVE HVS INI PUB SPR</OrderTypes>
                    </Permission>
                </UserInfo>
            </HTDResponseOrderData>
        """.trimIndent()
        XMLUtil.convertStringToJaxb<HTDResponseOrderData>(orderDataXml);
    }
}