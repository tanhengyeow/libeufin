package tech.libeufin.nexus

import io.ktor.routing.RootRouteSelector
import io.ktor.routing.Route
import io.ktor.util.InternalAPI
import org.junit.Ignore
import org.junit.Test
import tech.libeufin.util.Amount
import java.math.BigDecimal

/*
class TalerTest {

    @InternalAPI
    val taler = Taler(Route(null, RootRouteSelector("unused")))

    @InternalAPI
    @Test
    fun paytoParserTest() {
        val payto = taler.parsePayto("payto://iban/ABC/XYZ?name=foo")
        assert(payto.bic == "ABC" && payto.iban == "XYZ" && payto.name == "foo")
        val paytoNoBic = taler.parsePayto("payto://iban/XYZ?name=foo")
        assert(paytoNoBic.bic == "" && paytoNoBic.iban == "XYZ" && paytoNoBic.name == "foo")
    }

    @InternalAPI
    @Test
    fun amountParserTest() {
        val amount = taler.parseAmount("EUR:1")
        assert(amount.currency == "EUR" && amount.amount.equals(BigDecimal(1)))
        val amount299 = taler.parseAmount("EUR:2.99")
        assert(amount299.amount.compareTo(Amount("2.99")) == 0)
        val amount25 = taler.parseAmount("EUR:2.5")
        assert(amount25.amount.compareTo(Amount("2.5")) == 0)
    }
}

 */