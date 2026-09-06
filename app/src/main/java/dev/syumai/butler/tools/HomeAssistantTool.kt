package dev.syumai.butler.tools

import dev.syumai.butler.Settings
import dev.syumai.butler.ToolClient
import org.json.JSONObject

class HomeAssistantTool(private val settings: Settings, private val client: ToolClient) : Tool {
    override val name = "home_assistant"
    override val busyStatus = "家電を操作中…"
    override fun definition(): JSONObject = JSONObject().put("type", "function").put("name", name)
        .put("description", "自宅のHome Assistantで家電・照明・スイッチ・エアコンなどを操作したり、その状態を調べたりする。ユーザーの要望を日本語の短い命令文または質問文にして text に渡す（例: リビングの電気を消して / 寝室の温度は？）。鍵の解錠や高額・危険な操作はユーザーに口頭で確認してから呼ぶ。")
        .put("parameters", JSONObject("""{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"""))
    override fun execute(arguments: JSONObject): JSONObject = client.homeAssistant(settings, arguments.getString("text"))
}
