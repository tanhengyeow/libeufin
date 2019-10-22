package tech.libeufin.sandbox

import org.junit.Assert
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

class ResponseTest {

    val xmlprocess = XML()

    @Test
    fun loadResponse() {
        val response = Response(
            "0000",
        "[EBICS_OK]",
            "All is OK."
        )

        print(xmlprocess.convertJaxbToString(response.get()))


    }

}