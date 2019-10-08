
package tech.libeufin.messages;

import javax.xml.bind.JAXBElement;
import javax.xml.bind.annotation.XmlElementDecl;
import javax.xml.bind.annotation.XmlRegistry;
import javax.xml.namespace.QName;


/**
 * This object contains factory methods for each 
 * Java content interface and Java element interface 
 * generated in the tech.libeufin package. 
 * <p>An ObjectFactory allows you to programatically 
 * construct new instances of the Java representation 
 * for XML content. The Java representation of XML 
 * content can consist of schema derived interfaces 
 * and classes representing the binding of schema 
 * type definitions, element declarations and model 
 * groups.  Factory methods for each of these are 
 * provided in this class.
 * 
 */
@XmlRegistry
public class ObjectFactory {

    private final static QName _EbicsHEVResponse_QNAME = new QName("http://www.ebics.org/H000", "ebicsHEVResponse");
    private final static QName _EbicsHEVRequest_QNAME = new QName("http://www.ebics.org/H000", "ebicsHEVRequest");

    /**
     * Create a new ObjectFactory that can be used to create new instances of schema derived classes for package: tech.libeufin
     * 
     */
    public ObjectFactory() {
    }

    /**
     * Create an instance of {@link HEVResponseDataType }
     * 
     */
    public HEVResponseDataType createHEVResponseDataType() {
        return new HEVResponseDataType();
    }

    /**
     * Create an instance of {@link HEVRequestDataType }
     * 
     */
    public HEVRequestDataType createHEVRequestDataType() {
        return new HEVRequestDataType();
    }

    /**
     * Create an instance of {@link SystemReturnCodeType }
     * 
     */
    public SystemReturnCodeType createSystemReturnCodeType() {
        return new SystemReturnCodeType();
    }

    /**
     * Create an instance of {@link HEVResponseDataType.VersionNumber }
     * 
     */
    public HEVResponseDataType.VersionNumber createHEVResponseDataTypeVersionNumber() {
        return new HEVResponseDataType.VersionNumber();
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link HEVResponseDataType }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://www.ebics.org/H000", name = "ebicsHEVResponse")
    public JAXBElement<HEVResponseDataType> createEbicsHEVResponse(HEVResponseDataType value) {
        return new JAXBElement<HEVResponseDataType>(_EbicsHEVResponse_QNAME, HEVResponseDataType.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link HEVRequestDataType }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://www.ebics.org/H000", name = "ebicsHEVRequest")
    public JAXBElement<HEVRequestDataType> createEbicsHEVRequest(HEVRequestDataType value) {
        return new JAXBElement<HEVRequestDataType>(_EbicsHEVRequest_QNAME, HEVRequestDataType.class, null, value);
    }

}
