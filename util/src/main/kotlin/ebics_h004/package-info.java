/**
 * This package-info.java file defines the default namespace for the JAXB bindings
 * defined in the package.
 */

@XmlSchema(
        namespace = "urn:org:ebics:H004",
        elementFormDefault = XmlNsForm.QUALIFIED
)
package tech.libeufin.util.ebics_h004;
import javax.xml.bind.annotation.XmlSchema;
import javax.xml.bind.annotation.XmlNsForm;