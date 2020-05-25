import org.junit.Test
import java.time.*
import java.time.format.DateTimeFormatter

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

    @Test
    fun formatDateTime() {
        fun formatDashed(dateTime: LocalDateTime): String {
            val dtf = DateTimeFormatter.ISO_LOCAL_DATE
            return dtf.format(dateTime)
        }
        fun formatZonedWithOffset(dateTime: ZonedDateTime): String {
            val dtf = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            return dtf.format(dateTime)
        }
        val str = formatDashed(LocalDateTime.now())     
        println(str)
        val str0 = formatZonedWithOffset(LocalDateTime.now().atZone(ZoneId.systemDefault()))
        println(str0)
    }
}