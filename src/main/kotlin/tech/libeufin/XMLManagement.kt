package tech.libeufin;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.*;
import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.*; // has SchemaFactory()
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;

/**
 * This class takes care of importing XSDs and validate
 * XMLs against those.
 */

public class XMLManagement() {

    /**
     * Bundle of all the XSDs loaded in memory, from disk.
     */
    private val bundle = {
        val classLoader = ClassLoader.getSystemClassLoader()
        val ebicsHevPath = classLoader.getResourceAsStream("ebics_hev.xsd");
        val schemas = arrayOf(StreamSource(ebicsHevPath)
            // other StreamSources for other schemas here ..
        )

        try {
            val sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            sf.newSchema(schemas)
        } catch (e: SAXException) {
            e.printStackTrace();
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
    // public boolean validate(Source xmlDoc){
    private fun validate(xmlDoc: StreamSource): Boolean {
        try {
            validator?.validate(xmlDoc)


        } catch (e: SAXException) {
            e.printStackTrace()
            return false;
        } catch (e: IOException) {
            e.printStackTrace()
            return false;
        }
        return true
    }

    /**
     * Craft object to be passed to the XML validator.
     * @param xmlString XML body, as read from the POST body.
     * @return InputStream object, as wanted by the validator.
     */
    fun validateFromString(xmlString: java.lang.String): Boolean {
        val xmlInputStream: InputStream = ByteArrayInputStream(xmlString.bytes)
        var xmlSource: StreamSource = StreamSource(xmlInputStream)
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
            val jc = JAXBContext.newInstance("tech.libeufin.messages");

            /* Make the target document.  */
            val dbf = DocumentBuilderFactory.newInstance()
            val db = dbf.newDocumentBuilder();
            val document = db.newDocument();

            /* Marshalling the object into the document.  */
            val m = jc.createMarshaller()
            m.marshal(obj, document); // document absorbed XML!
            return document;

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
            val t = tf.newTransformer();

            t.setOutputProperty(OutputKeys.INDENT, "no");

            /* Make string writer.  */
            val sw = StringWriter();

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
     * @param object the JAXB instance
     * @return String representation of @a object, or null if errors occur
     */
    fun <T>getStringFromJaxb(obj: JAXBElement<T>): String? {
        try {
            val jc = JAXBContext.newInstance("tech.libeufin.messages")
            val sw = StringWriter();

            /* Getting the string.  */
            val m = jc.createMarshaller();
            m.marshal(obj, sw);
            m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            return sw.toString();

        } catch (e: JAXBException) {
            e.printStackTrace();
            return null;
        }
    }
};