package tech.libeufin.nexus

import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
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

fun isProduction(): Boolean {
    return System.getenv("NEXUS_PRODUCTION") != null
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
 * Retrieve bank account details, only if user owns it.
 */
fun getBankAccount(userId: String, accountId: String): BankAccountEntity {
    return transaction {
        val bankAccountMap = BankAccountMapEntity.find {
            BankAccountMapsTable.nexusUser eq userId
        }.firstOrNull() ?: throw NexusError(
        HttpStatusCode.NotFound,
        "Bank account '$accountId' not found"
        )
        bankAccountMap.bankAccount
    }
}

/**
 * Given a nexus user id, returns the _list_ of bank accounts associated to it.
 *
 * @param id the subscriber id
 * @return the (non-empty) list of bank accounts associated with this user.
 */
fun getBankAccountsFromNexusUserId(id: String): MutableList<BankAccountEntity> {
    logger.debug("Looking up bank account of user '$id'")
    val ret = mutableListOf<BankAccountEntity>()
    transaction {
        BankAccountMapEntity.find {
            BankAccountMapsTable.nexusUser eq id
        }.forEach {
            ret.add(it.bankAccount)
        }
    }
    if (ret.isEmpty()) {
        throw NexusError(
            HttpStatusCode.NotFound,
            "Such user '$id' does not have any bank account associated"
        )
    }
    return ret
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
fun createPain001document(paymentData: PreparedPaymentEntity): String {
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
            BankAccountsTable.iban eq paymentData.debitorIban and
                    (BankAccountsTable.bankCode eq paymentData.debitorBic)
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
                        text(paymentData.id.value.toString())
                    }
                    element("CreDtTm") {
                        val dateMillis = transaction {
                            paymentData.preparationDate
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
                        text(paymentData.sum.toString())
                    }
                    element("InitgPty/Nm") {
                        text(debitorBankAccountLabel)
                    }
                }
                element("PmtInf") {
                    element("PmtInfId") {
                        text(paymentData.id.value.toString())
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
                        text(paymentData.sum.toString())
                    }
                    element("PmtTpInf/SvcLvl/Cd") {
                        text("SEPA")
                    }
                    element("ReqdExctnDt") {
                        val dateMillis = transaction {
                            paymentData.preparationDate
                        }
                        text(DateTime(dateMillis).toString("Y-MM-dd"))
                    }
                    element("Dbtr/Nm") {
                        text(debitorBankAccountLabel)
                    }
                    element("DbtrAcct/Id/IBAN") {
                        text(paymentData.debitorIban)
                    }
                    element("DbtrAgt/FinInstnId/BIC") {
                        text(paymentData.debitorBic)
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
                            attribute("Ccy", paymentData.currency)
                            text(paymentData.sum.toString())
                        }
                        element("CdtrAgt/FinInstnId/BIC") {
                            text(paymentData.creditorBic)
                        }
                        element("Cdtr/Nm") {
                            text(paymentData.creditorName)
                        }
                        element("CdtrAcct/Id/IBAN") {
                            text(paymentData.creditorIban)
                        }
                        element("RmtInf/Ustrd") {
                            text(paymentData.subject)
                        }
                    }
                }
            }
        }
    }
    return s
}

/**
 * Retrieve prepared payment from database, raising exception
 * if not found.
 */
fun getPreparedPayment(uuid: String): PreparedPaymentEntity {
    return transaction {
        PreparedPaymentEntity.findById(uuid)
    } ?: throw NexusError(
        HttpStatusCode.NotFound,
        "Payment '$uuid' not found"
    )
}

/**
 * Insert one row in the database, and leaves it marked as non-submitted.
 * @param debtorAccountId the mnemonic id assigned by the bank to one bank
 * account of the subscriber that is creating the pain entity.  In this case,
 * it will be the account whose money will pay the wire transfer being defined
 * by this pain document.
 */
fun addPreparedPayment(paymentData: Pain001Data, nexusUser: NexusUserEntity): PreparedPaymentEntity {
    val randomId = Random().nextLong()
    return transaction {
        val debitorAccount = getBankAccount(nexusUser.id.value, paymentData.debitorAccount)
        PreparedPaymentEntity.new(randomId.toString()) {
            subject = paymentData.subject
            sum = paymentData.sum
            debitorIban = debitorAccount.iban
            debitorBic = debitorAccount.bankCode
            debitorName = debitorAccount.accountHolder
            creditorName = paymentData.creditorName
            creditorBic = paymentData.creditorBic
            creditorIban = paymentData.creditorIban
            preparationDate = DateTime.now().millis
            paymentId = randomId
            endToEndId = randomId
            this.nexusUser = nexusUser
        }
    }
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
 * Test HTTP basic auth.  Throws error if password is wrong,
 * and makes sure that the user exists in the system.
 *
 * @param authorization the Authorization:-header line.
 * @return user id
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

fun authenticateAdminRequest(authorization: String?): String {
    val userId = authenticateRequest(authorization)
    if (!userId.equals("admin")) throw NexusError(
        HttpStatusCode.Forbidden,
        "Not the 'admin' user"
    )
    return userId
}

/**
 * Check if the subscriber has the right to use the (claimed) bank account.
 * @param subscriber id of the EBICS subscriber to check
 * @param bankAccount id of the claimed bank account
 * @return true if the subscriber can use the bank account.
 */
fun subscriberHasRights(subscriber: EbicsSubscriberEntity, bankAccount: BankAccountEntity): Boolean {
    val row = transaction {
        BankAccountMapEntity.find {
            BankAccountMapsTable.bankAccount eq bankAccount.id and
                    (BankAccountMapsTable.ebicsSubscriber eq subscriber.id)
        }.firstOrNull()
    }
    return row != null
}

fun getBankAccountFromIban(iban: String): BankAccountEntity {
    return transaction {
        BankAccountEntity.find {
            BankAccountsTable.iban eq iban
        }.firstOrNull() ?: throw NexusError(
            HttpStatusCode.NotFound,
            "Bank account with IBAN '$iban' not found"
        )
    }
}

/** Check if the nexus user is allowed to use the claimed bank account.  */
fun userHasRights(nexusUser: NexusUserEntity, iban: String): Boolean {
    val row = transaction {
        val bankAccount = getBankAccountFromIban(iban)
        BankAccountMapEntity.find {
            BankAccountMapsTable.bankAccount eq bankAccount.id and
                    (BankAccountMapsTable.nexusUser eq nexusUser.id)
        }.firstOrNull()
    }
    return row != null
}