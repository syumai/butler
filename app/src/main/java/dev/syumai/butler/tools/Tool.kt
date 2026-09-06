package dev.syumai.butler.tools

import org.json.JSONObject

/**
 * An executable Realtime function tool. Adding a new tool means implementing this interface and
 * registering it in [ToolRegistry.available] — AssistantService itself never needs to change.
 */
interface Tool {
    /** Must match the "name" in [definition] and the function-call name the model sends back. */
    val name: String
    /** String resource id for the status text shown while [execute] is running on the worker thread. */
    val busyStatus: Int
    /** The Realtime function tool definition ("type", "name", "description", "parameters"). */
    fun definition(): JSONObject
    /** Runs the tool. Called off the main thread; must not touch Android UI state. */
    fun execute(arguments: JSONObject): JSONObject
}
