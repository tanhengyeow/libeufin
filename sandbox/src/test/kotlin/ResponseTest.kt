package tech.libeufin.sandbox

import org.junit.Test

class ResponseTest {

    val xmlprocess = XML()

    @Test
    fun loadResponse() {
        val response = EbicsResponse(
        "0000",
        "[EBICS_OK] All is okay"
        )

        print(xmlprocess.convertJaxbToString(response.get()))


    }

}