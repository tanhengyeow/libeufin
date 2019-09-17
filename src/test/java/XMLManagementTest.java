import org.junit.Test;
import org.w3c.dom.Element;
import tech.libeufin.XMLManagement;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import static org.junit.Assert.*;
import org.w3c.dom.Document;

@XmlRootElement(name="SimpleJAXBTest")
class SimpleJAXBTest {}

public class XMLManagementTest {

    @Test
    public void XMLManagementTest(){
        XMLManagement xm = new XMLManagement();

        /* Load XML from disk.  */
        ClassLoader classLoader = this.getClass().getClassLoader();
        Source ebics_hev_sample = new StreamSource(classLoader.getResourceAsStream("ebics_hev.xml"));
        assertTrue(xm.validate(ebics_hev_sample));

        /* Load XML from string.  */
        InputStream is = new ByteArrayInputStream("<InvalidXML>".getBytes());
        Source ebics_from_string = new StreamSource(is);
        assertFalse(xm.validate(ebics_from_string));

        assertFalse(xm.validate("<moreInvalidXML>"));

        /* Parse XML string into DOM */
        Document document = xm.parseStringIntoDOM("<root></root>");
        Element documentElement = document.getDocumentElement();
        assertTrue("root" == documentElement.getTagName());

        /* Make XML DOM from Java object (JAXB) */
        Document simpleRoot = xm.parseObjectIntoDocument(new SimpleJAXBTest());
        Element simpleRootDocumentElement = simpleRoot.getDocumentElement();
        assertTrue("SimpleJAXBTest" == simpleRootDocumentElement.getTagName());

        /* Serialize the DOM into string.  */
        String simpleRootString = XMLManagement.getStringFromDocument(simpleRoot);
        System.out.println(simpleRootString);
    }
}
