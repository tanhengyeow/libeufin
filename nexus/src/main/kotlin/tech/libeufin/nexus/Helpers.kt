package tech.libeufin.nexus

import io.ktor.application.ApplicationCall
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import tech.libeufin.util.Amount
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.EbicsClientSubscriberDetails
import tech.libeufin.util.base64ToBytes
import javax.sql.rowset.serial.SerialBlob
import java.util.Random
import tech.libeufin.util.ebics_h004.EbicsTypes
import java.security.interfaces.RSAPublicKey
import tech.libeufin.util.*
import java.time.format.DateTimeFormatter
import java.time.ZonedDateTime
import java.time.Instant
import java.time.ZoneId

fun getSubscriberEntityFromNexusUserId(nexusUserId: String?): EbicsSubscriberEntity {
    return transaction {
        val nexusUser = extractNexusUser(expectId(nexusUserId))
        getEbicsSubscriberFromUser(nexusUser)
    }
}

fun calculateRefund(amount: String): Amount {
    // fixme: must apply refund fees!
    return Amount(amount)
}

/**
 * Skip national only-numeric bank account ids, and return the first IBAN in list
 */
fun extractFirstIban(bankAccounts: List<EbicsTypes.AbstractAccountNumber>?): String? {
    if (bankAccounts == null)
        return null

    for (item in bankAccounts) {
        if (item is EbicsTypes.GeneralAccountNumber) {
            if (item.international)
                return item.value
        }
    }
    return null
}

/**
 * Skip national only-numeric codes, and returns the first BIC in list
 */
fun extractFirstBic(bankCodes: List<EbicsTypes.AbstractBankCode>?): String? {
    if (bankCodes == null)
        return null

    for (item in bankCodes) {
        if (item is EbicsTypes.GeneralBankCode) {
            if (item.international)
                return item.value
        }
    }
    return null
}

/**
 * Get EBICS subscriber details from bank account id.
 * bank account id => ... => ebics details
 */
fun getSubscriberDetailsFromBankAccount(bankAccountId: String): EbicsClientSubscriberDetails {
    return transaction {
        val map = EbicsToBankAccountEntity.find {
            EbicsToBankAccountsTable.bankAccount eq bankAccountId
        }.firstOrNull() ?: throw NexusError(
            HttpStatusCode.NotFound,
            "Such bank account '$bankAccountId' has no EBICS subscriber associated"
        )
        getSubscriberDetailsInternal(map.ebicsSubscriber)
    }
}

/**
 * Given a nexus user id, returns the _list_ of bank accounts associated to it.
 *
 * @param id the subscriber id
 * @return the bank account associated with this user.  Can/should be adapted to
 * return multiple bank accounts.
 */
fun getBankAccountFromNexusUserId(id: String): BankAccountEntity {
    logger.debug("Looking up bank account of user '$id'")
    val map = transaction {
        UserToBankAccountEntity.find {
            UserToBankAccountsTable.nexusUser eq id
        }
    }.firstOrNull() ?: throw NexusError(
        HttpStatusCode.NotFound,
        "Such user '$id' does not have any bank account associated"
    )
    return map.bankAccount
}

fun getSubscriberDetailsInternal(subscriber: EbicsSubscriberEntity): EbicsClientSubscriberDetails {
    var bankAuthPubValue: RSAPublicKey? = null
    if (subscriber.bankAuthenticationPublicKey != null) {
        bankAuthPubValue = CryptoUtil.loadRsaPublicKey(
            subscriber.bankAuthenticationPublicKey?.toByteArray()!!
        )
    }
    var bankEncPubValue: RSAPublicKey? = null
    if (subscriber.bankEncryptionPublicKey != null) {
        bankEncPubValue = CryptoUtil.loadRsaPublicKey(
            subscriber.bankEncryptionPublicKey?.toByteArray()!!
        )
    }
    return EbicsClientSubscriberDetails(
        bankAuthPub = bankAuthPubValue,
        bankEncPub = bankEncPubValue,

        ebicsUrl = subscriber.ebicsURL,
        hostId = subscriber.hostID,
        userId = subscriber.userID,
        partnerId = subscriber.partnerID,

        customerSignPriv = CryptoUtil.loadRsaPrivateKey(subscriber.signaturePrivateKey.toByteArray()),
        customerAuthPriv = CryptoUtil.loadRsaPrivateKey(subscriber.authenticationPrivateKey.toByteArray()),
        customerEncPriv = CryptoUtil.loadRsaPrivateKey(subscriber.encryptionPrivateKey.toByteArray())
    )
}

/** Return non null Ebics subscriber, or throw error otherwise. */
fun getEbicsSubscriberFromUser(nexusUser: NexusUserEntity): EbicsSubscriberEntity {
    return nexusUser.ebicsSubscriber ?: throw NexusError(
        HttpStatusCode.NotFound,
        "Ebics subscriber was never activated"
    )
}

