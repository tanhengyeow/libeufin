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

package tech.libeufin;

import org.w3c.dom.Document
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.bind.JAXBContext
import javax.xml.bind.JAXBElement
import javax.xml.bind.JAXBException
import javax.xml.bind.Marshaller
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerConfigurationException
import javax.xml.transform.TransformerException
import javax.xml.transform.TransformerFactory
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
     * Bundle of all the XSDs loaded in memory, from disk.
     */
    private val bundle = {
        val classLoader = ClassLoader.getSystemClassLoader()
        val schemas = arrayOf(
            // StreamSource(classLoader.getResourceAsStream("ebics_hev.xsd")),
            // StreamSource(classLoader.getResourceAsStream("ebics_H004.xsd")),
            // StreamSource(classLoader.getResourceAsStream("ebics_orders_H004.xsd")),
            StreamSource(classLoader.getResourceAsStream("xmldsig-core-schema.xsd")),
            StreamSource(classLoader.getResourceAsStream("ebics_types_H004.xsd")),
            // StreamSource(classLoader.getResourceAsStream("ebics_signature.xsd")),
            // StreamSource(classLoader.getResourceAsStream("ebics_response_H004.xsd")),
            // StreamSource(classLoader.getResourceAsStream("ebics_keymgmt_response_H004.xsd")),
            StreamSource(classLoader.getResourceAsStream("ebics_keymgmt_request_H004.xsd"))

        )

        try {
            val sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
            sf.newSchema(schemas)
        } catch (e: SAXException) {
            e.printStackTrace()
            // FIXME: must stop everything if schemas fail to load.
            null
        }
    }()
    private val validator = bundle?.newValidator()

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
            val document = builder.parse(InputSource(xmlInputStream));

            return document;

        } catch (e: ParserConfigurationException) {
            e.printStackTrace()
        } catch (e: SAXException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return null;
    }

    /**
     *
     * @param xmlDoc the XML document to validate
     * @return true when validation passes, false otherwise
     */
    private fun validate(xmlDoc: StreamSource): Boolean {
        try {
            validator?.validate(xmlDoc)
        } catch (e: SAXException) {
            println(e.message)
            return false;
        } catch (e: IOException) {
            e.printStackTrace()
            return false;
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
     * Return the DOM representation of the Java object, using the JAXB
     * interface.  FIXME: narrow input type to JAXB type!
     *
     * @param object to be transformed into DOM.  Typically, the object
     *               has already got its setters called.
     * @return the DOM Document, or null (if errors occur).
     */
    fun convertJaxbToDom(obj: JAXBElement<Unit>): Document? {

        try {
            val jc = JAXBContext.newInstance("tech.libeufin.messages")

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
        return null;
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
            val tf = TransformerFactory.newInstance();
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
        return null;
    }

    /**
     * Extract String from JAXB.
     *
     * @param obj the JAXB instance
     * @return String representation of @a object, or null if errors occur
     */
    fun <T> getStringFromJaxb(obj: JAXBElement<T>): String? {
        val sw = StringWriter()

        try {
            val jc = JAXBContext.newInstance("tech.libeufin.messages")
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