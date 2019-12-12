/**
 * This package-info.java file defines the default namespace for the JAXB bindings
 * defined in the package.
 */

@XmlSchema(
        namespace = "http://www.ebics.org/H000",
        elementFormDefault = XmlNsForm.QUALIFIED
)
package tech.libeufin.util.ebics_hev;

import javax.xml.bind.annotation.XmlNsForm;
import javax.xml.bind.annotation.XmlSchema;
