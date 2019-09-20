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
import javax.xml.validation.*; // has SchemaFactory
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import tech.libeufin.messages.HEVResponseDataType;

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
            // bundle = sf.newSchema(schemas);
            // validator = bundle.newValidator();
            sf.newSchema()
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
    // static public Document parseStringIntoDom(String xmlString) {
    fun parseStringIntoDom(xmlString: String): Document? {

        // DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        val factory = DocumentBuilderFactory.newInstance()

        try {

            // InputStream xmlInputStream = new ByteArrayInputStream(xmlString.getBytes());
            val xmlInputStream = ByteArrayInputStream(xmlString.toByteArray())
            // Source xmlSource = new StreamSource(xmlInputStream);

            // DocumentBuilder builder = factory.newDocumentBuilder();
            val builder = factory.newDocumentBuilder();
            // Document document = builder.parse(new InputSource(xmlInputStream));
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
    fun validate(xmlDoc: Source): Boolean {
        try{
            validator?.validate(xmlDoc);
        } catch (e: SAXException) {
            e.printStackTrace()
            return false;
        } catch (e: IOException) {
            e.printStackTrace()
            return false;
        }
        return true;
    }

    /**
     * Craft object to be passed to the XML validator.
     * @param xmlString XML body, as read from the POST body.
     * @return InputStream object, as wanted by the validator.
     */
    fun validate(xmlString: String): Boolean {
        // InputStream xmlInputStream = new ByteArrayInputStream(xmlString.getBytes());
        val xmlInputStream = ByteArrayInputStream(xmlString.toByteArray())
        // Source xmlSource = new StreamSource(xmlInputStream);
        val xmlSource = StreamSource(xmlInputStream)
        return validate(xmlSource);
    }

    /**
     * Return the DOM representation of the Java object, using the JAXB
     * interface.  FIXME: narrow input type to JAXB type!
     *
     * @param object to be transformed into DOM.  Typically, the object
     *               has already got its setters called.
     * @return the DOM Document, or null (if errors occur).
     */
    // static public Document convertJaxbToDom(JAXBElement<?> object) {
    fun convertJaxbToDom(obj: JAXBElement<Unit>): Document? {

        try {
            // JAXBContext jc = JAXBContext.newInstance("tech.libeufin.messages");
            val jc = JAXBContext.newInstance("tech.libeufin.messages");

            /* Make the target document.  */
            // DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            val dbf = DocumentBuilderFactory.newInstance()
            // DocumentBuilder db = dbf.newDocumentBuilder();
            val db = dbf.newDocumentBuilder();
            // Document document = db.newDocument();
            val document = db.newDocument();

            /* Marshalling the object into the document.  */
            // Marshaller m = jc.createMarshaller();
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
    // static public String getStringFromDocument(Document document){
    fun getStringFromDocument(document: Document): String? {

        try {
            /* Make Transformer.  */
            // TransformerFactory tf = TransformerFactory.newInstance();
            val tf = TransformerFactory.newInstance();
            val t = tf.newTransformer();
            // Transformer t = tf.newTransformer();
            // t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            t.setOutputProperty(OutputKeys.INDENT, "no");

            /* Make string writer.  */
            val sw = StringWriter();
            // StringWriter sw = new StringWriter();

            /* Extract string.  */
            // t.transform(new DOMSource(document), new StreamResult(sw));
            t.transform(DOMSource(document), StreamResult(sw))
            // String output = sw.toString();
            val output = sw.toString()

            return output;

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
    // public static String getStringFromJaxb(JAXBElement<?> object){
    fun <T>getStringFromJaxb(obj: JAXBElement<T>): String? {
        try {
            // JAXBContext jc = JAXBContext.newInstance("tech.libeufin.messages");
            val jc = JAXBContext.newInstance("tech.libeufin.messages")
            // StringWriter sw = new StringWriter();
            val sw = StringWriter();

            /* Getting the string.  */
            // Marshaller m = jc.createMarshaller();
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