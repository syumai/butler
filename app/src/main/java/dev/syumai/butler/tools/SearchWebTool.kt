package dev.syumai.butler.tools

import android.content.Context
import dev.syumai.butler.R
import dev.syumai.butler.Settings
import dev.syumai.butler.ToolClient
import org.json.JSONObject

class SearchWebTool(private val context: Context, private val settings: Settings, private val client: ToolClient) : Tool {
    override val name = "search_web"
    override val busyStatus = R.string.status_searching
    override fun definition(): JSONObject = JSONObject().put("type", "function").put("name", name)
        .put("description", context.getString(R.string.tool_search_description))
        .put("parameters", JSONObject("""{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""))
    override fun execute(arguments: JSONObject): JSONObject {
        val query = arguments.getString("query")
        val input = context.getString(R.string.prompt_search_input, query.take(2000))
        return client.search(settings, input)
    }
}
