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

package tech.libeufin.util

import com.sun.org.apache.xerces.internal.dom.DOMInputImpl
import com.sun.xml.bind.marshaller.NamespacePrefixMapper
import io.ktor.http.HttpStatusCode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.w3c.dom.ls.LSInput
import org.w3c.dom.ls.LSResourceResolver
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import tech.libeufin.util.ebics_h004.EbicsResponse
import java.io.*
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPrivateCrtKey
import javax.xml.XMLConstants
import javax.xml.bind.JAXBContext
import javax.xml.bind.JAXBElement
import javax.xml.bind.Marshaller
import javax.xml.crypto.*
import javax.xml.crypto.dom.DOMURIReference
import javax.xml.crypto.dsig.*
import javax.xml.crypto.dsig.dom.DOMSignContext
import javax.xml.crypto.dsig.dom.DOMValidateContext
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec
import javax.xml.crypto.dsig.spec.TransformParameterSpec
import javax.xml.namespace.NamespaceContext
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.Source
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import javax.xml.validation.Validator
import javax.xml.xpath.XPath
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

val logger: Logger = LoggerFactory.getLogger("tech.libeufin.util")
class DefaultNamespaces : NamespacePrefixMapper() {
    override fun getPreferredPrefix(namespaceUri: String?, suggestion: String?, requirePrefix: Boolean): String? {
        if (namespaceUri == "http://www.w3.org/2000/09/xmldsig#") return "ds"
        return null
    }
}


/**
 * Helpers for dealing with XML in EBICS.
 */
