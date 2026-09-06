package dev.syumai.butler.tools

import android.content.Context
import dev.syumai.butler.R
import dev.syumai.butler.Settings
import dev.syumai.butler.ToolClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns the set of tools available for one conversation. Adding a tool means adding it to
 * [available] (and, if it needs the model to be able to call it, nothing else — AssistantService
 * dispatches purely by name via [find]).
 *
 * end_conversation is kept here as a definition builder rather than a [Tool]: it is control flow
 * handled directly by AssistantService's conversation state machine, not something with a result to
 * execute. The optional hosted MCP tool definition is also built here, since like end_conversation
 * it is not dispatched through [find]/[Tool.execute] (MCP calls are handled via their own Realtime
 * events).
 */
class ToolRegistry(private val context: Context, settings: Settings, client: ToolClient) {
    companion object {
        fun endConversation(context: Context): JSONObject = JSONObject().put("type", "function").put("name", "end_conversation")
            .put("description", context.getString(R.string.tool_end_conversation_description))
            .put("parameters", JSONObject("""{"type":"object","properties":{}}"""))
    }

    private val tools: List<Tool> = buildList {
        add(SearchWebTool(context, settings, client))
        if (settings.get("haUrl").isNotBlank() && settings.secret("haToken").isNotBlank()) add(HomeAssistantTool(context, settings, client))
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
        defs.put(endConversation(context))
        mcp?.let { defs.put(it) }
        return defs
    }
}
