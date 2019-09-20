package tech.libeufin.messages;

import javax.xml.bind.JAXBElement;


/*public class HEVResponse {
    HEVResponseDataType value;

    public HEVResponse(String returnCode, String reportText){
        SystemReturnCodeType srt = new SystemReturnCodeType();
        srt.setReturnCode(returnCode);
        srt.setReportText(reportText);
        this.value = new HEVResponseDataType();
        this.value.setSystemReturnCode(srt);
    }

    /**
     * Instantiate the root element.
     * @return the JAXB object.
     */
    public JAXBElement<HEVResponseDataType> makeHEVResponse(){
        ObjectFactory of = new ObjectFactory();
        return of.createEbicsHEVResponse(this.value);
    }
}*/

class HEVResponse(returnCode: String, reportText: String) {

    val value = {
        // SystemReturnCodeType srt = new SystemReturnCodeType();
        val srt = SystemReturnCodeType()
        srt.setReturnCode(returnCode);
        srt.setReportText(reportText);
        val value = HEVResponseDataType();
        value.setSystemReturnCode(srt);
        value
    }()

    fun makeHEVResponse(): JAXBElement<HEVResponseDataType> {
        val of = ObjectFactory()
        return of.createEbicsHEVResponse(value)
    }
}