fun getSubscriberDetailsFromNexusUserId(id: String): EbicsClientSubscriberDetails {
    return transaction {
        val nexusUser = extractNexusUser(id)
        getSubscriberDetailsInternal(nexusUser.ebicsSubscriber ?: throw NexusError(
            HttpStatusCode.NotFound,
            "Cannot get details for non-activated subscriber!"
        ))
    }
}

/**
 * Create a PAIN.001 XML document according to the input data.
 * Needs to be called within a transaction block.
 */
fun createPain001document(pain001Entity: Pain001Entity): String {
    /**
     * Every PAIN.001 document contains at least three IDs:
     *
     * 1) MsgId: a unique id for the message itself
     * 2) PmtInfId: the unique id for the payment's set of information
     * 3) EndToEndId: a unique id to be shared between the debtor and
     *    creditor that uniquely identifies the transaction
     *
     * For now and for simplicity, since every PAIN entry in the database
     * has a unique ID, and the three values aren't required to be mutually different,
     * we'll assign the SAME id (= the row id) to all the three aforementioned
     * PAIN id types.
     */
    val debitorBankAccountLabel = transaction {
        val debitorBankAcount = BankAccountEntity.find {
            BankAccountsTable.iban eq pain001Entity.debitorIban and
                    (BankAccountsTable.bankCode eq pain001Entity.debitorBic)
        }.firstOrNull() ?: throw NexusError(
            HttpStatusCode.NotFound,
            "Please download bank accounts details first (HTD)"
        )
        debitorBankAcount.id.value
    }

    val s = constructXml(indent = true) {
        root("Document") {
            attribute("xmlns", "urn:iso:std:iso:20022:tech:xsd:pain.001.001.03")
            attribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
            attribute("xsi:schemaLocation", "urn:iso:std:iso:20022:tech:xsd:pain.001.001.03 pain.001.001.03.xsd")
            element("CstmrCdtTrfInitn") {
                element("GrpHdr") {
                    element("MsgId") {
                        text(pain001Entity.id.value.toString())
                    }
                    element("CreDtTm") {
                        val dateMillis = transaction {
                            pain001Entity.date
                        }
                        val dateFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
                        val instant = Instant.ofEpochSecond(dateMillis / 1000)
                        val zoned = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault())
                        text(dateFormatter.format(zoned))
                    }
                    element("NbOfTxs") {
                        text("1")
                    }
                    element("CtrlSum") {
                        text(pain001Entity.sum.toString())
                    }
                    element("InitgPty/Nm") {
                        text(debitorBankAccountLabel)
                    }
                }
                element("PmtInf") {
                    element("PmtInfId") {
                        text(pain001Entity.id.value.toString())
                    }
                    element("PmtMtd") {
                        text("TRF")
                    }
                    element("BtchBookg") {
                        text("true")
                    }
                    element("NbOfTxs") {
                        text("1")
                    }
                    element("CtrlSum") {
                        text(pain001Entity.sum.toString())
                    }
                    element("PmtTpInf/SvcLvl/Cd") {
                        text("SEPA")
                    }
                    element("ReqdExctnDt") {
                        val dateMillis = transaction {
                            pain001Entity.date
                        }
                        text(DateTime(dateMillis).toString("Y-MM-dd"))
                    }
                    element("Dbtr/Nm") {
                        text(debitorBankAccountLabel)
                    }
                    element("DbtrAcct/Id/IBAN") {
                        text(pain001Entity.debitorIban)
                    }
                    element("DbtrAgt/FinInstnId/BIC") {
                        text(pain001Entity.debitorBic)
                    }
                    element("ChrgBr") {
                        text("SLEV")
                    }
                    element("CdtTrfTxInf") {
                        element("PmtId") {
                            element("EndToEndId") {
                                // text(pain001Entity.id.value.toString())
                                text("NOTPROVIDED")
                            }
                        }
                        element("Amt/InstdAmt") {
                            attribute("Ccy", pain001Entity.currency)
                            text(pain001Entity.sum.toString())
                        }
                        element("CdtrAgt/FinInstnId/BIC") {
                            text(pain001Entity.creditorBic)
                        }
                        element("Cdtr/Nm") {
                            text(pain001Entity.creditorName)
                        }
                        element("CdtrAcct/Id/IBAN") {
                            text(pain001Entity.creditorIban)
                        }
                        element("RmtInf/Ustrd") {
                            text(pain001Entity.subject)
                        }
                    }
                }
            }
        }
    }
    return s
}

/**
 * Insert one row in the database, and leaves it marked as non-submitted.
 * @param debtorAccountId the mnemonic id assigned by the bank to one bank
 * account of the subscriber that is creating the pain entity.  In this case,
 * it will be the account whose money will pay the wire transfer being defined
 * by this pain document.
 */
fun createPain001entity(entry: Pain001Data, nexusUser: NexusUserEntity): Pain001Entity {
    val randomId = Random().nextLong()
    return transaction {
        Pain001Entity.new {
            subject = entry.subject
            sum = entry.sum
            debitorIban = entry.debitorIban
            debitorBic = entry.debitorBic
            debitorName = entry.debitorName
            creditorName = entry.creditorName
            creditorBic = entry.creditorBic
            creditorIban = entry.creditorIban
            date = DateTime.now().millis
            paymentId = randomId
            msgId = randomId
            endToEndId = randomId
            this.nexusUser = nexusUser
        }
    }
}

