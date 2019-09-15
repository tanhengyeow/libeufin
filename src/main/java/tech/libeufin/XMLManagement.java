package tech.libeufin;

import org.xml.sax.SAXException;
import java.io.IOException;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.transform.Source;
import javax.xml.validation.*; // has SchemaFactory
import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

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

};