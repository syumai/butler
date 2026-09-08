package dev.syumai.butler
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class HomeAssistantTest {
    @Test fun actionDone() {
        val json = JSONObject("""{"response":{"response_type":"action_done","speech":{"plain":{"speech":"リビングの照明をつけました"}},
            "data":{"targets":[],"success":[{"id":"light.living_room","name":"リビング 照明","type":"entity"}],"failed":[]}}}""")
        val result = parseAssist(json)
        assertEquals("action_done", result.getString("type"))
        assertEquals("リビングの照明をつけました", result.getString("speech"))
        assertEquals("リビング 照明", result.getJSONArray("success").getString(0))
        assertEquals(0, result.getJSONArray("failed").length())
    }
    @Test fun queryAnswer() {
        val json = JSONObject("""{"response":{"response_type":"query_answer","speech":{"plain":{"speech":"寝室は24度です"}}}}""")
        val result = parseAssist(json)
        assertEquals("query_answer", result.getString("type"))
        assertEquals("寝室は24度です", result.getString("speech"))
        assertFalse(result.has("success"))
    }
    @Test fun error() {
        val json = JSONObject("""{"response":{"response_type":"error","speech":{"plain":{"speech":"デバイスが見つかりません"}}}}""")
        val result = parseAssist(json)
        assertEquals("error", result.getString("type"))
        assertEquals("デバイスが見つかりません", result.getString("speech"))
    }

    private fun entity(id: String, friendlyName: String? = null, state: String = "on", area: String = ""): JSONObject {
        val json = JSONObject().put("entity_id", id).put("state", state).put("area", area)
        if (friendlyName != null) json.put("attributes", JSONObject().put("friendly_name", friendlyName))
        return json
    }

    @Test fun parseStatesDefaultFilter() {
        val states = JSONArray().put(entity("light.living_room", "リビング 照明"))
            .put(entity("switch.fan", "扇風機スイッチ")).put(entity("climate.bedroom", "寝室エアコン"))
            .put(entity("sensor.temperature", "温度センサー")).put(entity("binary_sensor.door", "ドア"))
            .put(entity("sun.sun")).put(entity("person.taro", "太郎"))
        val result = parseStates(states)
        assertEquals(3, result.getInt("count"))
        val ids = (0 until result.getJSONArray("devices").length()).map { result.getJSONArray("devices").getJSONObject(it).getString("id") }
        assertTrue(ids.containsAll(listOf("light.living_room", "switch.fan", "climate.bedroom")))
        assertFalse(ids.any { it.startsWith("sensor.") || it.startsWith("binary_sensor.") || it.startsWith("sun.") || it.startsWith("person.") })
    }

    @Test fun parseStatesNameFallback() {
        val states = JSONArray().put(entity("light.living_room", "リビング 照明")).put(entity("light.kitchen"))
            .put(JSONObject().put("entity_id", "light.hallway").put("state", "off").put("attributes", JSONObject().put("friendly_name", "  ")))
        val result = parseStates(states)
        val devices = result.getJSONArray("devices")
        val names = (0 until devices.length()).associate { devices.getJSONObject(it).getString("id") to devices.getJSONObject(it).getString("name") }
        assertEquals("リビング 照明", names["light.living_room"])
        assertEquals("light.kitchen", names["light.kitchen"])
        assertEquals("light.hallway", names["light.hallway"])
    }

    @Test fun parseStatesSorted() {
        val states = JSONArray().put(entity("switch.b", "B")).put(entity("light.b", "B"))
            .put(entity("light.a", "A")).put(entity("switch.a", "A"))
        val result = parseStates(states)
        val devices = result.getJSONArray("devices")
        val order = (0 until devices.length()).map { devices.getJSONObject(it).getString("id") }
        assertEquals(listOf("light.a", "light.b", "switch.a", "switch.b"), order)
    }

    @Test fun parseStatesDomainOverride() {
        val states = JSONArray().put(entity("sensor.temperature", "温度センサー")).put(entity("sensor.humidity", "湿度センサー"))
            .put(entity("light.living_room", "リビング 照明"))
        val result = parseStates(states, "sensor")
        assertEquals(2, result.getInt("count"))
        val devices = result.getJSONArray("devices")
        assertTrue((0 until devices.length()).all { devices.getJSONObject(it).getString("type") == "sensor" })
    }

    @Test fun parseStatesInvalidDomainFallsBackToDefaults() {
        val states = JSONArray().put(entity("sensor.temperature", "温度センサー")).put(entity("light.living_room", "リビング 照明"))
        val result = parseStates(states, "Sensor!")
        assertEquals(1, result.getInt("count"))
        assertEquals("light.living_room", result.getJSONArray("devices").getJSONObject(0).getString("id"))
    }

    @Test fun parseStatesTruncation() {
        val states = JSONArray()
        for (i in 0 until MAX_DEVICES + 5) states.put(entity("light.light_$i", "照明$i"))
        val result = parseStates(states)
        assertEquals(MAX_DEVICES, result.getInt("count"))
        assertTrue(result.getBoolean("truncated"))

        val small = JSONArray().put(entity("light.living_room", "リビング 照明"))
        val smallResult = parseStates(small)
        assertFalse(smallResult.has("truncated"))
    }

    @Test fun parseStatesSkipsInvalidElements() {
        val states = JSONArray().put("not an object").put(JSONObject().put("state", "on"))
            .put(entity("light.living_room", "リビング 照明"))
        val result = parseStates(states)
        assertEquals(1, result.getInt("count"))
        assertEquals("light.living_room", result.getJSONArray("devices").getJSONObject(0).getString("id"))
    }

    @Test fun parseStatesIncludesArea() {
        val states = JSONArray().put(entity("light.living_room", "リビング 照明", "on", "リビングルーム"))
            .put(entity("light.hallway", "廊下 照明"))
        val result = parseStates(states)
        val devices = result.getJSONArray("devices")
        val areas = (0 until devices.length()).associate { devices.getJSONObject(it).getString("id") to devices.getJSONObject(it).getString("area") }
        assertEquals("リビングルーム", areas["light.living_room"])
        assertEquals("", areas["light.hallway"])
    }

    @Test fun parseStatesIncludesAttributesViaDeviceStateJson() {
        val states = JSONArray().put(entityWithAttributes("climate.living", "リビング エアコン", "cool",
            JSONObject().put("current_temperature", 24).put("supported_features", 383), "リビングルーム"))
        val result = parseStates(states)
        val device = result.getJSONArray("devices").getJSONObject(0)
        assertEquals("リビングルーム", device.getString("area"))
        assertEquals(24, device.getJSONObject("attributes").getInt("current_temperature"))
        assertFalse(device.getJSONObject("attributes").has("supported_features"))
    }

    // --- normalizeDeviceName / deviceMatchScore / resolveDevices ---

    private fun entityWithAttributes(id: String, friendlyName: String, state: String, attributes: JSONObject, area: String = ""): JSONObject =
        attributes.put("friendly_name", friendlyName).let { JSONObject().put("entity_id", id).put("state", state).put("attributes", it).put("area", area) }

    /** The real home's living-room/bedroom fixture from the feature spec, with (or, per [withAreas], without)
     * area assignments: the two living-room lights and the climate in area リビングルーム, the bedroom light in 寝室
     * (the exact area names Home Assistant reports on-device). */
    private fun homeStates(withAreas: Boolean = true): JSONArray {
        val living = if (withAreas) "リビングルーム" else ""
        val bedroom = if (withAreas) "寝室" else ""
        return JSONArray()
            .put(entity("light.rihinku_nature_remo_rihinkuzhao_ming", "リビング Nature Remo リビング照明", "on", living))
            .put(entity("light.rihinku_nature_remo_taininkuzhao_ming", "リビング Nature Remo ダイニング照明", "on", living))
            .put(entity("light.qin_shi_nature_remo_qin_shi_zhao_ming", "寝室 Nature Remo 寝室照明", "off", bedroom))
            .put(entity("climate.rihinku_nature_remo_rihinkueakon", "リビング Nature Remo リビングエアコン", "dry", living))
    }

    private fun ids(result: JSONObject): List<String> {
        val devices = result.getJSONArray("devices")
        return (0 until devices.length()).map { devices.getJSONObject(it).getString("id") }
    }

    @Test fun normalizeDeviceNameStripsWhitespaceAndParticle() {
        assertEquals("リビング照明", normalizeDeviceName("リビング　の 照明"))
        assertEquals("natureremo", normalizeDeviceName("Nature Remo"))
    }

    @Test fun deviceMatchScoreSubstringDisjointPartial() {
        assertEquals(1.0f, deviceMatchScore("照明", "リビング照明"), 0f)
        assertEquals(0.0f, deviceMatchScore("abc", "xyz"), 0f)
        val partial = deviceMatchScore("abcde", "cdefg")
        assertTrue(partial > 0f && partial < 1f)
    }

    @Test fun resolveDevicesLivingRoomLightsByArea() {
        val result = resolveDevices(homeStates(), "リビングの照明")
        assertEquals("area", result.getString("matched_by"))
        assertEquals("リビングルーム", result.getString("area"))
        assertEquals(2, result.getJSONArray("devices").length())
        assertTrue(ids(result).containsAll(listOf("light.rihinku_nature_remo_rihinkuzhao_ming", "light.rihinku_nature_remo_taininkuzhao_ming")))
    }

    @Test fun resolveDevicesLivingRoomLightsByAreaWithExplicitDomain() {
        val result = resolveDevices(homeStates(), "リビングの照明", "light")
        assertEquals("area", result.getString("matched_by"))
        assertEquals("リビングルーム", result.getString("area"))
        assertEquals(2, result.getJSONArray("devices").length())
    }

    @Test fun resolveDevicesLivingRoomWholeArea() {
        val result = resolveDevices(homeStates(), "リビング")
        assertEquals("area", result.getString("matched_by"))
        assertEquals(3, result.getJSONArray("devices").length())
        assertTrue(ids(result).contains("climate.rihinku_nature_remo_rihinkueakon"))
    }

    @Test fun resolveDevicesAllLightsByKind() {
        val result = resolveDevices(homeStates(), "照明")
        assertEquals("kind", result.getString("matched_by"))
        assertEquals(3, result.getJSONArray("devices").length())
    }

    @Test fun resolveDevicesAllLightsByAllWord() {
        val result = resolveDevices(homeStates(), "全部の照明")
        assertEquals("kind", result.getString("matched_by"))
        assertEquals(3, result.getJSONArray("devices").length())
    }

    @Test fun resolveDevicesBedroomLightByArea() {
        val result = resolveDevices(homeStates(), "寝室の照明")
        assertEquals("area", result.getString("matched_by"))
        assertEquals(1, result.getJSONArray("devices").length())
        assertEquals("light.qin_shi_nature_remo_qin_shi_zhao_ming", result.getJSONArray("devices").getJSONObject(0).getString("id"))
    }

    @Test fun resolveDevicesAirConditionerByKind() {
        val result = resolveDevices(homeStates(), "エアコン")
        assertEquals("kind", result.getString("matched_by"))
        assertEquals(1, result.getJSONArray("devices").length())
        assertEquals("climate.rihinku_nature_remo_rihinkueakon", result.getJSONArray("devices").getJSONObject(0).getString("id"))
    }

    @Test fun resolveDevicesLivingRoomAirConditionerByArea() {
        val result = resolveDevices(homeStates(), "リビングのエアコン")
        assertEquals("area", result.getString("matched_by"))
        assertEquals(1, result.getJSONArray("devices").length())
        assertEquals("climate.rihinku_nature_remo_rihinkueakon", result.getJSONArray("devices").getJSONObject(0).getString("id"))
    }

    @Test fun resolveDevicesDiningLightByNameContainment() {
        val result = resolveDevices(homeStates(), "ダイニングの照明")
        assertEquals("name", result.getString("matched_by"))
        assertEquals(1, result.getJSONArray("devices").length())
        assertEquals("light.rihinku_nature_remo_taininkuzhao_ming", result.getJSONArray("devices").getJSONObject(0).getString("id"))
    }

    @Test fun resolveDevicesFullFriendlyNameIsExact() {
        val result = resolveDevices(homeStates(), "リビング Nature Remo リビング照明")
        assertEquals("name", result.getString("matched_by"))
        assertEquals(1, result.getJSONArray("devices").length())
        assertEquals("light.rihinku_nature_remo_rihinkuzhao_ming", result.getJSONArray("devices").getJSONObject(0).getString("id"))
    }

    @Test fun resolveDevicesByEntityId() {
        val result = resolveDevices(homeStates(), "climate.rihinku_nature_remo_rihinkueakon")
        assertEquals("id", result.getString("matched_by"))
        assertEquals(1, result.getJSONArray("devices").length())
    }

    @Test fun resolveDevicesLivingRoomFridgeIsNotASingleDevice() {
        val result = resolveDevices(homeStates(), "リビングの冷蔵庫")
        assertFalse(result.has("devices") && result.optString("matched_by").isNotEmpty())
        assertTrue(result.optBoolean("ambiguous") || result.getString("error") == "not_found")
    }

    @Test fun resolveDevicesFridgeNotFound() {
        val result = resolveDevices(homeStates(), "冷蔵庫")
        assertEquals("not_found", result.getString("error"))
        val devices = result.getJSONArray("devices")
        assertEquals(4, devices.length())
        for (i in 0 until devices.length()) {
            val d = devices.getJSONObject(i)
            assertTrue(d.has("name")); assertTrue(d.has("type")); assertTrue(d.has("area"))
        }
    }

    @Test fun resolveDevicesSensorDomainOverride() {
        val states = homeStates().put(entityWithAttributes("sensor.living_temperature", "リビング 温度", "23.5",
            JSONObject().put("unit_of_measurement", "°C"), "リビング"))
        val result = resolveDevices(states, "温度", "sensor")
        assertEquals(1, result.getJSONArray("devices").length())
        val device = result.getJSONArray("devices").getJSONObject(0)
        assertEquals("sensor.living_temperature", device.getString("id"))
        assertEquals("°C", device.getJSONObject("attributes").getString("unit_of_measurement"))
    }

    @Test fun resolveDevicesWithoutAreasFallsBackToNameContainment() {
        val result = resolveDevices(homeStates(withAreas = false), "リビングの照明")
        assertEquals("name", result.getString("matched_by"))
        assertEquals(2, result.getJSONArray("devices").length())
        assertTrue(ids(result).containsAll(listOf("light.rihinku_nature_remo_rihinkuzhao_ming", "light.rihinku_nature_remo_taininkuzhao_ming")))
    }

    @Test fun resolveDevicesClimateAttributeWhitelist() {
        val states = JSONArray().put(entityWithAttributes("climate.test", "テストエアコン", "cool",
            JSONObject().put("current_temperature", 24).put("temperature", 26).put("hvac_action", "cooling")
                .put("fan_mode", "auto").put("supported_features", 383)))
        val result = resolveDevices(states, "climate.test")
        val attrs = result.getJSONArray("devices").getJSONObject(0).getJSONObject("attributes")
        assertEquals(24, attrs.getInt("current_temperature"))
        assertEquals(26, attrs.getInt("temperature"))
        assertEquals("cooling", attrs.getString("hvac_action"))
        assertEquals("auto", attrs.getString("fan_mode"))
        assertFalse(attrs.has("supported_features"))
    }

    @Test fun resolveDevicesLightBrightnessPercent() {
        val states = JSONArray().put(entityWithAttributes("light.test", "テスト照明", "on", JSONObject().put("brightness", 128)))
        val result = resolveDevices(states, "light.test")
        val attrs = result.getJSONArray("devices").getJSONObject(0).getJSONObject("attributes")
        assertEquals(128, attrs.getInt("brightness"))
        assertEquals(50, attrs.getInt("brightness_percent"))
    }

    // --- resolveDevices: forControl scope guards, the area parameter, and unknown_area ---
    // (bug: "リビングの照明を消して" also turned off the bedroom light because control_devices let a kind-only
    // target ("照明") silently widen to "every light in the home"; forControl=true must never do that.)

    @Test fun controlLivingRoomLightsByAreaWord() {
        val result = resolveDevices(homeStates(), "リビングの照明", forControl = true)
        assertEquals("area", result.getString("matched_by"))
        assertEquals("リビングルーム", result.getString("area"))
        assertEquals(2, result.getJSONArray("devices").length())
    }

    @Test fun controlKindOnlyIsAmbiguous() {
        val result = resolveDevices(homeStates(), "照明", forControl = true)
        assertTrue(result.optBoolean("ambiguous"))
        assertEquals("kind_only", result.getString("reason"))
        assertEquals(3, result.getJSONArray("candidates").length())
    }

    @Test fun controlKindOnlyWithAllWordIsNotAmbiguous() {
        val result = resolveDevices(homeStates(), "全部の照明", forControl = true)
        assertEquals("kind", result.getString("matched_by"))
        assertEquals(3, result.getJSONArray("devices").length())
    }

    @Test fun controlBedroomLightByAreaWord() {
        val result = resolveDevices(homeStates(), "寝室の照明", forControl = true)
        assertEquals("area", result.getString("matched_by"))
        assertEquals(1, result.getJSONArray("devices").length())
    }

    @Test fun controlLivingRoomWholeAreaNotAmbiguous() {
        val result = resolveDevices(homeStates(), "リビング", forControl = true)
        assertEquals("area", result.getString("matched_by"))
        assertEquals(3, result.getJSONArray("devices").length())
    }

    @Test fun controlKindTargetWithAreaParameter() {
        val result = resolveDevices(homeStates(), "照明", area = "リビング", forControl = true)
        assertEquals("リビングルーム", result.getString("area"))
        assertEquals(2, result.getJSONArray("devices").length())
    }

    @Test fun controlEmptyTargetWithAreaParameter() {
        val result = resolveDevices(homeStates(), "", area = "寝室", forControl = true)
        assertEquals("寝室", result.getString("area"))
        assertEquals(1, result.getJSONArray("devices").length())
    }

    @Test fun controlUnknownAreaListsKnownAreas() {
        val result = resolveDevices(homeStates(), "照明", area = "キッチン", forControl = true)
        assertEquals("unknown_area", result.getString("error"))
        val areas = (0 until result.getJSONArray("areas").length()).map { result.getJSONArray("areas").getString(it) }
        assertEquals(2, areas.size)
        assertTrue(areas.containsAll(listOf("リビングルーム", "寝室")))
    }

    @Test fun controlMultipleAreasWithoutAllWordIsAmbiguous() {
        val result = resolveDevices(homeStates(), "natureremo", forControl = true)
        assertTrue(result.optBoolean("ambiguous"))
        assertEquals("multiple_areas", result.getString("reason"))
    }

    @Test fun readMultipleAreasReturnsAllMatches() {
        val result = resolveDevices(homeStates(), "natureremo", forControl = false)
        assertEquals("name", result.getString("matched_by"))
        assertEquals(4, result.getJSONArray("devices").length())
    }

    @Test fun readKindOnlyReturnsAllOfKind() {
        val result = resolveDevices(homeStates(), "照明", forControl = false)
        assertEquals("kind", result.getString("matched_by"))
        assertEquals(3, result.getJSONArray("devices").length())
    }

    // --- deviceStateKey / isActiveState ---

    @Test fun deviceStateKeyMapsKnownStates() {
        assertEquals("on", deviceStateKey("light", "on"))
        assertEquals("off", deviceStateKey("switch", "off"))
        assertEquals("cool", deviceStateKey("climate", "cool"))
        assertEquals("locked", deviceStateKey("lock", "locked"))
        assertEquals("playing", deviceStateKey("media_player", "playing"))
        assertEquals("unavailable", deviceStateKey("light", "unavailable"))
        assertEquals("unknown", deviceStateKey("sensor", "unknown"))
        assertEquals("raw", deviceStateKey("sensor", "23.5"))
    }

    @Test fun isActiveStateTable() {
        assertTrue(isActiveState("light", "on"))
        assertTrue(isActiveState("climate", "cool"))
        assertTrue(isActiveState("climate", "heat_cool"))
        assertTrue(isActiveState("cover", "open"))
        assertTrue(isActiveState("cover", "opening"))
        assertTrue(isActiveState("lock", "unlocked"))
        assertTrue(isActiveState("media_player", "playing"))
        assertFalse(isActiveState("light", "off"))
        assertFalse(isActiveState("cover", "closed"))
        assertFalse(isActiveState("lock", "locked"))
        assertFalse(isActiveState("media_player", "paused"))
        assertFalse(isActiveState("sensor", "unknown"))
    }

    // --- groupServiceCalls ---

    @Test fun groupServiceCallsCombinesIdenticalCalls() {
        val turnOff = ServiceCall("light", "turn_off", JSONObject())
        val grouped = groupServiceCalls(listOf("light.a" to turnOff, "light.b" to ServiceCall("light", "turn_off", JSONObject())))
        assertEquals(1, grouped.size)
        assertEquals("light", grouped[0].domain)
        assertEquals("turn_off", grouped[0].service)
        assertEquals(listOf("light.a", "light.b"), grouped[0].entityIds)
    }

    @Test fun groupServiceCallsLivingRoomToggleSkipsClimate() {
        val result = resolveDevices(homeStates(), "リビング")
        val devices = result.getJSONArray("devices")
        val calls = mutableListOf<Pair<String, ServiceCall>>()
        var skipped = 0
        for (i in 0 until devices.length()) {
            val d = devices.getJSONObject(i)
            val call = serviceFor(d.getString("type"), "toggle", null, null)
            if (call != null) calls.add(d.getString("id") to call) else skipped++
        }
        assertEquals(1, skipped) // climate doesn't support toggle
        val grouped = groupServiceCalls(calls)
        assertEquals(1, grouped.size)
        assertEquals("light", grouped[0].domain)
        assertEquals("toggle", grouped[0].service)
        assertEquals(2, grouped[0].entityIds.size)
    }

    // --- serviceFor / DEVICE_ACTIONS ---

    @Test fun serviceForLightSetBrightness() {
        val call = serviceFor("light", "set_brightness", "50", null)
        assertEquals("light", call!!.domain)
        assertEquals("turn_on", call.service)
        assertEquals(50, call.data.getInt("brightness_pct"))
    }

    @Test fun serviceForLightSetBrightnessOutOfRange() {
        assertNull(serviceFor("light", "set_brightness", "150", null))
    }

    @Test fun serviceForClimateSetTemperature() {
        val call = serviceFor("climate", "set_temperature", "26", null)
        assertEquals("climate", call!!.domain)
        assertEquals("set_temperature", call.service)
        assertEquals(26.0, call.data.getDouble("temperature"), 0.0)
    }

    @Test fun serviceForClimateSetHvacModeJapaneseSynonym() {
        val call = serviceFor("climate", "set_hvac_mode", "冷房", null)
        assertEquals("climate", call!!.domain)
        assertEquals("set_hvac_mode", call.service)
        assertEquals("cool", call.data.getString("hvac_mode"))
    }

    @Test fun serviceForClimateSetHvacModeRejectedWhenNotSupported() {
        val attributes = JSONObject().put("hvac_modes", JSONArray().put("off").put("dry").put("heat"))
        assertNull(serviceFor("climate", "set_hvac_mode", "cool", attributes))
    }

    @Test fun serviceForLightSetTemperatureUnsupported() {
        assertNull(serviceFor("light", "set_temperature", "26", null))
    }

    @Test fun serviceForCoverSetPosition() {
        val call = serviceFor("cover", "set_position", "30", null)
        assertEquals("cover", call!!.domain)
        assertEquals("set_cover_position", call.service)
        assertEquals(30, call.data.getInt("position"))
    }

    @Test fun serviceForMediaPlayerSetVolume() {
        val call = serviceFor("media_player", "set_volume", "40", null)
        assertEquals("media_player", call!!.domain)
        assertEquals("volume_set", call.service)
        assertEquals(0.4, call.data.getDouble("volume_level"), 0.0001)
    }

    @Test fun serviceForSceneActivate() {
        val call = serviceFor("scene", "activate", null, null)
        assertEquals("scene", call!!.domain)
        assertEquals("turn_on", call.service)
    }

    @Test fun serviceForUnknownActionIsNull() {
        assertNull(serviceFor("light", "nonexistent_action", null, null))
    }

    @Test fun deviceActionsAllProduceAServiceCall() {
        fun testValue(action: String): String = when (action) {
            "set_hvac_mode" -> "cool"
            "set_fan_mode", "set_swing_mode" -> "auto"
            "set_temperature" -> "26"
            else -> "50"
        }
        for ((domain, actions) in DEVICE_ACTIONS) for (action in actions) {
            assertNotNull("$domain/$action should produce a service call", serviceFor(domain, action, testValue(action), null))
        }
    }
}
