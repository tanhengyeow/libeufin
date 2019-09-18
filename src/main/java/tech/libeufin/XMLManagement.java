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

public class XMLManagement {

    /**
     * Bundle of all the XSDs loaded in memory.
     */
    Schema bundle;
    Validator validator;

    /**
     * Load all the XSDs from disk.
     */
    public XMLManagement(){
        ClassLoader classLoader = this.getClass().getClassLoader();

        File ebics_hev_file = new File(classLoader.getResource("ebics_hev.xsd").getFile());
        Source schemas[] = {new StreamSource(ebics_hev_file)
                // other StreamSources for other schemas here ..
        };

        try {
            SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            this.bundle = sf.newSchema(schemas);
            this.validator = this.bundle.newValidator();
        } catch (SAXException e) {
            System.out.println("SAX exception shall never happen here " + "(" + e + ")");
        }
    }

    /**
     * Parse string into XML DOM.
     * @param xmlString the string to parse.
     * @return the DOM representing @a xmlString
     */
    static public Document parseStringIntoDom(String xmlString) {

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

        try {

            InputStream xmlInputStream = new ByteArrayInputStream(xmlString.getBytes());
            // Source xmlSource = new StreamSource(xmlInputStream);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new InputSource(xmlInputStream));

            return document;

        } catch (ParserConfigurationException e) {
            System.out.println("Could not parse string into DOM: " + e);
        } catch (SAXException e) {
            System.out.println("Could not parse string into DOM: " + e);
        } catch (IOException e) {
            System.out.println("Could not parse string into DOM: " + e);
        }

        return null;
    }

    /**
     *
     * @param xmlDoc the XML document to validate
     * @return true when validation passes, false otherwise
     */
    public boolean validate(Source xmlDoc){
        try{
            this.validator.validate(xmlDoc);
        } catch (SAXException e) {
            System.out.println("Validation did not pass " + e);
            return false;
        } catch (IOException e) {
            System.out.println("Could not pass XML to validator.");
            return false;
        }
        return true;
    }

    /**
     * Craft object to be passed to the XML validator.
     * @param xmlString XML body, as read from the POST body.
     * @return InputStream object, as wanted by the validator.
     */
    public boolean validate(String xmlString){
        InputStream xmlInputStream = new ByteArrayInputStream(xmlString.getBytes());
        Source xmlSource = new StreamSource(xmlInputStream);
        return this.validate(xmlSource);
    }

    /**
     * Return the DOM representation of the Java object, using the JAXB
     * interface.  FIXME: narrow input type to JAXB type!
     *
     * @param object to be transformed into DOM.  Typically, the object
     *               has already got its setters called.
     * @return the DOM Document, or null (if errors occur).
     */
    static public Document convertJaxbToDom(JAXBElement<?> object) {

        try {
            JAXBContext jc = JAXBContext.newInstance("tech.libeufin.messages");

            /* Make the target document.  */
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document document = db.newDocument();

            /* Marshalling the object into the document.  */
            Marshaller m = jc.createMarshaller();
            m.marshal(object, document); // document absorbed XML!
            return document;

        } catch (JAXBException e) {
            System.out.println(e);
        } catch (ParserConfigurationException e) {
            System.out.println(e);
        }

        return null;
    }

    /**
     * Extract String from DOM.
     *
     * @param document the DOM to extract the string from.
     * @return the final String, or null if errors occur.
     */
    static public String getStringFromDocument(Document document){

        try {
            /* Make Transformer.  */
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer t = tf.newTransformer();
            // t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            t.setOutputProperty(OutputKeys.INDENT, "no");

            /* Make string writer.  */
            StringWriter sw = new StringWriter();

            /* Extract string.  */
            t.transform(new DOMSource(document), new StreamResult(sw));
            String output = sw.toString();

            return output;

        } catch (TransformerConfigurationException e) {
            System.out.println(e);
        } catch (TransformerException e) {
            System.out.println(e);
        }

        return null;
    }

    /**
     * Extract String from JAXB.
     *
     * @param object the JAXB instance
     * @return String representation of @a object, or null if errors occur
     */
    public static String getStringFromJaxb(JAXBElement<?> object){
        try {
            JAXBContext jc = JAXBContext.newInstance("tech.libeufin.messages");
            StringWriter sw = new StringWriter();

            /* Getting the string.  */
            Marshaller m = jc.createMarshaller();
            m.marshal(object, sw);
            m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);

            return sw.toString();
        } catch (JAXBException e) {
            e.printStackTrace();
            return null;
        }
    }
};