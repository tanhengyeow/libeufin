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

import org.junit.Test
import tech.libeufin.util.XmlElementBuilder
import tech.libeufin.util.constructXml

class XmlCombinatorsTest {

    @Test
    fun testWithModularity() {
        fun module(base: XmlElementBuilder) {
            base.element("module")
        }
        val s = constructXml {
            root("root") {
                module(this)
            }
        }
        println(s)
    }

    @Test
    fun testWithIterable() {
        val s = constructXml(indent = true) {
            namespace("iter", "able")
            root("iterable") {
                element("endOfDocument") {
                    for (i in 1..10)
                        element("$i") {
                            element("$i$i") {
                                text("$i$i$i")
                            }
                        }
                }
            }
        }
        println(s)
    }

    @Test
    fun testBasicXmlBuilding() {
        val s = constructXml(indent = true) {
            namespace("ebics", "urn:org:ebics:H004")
            root("ebics:ebicsRequest") {
                attribute("version", "H004")
                element("a/b/c") {
                    attribute("attribute-of", "c")
                    element("//d/e/f//") {
                        attribute("nested", "true")
                        element("g/h/")
                    }
                }
                element("one more")
            }
        }
        println(s)
    }
}
