package dev.syumai.butler.tools

import dev.syumai.butler.Settings
import dev.syumai.butler.ToolClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns the set of tools available for one conversation. Adding a tool means adding it to
 * [available] (and, if it needs the model to be able to call it, nothing else — AssistantService
 * dispatches purely by name via [find]).
 *
 * end_conversation is kept here as a definition constant rather than a [Tool]: it is control flow
 * handled directly by AssistantService's conversation state machine, not something with a result to
 * execute. The optional hosted MCP tool definition is also built here, since like end_conversation
 * it is not dispatched through [find]/[Tool.execute] (MCP calls are handled via their own Realtime
 * events).
 */
class ToolRegistry(settings: Settings, client: ToolClient) {
    companion object {
        val END_CONVERSATION: JSONObject = JSONObject().put("type", "function").put("name", "end_conversation")
            .put("description", "ユーザーが会話の終了を求めたとき、短く別れの挨拶をしてから終了する")
            .put("parameters", JSONObject("""{"type":"object","properties":{}}"""))
    }

    private val tools: List<Tool> = buildList {
        add(SearchWebTool(settings, client))
        if (settings.get("haUrl").isNotBlank() && settings.secret("haToken").isNotBlank()) add(HomeAssistantTool(settings, client))
    }

    private val mcp: JSONObject? = settings.get("mcpUrl").takeIf { it.isNotBlank() }?.let { url ->
        JSONObject().put("type", "mcp").put("server_label", "configured_server").put("server_url", url).put("require_approval", "always").also { mcp ->
            settings.secret("mcpToken").takeIf { it.isNotBlank() }?.let { mcp.put("authorization", it) }
        }
    }

    fun available(): List<Tool> = tools

    fun find(name: String): Tool? = tools.find { it.name == name }

    /** Executable tool definitions, then end_conversation, then the optional hosted MCP tool. */
    fun definitions(): JSONArray {
        val defs = JSONArray()
        tools.forEach { defs.put(it.definition()) }
        defs.put(END_CONVERSATION)
        mcp?.let { defs.put(it) }
        return defs
    }
}
