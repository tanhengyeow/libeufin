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

import com.sun.xml.txw2.output.IndentingXMLStreamWriter
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.StringWriter
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

class XmlElementBuilder(val w: XMLStreamWriter) {
    /**
     * First consumes all the path's components, and _then_ starts applying f.
     */
    fun element(path: MutableList<String>, f: XmlElementBuilder.() -> Unit = {}) {
        /* the wanted path got constructed, go on with f's logic now.  */
        if (path.isEmpty()) {
            f()
            return
        }
        w.writeStartElement(path.removeAt(0))
        this.element(path, f)
        w.writeEndElement()
    }

    fun element(path: String, f: XmlElementBuilder.() -> Unit = {}) {
        val splitPath = path.trim('/').split("/").toMutableList()
        this.element(splitPath, f)
    }

    fun attribute(name: String, value: String) {
        w.writeAttribute(name, value)
    }

    fun text(content: String) {
        w.writeCharacters(content)
    }
}

class XmlDocumentBuilder {

    private var maybeWriter: XMLStreamWriter? = null
    internal var writer: XMLStreamWriter
        get() {
            val w = maybeWriter
            return w ?: throw AssertionError("no writer set")
        }
        set(w: XMLStreamWriter) {
            maybeWriter = w
        }

    fun namespace(uri: String) {
        writer.setDefaultNamespace(uri)
    }

    fun namespace(prefix: String, uri: String) {
        writer.setPrefix(prefix, uri)
    }

    fun defaultNamespace(uri: String) {
        writer.setDefaultNamespace(uri)
    }

    fun root(name: String, f: XmlElementBuilder.() -> Unit) {
        val elementBuilder = XmlElementBuilder(writer)
        writer.writeStartElement(name)
        elementBuilder.f()
        writer.writeEndElement()
    }
}

fun constructXml(indent: Boolean = false, f: XmlDocumentBuilder.() -> Unit): String {
    val b = XmlDocumentBuilder()
    val factory = XMLOutputFactory.newFactory()
    factory.setProperty(XMLOutputFactory.IS_REPAIRING_NAMESPACES, true)
    val stream = StringWriter()
    var writer = factory.createXMLStreamWriter(stream)
    if (indent) {
        writer = IndentingXMLStreamWriter(writer)
    }
    b.writer = writer
    /**
     * NOTE: commenting out because it wasn't obvious how to output the
     * "standalone = 'yes' directive".  Manual forge was therefore preferred.
     */
    // writer.writeStartDocument()
    f(b)
    writer.writeEndDocument()
    return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n${stream.buffer.toString()}"
}

class DestructionError(m: String) : Exception(m)

private fun Element.getChildElements(ns: String, tag: String): List<Element> {
    val elements = mutableListOf<Element>()
    for (i in 0..this.childNodes.length) {
        val el = this.childNodes.item(i)
        if (el !is Element) {
            continue
        }
        if (ns != "*" && el.namespaceURI != ns) {
            continue
        }
        if (tag != "*" && el.localName != tag) {
            continue
        }
        elements.add(el)
    }
    return elements
}

class XmlElementDestructor internal constructor(val focusElement: Element) {
    fun <T> requireOnlyChild(f: XmlElementDestructor.(e: Element) -> T): T {
        val children = focusElement.getChildElements("*", "*")
        if (children.size != 1) throw DestructionError("expected singleton child tag")
        val destr = XmlElementDestructor(children[0])
        return f(destr, children[0])
    }

    fun <T> mapEachChildNamed(s: String, f: XmlElementDestructor.() -> T): List<T> {
        val res = mutableListOf<T>()
        val els = focusElement.getChildElements("*", s)
        for (child in els) {
            val destr = XmlElementDestructor(child)
            res.add(f(destr))
        }
        return res
    }

    fun <T> requireUniqueChildNamed(s: String, f: XmlElementDestructor.() -> T): T {
        val cl = focusElement.getChildElements("*", s)
        if (cl.size != 1) {
            throw DestructionError("expected exactly one unique $s child, got ${cl.size} instead at ${focusElement}")
        }
        val el = cl[0]
        val destr = XmlElementDestructor(el)
        return f(destr)
    }

    fun <T> maybeUniqueChildNamed(s: String, f: XmlElementDestructor.() -> T): T? {
        val cl = focusElement.getChildElements("*", s)
        if (cl.size > 1) {
            throw DestructionError("expected at most one unique $s child, got ${cl.size} instead")
        }
        if (cl.size == 1) {
            val el = cl[0]
            val destr = XmlElementDestructor(el)
            return f(destr)
        }
        return null
    }
}

class XmlDocumentDestructor internal constructor(val d: Document) {
    fun <T> requireRootElement(name: String, f: XmlElementDestructor.() -> T): T {
        if (this.d.documentElement.tagName != name) {
            throw DestructionError("expected '$name' tag")
        }
        val destr = XmlElementDestructor(d.documentElement)
        return f(destr)
    }
}

fun <T> destructXml(d: Document, f: XmlDocumentDestructor.() -> T): T {
    return f(XmlDocumentDestructor(d))
}
