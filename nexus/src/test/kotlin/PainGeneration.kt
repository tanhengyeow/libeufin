package tech.libeufin.nexus

import org.jetbrains.exposed.dao.EntityID
import org.junit.Before
import org.junit.Test

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SchemaUtils
import org.joda.time.DateTime
import tech.libeufin.util.Amount
import javax.sql.rowset.serial.SerialBlob



class PainTest {

    @Before
    fun prepare() {
        Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction {
            SchemaUtils.create(EbicsSubscribersTable)
            SchemaUtils.create(EbicsAccountsInfoTable)
            SchemaUtils.create(Pain001Table)

            val subscriberEntity = EbicsSubscriberEntity.new(id = "123asdf") {
                ebicsURL = "ebics url"
                hostID = "host"
                partnerID = "partner"
                userID = "user"
                systemID = "system"
                signaturePrivateKey = SerialBlob("signturePrivateKey".toByteArray())
                authenticationPrivateKey = SerialBlob("authenticationPrivateKey".toByteArray())
                encryptionPrivateKey = SerialBlob("encryptionPrivateKey".toByteArray())
            }
            EbicsAccountInfoEntity.new(id = "acctid") {
                subscriber = subscriberEntity
                accountHolder = "Account Holder"
                iban = "IBAN"
                bankCode = "BIC"
            }
        }
    }

    @Test
    fun testPain001document() {
        transaction {
            val pain001Entity = Pain001Entity.new {
                sum = Amount(1)
                debtorAccount = "acctid"
                subject = "subject line"
                creditorIban = "CREDIT IBAN"
                creditorBic = "CREDIT BIC"
                creditorName = "CREDIT NAME"
            }
            val s = createPain001document(pain001Entity)
            println(s)
        }
    }
}