
package tech.libeufin.messages.ebics.hev;

import java.util.ArrayList;
import java.util.List;
import javax.xml.bind.annotation.*;
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import org.w3c.dom.Element;


/**
 * Data type for Request data
 * 
 * <p>Java class for HEVResponseDataType complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="HEVResponseDataType">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence>
 *         &lt;element name="SystemReturnCode" type="{http://www.ebics.org/H000}SystemReturnCodeType"/>
 *         &lt;element name="VersionNumber" maxOccurs="unbounded" minOccurs="0">
 *           &lt;complexType>
 *             &lt;simpleContent>
 *               &lt;extension base="&lt;http://www.ebics.org/H000>VersionNumberType">
 *                 &lt;attribute name="ProtocolVersion" use="required" type="{http://www.ebics.org/H000}ProtocolVersionType" />
 *               &lt;/extension>
 *             &lt;/simpleContent>
 *           &lt;/complexType>
 *         &lt;/element>
 *         &lt;any processContents='lax' namespace='##other' maxOccurs="unbounded" minOccurs="0"/>
 *       &lt;/sequence>
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "HEVResponseDataType", namespace = "http://www.ebics.org/H000", propOrder = {
    "systemReturnCode",
    "versionNumber",
    "any"
})
@XmlRootElement(name = "ebicsHEVResponse")
public class HEVResponseDataType {

    @XmlElement(name = "SystemReturnCode", namespace = "http://www.ebics.org/H000", required = true)
    protected SystemReturnCodeType systemReturnCode;
    @XmlElement(name = "VersionNumber", namespace = "http://www.ebics.org/H000")
    protected List<HEVResponseDataType.VersionNumber> versionNumber;
    @XmlAnyElement(lax = true)
    protected List<Object> any;

    /**
     * Gets the value of the systemReturnCode property.
     * 
     * @return
     *     possible object is
     *     {@link SystemReturnCodeType }
     *     
     */
    public SystemReturnCodeType getSystemReturnCode() {
        return systemReturnCode;
    }

    /**
     * Sets the value of the systemReturnCode property.
     * 
     * @param value
     *     allowed object is
     *     {@link SystemReturnCodeType }
     *     
     */
    public void setSystemReturnCode(SystemReturnCodeType value) {
        this.systemReturnCode = value;
    }

    /**
     * Gets the value of the versionNumber property.
     * 
     * <p>
     * This accessor method returns a reference to the live list,
     * not a snapshot. Therefore any modification you make to the
     * returned list will be present inside the JAXB object.
     * This is why there is not a <CODE>set</CODE> method for the versionNumber property.
     * 
     * <p>
     * For example, to add a new item, do as follows:
     * <pre>
     *    getVersionNumber().add(newItem);
     * </pre>
     * 
     * 
     * <p>
     * Objects of the following type(s) are allowed in the list
     * {@link HEVResponseDataType.VersionNumber }
     * 
     * 
     */
    public List<HEVResponseDataType.VersionNumber> getVersionNumber() {
        if (versionNumber == null) {
            versionNumber = new ArrayList<HEVResponseDataType.VersionNumber>();
        }
        return this.versionNumber;
    }

    /**
     * Gets the value of the any property.
     * 
     * <p>
     * This accessor method returns a reference to the live list,
     * not a snapshot. Therefore any modification you make to the
     * returned list will be present inside the JAXB object.
     * This is why there is not a <CODE>set</CODE> method for the any property.
     * 
     * <p>
     * For example, to add a new item, do as follows:
     * <pre>
     *    getAny().add(newItem);
     * </pre>
     * 
     * 
     * <p>
     * Objects of the following type(s) are allowed in the list
     * {@link Object }
     * {@link Element }
     * 
     * 
     */
    public List<Object> getAny() {
        if (any == null) {
            any = new ArrayList<Object>();
        }
        return this.any;
    }


    /**
     * <p>Java class for anonymous complex type.
     * 
     * <p>The following schema fragment specifies the expected content contained within this class.
     * 
     * <pre>
     * &lt;complexType>
     *   &lt;simpleContent>
     *     &lt;extension base="&lt;http://www.ebics.org/H000>VersionNumberType">
     *       &lt;attribute name="ProtocolVersion" use="required" type="{http://www.ebics.org/H000}ProtocolVersionType" />
     *     &lt;/extension>
     *   &lt;/simpleContent>
     * &lt;/complexType>
     * </pre>
     * 
     * 
     */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "", propOrder = {
        "value"
    })
    public static class VersionNumber {

        @XmlValue
        @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
        protected String value;
        @XmlAttribute(name = "ProtocolVersion", required = true)
        @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
        protected String protocolVersion;

        /**
         * Datatype for a release number 
         * 
         * @return
         *     possible object is
         *     {@link String }
         *     
         */
        public String getValue() {
            return value;
        }

        /**
         * Sets the value of the value property.
         * 
         * @param value
         *     allowed object is
         *     {@link String }
         *     
         */
        public void setValue(String value) {
            this.value = value;
        }

        /**
         * Gets the value of the protocolVersion property.
         * 
         * @return
         *     possible object is
         *     {@link String }
         *     
         */
        public String getProtocolVersion() {
            return protocolVersion;
        }

        /**
         * Sets the value of the protocolVersion property.
         * 
         * @param value
         *     allowed object is
         *     {@link String }
         *     
         */
        public void setProtocolVersion(String value) {
            this.protocolVersion = value;
        }

    }

}
