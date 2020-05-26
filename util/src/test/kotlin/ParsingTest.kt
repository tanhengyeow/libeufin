import org.junit.Test
import tech.libeufin.util.XMLUtil
import tech.libeufin.util.parseCamt

class ParsingTest {
    @Test
    fun camtParsing() {
        val camt53 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02 camt.053.001.02.xsd">
              <BkToCstmrStmt>
                <GrpHdr>
                  <MsgId>053D2020-03-13T21:29:09.0N200000005</MsgId>
                  <CreDtTm>2020-03-13T21:29:00.0+01:00</CreDtTm>
                  <MsgPgntn>
                    <PgNb>001</PgNb>
                    <LastPgInd>true</LastPgInd>
                  </MsgPgntn>
                </GrpHdr>
                <Stmt>
                  <Id>1234567890ABC</Id>
                  <ElctrncSeqNb>123</ElctrncSeqNb>
                  <LglSeqNb>123</LglSeqNb>
                  <CreDtTm>2020-03-13T21:29:00.0+01:00</CreDtTm>
                  <Acct>
                    <Id>
                      <IBAN>REAL-ACCOUNT-OWNER-IBAN-HERE</IBAN>
                    </Id>
                    <Ccy>EUR</Ccy>
                    <Ownr>
                      <Nm>Owner Name</Nm>
                    </Ownr>
                    <Svcr>
                      <FinInstnId>
                        <BIC>OWNER-BIC</BIC>
                        <Nm>GLS Gemeinschaftsbank eG</Nm>
                        <Othr>
                          <Id>DE 124090847</Id>
                          <Issr>UmsStId</Issr>
                        </Othr>
                      </FinInstnId>
                    </Svcr>
                  </Acct>
                  <Bal>
                    <Tp>
                      <CdOrPrtry>
                        <Cd>PRCD</Cd>
                      </CdOrPrtry>
                    </Tp>
                    <Amt Ccy="EUR">REAL-BALANCE-BEFORE-PAYMENT</Amt>
                    <CdtDbtInd>CRDT</CdtDbtInd>
                    <Dt>
                      <Dt>2020-03-13</Dt>
                    </Dt>
                  </Bal>
                  <Bal>
                    <Tp>
                      <CdOrPrtry>
                        <Cd>CLBD</Cd>
                      </CdOrPrtry>
                    </Tp>
                    <Amt Ccy="EUR">REAL-BALANCE-AFTER-PAYMENT</Amt>
                    <CdtDbtInd>CRDT</CdtDbtInd>
                    <Dt>
                      <Dt>2020-03-13</Dt>
                    </Dt>
                  </Bal>
                  <Ntry>
                    <Amt Ccy="EUR">1.00</Amt>
                    <CdtDbtInd>DBIT</CdtDbtInd>
                    <Sts>BOOK</Sts>
                    <BookgDt>
                      <Dt>2020-03-13</Dt>
                    </BookgDt>
                    <ValDt>
                      <Dt>2020-03-13</Dt>
                    </ValDt>
                    <AcctSvcrRef>1234567890</AcctSvcrRef>
                    <BkTxCd>
                      <Domn>
                        <Cd>PMNT</Cd>
                        <Fmly>
                          <Cd>ICDT</Cd>
                          <SubFmlyCd>ESCT</SubFmlyCd>
                        </Fmly>
                      </Domn>
                      <Prtry>
                        <!-- Code -->
                        <Cd>XYZ+123+12345</Cd>
                        <Issr>DK</Issr>
                      </Prtry>
                    </BkTxCd>
                    <NtryDtls>
                      <TxDtls>
                        <Refs>
                          <MsgId>1</MsgId>
                          <PmtInfId>1</PmtInfId>
                          <EndToEndId>NOTPROVIDED</EndToEndId>
                        </Refs>
                        <AmtDtls>
                          <TxAmt>
                            <Amt Ccy="EUR">1.00</Amt>
                          </TxAmt>
                        </AmtDtls>
                        <BkTxCd>
                          <Domn>
                            <Cd>PMNT</Cd>
                            <Fmly>
                              <Cd>ICDT</Cd>
                              <SubFmlyCd>ESCT</SubFmlyCd>
                            </Fmly>
                          </Domn>
                          <Prtry>
                            <Cd>XYZ+123+12345</Cd>
                            <Issr>DK</Issr>
                          </Prtry>
                        </BkTxCd>
                        <RltdPties>
                          <Dbtr>
                            <Nm>Debitor Name</Nm>
                          </Dbtr>
                          <DbtrAcct>
                            <Id>
                              <IBAN>REAL-DEBITOR-IBAN-HERE</IBAN>
                            </Id>
                          </DbtrAcct>
                          <Cdtr>
                            <Nm>Creditor Name</Nm>
                          </Cdtr>
                          <CdtrAcct>
                            <Id>
                              <IBAN>REAL-CREDITOR-IBAN-HERE</IBAN>
                            </Id>
                          </CdtrAcct>
                        </RltdPties>
                        <RltdAgts>
                          <CdtrAgt>
                            <FinInstnId>
                              <BIC>CREDITOR-BIC</BIC>
                            </FinInstnId>
                          </CdtrAgt>
                        </RltdAgts>
                        <RmtInf>
                          <Ustrd>personal payment march</Ustrd>
                        </RmtInf>
                      </TxDtls>
                    </NtryDtls>
                    <AddtlNtryInf>Überweisungsauftrag</AddtlNtryInf>
                  </Ntry>
                </Stmt>
              </BkToCstmrStmt>
            </Document>
        """.trimIndent()
        val doc = XMLUtil.parseStringIntoDom(camt53)
        parseCamt(doc)
    }
}