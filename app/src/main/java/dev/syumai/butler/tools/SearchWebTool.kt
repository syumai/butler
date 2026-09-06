package dev.syumai.butler.tools

import dev.syumai.butler.Settings
import dev.syumai.butler.ToolClient
import org.json.JSONObject

class SearchWebTool(private val settings: Settings, private val client: ToolClient) : Tool {
    override val name = "search_web"
    override val busyStatus = "検索中…"
    override fun definition(): JSONObject = JSONObject().put("type", "function").put("name", name)
        .put("description", "最新情報をインターネットで検索する")
        .put("parameters", JSONObject("""{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""))
    override fun execute(arguments: JSONObject): JSONObject = client.search(settings, arguments.getString("query"))
}
