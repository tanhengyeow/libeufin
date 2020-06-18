/*
 * This file is part of LibEuFin.
 * Copyright (C) 2020 Taler Systems S.A.
 *
 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.
 *
 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

package tech.libeufin.nexus.bankaccount

import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.not
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.nexus.*
import tech.libeufin.nexus.ebics.doEbicsUploadTransaction
import tech.libeufin.nexus.ebics.submitEbicsPaymentInitiation
import tech.libeufin.util.EbicsClientSubscriberDetails
import tech.libeufin.util.EbicsStandardOrderParams


/**
 * Submit all pending prepared payments.
 */
suspend fun submitPreparedPayments(httpClient: HttpClient) {
    data class Submission(
        val id: Long,
        val type: String
    )
    logger.debug("auto-submitter started")
    val workQueue = mutableListOf<Submission>()
    transaction {
        NexusBankAccountEntity.all().forEach {
            val defaultBankConnectionId = it.defaultBankConnection?.id ?: throw NexusError(
                HttpStatusCode.BadRequest,
                "needs default bank connection"
            )
            val bankConnection = NexusBankConnectionEntity.findById(defaultBankConnectionId) ?: throw NexusError(
                HttpStatusCode.InternalServerError,
                "Bank account '${it.id.value}' doesn't map to any bank connection (named '${it.defaultBankConnection}')"
            )
            if (bankConnection.type != "ebics") {
                logger.info("Skipping non-implemented bank connection '${bankConnection.type}'")
                return@forEach
            }
            val bankAccount: NexusBankAccountEntity = it
            InitiatedPaymentEntity.find {
                InitiatedPaymentsTable.debitorIban eq bankAccount.iban and
                        not(InitiatedPaymentsTable.submitted)
            }.forEach {
                workQueue.add(Submission(it.id.value, bankConnection.type))
            }
        }
    }
    workQueue.forEach {
        when (it.type) {
            "ebics" -> {
                submitEbicsPaymentInitiation(httpClient, it.id)
            }
            else -> throw NexusError(HttpStatusCode.NotImplemented, "submission for ${it.type }not supported")
        }

    }
}