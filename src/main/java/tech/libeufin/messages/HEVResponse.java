package tech.libeufin.messages;

import javax.xml.bind.JAXBElement;


public class HEVResponse {
    HEVResponseDataType value;

    public HEVResponse(String returnCode, String reportText){
        SystemReturnCodeType srt = new SystemReturnCodeType();
        srt.setReturnCode(returnCode);
        srt.setReportText(reportText);
        this.value = new HEVResponseDataType();
        this.value.setSystemReturnCode(srt);
    }

    public JAXBElement<HEVResponseDataType> makeHEVResponse(){
        ObjectFactory of = new ObjectFactory();
        return of.createEbicsHEVResponse(this.value);
    }
}
