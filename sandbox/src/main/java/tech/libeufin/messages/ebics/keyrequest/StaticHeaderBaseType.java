//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.8-b130911.1802 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2019.10.10 at 06:36:01 PM CEST 
//


package tech.libeufin.messages.ebics.keyrequest;

import java.util.ArrayList;
import java.util.List;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAnyElement;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementRef;
import javax.xml.bind.annotation.XmlSchemaType;
import javax.xml.bind.annotation.XmlSeeAlso;
import javax.xml.bind.annotation.XmlType;
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter;
import javax.xml.bind.annotation.adapters.HexBinaryAdapter;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import javax.xml.datatype.XMLGregorianCalendar;
import org.w3c.dom.Element;


/**
 * Datentyp für den statischen EBICS-Header (allgemein).
 * 
 * <p>Java class for StaticHeaderBaseType complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="StaticHeaderBaseType">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence>
 *         &lt;element name="HostID" type="{urn:org:ebics:H004}HostIDType"/>
 *         &lt;element name="Nonce" type="{urn:org:ebics:H004}NonceType" minOccurs="0"/>
 *         &lt;element name="Timestamp" type="{urn:org:ebics:H004}TimestampType" minOccurs="0"/>
 *         &lt;element name="PartnerID" type="{urn:org:ebics:H004}PartnerIDType"/>
 *         &lt;element name="UserID" type="{urn:org:ebics:H004}UserIDType"/>
 *         &lt;element name="SystemID" type="{urn:org:ebics:H004}UserIDType" minOccurs="0"/>
 *         &lt;element name="Product" type="{urn:org:ebics:H004}ProductElementType" minOccurs="0"/>
 *         &lt;element name="OrderDetails" type="{urn:org:ebics:H004}OrderDetailsType"/>
 *         &lt;element name="SecurityMedium" type="{urn:org:ebics:H004}SecurityMediumType"/>
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
@XmlType(name = "StaticHeaderBaseType", propOrder = {
    "hostID",
    "nonce",
    "timestamp",
    "partnerID",
    "userID",
    "systemID",
    "product",
    "orderDetails",
    "securityMedium",
    "any"
})
@XmlSeeAlso({
    NoPubKeyDigestsRequestStaticHeaderType.class,
    UnsignedRequestStaticHeaderType.class,
    UnsecuredRequestStaticHeaderType.class
})
public abstract class StaticHeaderBaseType {

    @XmlElement(name = "HostID", required = true)
    @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
    @XmlSchemaType(name = "token")
    protected String hostID;
    @XmlElement(name = "Nonce", type = String.class)
    @XmlJavaTypeAdapter(HexBinaryAdapter.class)
    @XmlSchemaType(name = "hexBinary")
    protected byte[] nonce;
    @XmlElement(name = "Timestamp")
    @XmlSchemaType(name = "dateTime")
    protected XMLGregorianCalendar timestamp;
    @XmlElement(name = "PartnerID", required = true)
    @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
    @XmlSchemaType(name = "token")
    protected String partnerID;
    @XmlElement(name = "UserID", required = true)
    @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
    @XmlSchemaType(name = "token")
    protected String userID;
    @XmlElement(name = "SystemID")
    @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
    @XmlSchemaType(name = "token")
    protected String systemID;
    @XmlElementRef(name = "Product", namespace = "urn:org:ebics:H004", type = JAXBElement.class, required = false)
    protected JAXBElement<ProductElementType> product;
    @XmlElement(name = "OrderDetails", required = true)
    protected OrderDetailsType orderDetails;
    @XmlElement(name = "SecurityMedium", required = true)
    protected String securityMedium;
    @XmlAnyElement(lax = true)
    protected List<Object> any;

    /**
     * Gets the value of the hostID property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getHostID() {
        return hostID;
    }

    /**
     * Sets the value of the hostID property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setHostID(String value) {
        this.hostID = value;
    }

    /**
     * Gets the value of the nonce property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public byte[] getNonce() {
        return nonce;
    }

    /**
     * Sets the value of the nonce property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setNonce(byte[] value) {
        this.nonce = value;
    }

    /**
     * Gets the value of the timestamp property.
     * 
     * @return
     *     possible object is
     *     {@link XMLGregorianCalendar }
     *     
     */
    public XMLGregorianCalendar getTimestamp() {
        return timestamp;
    }

    /**
     * Sets the value of the timestamp property.
     * 
     * @param value
     *     allowed object is
     *     {@link XMLGregorianCalendar }
     *     
     */
    public void setTimestamp(XMLGregorianCalendar value) {
        this.timestamp = value;
    }

    /**
     * Gets the value of the partnerID property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getPartnerID() {
        return partnerID;
    }

    /**
     * Sets the value of the partnerID property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setPartnerID(String value) {
        this.partnerID = value;
    }

    /**
     * Gets the value of the userID property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getUserID() {
        return userID;
    }

    /**
     * Sets the value of the userID property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setUserID(String value) {
        this.userID = value;
    }

    /**
     * Gets the value of the systemID property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getSystemID() {
        return systemID;
    }

    /**
     * Sets the value of the systemID property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setSystemID(String value) {
        this.systemID = value;
    }

    /**
     * Gets the value of the product property.
     * 
     * @return
     *     possible object is
     *     {@link JAXBElement }{@code <}{@link ProductElementType }{@code >}
     *     
     */
    public JAXBElement<ProductElementType> getProduct() {
        return product;
    }

    /**
     * Sets the value of the product property.
     * 
     * @param value
     *     allowed object is
     *     {@link JAXBElement }{@code <}{@link ProductElementType }{@code >}
     *     
     */
    public void setProduct(JAXBElement<ProductElementType> value) {
        this.product = value;
    }

    /**
     * Gets the value of the orderDetails property.
     * 
     * @return
     *     possible object is
     *     {@link OrderDetailsType }
     *     
     */
    public OrderDetailsType getOrderDetails() {
        return orderDetails;
    }

    /**
     * Sets the value of the orderDetails property.
     * 
     * @param value
     *     allowed object is
     *     {@link OrderDetailsType }
     *     
     */
    public void setOrderDetails(OrderDetailsType value) {
        this.orderDetails = value;
    }

    /**
     * Gets the value of the securityMedium property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getSecurityMedium() {
        return securityMedium;
    }

    /**
     * Sets the value of the securityMedium property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setSecurityMedium(String value) {
        this.securityMedium = value;
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
     * {@link Element }
     * {@link Object }
     * 
     * 
     */
    public List<Object> getAny() {
        if (any == null) {
            any = new ArrayList<Object>();
        }
        return this.any;
    }

}