/**
 * Inserts spaces every 2 characters, and a newline after 8 pairs.
 */
fun chunkString(input: String): String {
    val ret = StringBuilder()
    var columns = 0
    for (i in input.indices) {
        if ((i + 1).rem(2) == 0) {
            if (columns == 15) {
                ret.append(input[i] + "\n")
                columns = 0
                continue
            }
            ret.append(input[i] + " ")
            columns++
            continue
        }
        ret.append(input[i])
    }
    return ret.toString().toUpperCase()
}

fun expectId(param: String?): String {
    return param ?: throw NexusError(HttpStatusCode.BadRequest, "Bad ID given")
}

/* Needs a transaction{} block to be called */
fun extractNexusUser(param: String?): NexusUserEntity {
    if (param == null) {
        throw NexusError(HttpStatusCode.BadRequest, "Null Id given")
    }
    return transaction{
        NexusUserEntity.findById(param) ?: throw NexusError(
            HttpStatusCode.NotFound,
            "Subscriber: $param not found"
        )
    }
}

fun ApplicationCall.expectUrlParameter(name: String): String {
    return this.request.queryParameters[name]
        ?: throw NexusError(HttpStatusCode.BadRequest, "Parameter '$name' not provided in URI")
}

fun expectInt(param: String): Int {
    return try {
        param.toInt()
    } catch (e: Exception) {
        throw NexusError(HttpStatusCode.BadRequest,"'$param' is not Int")
    }
}

fun expectLong(param: String): Long {
    return try {
        param.toLong()
    } catch (e: Exception) {
        throw NexusError(HttpStatusCode.BadRequest,"'$param' is not Long")
    }
}

fun expectLong(param: String?): Long? {
    if (param != null) {
        return expectLong(param)
    }
    return null
}

/* Needs a transaction{} block to be called */
fun expectAcctidTransaction(param: String?): BankAccountEntity {
    if (param == null) {
        throw NexusError(HttpStatusCode.BadRequest, "Null Acctid given")
    }
    return BankAccountEntity.findById(param) ?: throw NexusError(HttpStatusCode.NotFound, "Account: $param not found")
}

/**
 * This helper function parses a Authorization:-header line, decode the credentials
 * and returns a pair made of username and hashed (sha256) password.  The hashed value
 * will then be compared with the one kept into the database.
 */
fun extractUserAndHashedPassword(authorizationHeader: String): Pair<String, ByteArray> {
    val (username, password) = try {
        val split = authorizationHeader.split(" ")
        val valueUtf8 = String(base64ToBytes(split[1]), Charsets.UTF_8) // newline introduced here: BUG!
        valueUtf8.split(":")
    } catch (e: java.lang.Exception) {
        throw NexusError(
            HttpStatusCode.BadRequest, "invalid Authorization:-header received"
        )
    }
    return Pair(username, CryptoUtil.hashStringSHA256(password))
}

/**
 * Test HTTP basic auth.  Throws error if password is wrong
 *
 * @param authorization the Authorization:-header line.
 * @return subscriber id
 */
fun authenticateRequest(authorization: String?): String {
    val headerLine = if (authorization == null) throw NexusError(
        HttpStatusCode.BadRequest, "Authentication:-header line not found"
    ) else authorization
    val subscriber = transaction {
        val (user, pass) = extractUserAndHashedPassword(headerLine)
        NexusUserEntity.find {
            NexusUsersTable.id eq user and (NexusUsersTable.password eq SerialBlob(pass))
        }.firstOrNull()
    } ?: throw NexusError(HttpStatusCode.Forbidden, "Wrong password")
    return subscriber.id.value
}

/**
 * Check if the subscriber has the right to use the (claimed) bank account.
 * @param subscriber id of the EBICS subscriber to check
 * @param bankAccount id of the claimed bank account
 * @return true if the subscriber can use the bank account.
 */
fun subscriberHasRights(subscriber: EbicsSubscriberEntity, bankAccount: BankAccountEntity): Boolean {
    val row = transaction {
        EbicsToBankAccountEntity.find {
            EbicsToBankAccountsTable.bankAccount eq bankAccount.id and
                    (EbicsToBankAccountsTable.ebicsSubscriber eq subscriber.id)
        }.firstOrNull()
    }
    return row != null
}

/** Check if the nexus user is allowed to use the claimed bank account.  */
fun userHasRights(subscriber: NexusUserEntity, bankAccount: BankAccountEntity): Boolean {
    val row = transaction {
        UserToBankAccountEntity.find {
            UserToBankAccountsTable.bankAccount eq bankAccount.id and
                    (UserToBankAccountsTable.nexusUser eq subscriber.id)
        }.firstOrNull()
    }
    return row != null
}

fun parseDate(date: String): DateTime {
    return DateTime.parse(date, DateTimeFormat.forPattern("YYYY-MM-DD"))
}
