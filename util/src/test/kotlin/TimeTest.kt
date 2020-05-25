import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class TimeTest {

    @Test
    fun importMillis() {
        fun fromLong(millis: Long): LocalDateTime {
            return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(millis),
                ZoneId.systemDefault()
            )
        }
        val ret = fromLong(0)
        println(ret.toString())
    }
}