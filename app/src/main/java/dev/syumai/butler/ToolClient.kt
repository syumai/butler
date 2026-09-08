package dev.syumai.butler

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ToolClient {
    private val http = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build()
    private val haHttp = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    fun cancel() { http.dispatcher.cancelAll(); haHttp.dispatcher.cancelAll() }
    private fun request(request: Request, client: OkHttpClient = http): JSONObject = client.newCall(request).execute().use {
        check(it.isSuccessful) { "HTTP ${it.code}" }
        val source = it.body!!.source()
        check(!source.request(1_000_001)) { "Response too large" }
        JSONObject(source.readUtf8())
    }
    private fun requestArray(request: Request, client: OkHttpClient = http): JSONArray = client.newCall(request).execute().use {
        check(it.isSuccessful) { "HTTP ${it.code}" }
        val source = it.body!!.source()
        check(!source.request(1_000_001)) { "Response too large" }
        JSONArray(source.readUtf8())
    }
    private fun requestText(request: Request, client: OkHttpClient = http): String = client.newCall(request).execute().use {
        check(it.isSuccessful) { "HTTP ${it.code}" }
        val source = it.body!!.source()
        check(!source.request(1_000_001)) { "Response too large" }
        source.readUtf8()
    }
    fun search(settings: Settings, input: String): JSONObject {
        val body = JSONObject().put("model", settings.get("searchModel", "gpt-5.6-luna"))
            .put("store", false).put("max_output_tokens", 1800)
            .put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
            .put("tool_choice", "required").put("input", input)
        val response = request(Request.Builder().url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer ${settings.secret("openai")}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build())
        val result = JSONObject(); val parts = JSONArray()
        val output = response.optJSONArray("output") ?: JSONArray()
        for (i in 0 until output.length()) {
            val content = output.getJSONObject(i).optJSONArray("content") ?: continue
            for (j in 0 until content.length()) if (content.getJSONObject(j).optString("type") == "output_text") parts.put(content.getJSONObject(j))
        }
        return result.put("content", parts)
    }
    fun weather(lat: Double, lon: Double): JSONObject = request(Request.Builder().url(
        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day&timezone=auto").build())
    fun homeAssistant(settings: Settings, text: String, language: String): JSONObject {
        val body = JSONObject().put("text", text).put("language", language)
        val response = request(Request.Builder().url("${settings.get("haUrl")}/api/conversation/process")
            .header("Authorization", "Bearer ${settings.secret("haToken")}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build(), haHttp)
        return parseAssist(response)
    }

    private val areaCacheSuccessTtlMs = 10 * 60 * 1000L
    private val areaCacheFailureTtlMs = 60 * 1000L
    @Volatile private var areaCache: Map<String, String> = emptyMap()
    @Volatile private var areaCacheTime: Long = 0L
    @Volatile private var areaCacheTtlMs: Long = 0L
    /** Home Assistant's `/api/states` has no area field, so areas are fetched separately via a Jinja template
     * that pairs every entity_id with `area_name(entity_id)`. A successful fetch is cached for
     * [areaCacheSuccessTtlMs] since areas rarely change; any failure (HTTP error, timeout, bad JSON) is swallowed
     * and yields (and caches, for the much shorter [areaCacheFailureTtlMs]) an empty map, so a broken or
     * unsupported template endpoint isn't retried on every single tool call — areas are an enhancement to
     * matching, not a requirement, so name-based matching keeps working without them either way. */
    private fun fetchAreas(settings: Settings): Map<String, String> {
        val now = System.currentTimeMillis()
        if (now - areaCacheTime < areaCacheTtlMs) return areaCache
        val fetched = runCatching {
            val template = "{% set ns = namespace(items=[]) %}{% for s in states %}" +
                "{% set ns.items = ns.items + [[s.entity_id, area_name(s.entity_id) or \"\"]] %}{% endfor %}{{ ns.items | tojson }}"
            val body = JSONObject().put("template", template)
            val text = requestText(Request.Builder().url("${settings.get("haUrl")}/api/template")
                .header("Authorization", "Bearer ${settings.secret("haToken")}")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build(), haHttp)
            val pairs = JSONArray(text)
            val map = mutableMapOf<String, String>()
            for (i in 0 until pairs.length()) {
                val pair = pairs.optJSONArray(i) ?: continue
                val id = pair.optString(0)
                val area = pair.optString(1)
                if (id.isNotBlank() && area.isNotBlank()) map[id] = area
            }
            map
        }.getOrNull()
        areaCache = fetched ?: emptyMap()
        areaCacheTime = now
        areaCacheTtlMs = if (fetched != null) areaCacheSuccessTtlMs else areaCacheFailureTtlMs
        return areaCache
    }
    /** GET `/api/states`, with each entity given a top-level "area" string field (empty when unknown/unavailable). */
    private fun fetchStates(settings: Settings): JSONArray {
        val states = requestArray(Request.Builder().url("${settings.get("haUrl")}/api/states")
            .header("Authorization", "Bearer ${settings.secret("haToken")}").build(), haHttp)
        val areas = fetchAreas(settings)
        for (i in 0 until states.length()) {
            val entity = states.optJSONObject(i) ?: continue
            entity.put("area", areas[entity.optString("entity_id")].orEmpty())
        }
        return states
    }
    fun homeAssistantDevices(settings: Settings, domain: String? = null): JSONObject = parseStates(fetchStates(settings), domain)
    fun homeAssistantDeviceStates(settings: Settings, query: String, domain: String? = null, area: String? = null): JSONObject =
        resolveDevices(fetchStates(settings), query, domain, area, forControl = false)
    /** Resolves [query]/[area] against `/api/states` (possibly to several devices — an area, a kind of device, or
     * a name/id; see [resolveDevices], called with `forControl = true` so scope can never silently widen beyond
     * what the caller actually named), calls the Home Assistant service matching [action]/[value] on each,
     * grouping devices that share the same call into one request, then re-fetches fresh state for every targeted
     * device. Returns the unresolved [resolveDevices] result (ambiguous/not_found/unknown_area) unchanged when
     * resolution fails, `{"error":"unsupported_action"|"invalid_value","skipped":[...]}` when [action]/[value]
     * cannot be turned into a service call for *any* resolved device, or `{"done":true,"action","matched_by",
     * "area"?,"devices","skipped"?}` on (at least partial) success. */
    fun homeAssistantControl(settings: Settings, query: String, action: String, value: String?, domain: String? = null, area: String? = null): JSONObject {
        val states = fetchStates(settings)
        val resolved = resolveDevices(states, query, domain, area, forControl = true)
        if (resolved.has("error") || resolved.has("ambiguous")) return resolved
        val devices = resolved.getJSONArray("devices")
        val matchedBy = resolved.optString("matched_by")
        val area = resolved.optString("area").takeIf { resolved.has("area") }

        val rawAttributesById = mutableMapOf<String, JSONObject?>()
        for (i in 0 until states.length()) {
            val entity = states.optJSONObject(i) ?: continue
            val id = entity.optString("entity_id")
            if (id.isNotBlank()) rawAttributesById[id] = entity.optJSONObject("attributes")
        }

        data class Targeted(val id: String, val call: ServiceCall)
        val targeted = mutableListOf<Targeted>()
        val skipped = JSONArray()
        for (i in 0 until devices.length()) {
            val d = devices.getJSONObject(i)
            val id = d.getString("id"); val name = d.getString("name"); val type = d.getString("type")
            val call = serviceFor(type, action, value, rawAttributesById[id])
            if (call != null) { targeted.add(Targeted(id, call)); continue }
            val supported = DEVICE_ACTIONS[type].orEmpty()
            val reason = if (action in supported) "invalid_value" else "unsupported_action"
            val entry = JSONObject().put("name", name).put("type", type).put("reason", reason)
            if (reason == "unsupported_action") entry.put("supported", JSONArray(supported))
            skipped.put(entry)
        }

        if (targeted.isEmpty()) {
            val reasons = (0 until skipped.length()).map { skipped.getJSONObject(it).getString("reason") }.toSet()
            val error = reasons.singleOrNull() ?: "unsupported_action"
            return JSONObject().put("error", error).put("skipped", skipped)
        }

        for (group in groupServiceCalls(targeted.map { it.id to it.call })) {
            val body = JSONObject(group.data.toString()).put("entity_id", JSONArray(group.entityIds))
            requestArray(Request.Builder().url("${settings.get("haUrl")}/api/services/${group.domain}/${group.service}")
                .header("Authorization", "Bearer ${settings.secret("haToken")}")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build(), haHttp)
        }

        val freshStates = fetchStates(settings)
        fun findFresh(id: String): JSONObject? {
            for (i in 0 until freshStates.length()) {
                val entity = freshStates.optJSONObject(i) ?: continue
                if (entity.optString("entity_id") == id) return entity
            }
            return null
        }
        val freshDevices = JSONArray()
        targeted.forEach { t -> findFresh(t.id)?.let { freshDevices.put(deviceStateJson(it)) } }

        val result = JSONObject().put("done", true).put("action", action).put("matched_by", matchedBy).put("devices", freshDevices)
        if (area != null) result.put("area", area)
        if (skipped.length() > 0) result.put("skipped", skipped)
        return result
    }
}
/** Pure parsing of a Home Assistant /api/conversation/process response into a compact model-facing result. */
fun parseAssist(json: JSONObject): JSONObject {
    val response = json.optJSONObject("response") ?: return JSONObject().put("error", "Cannot parse Home Assistant response")
    val type = response.optString("response_type")
    val speech = response.optJSONObject("speech")?.optJSONObject("plain")?.optString("speech").orEmpty()
    val result = JSONObject().put("speech", speech).put("type", type)
    if (type == "action_done") {
        val data = response.optJSONObject("data")
        fun names(key: String): JSONArray {
            val out = JSONArray(); val arr = data?.optJSONArray(key) ?: JSONArray()
            for (i in 0 until arr.length()) out.put(arr.optJSONObject(i)?.optString("name").orEmpty())
            return out
        }
        result.put("success", names("success")).put("failed", names("failed"))
    }
    return result
}
/** Domains listed by default: things a person would call an appliance/device, not the long tail of sensors. */
val DEVICE_DOMAINS = setOf("light", "switch", "climate", "fan", "cover", "lock", "media_player", "humidifier", "vacuum", "scene", "script", "input_boolean")
const val MAX_DEVICES = 150
/** Pure reduction of a Home Assistant /api/states array into a compact model-facing device list. [domain] (a single entity domain such as "sensor", letters/underscore only) overrides the default domain filter. */
fun parseStates(states: JSONArray, domain: String? = null): JSONObject {
    val filterDomain = domain?.takeIf { it.isNotBlank() && Regex("[a-z_]+").matches(it) }
    data class Entry(val type: String, val name: String, val entity: JSONObject)
    val entries = mutableListOf<Entry>()
    for (i in 0 until states.length()) {
        val entity = states.optJSONObject(i) ?: continue
        val id = entity.optString("entity_id").takeIf { it.isNotBlank() } ?: continue
        val type = id.substringBefore(".")
        if (filterDomain != null) { if (type != filterDomain) continue } else if (type !in DEVICE_DOMAINS) continue
        val friendlyName = entity.optJSONObject("attributes")?.optString("friendly_name").orEmpty()
        val name = friendlyName.ifBlank { id }
        entries.add(Entry(type, name, entity))
    }
    entries.sortWith(compareBy({ it.type }, { it.name }))
    val truncated = entries.size > MAX_DEVICES
    val listed = if (truncated) entries.take(MAX_DEVICES) else entries
    val array = JSONArray()
    // Each device is built via deviceStateJson so the Settings devices list carries the same
    // {"name","id","type","state","area","attributes"} shape the model-facing tools return.
    listed.forEach { array.put(deviceStateJson(it.entity)) }
    val result = JSONObject().put("devices", array).put("count", listed.size)
    if (truncated) result.put("truncated", true)
    return result
}

/** Attributes worth relaying to the model, across domains; everything else in `attributes` is dropped. */
val STATE_ATTRIBUTES = listOf(
    "current_temperature", "temperature", "target_temp_high", "target_temp_low", "hvac_action", "hvac_modes",
    "fan_mode", "swing_mode", "preset_mode", "humidity", "current_humidity", "brightness", "color_temp_kelvin",
    "rgb_color", "percentage", "current_position", "volume_level", "media_title", "source", "battery_level",
    "unit_of_measurement", "device_class",
)

/** Builds the model-facing device object ({"name","id","type","state","area","attributes"}) from a raw
 * /api/states entity (or /api/states/<entity_id> single-entity response, or a synthetic entity built by
 * [resolveDevices]), keeping only [STATE_ATTRIBUTES] and adding a computed brightness_percent (0..100) for
 * lights that report brightness (0..255). "area" is "" when the entity has no area or areas could not be
 * fetched. Shared by [resolveDevices] and [ToolClient.homeAssistantControl] so every tool describes a device
 * the same way. */
fun deviceStateJson(entity: JSONObject): JSONObject {
    val id = entity.optString("entity_id")
    val type = id.substringBefore(".")
    val attributes = entity.optJSONObject("attributes") ?: JSONObject()
    val friendlyName = attributes.optString("friendly_name").orEmpty()
    val name = friendlyName.ifBlank { id }
    val attrsOut = JSONObject()
    for (key in STATE_ATTRIBUTES) if (attributes.has(key)) attrsOut.put(key, attributes.get(key))
    if (type == "light" && attributes.has("brightness")) {
        val brightness = attributes.optDouble("brightness", Double.NaN)
        if (!brightness.isNaN()) attrsOut.put("brightness_percent", Math.round(brightness / 255.0 * 100).toInt())
    }
    return JSONObject().put("name", name).put("id", id).put("type", type).put("state", entity.optString("state"))
        .put("area", entity.optString("area")).put("attributes", attrsOut)
}

/** The state strings the Settings devices dialog has a localized label and chip style for. */
private val KNOWN_STATE_KEYS = setOf(
    "on", "off", "cool", "heat", "dry", "fan_only", "auto", "heat_cool", "open", "closed", "opening", "closing",
    "locked", "unlocked", "playing", "paused", "idle", "standby", "unavailable", "unknown",
)
/** States that read as the device actively "on"/engaged, for choosing a filled/gold vs. outlined/dim chip. */
private val ACTIVE_STATE_KEYS = setOf("on", "cool", "heat", "dry", "fan_only", "auto", "heat_cool", "open", "opening", "unlocked", "playing")

/** Maps a raw Home Assistant [state] (for an entity of [type]) to one of the keys the Settings devices dialog
 * has a localized label for, or "raw" when it doesn't recognize the state (in which case the raw state text is
 * shown as-is). [type] is accepted for a stable, future-proof signature but the current key set needs no
 * per-domain disambiguation — every domain's state strings are already distinct from one another. */
fun deviceStateKey(type: String, state: String): String = if (state in KNOWN_STATE_KEYS) state else "raw"

/** True when [state] represents [type] as actively "on"/engaged (vs. off/idle/inactive), for the Settings
 * devices dialog to pick a filled/gold chip instead of an outlined/dim one. */
fun isActiveState(type: String, state: String): Boolean = state in ACTIVE_STATE_KEYS

/** Normalizes for matching: Unicode NFKC, lowercase, strip all whitespace and the Japanese particle "の". */
fun normalizeDeviceName(s: String): String {
    val nfkc = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
    return nfkc.filter { !it.isWhitespace() && it != 'の' }.lowercase()
}

/** Similarity in 0..1 between a normalized query and a normalized name: 1.0 when name contains query (or
 * equals), else the Dice coefficient of character bigrams (unigrams when either side is shorter than 2 chars). */
fun deviceMatchScore(query: String, name: String): Float {
    if (query.isEmpty() || name.isEmpty()) return 0f
    if (name == query || name.contains(query)) return 1.0f
    val n = if (query.length < 2 || name.length < 2) 1 else 2
    fun grams(s: String): List<String> = if (s.length < n) listOf(s) else (0..s.length - n).map { s.substring(it, it + n) }
    val queryGrams = grams(query)
    val nameGramCounts = grams(name).groupingBy { it }.eachCount().toMutableMap()
    var overlap = 0
    for (g in queryGrams) {
        val count = nameGramCounts[g] ?: 0
        if (count > 0) { overlap++; nameGramCounts[g] = count - 1 }
    }
    val total = queryGrams.size + grams(name).size
    return if (total == 0) 0f else (2.0 * overlap / total).toFloat()
}

/** Kind words (Japanese and English, matched after [normalizeDeviceName]) that name an entity domain. */
val KIND_WORDS: Map<String, String> = mapOf(
    "照明" to "light", "電気" to "light", "ライト" to "light", "明かり" to "light", "light" to "light", "lights" to "light", "lamp" to "light",
    "エアコン" to "climate", "冷房" to "climate", "暖房" to "climate", "空調" to "climate", "climate" to "climate", "ac" to "climate", "airconditioner" to "climate",
    "スイッチ" to "switch", "switch" to "switch",
    "扇風機" to "fan", "ファン" to "fan", "fan" to "fan",
    "カーテン" to "cover", "シャッター" to "cover", "ブラインド" to "cover", "cover" to "cover", "curtain" to "cover", "blind" to "cover", "shutter" to "cover",
    "鍵" to "lock", "ロック" to "lock", "lock" to "lock",
    "テレビ" to "media_player", "スピーカー" to "media_player", "tv" to "media_player", "speaker" to "media_player", "mediaplayer" to "media_player",
    "加湿器" to "humidifier", "humidifier" to "humidifier",
    "掃除機" to "vacuum", "vacuum" to "vacuum",
)
/** Words meaning "every one of them", stripped from the residual query alongside the matched kind word. */
val ALL_WORDS = setOf("全部", "すべて", "全て", "all")

private const val DEVICE_MATCH_THRESHOLD = 0.3f
private const val DEVICE_MATCH_TIE_MARGIN = 0.05f
private const val MAX_CANDIDATES = 5

/** One resolved Home Assistant entity, carrying enough of its raw state to both describe it to the model and
 * (for [ToolClient.homeAssistantControl]) act on it. */
private data class ResolvedEntity(val id: String, val name: String, val type: String, val state: String, val attributes: JSONObject, val area: String)

/** Resolves [query] (what the user called a device or an area, a kind of device, "all" of a kind, a specific
 * device's full name, or an entity_id) against a /api/states array (each entity carrying a top-level "area"
 * field, "" when unknown) to one or more entities. [area] optionally pins the whole resolution to one room,
 * independent of anything named in [query] (see below). [forControl] tightens scope for
 * [ToolClient.homeAssistantControl] so an operation can never silently widen beyond what the caller actually
 * named (see below); [ToolClient.homeAssistantDeviceStates] passes `false` to keep the read-friendly behavior
 * of returning every device of a kind. Returns:
 *  - `{"devices": [{name,id,type,state,area,attributes}, ...], "matched_by": "id"|"name"|"area"|"kind"|"fuzzy", "area": "<area>"?}` on a match (one or several devices, ordered by type then name; "area" present whenever [area] was given or the query itself named a room),
 *  - `{"ambiguous": true, "candidates": [{name,id,type,state,area}, ...up to 5], "reason": "kind_only"|"multiple_areas"?}` when disambiguation is needed — a fuzzy-match tie (no reason), or (forControl only) a kind-only target with several matches and no "all" word (`"kind_only"`), or a name-containment match spanning more than one room with no "all" word (`"multiple_areas"`),
 *  - `{"error": "not_found", "devices": [{name,type,area}, ...], "area": "<area>"?}` when nothing matches (the devices of the resolved kind when an area matched but held none of that kind, otherwise the default-domain devices in the home),
 *  - `{"error": "unknown_area", "areas": [<known area name>, ...]}` when [area] is given but matches no known area.
 *
 * If [area] is non-blank, it is resolved against known area names (equal, or an area name containing it — e.g.
 * "リビング" matches "リビングルーム") *before* anything else, and on a match every subsequent step (including
 * entity_id/exact-name matching) only considers entities in that one room; an empty (post-kind-extraction)
 * query then resolves to every matching device (of the extracted kind, or every default-domain device when no
 * kind was named) in that room rather than falling through to the whole-home behavior below.
 *
 * Otherwise, resolution proceeds in order: (1) an entity_id equal to the trimmed query; (2) a friendly name
 * that normalizes to exactly the (normalized) query — this is how the model targets one specific device
 * deterministically, by reusing a full name or id from list_home_devices; (3) extract a device kind from the
 * query (the explicit [domain] if given, else the longest [KIND_WORDS] key contained in it), narrowing the
 * search to that domain and leaving a residual with the kind word (and any [ALL_WORDS]) removed; (4) if a
 * residual remains and it names a known area (equals or is contained by an area of some entity), every
 * kind-matching entity in that area; (5) if the residual is empty (the query was only a kind/"all" word), every
 * kind-matching entity — but under [forControl], with no "all" word, more than one match is reported as
 * `ambiguous`/`"kind_only"` instead, since the caller only named a kind, not a room — or not_found if no kind
 * was named either; (6) every kind-matching entity whose name contains the residual — under [forControl], with
 * no "all" word, a match spanning more than one room is reported as `ambiguous`/`"multiple_areas"` instead;
 * (7) otherwise the closest name(s) by [deviceMatchScore] (threshold 0.3, ties within 0.05 reported as
 * ambiguous rather than guessed). */
fun resolveDevices(states: JSONArray, query: String, domain: String? = null, area: String? = null, forControl: Boolean = false): JSONObject {
    val trimmedQuery = query.trim()
    val filterDomain = domain?.takeIf { it.isNotBlank() && Regex("[a-z_]+").matches(it) }
    val useAllDomains = trimmedQuery.contains(".")

    val allEntities = mutableListOf<ResolvedEntity>()
    val defaultDomainEntities = mutableListOf<ResolvedEntity>()
    var basePool = mutableListOf<ResolvedEntity>()
    for (i in 0 until states.length()) {
        val entity = states.optJSONObject(i) ?: continue
        val id = entity.optString("entity_id").takeIf { it.isNotBlank() } ?: continue
        val type = id.substringBefore(".")
        val attributes = entity.optJSONObject("attributes") ?: JSONObject()
        val friendlyName = attributes.optString("friendly_name").orEmpty()
        val name = friendlyName.ifBlank { id }
        val entityArea = entity.optString("area")
        val resolved = ResolvedEntity(id, name, type, entity.optString("state"), attributes, entityArea)
        allEntities.add(resolved)
        if (type in DEVICE_DOMAINS) defaultDomainEntities.add(resolved)
        val included = if (useAllDomains) true else if (filterDomain != null) type == filterDomain else type in DEVICE_DOMAINS
        if (included) basePool.add(resolved)
    }
    val areaNames = allEntities.mapNotNull { it.area.takeIf(String::isNotBlank) }.distinct()

    // Explicit [area] parameter: resolved and applied as a pool restriction before everything else.
    var scopeArea: String? = null
    area?.trim()?.takeIf { it.isNotBlank() }?.let { requestedArea ->
        val normalizedRequestedArea = normalizeDeviceName(requestedArea)
        scopeArea = areaNames.firstOrNull { normalizeDeviceName(it) == normalizedRequestedArea }
            ?: areaNames.firstOrNull { normalizeDeviceName(it).contains(normalizedRequestedArea) }
        if (scopeArea == null) {
            val areasArray = JSONArray(); areaNames.sorted().forEach { areasArray.put(it) }
            return JSONObject().put("error", "unknown_area").put("areas", areasArray)
        }
        basePool = basePool.filter { it.area == scopeArea }.toMutableList()
    }

    fun deviceJson(e: ResolvedEntity): JSONObject = deviceStateJson(
        JSONObject().put("entity_id", e.id).put("state", e.state).put("attributes", e.attributes).put("area", e.area))
    fun candidateJson(e: ResolvedEntity): JSONObject =
        JSONObject().put("name", e.name).put("id", e.id).put("type", e.type).put("state", e.state).put("area", e.area)
    fun listedJson(e: ResolvedEntity): JSONObject = JSONObject().put("name", e.name).put("type", e.type).put("area", e.area)
    fun sorted(list: List<ResolvedEntity>): List<ResolvedEntity> = list.sortedWith(compareBy({ it.type }, { it.name }))
    fun matched(list: List<ResolvedEntity>, matchedBy: String, stepArea: String? = null): JSONObject {
        val array = JSONArray(); sorted(list).forEach { array.put(deviceJson(it)) }
        val result = JSONObject().put("devices", array).put("matched_by", matchedBy)
        (scopeArea ?: stepArea)?.let { result.put("area", it) }
        return result
    }
    fun notFound(list: List<ResolvedEntity>, stepArea: String? = null): JSONObject {
        val array = JSONArray(); sorted(list).forEach { array.put(listedJson(it)) }
        val result = JSONObject().put("error", "not_found").put("devices", array)
        (scopeArea ?: stepArea)?.let { result.put("area", it) }
        return result
    }
    fun ambiguous(list: List<ResolvedEntity>, reason: String): JSONObject {
        val candidates = JSONArray(); sorted(list).take(MAX_CANDIDATES).forEach { candidates.put(candidateJson(it)) }
        return JSONObject().put("ambiguous", true).put("candidates", candidates).put("reason", reason)
    }

    // 1: entity_id exact match
    basePool.find { it.id == trimmedQuery }?.let { return matched(listOf(it), "id") }

    val normalizedQuery = normalizeDeviceName(trimmedQuery)

    // 2: exact normalized friendly-name match
    basePool.find { normalizeDeviceName(it.name) == normalizedQuery }?.let { return matched(listOf(it), "name") }

    // 3: kind extraction. A kind word is always looked for and stripped out of the residual — so an area+kind
    // phrase like "リビングの照明" still isolates down to "リビング" even when the caller also passed an explicit
    // domain="light" — but the explicit [domain], when given, wins over the word's own mapped domain for D.
    val matchedKindKey = KIND_WORDS.keys.sortedByDescending { it.length }.firstOrNull { normalizedQuery.contains(it) }
    val kindDomain = filterDomain ?: matchedKindKey?.let { KIND_WORDS.getValue(it) }
    var residual = normalizedQuery
    if (matchedKindKey != null) residual = residual.replace(matchedKindKey, "")
    var hasAllWord = false
    ALL_WORDS.forEach { if (residual.contains(it)) hasAllWord = true; residual = residual.replace(it, "") }
    val kindPool = if (kindDomain != null) basePool.filter { it.type == kindDomain } else basePool

    // 4: area named in the query text itself (skipped when [area] already pinned the scope above)
    if (scopeArea == null && residual.isNotEmpty()) {
        val matchedArea = areaNames.firstOrNull { normalizeDeviceName(it) == residual }
            ?: areaNames.firstOrNull { normalizeDeviceName(it).contains(residual) }
        if (matchedArea != null) {
            val inArea = kindPool.filter { it.area == matchedArea }
            return if (inArea.isNotEmpty()) matched(inArea, "area", matchedArea) else notFound(kindPool, matchedArea)
        }
    }

    // 5: residual is empty -> the query was only a kind/"all" word (or, with [area] pinned, no kind at all)
    if (residual.isEmpty()) {
        if (scopeArea != null) return matched(kindPool, "area") // every (kind-matching, if any) device in the pinned room
        if (kindDomain == null) return notFound(defaultDomainEntities)
        if (forControl && !hasAllWord && kindPool.size > 1) return ambiguous(kindPool, "kind_only")
        return matched(kindPool, "kind")
    }

    // 6: name containment
    val containing = kindPool.filter { normalizeDeviceName(it.name).contains(residual) }
    if (containing.isNotEmpty()) {
        if (forControl && scopeArea == null && !hasAllWord && containing.size > 1 && containing.map { it.area }.distinct().size > 1) {
            return ambiguous(containing, "multiple_areas")
        }
        return matched(containing, "name")
    }

    // 7: fuzzy match
    data class Scored(val entity: ResolvedEntity, val score: Float)
    val scored = kindPool.map { Scored(it, deviceMatchScore(residual, normalizeDeviceName(it.name))) }
        .filter { it.score >= DEVICE_MATCH_THRESHOLD }
        .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.entity.name })
    if (scored.isEmpty()) return notFound(defaultDomainEntities)
    val topScore = scored[0].score
    if (scored.size > 1 && topScore - scored[1].score < DEVICE_MATCH_TIE_MARGIN) {
        val candidates = JSONArray()
        scored.filter { topScore - it.score < DEVICE_MATCH_TIE_MARGIN }.take(MAX_CANDIDATES).forEach { candidates.put(candidateJson(it.entity)) }
        return JSONObject().put("ambiguous", true).put("candidates", candidates)
    }
    return matched(listOf(scored[0].entity), "fuzzy")
}

/** One Home Assistant service call: "<domain>/<service>" plus its data (entity_id is added by the caller). */
class ServiceCall(val domain: String, val service: String, val data: JSONObject)

/** Actions the model may request per entity domain, in the order surfaced to callers (e.g. in an
 * unsupported_action result's "supported" list). */
val DEVICE_ACTIONS: Map<String, List<String>> = mapOf(
    "light" to listOf("turn_on", "turn_off", "toggle", "set_brightness"),
    "switch" to listOf("turn_on", "turn_off", "toggle"),
    "input_boolean" to listOf("turn_on", "turn_off", "toggle"),
    "fan" to listOf("turn_on", "turn_off", "toggle", "set_speed"),
    "humidifier" to listOf("turn_on", "turn_off", "toggle", "set_humidity"),
    "climate" to listOf("turn_on", "turn_off", "set_temperature", "set_hvac_mode", "set_fan_mode", "set_swing_mode"),
    "cover" to listOf("open", "close", "stop", "set_position"),
    "lock" to listOf("lock", "unlock"),
    "media_player" to listOf("turn_on", "turn_off", "play", "pause", "stop", "next", "previous", "set_volume", "mute", "unmute"),
    "vacuum" to listOf("start", "pause", "stop", "return_to_base"),
    "scene" to listOf("activate"),
    "script" to listOf("run"),
)

/** Maps a spoken/typed hvac mode (English or Japanese) to the Home Assistant hvac_mode id, or null if unrecognized. */
private fun normalizeHvacMode(value: String?): String? = when (value?.trim()?.lowercase()) {
    "cool", "冷房" -> "cool"
    "heat", "暖房" -> "heat"
    "dry", "除湿" -> "dry"
    "fan_only", "送風" -> "fan_only"
    "auto", "自動" -> "auto"
    "heat_cool" -> "heat_cool"
    "off" -> "off"
    else -> null
}

private fun jsonArrayContains(array: JSONArray, value: String): Boolean {
    for (i in 0 until array.length()) if (array.optString(i) == value) return true
    return false
}

/** Builds the Home Assistant service call for [action] on an entity of [domain] with an optional model-supplied
 * [value], or null when [action] is not supported for [domain] or [value] is missing/out of range/not one of
 * the entity's allowed modes. [attributes] is the entity's raw (unfiltered) attributes, used to validate
 * hvac_mode/fan_mode/swing_mode against hvac_modes/fan_modes/swing_modes when Home Assistant reports them;
 * when a mode list is absent the value is accepted as-is. */
fun serviceFor(domain: String, action: String, value: String?, attributes: JSONObject?): ServiceCall? {
    if (action !in (DEVICE_ACTIONS[domain] ?: return null)) return null

    fun percent(): Int? {
        val n = value?.trim()?.toDoubleOrNull() ?: return null
        return Math.round(n).toInt().takeIf { it in 0..100 }
    }
    fun number(): Double? = value?.trim()?.toDoubleOrNull()
    fun mode(allowedKey: String): String? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val allowed = attributes?.optJSONArray(allowedKey) ?: return v
        return v.takeIf { jsonArrayContains(allowed, it) }
    }
    fun call(service: String, data: JSONObject = JSONObject()) = ServiceCall(domain, service, data)

    return when (domain) {
        "light" -> when (action) {
            "turn_on" -> call("turn_on")
            "turn_off" -> call("turn_off")
            "toggle" -> call("toggle")
            "set_brightness" -> percent()?.let { call("turn_on", JSONObject().put("brightness_pct", it)) }
            else -> null
        }
        "switch", "input_boolean" -> when (action) {
            "turn_on" -> call("turn_on")
            "turn_off" -> call("turn_off")
            "toggle" -> call("toggle")
            else -> null
        }
        "fan" -> when (action) {
            "turn_on" -> call("turn_on")
            "turn_off" -> call("turn_off")
            "toggle" -> call("toggle")
            "set_speed" -> percent()?.let { call("set_percentage", JSONObject().put("percentage", it)) }
            else -> null
        }
        "humidifier" -> when (action) {
            "turn_on" -> call("turn_on")
            "turn_off" -> call("turn_off")
            "toggle" -> call("toggle")
            "set_humidity" -> percent()?.let { call("set_humidity", JSONObject().put("humidity", it)) }
            else -> null
        }
        "climate" -> when (action) {
            "turn_on" -> call("turn_on")
            "turn_off" -> call("turn_off")
            "set_temperature" -> number()?.let { call("set_temperature", JSONObject().put("temperature", it)) }
            "set_hvac_mode" -> normalizeHvacMode(value)?.let { normalized ->
                val allowed = attributes?.optJSONArray("hvac_modes")
                if (allowed != null && !jsonArrayContains(allowed, normalized)) null
                else call("set_hvac_mode", JSONObject().put("hvac_mode", normalized))
            }
            "set_fan_mode" -> mode("fan_modes")?.let { call("set_fan_mode", JSONObject().put("fan_mode", it)) }
            "set_swing_mode" -> mode("swing_modes")?.let { call("set_swing_mode", JSONObject().put("swing_mode", it)) }
            else -> null
        }
        "cover" -> when (action) {
            "open" -> call("open_cover")
            "close" -> call("close_cover")
            "stop" -> call("stop_cover")
            "set_position" -> percent()?.let { call("set_cover_position", JSONObject().put("position", it)) }
            else -> null
        }
        "lock" -> when (action) {
            "lock" -> call("lock")
            "unlock" -> call("unlock")
            else -> null
        }
        "media_player" -> when (action) {
            "turn_on" -> call("turn_on")
            "turn_off" -> call("turn_off")
            "play" -> call("media_play")
            "pause" -> call("media_pause")
            "stop" -> call("media_stop")
            "next" -> call("media_next_track")
            "previous" -> call("media_previous_track")
            "set_volume" -> percent()?.let { call("volume_set", JSONObject().put("volume_level", it / 100.0)) }
            "mute" -> call("volume_mute", JSONObject().put("is_volume_muted", true))
            "unmute" -> call("volume_mute", JSONObject().put("is_volume_muted", false))
            else -> null
        }
        "vacuum" -> when (action) {
            "start" -> call("start")
            "pause" -> call("pause")
            "stop" -> call("stop")
            "return_to_base" -> call("return_to_base")
            else -> null
        }
        "scene" -> if (action == "activate") call("turn_on") else null
        "script" -> if (action == "run") call("turn_on") else null
        else -> null
    }
}

/** One grouped Home Assistant service call: every entity sharing the same domain/service/data (e.g. several
 * lights all being turned off) is combined into a single call with an "entity_id" array. */
class GroupedServiceCall(val domain: String, val service: String, val data: JSONObject, val entityIds: List<String>)

/** Groups (entityId, [ServiceCall]) pairs that share the same domain+service+data into one [GroupedServiceCall]
 * each, in first-seen order (both across groups and of entity ids within a group). */
fun groupServiceCalls(calls: List<Pair<String, ServiceCall>>): List<GroupedServiceCall> {
    val groups = LinkedHashMap<String, Pair<ServiceCall, MutableList<String>>>()
    for ((id, call) in calls) {
        val key = "${call.domain}/${call.service}/${call.data}"
        groups.getOrPut(key) { call to mutableListOf() }.second.add(id)
    }
    return groups.values.map { (call, ids) -> GroupedServiceCall(call.domain, call.service, call.data, ids) }
}
