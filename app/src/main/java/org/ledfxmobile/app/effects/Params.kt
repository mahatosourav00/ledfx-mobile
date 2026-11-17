package org.ledfxmobile.app.effects

import org.ledfxmobile.app.audio.AudioFrame
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// ------------------------------------------------------------------ settings schema

/** One adjustable setting of an effect. Keys match LedFx's config keys so its presets apply. */
sealed class Param(val key: String, val label: String) {
    abstract val default: Any
}

class FloatParam(key: String, label: String, val min: Float, val max: Float, override val default: Float) :
    Param(key, label)

class IntParam(key: String, label: String, val min: Int, val max: Int, override val default: Int) :
    Param(key, label)

class BoolParam(key: String, label: String, override val default: Boolean) : Param(key, label)

class ChoiceParam(key: String, label: String, val options: List<String>, override val default: String) :
    Param(key, label)

class ColorParam(key: String, label: String, override val default: Int) : Param(key, label)

/** Settings every effect has (blur, mirror, flip), with per-effect defaults like LedFx. */
fun commonParams(blur: Float = 0f, mirror: Boolean = false, flip: Boolean = false) = listOf(
    FloatParam("blur", "Blur", 0f, 10f, blur),
    BoolParam("mirror", "Mirror from the centre", mirror),
    BoolParam("flip", "Reverse direction", flip),
)

val FREQUENCY_RANGES = listOf("Lows (beat+bass)", "Beat", "Bass", "Mids", "High")

/** Read access to an effect's current values with schema defaults and type coercion. */
class ParamValues(private val params: List<Param>, var values: Map<String, Any> = emptyMap()) {
    private val defaults = params.associate { it.key to it.default }

    fun f(key: String): Float = (values[key] as? Number)?.toFloat() ?: (defaults[key] as? Number)?.toFloat() ?: 0f
    fun i(key: String): Int = (values[key] as? Number)?.toFloat()?.roundToInt() ?: (defaults[key] as? Number)?.toInt() ?: 0
    fun b(key: String): Boolean = values[key] as? Boolean ?: defaults[key] as? Boolean ?: false
    fun s(key: String): String = when (val v = values[key]) {
        is String -> v
        is Number -> if (v.toDouble() == v.toInt().toDouble()) v.toInt().toString() else v.toString()
        else -> defaults[key] as? String ?: ""
    }
    fun color(key: String): Int = when (val v = values[key]) {
        is Number -> v.toInt()
        is String -> parseColor(v) ?: (defaults[key] as? Int ?: 0)
        else -> defaults[key] as? Int ?: 0
    }
}

private val NAMED_COLORS = mapOf(
    "white" to 0xFFFFFF, "black" to 0x000000, "red" to 0xFF0000, "green" to 0x00FF00,
    "blue" to 0x0000FF, "yellow" to 0xFFFF00, "cyan" to 0x00FFFF, "magenta" to 0xFF00FF,
    "orange" to 0xFFA500, "purple" to 0x800080, "pink" to 0xFF00B2, "lightblue" to 0xADD8E6,
    "gold" to 0xFFD700,
)

/** Parses "#rrggbb", "rgb(r, g, b)" or a colour name. */
fun parseColor(s: String): Int? {
    val t = s.trim().lowercase()
    if (t.startsWith("#") && t.length >= 7) return t.substring(1, 7).toIntOrNull(16)
    if (t.startsWith("rgb")) {
        val nums = Regex("""\d+""").findAll(t).map { it.value.toInt() }.toList()
        if (nums.size >= 3) return Rgb.of(nums[0], nums[1], nums[2])
    }
    return NAMED_COLORS[t]
}

fun colorToHex(c: Int) = "#%06X".format(c and 0xFFFFFF)

// ------------------------------------------------------------------ render context

/** Everything an effect needs for one frame. */
class RenderContext {
    var audio: AudioFrame = AudioFrame.EMPTY
    /** Seconds since the engine started. */
    var time = 0.0
    /** Seconds since the previous frame. */
    var dt = 1f / 60f
    var p = ParamValues(emptyList())
    var palette: Palette = Palettes.all[0]
    /** Gradient roll offset (fraction of the strip), advanced by the engine. */
    var gradientOffset = 0f

    /** Gradient colour at strip position [t] (0..1), including gradient roll. */
    fun grad(t: Float): Int = if (gradientOffset == 0f) palette.clamped(t) else palette.at(t + gradientOffset)

