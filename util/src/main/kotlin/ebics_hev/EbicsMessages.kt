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

package tech.libeufin.util.ebics_hev

import java.util.*
import javax.xml.bind.annotation.*
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter
import javax.xml.bind.annotation.adapters.NormalizedStringAdapter
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter

@XmlAccessorType(XmlAccessType.NONE)
@XmlType(
    name = "HEVRequestDataType"
)
@XmlRootElement(name = "ebicsHEVRequest")
class HEVRequest{
    @get:XmlElement(name = "HostID", required = true)
    lateinit var hostId: String
}

@XmlAccessorType(XmlAccessType.NONE)
@XmlType(
    name = "HEVResponseDataType",
    propOrder = ["systemReturnCode", "versionNumber", "any"]
)
@XmlRootElement(name = "ebicsHEVResponse")
class HEVResponse {
    @get:XmlElement(name = "SystemReturnCode", required = true)
    lateinit var systemReturnCode: SystemReturnCodeType

    @get:XmlElement(name = "VersionNumber", namespace = "http://www.ebics.org/H000")
    var versionNumber: List<VersionNumber> = LinkedList()

    @get:XmlAnyElement(lax = true)
    var any: List<Any>? = null

    @XmlAccessorType(XmlAccessType.NONE)
    class VersionNumber {
        @get:XmlValue
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        lateinit var value: String

        @get:XmlAttribute(name = "ProtocolVersion", required = true)
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        lateinit var protocolVersion: String

        companion object {
            fun create(protocolVersion: String, versionNumber: String): VersionNumber {
                return VersionNumber().apply {
                    this.protocolVersion = protocolVersion
                    this.value = versionNumber
                }
            }
        }
    }
}


@XmlAccessorType(XmlAccessType.NONE)
@XmlType(
    name = "SystemReturnCodeType",
    propOrder = [
        "returnCode",
        "reportText"
    ]
)
class SystemReturnCodeType {
    @get:XmlElement(name = "ReturnCode", required = true)
    @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
    lateinit var returnCode: String

    @get:XmlElement(name = "ReportText", required = true)
    @get:XmlJavaTypeAdapter(NormalizedStringAdapter::class)
    lateinit var reportText: String
}
