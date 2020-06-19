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

package tech.libeufin.nexus

import io.ktor.client.HttpClient
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.time.delay
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.nexus.bankaccount.fetchTransactionsInternal
import tech.libeufin.nexus.bankaccount.submitAllPaymentInitiations
import tech.libeufin.nexus.server.FetchLevel
import tech.libeufin.nexus.server.FetchSpecJson
import tech.libeufin.nexus.server.FetchSpecLatestJson
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Duration

/** Crawls all the facades, and requests history for each of its creators. */
suspend fun downloadTalerFacadesTransactions(httpClient: HttpClient, fetchSpec: FetchSpecJson) {
    val work = mutableListOf<Pair<String, String>>()
    transaction {
        TalerFacadeStateEntity.all().forEach {
            logger.debug("Fetching history for facade: ${it.id.value}, bank account: ${it.bankAccount}")
            work.add(Pair(it.facade.creator.id.value, it.bankAccount))
        }
    }
    work.forEach {
        fetchTransactionsInternal(
            client = httpClient,
            fetchSpec = fetchSpec,
            userId = it.first,
            accountid = it.second
        )
    }
}


private inline fun reportAndIgnoreErrors(f: () -> Unit) {
    try {
        f()
    } catch (e: java.lang.Exception) {
        logger.error("ignoring exception", e)
    }
}

fun moreFrequentBackgroundTasks(httpClient: HttpClient) {
    GlobalScope.launch {
        while (true) {
            logger.debug("Running more frequent background jobs")
            reportAndIgnoreErrors {
                downloadTalerFacadesTransactions(
                    httpClient,
                    FetchSpecLatestJson(
                        FetchLevel.ALL,
                        null
                    )
                )
            }
            // FIXME: should be done automatically after raw ingestion
            reportAndIgnoreErrors { ingestTalerTransactions() }
            reportAndIgnoreErrors { submitAllPaymentInitiations(httpClient) }
            logger.debug("More frequent background jobs done")
            delay(Duration.ofSeconds(1))
        }
    }
}

fun lessFrequentBackgroundTasks(httpClient: HttpClient) {
    GlobalScope.launch {
        while (true) {
            logger.debug("Less frequent background job")
            try {
                //downloadTalerFacadesTransactions(httpClient, "C53")
            } catch (e: Exception) {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                e.printStackTrace(pw)
                logger.info("==== Less frequent background task exception ====\n${sw}======")
            }
            delay(Duration.ofSeconds(10))
        }
    }
}
