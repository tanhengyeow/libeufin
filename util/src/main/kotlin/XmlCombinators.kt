package tech.libeufin.util

import com.sun.xml.txw2.output.IndentingXMLStreamWriter
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
    writer.writeStartDocument()
    f(b)
    writer.writeEndDocument()
    return stream.buffer.toString()
}

class XmlDocumentDestructor {
}

fun <T>destructXml(input: String, f: XmlDocumentDestructor.() -> T): T {
    val d = XmlDocumentDestructor()
    return f(d)
}