class XMLUtil private constructor() {
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
            val nodeSet = xp.compile("//*[@authenticate='true']/descendant-or-self::node()").evaluate(
                myRef.here.ownerDocument, XPathConstants.NODESET
            )
            if (nodeSet !is NodeList)
                throw Exception("invalid type")
            if (nodeSet.length <= 0) {
                throw Exception("no nodes to sign")
            }
            val nodeList = ArrayList<Node>()
            for (i in 0 until nodeSet.length) {
                val node = nodeSet.item(i)
                nodeList.add(node)
            }
            return NodeSetData { nodeList.iterator() }
        }
    }

    /**
     * Validator for EBICS messages.
     */
    private val validator = try {
    } catch (e: SAXException) {
        e.printStackTrace()
        throw e
    }

    companion object {
        private var cachedEbicsValidator: Validator? = null
        private fun getEbicsValidator(): Validator {
            val currentValidator = cachedEbicsValidator
            if (currentValidator != null)
                return currentValidator
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
                    val res = classLoader.getResourceAsStream("xsd/$systemId") ?: return null
                    return DOMInputImpl(publicId, systemId, baseUri, res, "UTF-8")
                }
            }
            val schemaInputs: Array<Source> = listOf(
                "xsd/ebics_H004.xsd",
                "xsd/ebics_hev.xsd",
                "xsd/camt.052.001.02.xsd",
                "xsd/camt.053.001.02.xsd",
                "xsd/camt.054.001.02.xsd",
                "xsd/pain.001.001.03.xsd"
            ).map {
                val stream =
                    classLoader.getResourceAsStream(it) ?: throw FileNotFoundException("Schema file $it not found.")
                StreamSource(stream)
            }.toTypedArray()
            val bundle = sf.newSchema(schemaInputs)
            val newValidator = bundle.newValidator()
            cachedEbicsValidator = newValidator
            return newValidator
        }

        /**
         *
         * @param xmlDoc the XML document to validate
         * @return true when validation passes, false otherwise
         */
        fun validate(xmlDoc: StreamSource): Boolean {
            try {
                getEbicsValidator().validate(xmlDoc)
            } catch (e: Exception) {
                logger.warn("Validation failed: ${e}")
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
                getEbicsValidator().validate(DOMSource(domDocument))
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
            return validate(xmlSource)
        }

        inline fun <reified T> convertJaxbToString(obj: T): String {
            val sw = StringWriter()
            val jc = JAXBContext.newInstance(T::class.java)
            val m = jc.createMarshaller()
            m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true)
            m.setProperty("com.sun.xml.bind.namespacePrefixMapper", DefaultNamespaces())
            m.marshal(obj, sw)
            return sw.toString()
        }

        inline fun <reified T> convertJaxbToDocument(obj: T): Document {
            val dbf: DocumentBuilderFactory = DocumentBuilderFactory.newInstance()
            dbf.isNamespaceAware = true
            val doc = dbf.newDocumentBuilder().newDocument()
            val jc = JAXBContext.newInstance(T::class.java)
            val m = jc.createMarshaller()
            m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true)
            m.setProperty("com.sun.xml.bind.namespacePrefixMapper", DefaultNamespaces())
            m.marshal(obj, doc)
            return doc
        }

        /**
         * Convert a XML string to the JAXB representation.
         *
         * @param documentString the string to convert into JAXB.
         * @return the JAXB object reflecting the original XML document.
         */
        inline fun <reified T> convertStringToJaxb(documentString: String): JAXBElement<T> {
            val jc = JAXBContext.newInstance(T::class.java)
            val u = jc.createUnmarshaller()
            return u.unmarshal(            /* Marshalling the object into the document.  */
                StreamSource(StringReader(documentString)),
                T::class.java
            )
        }

        /**
         * Extract String from DOM.
         *
         * @param document the DOM to extract the string from.
         * @return the final String, or null if errors occur.
         */
        fun convertDomToString(document: Document): String {
            /* Make Transformer.  */
            val tf = TransformerFactory.newInstance()
            val t = tf.newTransformer()

            //t.setOutputProperty(OutputKeys.INDENT, "yes")

            /* Make string writer.  */
            val sw = StringWriter()

            /* Extract string.  */
            t.transform(DOMSource(document), StreamResult(sw))
            return sw.toString()
        }

        /**
         * Convert a node to a string without the XML declaration or
         * indentation.
         */
        fun convertNodeToString(node: Node): String {
            /* Make Transformer.  */
            val tf = TransformerFactory.newInstance()
            val t = tf.newTransformer()
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            /* Make string writer.  */
            val sw = StringWriter()
            /* Extract string.  */
            t.transform(DOMSource(node), StreamResult(sw))
            return sw.toString()
        }

        /**
         * Convert a DOM document to the JAXB representation.
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

        fun signEbicsResponse(ebicsResponse: EbicsResponse, privateKey: RSAPrivateCrtKey): String {
            val doc = convertJaxbToDocument(ebicsResponse)
            signEbicsDocument(doc, privateKey)
            val signedDoc = XMLUtil.convertDomToString(doc)
            logger.debug("response: $signedDoc")
            return signedDoc
        }

        /**
         * Sign an EBICS document with the authentication and identity signature.
         */
        fun signEbicsDocument(doc: Document, signingPriv: PrivateKey): Unit {
            val xpath = XPathFactory.newInstance().newXPath()
            xpath.namespaceContext = object : NamespaceContext {
                override fun getNamespaceURI(p0: String?): String {
                    return when (p0) {
                        "ebics" -> "urn:org:ebics:H004"
                        else -> throw IllegalArgumentException()
                    }
                }

                override fun getPrefix(p0: String?): String {
                    throw UnsupportedOperationException()
                }

                override fun getPrefixes(p0: String?): MutableIterator<String> {
                    throw UnsupportedOperationException()
                }
            }
            val authSigNode = xpath.compile("/*[1]/ebics:AuthSignature").evaluate(doc, XPathConstants.NODE)
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
            val signatureMethod = fac.newSignatureMethod("http://www.w3.org/2001/04/xmldsig-more#rsa-sha256", null)
            val si: SignedInfo = fac.newSignedInfo(canon, signatureMethod, listOf(ref))
            val sig: XMLSignature = fac.newXMLSignature(si, null)
            val dsc = DOMSignContext(signingPriv, authSigNode)
            dsc.defaultNamespacePrefix = "ds"
            dsc.uriDereferencer = EbicsSigUriDereferencer()
            dsc.setProperty("javax.xml.crypto.dsig.cacheReference", true)
            sig.sign(dsc)
            println("canon data: " + sig.signedInfo.canonicalizedData.readAllBytes().toString(Charsets.UTF_8))
            val innerSig = authSigNode.firstChild
            while (innerSig.hasChildNodes()) {
                authSigNode.appendChild(innerSig.firstChild)
            }
            authSigNode.removeChild(innerSig)
        }

        fun verifyEbicsDocument(doc: Document, signingPub: PublicKey): Boolean {
            val xpath = XPathFactory.newInstance().newXPath()
            xpath.namespaceContext = object : NamespaceContext {
                override fun getNamespaceURI(p0: String?): String {
                    return when (p0) {
                        "ebics" -> "urn:org:ebics:H004"
                        else -> throw IllegalArgumentException()
                    }
                }

                override fun getPrefix(p0: String?): String {
                    throw UnsupportedOperationException()
                }

                override fun getPrefixes(p0: String?): MutableIterator<String> {
                    throw UnsupportedOperationException()
                }
            }
            val doc2: Document = doc.cloneNode(true) as Document
            val authSigNode = xpath.compile("/*[1]/ebics:AuthSignature").evaluate(doc2, XPathConstants.NODE)
            if (authSigNode !is Node)
                throw java.lang.Exception("no AuthSignature")
            val sigEl = doc2.createElementNS("http://www.w3.org/2000/09/xmldsig#", "ds:Signature")
            authSigNode.parentNode.insertBefore(sigEl, authSigNode)
            while (authSigNode.hasChildNodes()) {
                sigEl.appendChild(authSigNode.firstChild)
            }
            authSigNode.parentNode.removeChild(authSigNode)
            val fac = XMLSignatureFactory.getInstance("DOM")
            val dvc = DOMValidateContext(signingPub, sigEl)
            dvc.setProperty("javax.xml.crypto.dsig.cacheReference", true)
            dvc.uriDereferencer = EbicsSigUriDereferencer()
            val sig = fac.unmarshalXMLSignature(dvc)
            // FIXME: check that parameters are okay!s
            val valResult = sig.validate(dvc)
            sig.signedInfo.references[0].validate(dvc)
            return valResult
        }

        fun getNodeFromXpath(doc: Document, query: String): Node {
            val xpath = XPathFactory.newInstance().newXPath()
            val ret = xpath.evaluate(query, doc, XPathConstants.NODE)
                ?: throw EbicsProtocolError(HttpStatusCode.NotFound, "Unsuccessful XPath query string: $query")
            return ret as Node
        }

        fun getStringFromXpath(doc: Document, query: String): String {
            val xpath = XPathFactory.newInstance().newXPath()
            val ret = xpath.evaluate(query, doc, XPathConstants.STRING) as String
            if (ret.isEmpty()) {
                throw EbicsProtocolError(HttpStatusCode.NotFound, "Unsuccessful XPath query string: $query")
            }
            return ret
        }
    }
}

fun Document.pickString(xpath: String): String {
    return XMLUtil.getStringFromXpath(this, xpath)
}

fun Document.pickStringWithRootNs(xpathQuery: String): String {
    val doc = this
    val xpath = XPathFactory.newInstance().newXPath()
    xpath.namespaceContext = object : NamespaceContext {
        override fun getNamespaceURI(p0: String?): String {
            return when (p0) {
                "root" -> doc.documentElement.namespaceURI
                else -> throw IllegalArgumentException()
            }
        }

        override fun getPrefix(p0: String?): String {
            throw UnsupportedOperationException()
        }

        override fun getPrefixes(p0: String?): MutableIterator<String> {
            throw UnsupportedOperationException()
        }
    }
    val ret = xpath.evaluate(xpathQuery, this, XPathConstants.STRING) as String
    if (ret.isEmpty()) {
        throw EbicsProtocolError(HttpStatusCode.NotFound, "Unsuccessful XPath query string: $xpathQuery")
    }
    return ret
}