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

package tech.libeufin.util

/**
 * (Very) generic information about one payment.  Can be
 * derived from a CAMT response, or from a prepared PAIN
 * document.
 */
data class RawPayment(
    val creditorIban: String,
    val creditorBic: String? = null,
    val creditorName: String,
    val debitorIban: String,
    val debitorBic: String? = null,
    val debitorName: String,
    val amount: String,
    val currency: String,
    val subject: String,
    val date: String? = null,
    // this (uid) field is null when RawPayment is a _requested_ payment
    // over the admin API, and it's not null when RawPayment represent
    // a database row of a settled payment.
    val uid: Int? = null
)