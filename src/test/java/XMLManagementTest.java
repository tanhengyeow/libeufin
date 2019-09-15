import org.junit.Test;
import tech.libeufin.XMLManagement;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import static org.junit.Assert.*;

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
    }
}
