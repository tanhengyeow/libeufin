package tech.libeufin.nexus

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import tech.libeufin.util.*
import java.util.*

suspend inline fun HttpClient.postToBank(url: String, body: String): String {
    logger.debug("Posting: $body")
    val response = try {
        this.post<String>(
            urlString = url,
            block = {
                this.body = body
            }
        )
    } catch (e: Exception) {
        throw UnreachableBankError(HttpStatusCode.InternalServerError)
    }
    return response
}

sealed class EbicsDownloadResult

class EbicsDownloadSuccessResult(
    val orderData: ByteArray
) : EbicsDownloadResult()

/**
 * Some bank-technical error occured.
 */
class EbicsDownloadBankErrorResult(
    val returnCode: EbicsReturnCode
) : EbicsDownloadResult()

/**
 * Do an EBICS download transaction.  This includes the initialization phase, transaction phase
 * and receipt phase.
 */
suspend fun doEbicsDownloadTransaction(
    client: HttpClient,
    subscriberDetails: EbicsClientSubscriberDetails,
    orderType: String,
    orderParams: EbicsOrderParams
): EbicsDownloadResult {

    // Initialization phase
    val initDownloadRequestStr = createEbicsRequestForDownloadInitialization(subscriberDetails, orderType, orderParams)
    val payloadChunks = LinkedList<String>();
    val initResponseStr = client.postToBank(subscriberDetails.ebicsUrl, initDownloadRequestStr)

    val initResponse = parseAndValidateEbicsResponse(subscriberDetails, initResponseStr)

    when (initResponse.technicalReturnCode) {
        EbicsReturnCode.EBICS_OK -> {
            // Success, nothing to do!
        }
        else -> {
            throw ProtocolViolationError("unexpected return code ${initResponse.technicalReturnCode}")
        }
    }

    when (initResponse.bankReturnCode) {
        EbicsReturnCode.EBICS_OK -> {
            // Success, nothing to do!
        }
        else -> {
            return EbicsDownloadBankErrorResult(initResponse.bankReturnCode)
        }
    }

    val transactionID =
        initResponse.transactionID ?: throw ProtocolViolationError("initial response must contain transaction ID")

    val encryptionInfo = initResponse.dataEncryptionInfo
        ?: throw ProtocolViolationError("initial response did not contain encryption info")

    val initOrderDataEncChunk = initResponse.orderDataEncChunk
        ?: throw ProtocolViolationError("initial response for download transaction does not contain data transfer")

    payloadChunks.add(initOrderDataEncChunk)

    val respPayload = decryptAndDecompressResponse(subscriberDetails, encryptionInfo, payloadChunks)

    // Acknowledgement phase

    val ackRequest = createEbicsRequestForDownloadReceipt(subscriberDetails, transactionID)
    val ackResponseStr = client.postToBank(
        subscriberDetails.ebicsUrl,
        ackRequest
    )
    val ackResponse = parseAndValidateEbicsResponse(subscriberDetails, ackResponseStr)
    when (ackResponse.technicalReturnCode) {
        EbicsReturnCode.EBICS_DOWNLOAD_POSTPROCESS_DONE -> {
        }
        else -> {
            throw ProtocolViolationError("unexpected return code")
        }
    }
    return EbicsDownloadSuccessResult(respPayload)
}


suspend fun doEbicsUploadTransaction(
    client: HttpClient,
    subscriberDetails: EbicsClientSubscriberDetails,
    orderType: String,
    payload: ByteArray,
    orderParams: EbicsOrderParams
) {
    if (subscriberDetails.bankEncPub == null) {
        throw InvalidSubscriberStateError("bank encryption key unknown, request HPB first")
    }
    val preparedUploadData = prepareUploadPayload(subscriberDetails, payload)
    val req = createEbicsRequestForUploadInitialization(subscriberDetails, orderType, orderParams, preparedUploadData)
    val responseStr = client.postToBank(subscriberDetails.ebicsUrl, req)

    val initResponse = parseAndValidateEbicsResponse(subscriberDetails, responseStr)
    if (initResponse.technicalReturnCode != EbicsReturnCode.EBICS_OK) {
        throw ProtocolViolationError("unexpected return code")
    }

    val transactionID =
        initResponse.transactionID ?: throw ProtocolViolationError("init response must have transaction ID")

    logger.debug("INIT phase passed!")
    /* now send actual payload */

    val tmp = createEbicsRequestForUploadTransferPhase(
        subscriberDetails,
        transactionID,
        preparedUploadData,
        0
    )

    val txRespStr = client.postToBank(
        subscriberDetails.ebicsUrl,
        tmp
    )

    val txResp = parseAndValidateEbicsResponse(subscriberDetails, txRespStr)

    when (txResp.technicalReturnCode) {
        EbicsReturnCode.EBICS_OK -> {
        }
        else -> {
            throw ProtocolViolationError("unexpected return code")
        }
    }
}
