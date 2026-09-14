package dev.syumai.butler.tools

import android.content.Context
import dev.syumai.butler.R
import dev.syumai.butler.SessionContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Returns the current local date/time. The date/time deliberately aren't baked into the session
 * instructions (see [SessionContext]) since they change during a session; the model is expected to
 * call this tool instead of guessing whenever the answer depends on the current moment.
 */
class GetCurrentTimeTool(private val context: Context) : Tool {
    override val name = "get_current_time"
    override val busyStatus = R.string.status_checking_time
    override fun definition(): JSONObject = JSONObject().put("type", "function").put("name", name)
        .put("description", context.getString(R.string.tool_get_current_time_description))
        .put("parameters", JSONObject("""{"type":"object","properties":{}}"""))
    override fun execute(arguments: JSONObject): JSONObject = payload(System.currentTimeMillis(), TimeZone.getDefault(), Locale.getDefault())

    companion object {
        /** Pure, unit-testable: builds the tool's result for a given instant/zone/locale. */
        fun payload(epochMs: Long, zone: TimeZone, locale: Locale): JSONObject {
            val date = Date(epochMs)
            fun format(pattern: String) = SimpleDateFormat(pattern, locale).apply { timeZone = zone }.format(date)
            val offset = SessionContext.formatUtcOffset(zone.getOffset(epochMs))
            return JSONObject()
                .put("datetime", format("yyyy-MM-dd'T'HH:mm:ss") + offset)
                .put("date", format("yyyy-MM-dd"))
                .put("time", format("HH:mm"))
                .put("weekday", format("EEEE"))
                .put("time_zone", zone.id)
                .put("utc_offset", offset)
                .put("epoch_seconds", epochMs / 1000)
        }
    }
}
