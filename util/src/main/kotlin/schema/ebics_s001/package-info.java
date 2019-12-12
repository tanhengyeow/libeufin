/**
 * This package-info.java file defines the default namespace for the JAXB bindings
 * defined in the package.
 */

@XmlSchema(
        namespace = "http://www.ebics.org/S001",
        elementFormDefault = XmlNsForm.QUALIFIED
)
package schema.ebics_s001;

import javax.xml.bind.annotation.XmlNsForm;
import javax.xml.bind.annotation.XmlSchema;