import org.junit.Test;
import tech.libeufin.XMLManagement;
import java.io.File;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import static org.junit.Assert.*;

public class XMLManagementTest {

    @Test
    public void XMLManagementTest(){
        XMLManagement xm = new XMLManagement();
        ClassLoader classLoader = this.getClass().getClassLoader();
        Source ebics_hev_sample = new StreamSource(classLoader.getResourceAsStream("ebics_hev.xsd"));
        assertTrue(xm.validate(ebics_hev_sample));
    }
}
