import org.junit.Test;
import org.w3c.dom.Element;
import tech.libeufin.XMLManagement;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import static org.junit.Assert.*;
import org.w3c.dom.Document;
import tech.libeufin.messages.HEVResponse;
import tech.libeufin.messages.HEVResponseDataType;

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
        Document document = xm.parseStringIntoDom("<root></root>");
        Element documentElement = document.getDocumentElement();
        assertTrue("root".equals(documentElement.getTagName()));

        /* Make XML DOM from Java object (JAXB) */
        HEVResponse hr = new HEVResponse("rc", "rt");
        JAXBElement<HEVResponseDataType> hrObject = hr.makeHEVResponse();
        Document hevDocument = XMLManagement.convertJaxbToDom(hrObject);
        assertTrue("ns2:ebicsHEVResponse".equals(hevDocument.getDocumentElement().getTagName()));
    }
}
