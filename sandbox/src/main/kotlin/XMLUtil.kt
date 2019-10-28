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
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.w3c.dom.ls.LSInput
import org.w3c.dom.ls.LSResourceResolver
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import java.io.*
import java.security.PrivateKey
import java.security.PublicKey
import java.util.*
import javax.xml.XMLConstants
import javax.xml.bind.JAXBContext
import javax.xml.bind.JAXBElement
import javax.xml.bind.JAXBException
import javax.xml.bind.Marshaller
import javax.xml.crypto.*
import javax.xml.crypto.dom.DOMURIReference
import javax.xml.crypto.dsig.*
import javax.xml.crypto.dsig.dom.DOMSignContext
import javax.xml.crypto.dsig.dom.DOMValidateContext
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec
import javax.xml.crypto.dsig.spec.TransformParameterSpec
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.OutputKeys
import javax.xml.transform.Source
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import javax.xml.xpath.XPath
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * Helpers for dealing with XML in EBICS.
 */
class XMLUtil {
    /**
     * This URI dereferencer allows handling the resource reference used for
     * XML signatures in EBICS.
     */
    private class EbicsSigUriDereferencer : URIDereferencer {
        override fun dereference(myRef: URIReference?, myCtx: XMLCryptoContext?): Data {
            val ebicsXpathExpr = "//*[@authenticate='true']"
            if (myRef !is DOMURIReference)
                throw Exception("invalid type")
            if (myRef.uri != "#xpointer($ebicsXpathExpr)")
                throw Exception("invalid EBICS XML signature URI: '${myRef.uri}'")
            val xp: XPath = XPathFactory.newInstance().newXPath()
            val nodeSet = xp.compile(ebicsXpathExpr).evaluate(myRef.here.ownerDocument, XPathConstants.NODESET)
            if (nodeSet !is NodeList)
                throw Exception("invalid type")
            if (nodeSet.length <= 0) {
                throw Exception("no nodes to sign")
            }
            val nodeList = LinkedList<Node>()
            for (i in 0 until nodeSet.length) {
                nodeList.add(nodeSet.item(i))
            }
            return NodeSetData { nodeList.iterator() }
        }
    }

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
     *
     * @param xmlDoc the XML document to validate
     * @return true when validation passes, false otherwise
     */
    fun validate(xmlDoc: StreamSource): Boolean {
        try {
            validator?.validate(xmlDoc)
        } catch (e: Exception) {
            logger.warn("Validation failed: {}", e)
            return false
        }
        return true;
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
     * @param finalType class type of the output
     * @param document the document to convert into JAXB.
     * @return the JAXB object reflecting the original XML document.
     */
    fun <T> convertDomToJaxb(finalType: Class<T>, document: Document): JAXBElement<T> {

        val jc = JAXBContext.newInstance(finalType)

        /* Marshalling the object into the document.  */
        val m = jc.createUnmarshaller()
        return m.unmarshal(document, finalType) // document "went" into Jaxb
    }

    /**
     * Convert a XML string to the JAXB representation.
     *
     * @param finalType class type of the object to instantiate
     * @param documentString the string to convert into JAXB.
     * @return the JAXB object reflecting the original XML document.
     */
    fun <T> convertStringToJaxb(finalType: Class<T>, documentString: String): JAXBElement<T> {

        val jc = JAXBContext.newInstance(finalType.packageName)

        /* Marshalling the object into the document.  */
        val u = jc.createUnmarshaller()
        return u.unmarshal(
            StreamSource(StringReader(documentString)),
            finalType
        ) // document "went" into Jaxb
    }


    /**
     * Return the DOM representation of the Java object, using the JAXB
     * interface.  FIXME: narrow input type to JAXB type!
     *
     * @param object to be transformed into DOM.  Typically, the object
     *               has already got its setters called.
     * @return the DOM Document, or null (if errors occur).
     */
    fun <T> convertJaxbToDom(obj: JAXBElement<T>): Document? {

        try {
            val jc = JAXBContext.newInstance(obj.declaredType)

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
     * Extract String from JAXB.
     *
     * @param obj the JAXB instance
     * @return String representation of @a object, or null if errors occur
     */
    fun <T> convertJaxbToString(obj: JAXBElement<T>): String? {
        val sw = StringWriter()

        try {
            val jc = JAXBContext.newInstance(obj.declaredType)
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

    companion object {
        /**
         * Extract String from DOM.
         *
         * @param document the DOM to extract the string from.
         * @return the final String, or null if errors occur.
         */
        fun convertDomToString(document: Document): String? {
            /* Make Transformer.  */
            val tf = TransformerFactory.newInstance()
            val t = tf.newTransformer()

            t.setOutputProperty(OutputKeys.INDENT, "yes")

            /* Make string writer.  */
            val sw = StringWriter()

            /* Extract string.  */
            t.transform(DOMSource(document), StreamResult(sw))
            return sw.toString()
        }

        fun convertNodeToString(node: Node): String? {
            /* Make Transformer.  */
            val tf = TransformerFactory.newInstance()
            val t = tf.newTransformer()

            t.setOutputProperty(OutputKeys.INDENT, "yes")

            /* Make string writer.  */
            val sw = StringWriter()

            /* Extract string.  */
            t.transform(DOMSource(node), StreamResult(sw))
            return sw.toString()
        }

        /**
         * Parse string into XML DOM.
         * @param xmlString the string to parse.
         * @return the DOM representing @a xmlString
         */
        fun parseStringIntoDom(xmlString: String): Document {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }
            val xmlInputStream = ByteArrayInputStream(xmlString.toByteArray())
            val builder = factory.newDocumentBuilder()
            return builder.parse(InputSource(xmlInputStream))
        }


        /**
         * Sign an EBICS document with the authentication and identity signature.
         */
        fun signEbicsDocument(doc: Document, signingPriv: PrivateKey): Unit {
            val xpath = XPathFactory.newInstance().newXPath()
            val authSigNode = xpath.compile("/*[1]/AuthSignature").evaluate(doc, XPathConstants.NODE)
            if (authSigNode !is Node)
                throw java.lang.Exception("no AuthSignature")
            val fac = XMLSignatureFactory.getInstance("DOM")
            val c14n = fac.newTransform(CanonicalizationMethod.INCLUSIVE, null as TransformParameterSpec?)
            val ref: Reference =
                fac.newReference(
                    "#xpointer(//*[@authenticate='true'])",
                    fac.newDigestMethod(DigestMethod.SHA256, null),
                    listOf(c14n),
                    null,
                    null
                )
            val canon: CanonicalizationMethod =
                fac.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE, null as C14NMethodParameterSpec?)
            val signatureMethod = fac.newSignatureMethod(SignatureMethod.RSA_SHA256, null)
            val si: SignedInfo = fac.newSignedInfo(canon, signatureMethod, listOf(ref))
            val sig: XMLSignature = fac.newXMLSignature(si, null)
            val dsc = DOMSignContext(signingPriv, authSigNode)
            dsc.defaultNamespacePrefix = "ds"
            dsc.uriDereferencer = EbicsSigUriDereferencer()

            sig.sign(dsc)

            val innerSig = authSigNode.firstChild
            while (innerSig.hasChildNodes()) {
                authSigNode.appendChild(innerSig.firstChild)
            }
            authSigNode.removeChild(innerSig)
        }

        fun verifyEbicsDocument(doc: Document, signingPub: PublicKey): Boolean {
            val xpath = XPathFactory.newInstance().newXPath()
            val doc2: Document = doc.cloneNode(true) as Document
            val authSigNode = xpath.compile("/*[1]/AuthSignature").evaluate(doc2, XPathConstants.NODE)
            if (authSigNode !is Node)
                throw java.lang.Exception("no AuthSignature")
            val sigEl = doc2.createElementNS("http://www.w3.org/2000/09/xmldsig#", "ds:Signature")
            authSigNode.parentNode.insertBefore(sigEl, authSigNode)
            while (authSigNode.hasChildNodes()) {
                sigEl.appendChild(authSigNode.firstChild)
            }
            authSigNode.parentNode.removeChild(authSigNode)
            val fac = XMLSignatureFactory.getInstance("DOM")
            println(convertDomToString(doc2))
            val dvc = DOMValidateContext(signingPub, sigEl)
            dvc.uriDereferencer = EbicsSigUriDereferencer()
            val sig = fac.unmarshalXMLSignature(dvc)
            // FIXME: check that parameters are okay!
            return sig.validate(dvc)
        }
    }
}