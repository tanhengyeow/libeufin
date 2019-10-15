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

import com.sun.org.apache.xerces.internal.dom.DOMInputImpl
import org.w3c.dom.Document
import org.w3c.dom.ls.LSInput
import org.w3c.dom.ls.LSResourceResolver
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import java.io.*
import javax.xml.XMLConstants
import javax.xml.bind.JAXBContext
import javax.xml.bind.JAXBElement
import javax.xml.bind.JAXBException
import javax.xml.bind.Marshaller
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.*
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory


/**
 * This class takes care of importing XSDs and validate
 * XMLs against those.
 */
class XML {
    /**
     * Validator for EBICS messages.
     */
    private val validator = try {
        val classLoader = ClassLoader.getSystemClassLoader()
        val sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
        sf.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file")
        sf.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        sf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        sf.errorHandler = object : ErrorHandler {
            override fun warning(p0: SAXParseException?) {
                println("Warning: $p0")
            }

            override fun error(p0: SAXParseException?) {
                println("Error: $p0")
            }

            override fun fatalError(p0: SAXParseException?) {
                println("Fatal error: $p0")
            }
        }
        sf.resourceResolver = object : LSResourceResolver {
            override fun resolveResource(
                type: String?,
                namespaceURI: String?,
                publicId: String?,
                systemId: String?,
                baseUri: String?
            ): LSInput? {
                if (type != "http://www.w3.org/2001/XMLSchema") {
                    return null
                }
                val res = classLoader.getResourceAsStream(systemId) ?: return null
                return DOMInputImpl(publicId, systemId, baseUri, res, "UTF-8")
            }
        }
        val schemaInputs: Array<Source> = listOf("ebics_H004.xsd", "ebics_hev.xsd").map {
            val resUrl = classLoader.getResource(it) ?: throw FileNotFoundException("Schema file $it not found.")
            StreamSource(File(resUrl.toURI()))
        }.toTypedArray()
        val bundle = sf.newSchema(schemaInputs)
        bundle.newValidator()
    } catch (e: SAXException) {
        e.printStackTrace()
        throw e
    }


    /**
     * Parse string into XML DOM.
     * @param xmlString the string to parse.
     * @return the DOM representing @a xmlString
     */
    fun parseStringIntoDom(xmlString: String): Document? {

        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true

        try {
            val xmlInputStream = ByteArrayInputStream(xmlString.toByteArray())
            val builder = factory.newDocumentBuilder()
            val document = builder.parse(InputSource(xmlInputStream))

            return document

        } catch (e: ParserConfigurationException) {
            e.printStackTrace()
        } catch (e: SAXException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return null
    }

    /**
     *
     * @param xmlDoc the XML document to validate
     * @return true when validation passes, false otherwise
     */
    fun validate(xmlDoc: StreamSource): Boolean {
        try {
            validator?.validate(xmlDoc)
        } catch (e: SAXException) {
            println(e.message)
            return false
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        }

        return true
    }

    /**
     * Validates the DOM against the Schema(s) of this object.
     * @param domDocument DOM to validate
     * @return true/false if the document is valid/invalid
     */
    fun validateFromDom(domDocument: Document): Boolean {
        try {
            validator?.validate(DOMSource(domDocument))
        } catch (e: SAXException) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * Craft object to be passed to the XML validator.
     * @param xmlString XML body, as read from the POST body.
     * @return InputStream object, as wanted by the validator.
     */
    fun validateFromString(xmlString: String): Boolean {
        val xmlInputStream: InputStream = ByteArrayInputStream(xmlString.toByteArray())
        val xmlSource = StreamSource(xmlInputStream)
        return this.validate(xmlSource)
    }

    /**
     * Convert a DOM document - of a XML document - to the JAXB representation.
     *
     * @param packageName the package containing the ObjectFactory used to
     *        instantiate the wanted object.
     * @param document the document to convert into JAXB.
     * @return the JAXB object reflecting the original XML document.
     */
    fun <T>convertDomToJaxb(packageName: String, document: Document) : T {

        val jc = JAXBContext.newInstance(packageName)

        /* Marshalling the object into the document.  */
        val m = jc.createUnmarshaller()
        return m.unmarshal(document) as T // document "went" into Jaxb
    }

    /**
     * Convert a XML string to the JAXB representation.
     *
     * @param packageName the package containing the ObjectFactory used to
     *        instantiate the wanted object.
     * @param documentString the string to convert into JAXB.
     * @return the JAXB object reflecting the original XML document.
     */
    fun <T>convertStringToJaxb(packageName: String, documentString: String) : T {

        val jc = JAXBContext.newInstance(packageName)

        /* Marshalling the object into the document.  */
        val m = jc.createUnmarshaller()
        return m.unmarshal(StringReader(documentString)) as T // document "went" into Jaxb
    }



    /**
     * Return the DOM representation of the Java object, using the JAXB
     * interface.  FIXME: narrow input type to JAXB type!
     *
     * @param packageName the package containing the ObjectFactory used to
     *        instantiate the wanted object.
     * @param object to be transformed into DOM.  Typically, the object
     *               has already got its setters called.
     * @return the DOM Document, or null (if errors occur).
     */
    fun convertJaxbToDom(packageName: String, obj: JAXBElement<Unit>): Document? {

        try {
            val jc = JAXBContext.newInstance(packageName)

            /* Make the target document.  */
            val dbf = DocumentBuilderFactory.newInstance()
            val db = dbf.newDocumentBuilder()
            val document = db.newDocument()

            /* Marshalling the object into the document.  */
            val m = jc.createMarshaller()
            m.marshal(obj, document) // document absorbed the XML!
            return document

        } catch (e: JAXBException) {
            e.printStackTrace()
        } catch (e: ParserConfigurationException) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * Extract String from DOM.
     *
     * @param document the DOM to extract the string from.
     * @return the final String, or null if errors occur.
     */
    fun getStringFromDocument(document: Document): String? {

        try {
            /* Make Transformer.  */
            val tf = TransformerFactory.newInstance()
            val t = tf.newTransformer()

            t.setOutputProperty(OutputKeys.INDENT, "no")

            /* Make string writer.  */
            val sw = StringWriter()

            /* Extract string.  */
            t.transform(DOMSource(document), StreamResult(sw))
            return sw.toString()

        } catch (e: TransformerConfigurationException) {
            e.printStackTrace()
        } catch (e: TransformerException) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * Extract String from JAXB.
     *
     * @param packageName the package containing the ObjectFactory used to
     *        instantiate the wanted object.
     * @param obj the JAXB instance
     * @return String representation of @a object, or null if errors occur
     */
    fun <T> getStringFromJaxb(packageName: String, obj: JAXBElement<T>): String? {
        val sw = StringWriter()

        try {
            val jc = JAXBContext.newInstance(packageName)
            /* Getting the string.  */
            val m = jc.createMarshaller()
            m.marshal(obj, sw)
            m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true)

        } catch (e: JAXBException) {
            e.printStackTrace()
            return "Bank fatal error."
        }

        return sw.toString()
    }
}