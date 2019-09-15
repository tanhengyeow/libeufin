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

        Source ebics_hev_file = new StreamSource(new File("resources/ebics_hev.xsd"));
        Source xsds[] = {ebics_hev_file};
        SchemaFactory sf = new SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI);

        try{
            this.bundle = sf.newSchema(xsds);
            this.validator = this.bundle.newValidator();
        } catch (IOException e){
            System.out.println("Could not import all XSDs from disk" + "(" + e + ")");
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

    }
};