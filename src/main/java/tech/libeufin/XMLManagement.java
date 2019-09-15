package tech.libeufin;

import org.xml.sax.SAXException;
import java.io.IOException;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.transform.Source;
import javax.xml.validation.*; // has SchemaFactory
import java.io.File;

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

    public boolean validate(Source xmlDoc){
        try{
            this.validator.validate(xmlDoc);
        } catch (SAXException e) {
            return false;
        } catch (IOException e) {
            System.out.println("Could not pass XML to validator.");
            return false;
        }
        return true;
    }
};