//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.8-b130911.1802 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2019.10.10 at 06:36:01 PM CEST 
//


package tech.libeufin.messages.ebics.keyrequest;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;


/**
 * <p>Java class for anonymous complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType>
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence>
 *         &lt;element name="header">
 *           &lt;complexType>
 *             &lt;complexContent>
 *               &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *                 &lt;sequence>
 *                   &lt;element name="static" type="{urn:org:ebics:H004}NoPubKeyDigestsRequestStaticHeaderType"/>
 *                   &lt;element name="mutable" type="{urn:org:ebics:H004}EmptyMutableHeaderType"/>
 *                 &lt;/sequence>
 *                 &lt;attGroup ref="{urn:org:ebics:H004}AuthenticationMarker"/>
 *               &lt;/restriction>
 *             &lt;/complexContent>
 *           &lt;/complexType>
 *         &lt;/element>
 *         &lt;element ref="{urn:org:ebics:H004}AuthSignature"/>
 *         &lt;element name="body">
 *           &lt;complexType>
 *             &lt;complexContent>
 *               &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *                 &lt;sequence>
 *                   &lt;element ref="{http://www.w3.org/2000/09/xmldsig#}X509Data" maxOccurs="0" minOccurs="0"/>
 *                 &lt;/sequence>
 *               &lt;/restriction>
 *             &lt;/complexContent>
 *           &lt;/complexType>
 *         &lt;/element>
 *       &lt;/sequence>
 *       &lt;attGroup ref="{urn:org:ebics:H004}VersionAttrGroup"/>
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "", propOrder = {
    "header",
    "authSignature",
    "body"
})
@XmlRootElement(name = "ebicsNoPubKeyDigestsRequest")
public class EbicsNoPubKeyDigestsRequest {

    @XmlElement(required = true)
    protected EbicsNoPubKeyDigestsRequest.Header header;
    @XmlElement(name = "AuthSignature", required = true)
    protected SignatureType authSignature;
    @XmlElement(required = true)
    protected EbicsNoPubKeyDigestsRequest.Body body;
    @XmlAttribute(name = "Version", required = true)
    @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
    protected String version;
    @XmlAttribute(name = "Revision")
    protected Integer revision;

    /**
     * Gets the value of the header property.
     * 
     * @return
     *     possible object is
     *     {@link EbicsNoPubKeyDigestsRequest.Header }
     *     
     */
    public EbicsNoPubKeyDigestsRequest.Header getHeader() {
        return header;
    }

    /**
     * Sets the value of the header property.
     * 
     * @param value
     *     allowed object is
     *     {@link EbicsNoPubKeyDigestsRequest.Header }
     *     
     */
    public void setHeader(EbicsNoPubKeyDigestsRequest.Header value) {
        this.header = value;
    }

    /**
     * Authentifikationssignatur.
     * 
     * @return
     *     possible object is
     *     {@link SignatureType }
     *     
     */
    public SignatureType getAuthSignature() {
        return authSignature;
    }

    /**
     * Sets the value of the authSignature property.
     * 
     * @param value
     *     allowed object is
     *     {@link SignatureType }
     *     
     */
    public void setAuthSignature(SignatureType value) {
        this.authSignature = value;
    }

    /**
     * Gets the value of the body property.
     * 
     * @return
     *     possible object is
     *     {@link EbicsNoPubKeyDigestsRequest.Body }
     *     
     */
    public EbicsNoPubKeyDigestsRequest.Body getBody() {
        return body;
    }

    /**
     * Sets the value of the body property.
     * 
     * @param value
     *     allowed object is
     *     {@link EbicsNoPubKeyDigestsRequest.Body }
     *     
     */
    public void setBody(EbicsNoPubKeyDigestsRequest.Body value) {
        this.body = value;
    }

    /**
     * Gets the value of the version property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getVersion() {
        return version;
    }

    /**
     * Sets the value of the version property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setVersion(String value) {
        this.version = value;
    }

    /**
     * Gets the value of the revision property.
     * 
     * @return
     *     possible object is
     *     {@link Integer }
     *     
     */
    public Integer getRevision() {
        return revision;
    }

    /**
     * Sets the value of the revision property.
     * 
     * @param value
     *     allowed object is
     *     {@link Integer }
     *     
     */
    public void setRevision(Integer value) {
        this.revision = value;
    }


    /**
     * <p>Java class for anonymous complex type.
     * 
     * <p>The following schema fragment specifies the expected content contained within this class.
     * 
     * <pre>
     * &lt;complexType>
     *   &lt;complexContent>
     *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
     *       &lt;sequence>
     *         &lt;element ref="{http://www.w3.org/2000/09/xmldsig#}X509Data" maxOccurs="0" minOccurs="0"/>
     *       &lt;/sequence>
     *     &lt;/restriction>
     *   &lt;/complexContent>
     * &lt;/complexType>
     * </pre>
     * 
     * 
     */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "")
    public static class Body {


    }


    /**
     * <p>Java class for anonymous complex type.
     * 
     * <p>The following schema fragment specifies the expected content contained within this class.
     * 
     * <pre>
     * &lt;complexType>
     *   &lt;complexContent>
     *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
     *       &lt;sequence>
     *         &lt;element name="static" type="{urn:org:ebics:H004}NoPubKeyDigestsRequestStaticHeaderType"/>
     *         &lt;element name="mutable" type="{urn:org:ebics:H004}EmptyMutableHeaderType"/>
     *       &lt;/sequence>
     *       &lt;attGroup ref="{urn:org:ebics:H004}AuthenticationMarker"/>
     *     &lt;/restriction>
     *   &lt;/complexContent>
     * &lt;/complexType>
     * </pre>
     * 
     * 
     */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "", propOrder = {
        "_static",
        "mutable"
    })
    public static class Header {

        @XmlElement(name = "static", required = true)
        protected NoPubKeyDigestsRequestStaticHeaderType _static;
        @XmlElement(required = true)
        protected EmptyMutableHeaderType mutable;
        @XmlAttribute(name = "authenticate", required = true)
        protected boolean authenticate;

        /**
         * Gets the value of the static property.
         * 
         * @return
         *     possible object is
         *     {@link NoPubKeyDigestsRequestStaticHeaderType }
         *     
         */
        public NoPubKeyDigestsRequestStaticHeaderType getStatic() {
            return _static;
        }

        /**
         * Sets the value of the static property.
         * 
         * @param value
         *     allowed object is
         *     {@link NoPubKeyDigestsRequestStaticHeaderType }
         *     
         */
        public void setStatic(NoPubKeyDigestsRequestStaticHeaderType value) {
            this._static = value;
        }

        /**
         * Gets the value of the mutable property.
         * 
         * @return
         *     possible object is
         *     {@link EmptyMutableHeaderType }
         *     
         */
        public EmptyMutableHeaderType getMutable() {
            return mutable;
        }

        /**
         * Sets the value of the mutable property.
         * 
         * @param value
         *     allowed object is
         *     {@link EmptyMutableHeaderType }
         *     
         */
        public void setMutable(EmptyMutableHeaderType value) {
            this.mutable = value;
        }

        /**
         * Gets the value of the authenticate property.
         * 
         */
        public boolean isAuthenticate() {
            return authenticate;
        }

        /**
         * Sets the value of the authenticate property.
         * 
         */
        public void setAuthenticate(boolean value) {
            this.authenticate = value;
        }

    }

}