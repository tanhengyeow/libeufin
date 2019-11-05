/*
 * This file is part of LibEuFin.
 * Copyright (C) 2019 Stanisci and Dold.

 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.

 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.

 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

package tech.libeufin.sandbox

/**
 * Error message.
 */
data class SandboxError(
    val message: String
)

/**
 * Request for POST /admin/customers
 */
data class CustomerRequest(
    val name: String
)

data class CustomerResponse(
    val id: Int
)

/**
 * Response for GET /admin/customers/:id
 */
data class CustomerInfo(
    val name: String,
    val ebicsInfo: CustomerEbicsInfo
)

data class CustomerEbicsInfo(
    val userId: String
)

/**
 * Wrapper type around initialization letters
 * for RSA keys.
 */
data class IniHiaLetters(
    val ini: IniLetter,
    val hia: HiaLetter
)

/**
 * Request for INI letter.
 */
data class IniLetter(
    val userId: String,
    val customerId: String,
    val name: String,
    val date: String,
    val time: String,
    val recipient: String,
    val public_exponent_length: Int,
    val public_exponent: String,
    val public_modulus_length: Int,
    val public_modulus: String,
    val hash: String
)

/**
 * Request for HIA letter.
 */
data class HiaLetter(
    val userId: String,
    val customerId: String,
    val name: String,
    val date: String,
    val time: String,
    val recipient: String,
    val ia_public_exponent_length: Int,
    val ia_public_exponent: String,
    val ia_public_modulus_length: Int,
    val ia_public_modulus: String,
    val ia_hash: String,
    val enc_public_exponent_length: Int,
    val enc_public_exponent: String,
    val enc_public_modulus_length: Int,
    val enc_public_modulus: String,
    val enc_hash: String
)

data class EbicsSubscribersResponse(
    val subscribers: List<String>
)

data class EbicsSubscriberResponse(
    val id: String,
    val partnerID: String,
    val userID: String,
    val systemID: String?,
    val state: String
)

data class EbicsHostsResponse(
    val ebicsHosts: List<String>
)

data class EbicsHostResponse(
    val hostID: String,
    val ebicsVersion: String
)

data class EbicsHostCreateRequest(
    val hostID: String,
    val ebicsVersion: String
)