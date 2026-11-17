package org.ledfxmobile.app.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * How one device's lights look. Every effect keeps its own settings in [configs], so switching
 * effects and back restores what you had (like LedFx).
 */
data class EffectSettings(
    val effectId: String = "wavelength",
    val paletteId: String = "rainbow",
    /** Gradient from a preset; overrides [paletteId] until a palette is picked. */
    val customGradient: List<Int>? = null,
    /** Device brightness 0..1 (multiplied by the app-wide master brightness). */
    val brightness: Float = 1f,
    val configs: Map<String, Map<String, Any>> = emptyMap(),
) {
    fun config(effect: String = effectId): Map<String, Any> = configs[effect] ?: emptyMap()

    fun withValue(key: String, value: Any): EffectSettings =
        copy(configs = configs + (effectId to (config() + (key to value))))

    fun toJson(): JSONObject {
        val cfg = JSONObject()
        configs.forEach { (effect, values) -> cfg.put(effect, mapToJson(values)) }
        return JSONObject()
            .put("effect", effectId).put("palette", paletteId)
            .put("gradient", customGradient?.let { JSONArray(it) } ?: JSONObject.NULL)
            .put("brightness", brightness.toDouble())
            .put("configs", cfg)
    }

    companion object {
        fun fromJson(o: JSONObject): EffectSettings {
            val configs = HashMap<String, Map<String, Any>>()
            o.optJSONObject("configs")?.let { c ->
                c.keys().forEach { k -> c.optJSONObject(k)?.let { configs[k] = jsonToMap(it) } }
            }
            return EffectSettings(
                effectId = o.optString("effect", "wavelength"),
                paletteId = o.optString("palette", "rainbow"),
                customGradient = o.optJSONArray("gradient")?.let { a -> List(a.length()) { a.getInt(it) } },
                brightness = o.optDouble("brightness", 1.0).toFloat(),
                configs = configs,
            )
        }
    }
}

enum class DeviceKind { WLED, VIRTUAL }

/** An LED output: a WLED controller on Wi-Fi, or a virtual strip shown only in the app. */
data class DeviceConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: DeviceKind,
    val host: String = "",
    val ledCount: Int,
    val enabled: Boolean = true,
    val settings: EffectSettings = EffectSettings(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("kind", kind.name).put("host", host)
        .put("leds", ledCount).put("enabled", enabled).put("settings", settings.toJson())

    companion object {
        fun fromJson(o: JSONObject) = DeviceConfig(
            id = o.getString("id"),
            name = o.optString("name", "LEDs"),
            kind = runCatching { DeviceKind.valueOf(o.optString("kind")) }.getOrDefault(DeviceKind.WLED),
            host = o.optString("host", ""),
            ledCount = o.optInt("leds", 60).coerceIn(1, 4096),
            enabled = o.optBoolean("enabled", true),
            settings = o.optJSONObject("settings")?.let { EffectSettings.fromJson(it) } ?: EffectSettings(),
        )
    }
}

fun mapToJson(m: Map<String, Any>): JSONObject {
    val o = JSONObject()
    m.forEach { (k, v) -> o.put(k, if (v is Float) v.toDouble() else v) }
    return o
}

fun jsonToMap(o: JSONObject): Map<String, Any> {
    val m = HashMap<String, Any>()
    o.keys().forEach { k ->
        when (val v = o.get(k)) {
            is Number, is Boolean, is String -> m[k] = v
        }
    }
    return m
}