    /** Like grad() but always wraps (for colour cycling and hue-like values). */
    fun gradWrap(t: Float): Int = palette.at(t + gradientOffset)
}

/** Effects fill `out` (0xRRGGBB per LED). Each device has its own instance, so state is fine. */
abstract class Effect {
    abstract fun render(ctx: RenderContext, out: IntArray)
}

enum class EffectCategory(val title: String) {
    CLASSIC("Classic"), ATMOSPHERIC("Atmospheric"), BPM("Beat synced"), SIMPLE("Simple"),
    BANDS("Bands"), AMBIENT("No audio"), EXTRA("Extras"),
}

class EffectInfo(
    val id: String,
    val name: String,
    val category: EffectCategory,
    val description: String,
    val params: List<Param>,
    val usesGradient: Boolean = true,
    val create: () -> Effect,
) {
    val audioReactive get() = category != EffectCategory.AMBIENT
}

// ------------------------------------------------------------------ helpers for effects

/** Float RGB buffer (0..255 per channel) for effects that accumulate and decay colour. */
class FloatPixels(val n: Int) {
    val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)

    fun clear() { r.fill(0f); g.fill(0f); b.fill(0f) }

    fun set(i: Int, c: Int, s: Float = 1f) {
        r[i] = Rgb.r(c) * s; g[i] = Rgb.g(c) * s; b[i] = Rgb.b(c) * s
    }

    fun add(i: Int, c: Int, s: Float = 1f) {
        r[i] += Rgb.r(c) * s; g[i] += Rgb.g(c) * s; b[i] += Rgb.b(c) * s
    }

    fun fill(from: Int, to: Int, c: Int, s: Float = 1f, additive: Boolean = false) {
        for (i in max(0, from) until min(n, to)) if (additive) add(i, c, s) else set(i, c, s)
    }

    fun scale(f: Float) {
        for (i in 0 until n) { r[i] *= f; g[i] *= f; b[i] *= f }
    }

    fun shift(steps: Int) {
        if (steps <= 0) return
        val k = min(steps, n)
        System.arraycopy(r, 0, r, k, n - k); System.arraycopy(g, 0, g, k, n - k); System.arraycopy(b, 0, b, k, n - k)
    }

    fun toInts(out: IntArray) {
        for (i in 0 until min(n, out.size)) out[i] = Rgb.of(r[i].toInt(), g[i].toInt(), b[i].toInt())
    }
}

/** LedFx's ExpFilter: different smoothing for rising and falling values. */
class ExpFilter(var alphaDecay: Float, var alphaRise: Float, var value: Float = 0f) {
    fun update(x: Float): Float {
        val a = if (x > value) alphaRise else alphaDecay
        value = a * x + (1 - a) * value
        return value
    }
}

fun frac(x: Float) = x - floor(x)
fun frac(x: Double) = x - floor(x)
/** Sawtooth 0..1 -> sine 0..1 (LedFx hsv "sin"). */
fun sin01(x: Float) = 0.5f * sin(x * 2f * PI.toFloat()) + 0.5f
/** Sawtooth 0..1 -> triangle 0..1 (LedFx hsv "triangle"; not wrapped, like LedFx). */
fun tri(x: Float) = 1f - 2f * abs(x - 0.5f)
fun posOf(i: Int, n: Int) = if (n <= 1) 0f else i.toFloat() / (n - 1)

/** LedFx aggressive_top_end_bias: boost=0 linear, boost=1 pushes highs towards 1. */
fun topEndBias(x: Float, boost: Float): Float {
    val v = x.coerceIn(0f, 1f)
    return (v * (1 - boost) + (1 - (1 - v).pow(1 + 6 * boost)) * boost).coerceIn(0f, 1f)
}

/** In-place gaussian-ish blur of one channel (sigma in pixels). */
fun blurChannel(a: FloatArray, tmp: FloatArray, sigma: Float) {
    if (sigma < 0.3f || a.size < 3) return
    val radius = min(a.size / 2, max(1, (sigma * 2.5f).roundToInt()))
    var wsum = 0f
    val w = FloatArray(radius * 2 + 1) { k ->
        val d = (k - radius).toFloat()
        exp(-(d * d) / (2 * sigma * sigma)).also { wsum += it }
    }
    for (k in w.indices) w[k] /= wsum
    val n = a.size
    for (i in 0 until n) {
        var s = 0f
        for (k in -radius..radius) s += a[(i + k).coerceIn(0, n - 1)] * w[k + radius]
        tmp[i] = s
    }
    System.arraycopy(tmp, 0, a, 0, n)
}
