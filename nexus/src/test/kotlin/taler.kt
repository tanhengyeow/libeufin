package tech.libeufin.nexus

import io.ktor.routing.RootRouteSelector
import io.ktor.routing.Route
import io.ktor.routing.RouteSelector
import io.ktor.routing.RouteSelectorEvaluation
import io.ktor.util.InternalAPI
import org.junit.Test
import tech.libeufin.nexus.Taler

class TalerTest {
    @InternalAPI
    @Test
    fun paytoParserTest() {
        val taler = Taler(Route(null, RootRouteSelector("unused")))
    }
}