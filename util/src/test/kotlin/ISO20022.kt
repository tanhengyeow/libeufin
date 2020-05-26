package tech.libeufin.util

import org.w3c.dom.Document

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

data class CamtData(
    val bookingDate: Long,
    val subject: String,
    val txType: String, /* only "DBIT" / "CRDT" are admitted */
    val currency: String,
    val amount: String,
    val status: String, /* only "BOOK" is admitted */
    val counterpartIban: String,
    val counterpartBic: String,
    val counterpartName: String
)

fun parseCamt(camtDoc: Document): CamtData {
    val txType = camtDoc.pickString("//*[local-name()='Ntry']//*[local-name()='CdtDbtInd']")
    val bd = parseDashedDate(camtDoc.pickString("//*[local-name()='BookgDt']//*[local-name()='Dt']"))
    return CamtData(
        txType = txType,
        bookingDate = bd.millis(),
        subject = camtDoc.pickString("//*[local-name()='Ntry']//*[local-name()='Ustrd']"),
        currency = camtDoc.pickString("//*[local-name()='Ntry']//*[local-name()='Amt']/@Ccy"),
        amount = camtDoc.pickString("//*[local-name()='Ntry']//*[local-name()='Amt']"),
        status = camtDoc.pickString("//*[local-name()='Ntry']//*[local-name()='Sts']"),
        counterpartBic = camtDoc.pickString("//*[local-name()='RltdAgts']//*[local-name()='BIC']"),
        counterpartIban = camtDoc.pickString("//*[local-name()='${if (txType == "DBIT") "CdtrAcct" else "DbtrAcct"}']//*[local-name()='IBAN']"),
        counterpartName = camtDoc.pickString("//*[local-name()='RltdPties']//*[local-name()='${if (txType == "DBIT") "Cdtr" else "Dbtr"}']//*[local-name()='Nm']")
    )
}