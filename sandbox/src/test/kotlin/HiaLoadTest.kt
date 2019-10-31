package tech.libeufin.sandbox

import org.junit.Test
import org.w3c.dom.Element
import tech.libeufin.schema.ebics_h004.EbicsUnsecuredRequest

class HiaLoadTest {

    @Test
    fun hiaLoad() {

        val processor = XMLUtil()
        val classLoader = ClassLoader.getSystemClassLoader()
        val hia = classLoader.getResource("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ebicsUnsecuredRequest xmlns=\"urn:org:ebics:H004\"\n                       xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"\n                       xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n                       xsi:schemaLocation=\"urn:org:ebics:H004 ebics_keymgmt_request_H004.xsd\"\n                       Version=\"H004\"\n                       Revision=\"1\">\n  <header authenticate=\"true\">\n    <static>\n      <HostID>LIBEUFIN-SANDBOX</HostID>\n      <PartnerID>CUSTM001</PartnerID>\n      <UserID>u1</UserID>\n      <OrderDetails>\n      <OrderType>HIA</OrderType>\n      <OrderAttribute>DZNNN</OrderAttribute>\n      </OrderDetails>\n      <SecurityMedium>0000</SecurityMedium>\n    </static>\n    <mutable/>\n  </header>\n  <body>\n    <DataTransfer>\n      <OrderData>eJzNlsmyo0YWhvf1FBW3l0SZZBY3VHIwCwESM0g7ZpCYxAxPb/mWh7ZdrlUvmhX8Z/gzyPNl5P7npSo/T0nXF0399Q35Cbz9fPi0P8qMmTzHpB8uXZx0fDAEn1+Jdf8e91/f8mFo32F4nuefZuynpstgFAAAAxp+5cR9kf3n7dPn7z0fLb6+jV39/qp6T8Ii6t+PAOA/yn9PXh3/YvpR9+FrAYD8sHbpi39ZLwL7mmpFeVIFX4q6H4I6St4Or157ZhzypB6KKBheP0UfQyVZ5TptDh9G+2+CG5RjcvjNeh/376bF/F3+FtCaeCzH/sCccjF6CEpIHO9qNg7sk6vEJqcu5+zCjy3Te4YaE00r3WCcLidQGGEqJ4uoRMrcaQFlAnAK2xDVb1T2KGFMbW87hbMe9m5XzjCb6kuIareAuStQx66uzs+PUlk5oQim7B6eoNW2qo4Ljxu4jbFWLM4M7n4geL2ZNSiMnxum8+d+YTntqIqVV3eS06KTHYeBuOmqaXA1Ar/csDRAAFFNmr8cGc07u8ZC9TidnjhurtHURIQpvLBIf6Uj/5xQT2pGj62hFy2jbldl1wQtTkKbBKm0d2pWZIxX6Q6brViRT8Hl5UeW6FnsgIC1YQslccVX8AhjALHjgHF/ENYD8+3O7ZLOjPhJWvOeMniZ3ayU8F9rAxgdA9xMO1mN0ITDKKWuzZ23hRWH6P3ZKRn3FBhahVPVUOgmF1Br6mWEGkoWQ9h1eXcWlHsA5sGwVg6PurNwXCdWaw70kceyi3Gye6IjoQTPmksNZ5SAyYFlYQD2HLzenvaur+j7YOw20fN9ZEQK+yYyl0AYctO+tL7HmmVR6lw3hpVuLQpfJxvUcjJfsQ+pQCzmVCuiBpbg+DR1uktRiLu3+LHTSiTqQrWN+QF3a4lGdo/NMirzSlakYYUsCgekexluTTKjt4LKV65gMNMX5fIM3xBPu3PUGepNk19iM6yPa4K3z9m8X4KU3gVayt7E+jGR7DWwoZlcyj38X4P7l2kWlrapX3QcGINhP9L+UH6HAf4+DXu7qBJrCKr2gAJk9wVBvgDERpB3BLxjxG0P/xn/Bhr8D9L+hqb77dg6+ACge/j7sV+Bhn9E9F6oo25t/7eoqyBWNpkKhBnWdoKGuhmCBRJ82aiLXKO+c69WObdyykqpGrcg2A+PPp8it7LPdjNhK5BiMEIn6h6QgJHjEWsmk3Jz+9arzzIBzYNz3qj0mNT8xVTYUR8xVxXWklfOR9nz6FQ6z/gI7Gwkg6kTjx2OnisyF3DN823VzoSr4g5ryJwbHMquYT4NDSEM0PbsLwYsRRPq9lvpA5nnoqlXi9vTCYT4FkyoE7QDt5aBImu2fMdO5d0bBJftAd5cSFpop0IvJKXEH4KxYVdK8B7lsp0JfSmIiwiriH9a+plTZiGxZc8hezlARdah8KgLdshknHN6xLyi0SWHjCm6cYu2J4n6VkCYMjfdzMMxRs1sRCsAt9LXlrr01YT4jeM2RE95x8qg+zShwUrm7MyWCdonrmK+SL8U8gsGwbCZbECR3PP5ihjNrA6ryssn9bR6BnTlRnBkNYmdG6aEuzBLGnrk1WxY2VwBGH5S6tCp/VFGBobwT2FY2JSrPquMjHjlFnGFWDCutPVlcNIwljSFU34Ws5h4bdgq2eKUHV+jcHVQx5v74z2uToGp3xmRjGxpTkTEnnnaf7zQb+rdLpo0DlJL7ZwxCJ8OJIRM6iQvetZRo4sQoCTSbQp1HVovaZ95jUK/zljDup8ehRVrEBGCRGZmzukmaSZn61EXF/pRszUNjc8sVSdM9+Wuq1WA6f+vqP+J5e8oCx+Y/1P/QPzfKN7rQTfUSSfzB8HnjsxZEl5uf2i/Zjj9x6vNqIK5h3/7+rSHv3MJOnz6BXLJ7gw=</OrderData>\n    </DataTransfer>\n  </body>\n</ebicsUnsecuredRequest>\n")
        val hiaDom = XMLUtil.parseStringIntoDom(hia.readText())
        val x: Element = hiaDom.getElementsByTagNameNS(
            "urn:org:ebics:H004",
            "OrderDetails"
        )?.item(0) as Element

        x.setAttributeNS(
            "http://www.w3.org/2001/XMLSchema-instance",
            "type",
            "UnsecuredReqOrderDetailsType"
        )

        XMLUtil.convertDomToJaxb<EbicsUnsecuredRequest>(
            EbicsUnsecuredRequest::class.java,
            hiaDom
        )
    }
}