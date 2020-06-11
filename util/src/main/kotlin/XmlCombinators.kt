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

class XmlElementDestructor internal constructor(val d: Document, val e: Element) {
    fun <T> requireOnlyChild(f: XmlElementDestructor.(e: Element) -> T): T {
        val child =
            e.getChildElements("*", "*").elementAtOrNull(0)
                ?: throw DestructionError("expected singleton child tag")
        val destr = XmlElementDestructor(d, child)
        return f(destr, child)
    }

    fun <T> mapEachChildNamed(s: String, f: XmlElementDestructor.(e: Element) -> T): List<T> {
        val res = mutableListOf<T>()
        val els = e.getChildElements("*", s)
        for (child in els) {
            val destr = XmlElementDestructor(d, child)
            res.add(f(destr, child))
        }
        return res
    }

    fun <T> requireUniqueChildNamed(s: String, f: XmlElementDestructor.(e: Element) -> T): T {
        val cl = e.getChildElements("*", s)
        if (cl.size != 1) {
            throw DestructionError("expected exactly one unique $s child, got ${cl.size} instead")
        }
        val el = cl[0]
        val destr = XmlElementDestructor(d, el)
        return f(destr, el)
    }

    fun <T> maybeUniqueChildNamed(s: String, f: XmlElementDestructor.(e: Element) -> T): T? {
        val cl = e.getChildElements("*", s)
        if (cl.size > 1) {
            throw DestructionError("expected at most one unique $s child, got ${cl.size} instead")
        }
        if (cl.size == 1) {
            val el = cl[0]
            val destr = XmlElementDestructor(d, el)
            println("found child $s")
            return f(destr, el)
        }
        return null
    }
}

class XmlDocumentDestructor internal constructor(val d: Document) {
    fun <T> requireRootElement(name: String, f: XmlElementDestructor.(e: Element) -> T): T {
        if (this.d.documentElement.tagName != name) {
            throw DestructionError("expected '$name' tag")
        }
        val destr = XmlElementDestructor(d, d.documentElement)
        return f(destr, this.d.documentElement)
    }
}

fun <T> destructXml(d: Document, f: XmlDocumentDestructor.() -> T): T {
    return f(XmlDocumentDestructor(d))
}
