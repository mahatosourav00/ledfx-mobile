package org.ledfxmobile.app.effects

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// Ports of LedFx's "Simple", band, and non-reactive effects, plus two extras.

internal class Filter : Effect() {
    override fun render(ctx: RenderContext, out: IntArray) {
        val p = ctx.p
        val color = if (p.b("use_gradient")) {
            val roll = p.f("roll_speed")
            if (roll > 0f) {
                val rollTime = (1 - roll) * 59 + 1
                ctx.gradWrap((ctx.time % rollTime / rollTime).toFloat())
            } else ctx.grad(0f)
        } else p.color("color")
        val level = topEndBias(ctx.audio.power(p.s("frequency_range")), p.f("boost"))
        out.fill(Rgb.scale(color, level))
    }
}

internal class Hierarchy : Effect() {
    private var color = 0
    private var lastLow = -10.0
    private var lastMid = -10.0
    private var level = 0f

    override fun render(ctx: RenderContext, out: IntArray) {
        val p = ctx.p
        val a = ctx.audio
        val now = ctx.time
        val switch = p.f("switch_time")
        level = a.lowsPower()
        if (level > p.f("threshold_lows")) {
            color = p.color("color_lows"); lastLow = now
        } else if (now - lastLow > switch) {
            level = a.midsPower()
            if (level > p.f("threshold_mids")) {
                color = p.color("color_mids"); lastMid = now
            } else if (now - lastMid > switch) {
                level = a.highPower(); color = p.color("color_high")
            }
        }
        out.fill(Rgb.scale(color, topEndBias(level, p.f("brightness_boost"))))
    }
}

internal class VuMeter : Effect() {
    private val peak = ExpFilter(0.1f, 0.99f)
    private val minPeak = ExpFilter(0.1f, 0.99f)

    override fun render(ctx: RenderContext, out: IntArray) {
        val n = out.size
        val p = ctx.p
        peak.alphaDecay = p.f("peak_decay"); minPeak.alphaDecay = p.f("peak_decay")
        val vol = ctx.audio.volume.coerceIn(0f, 1f)
        val volPeak = peak.update(vol)
        val volMinPeak = minPeak.update(1 - vol)
        val level = (n * vol).toInt()
        val lowEnd = (n * 0.2f).toInt()
        val highStart = (n * p.f("max_volume")).toInt()
        out.fill(0)
        for (i in 0 until min(level, n)) {
            out[i] = when {
                i < lowEnd -> p.color("color_min")
                i < highStart -> p.color("color_mid")
                else -> p.color("color_max")
            }
        }
        val peakW = (p.i("peak_percent") * n / 100f).toInt()
        if (peakW > 0) {
            val ps = (n * volPeak).toInt().coerceIn(0, n)
            for (i in ps until min(n, ps + peakW)) out[i] = p.color("color_peak")
            val ms = (n * (1 - volMinPeak)).toInt().coerceIn(0, n)
            for (i in max(0, ms - peakW) until ms) out[i] = p.color("color_peak")
        }
    }
}

// ---------------------------------------------------------------- bands

/** Splits [n] LEDs into [k] nearly-equal parts like numpy.array_split. */
private fun split(n: Int, k: Int): List<IntRange> {
    val base = n / k
    val extra = n % k
    var start = 0
    return List(k) { i ->
        val len = base + if (i < extra) 1 else 0
        (start until start + len).also { start += len }
    }
}

/** Index inside a band of [w] LEDs that is lit for fill length [vol], honouring LedFx "align". */
private fun alignedLit(j: Int, w: Int, vol: Int, align: String): Boolean = when (align) {
    "right" -> j >= w - vol
    "center" -> { val s = (w - vol) / 2; j in s until s + vol }
    "invert" -> { val src = Math.floorMod(j + vol / 2, w); src < vol }
    else -> j < vol
}

internal class Bands : Effect() {
    private var r = FloatArray(0)
    override fun render(ctx: RenderContext, out: IntArray) {
        val n = out.size
        if (r.size != n) r = FloatArray(n)
        ctx.audio.melbank(r)
        val k = min(ctx.p.i("band_count"), n)
        val align = ctx.p.s("align")
        split(n, k).forEachIndexed { b, range ->
            val w = range.count()
            var mx = 0f
            for (i in range) mx = max(mx, r[i].coerceIn(0f, 1f))
            val vol = (mx * w).toInt()
            val color = ctx.grad(b.toFloat() / k)
            for ((j, i) in range.withIndex()) out[i] = if (vol > 0 && alignedLit(j, w, vol, align)) color else 0
        }
    }
}

internal class BandsMatrix : Effect() {
    private var r = FloatArray(0)
    override fun render(ctx: RenderContext, out: IntArray) {
        val n = out.size
        if (r.size != n) r = FloatArray(n)
        ctx.audio.melbank(r)
        val p = ctx.p
        val k = min(p.i("band_count"), n)
        val flipGrad = p.b("flip_gradient")
        val ranges = split(n, k)
        val values = ranges.map { range -> range.maxOf { r[it].coerceIn(0f, 1f) } }
        val order = if (p.b("flip_horizontal")) values.reversed() else values
        ranges.forEachIndexed { b, range ->
            val w = range.count()
            val vol = (order[b] * w).toInt()
            for ((j, i) in range.withIndex()) {
                val jj = if (b and 1 == 1) w - 1 - j else j
                out[i] = if (jj < vol) {
                    val g = jj.toFloat() / w
                    ctx.grad(if (flipGrad) 1 - g else g)
                } else 0
            }
        }
    }
}

internal class Blocks : Effect() {
    private var r = FloatArray(0)
    override fun render(ctx: RenderContext, out: IntArray) {
        val n = out.size
        if (r.size != n) r = FloatArray(n)
        ctx.audio.melbank(r)
        val k = min(ctx.p.i("block_count"), n)
        split(n, k).forEachIndexed { b, range ->
            var mx = 0f
            for (i in range) mx = max(mx, r[i])
            val color = ctx.grad(b.toFloat() / k)
            for (i in range) out[i] = Rgb.scale(color, (r[i] * mx).coerceIn(0f, 1f))
        }
    }
}

internal class Equalizer : Effect() {
    private var r = FloatArray(0)
    override fun render(ctx: RenderContext, out: IntArray) {
        val n = out.size
        if (r.size != n) r = FloatArray(n)
        ctx.audio.melbank(r)
        val k = min(ctx.p.i("gradient_repeat"), n)
        val align = ctx.p.s("align")
        split(n, k).forEach { range ->
            val w = range.count()
            var sum = 0f
            for (i in range) sum += r[i].coerceIn(0f, 1f)
            val vol = (sum / w * w).toInt()
            for ((j, i) in range.withIndex()) {
                out[i] = if (vol > 0 && alignedLit(j, w, vol, align)) ctx.grad(posOf(i, n)) else 0
            }
        }
    }
}

// ---------------------------------------------------------------- non-reactive

/** LedFx ModulateEffect: optional moving sine wave or "breathing" length. */
internal class Modulator {
    private var counter = 0f
    fun apply(ctx: RenderContext, out: IntArray) {
        val p = ctx.p
        if (!p.b("modulate")) return
        val n = out.size
        val speed = p.f("modulation_speed")
        if (p.s("modulation_effect") == "breath") {
            counter = frac(counter + ctx.dt * speed * 0.4f)
            val len = ((0.5f - 0.5f * cos(2 * PI.toFloat() * counter)) * n).toInt()
            for (i in len until n) out[i] = 0
        } else {
            counter += 0.1f * speed / PI.toFloat() * (ctx.dt * 60f)
            if (counter >= 2 * PI) counter = 0f
            for (i in 0 until n) {
                val x = counter + PI.toFloat() - posOf(i, n) * PI.toFloat()
                out[i] = Rgb.scale(out[i], 0.3f * sin(x) + 0.4f)
            }
        }
    }
}

internal class GradientFx : Effect() {
    private val mod = Modulator()
    override fun render(ctx: RenderContext, out: IntArray) {
        for (i in out.indices) out[i] = ctx.grad(posOf(i, out.size))
        mod.apply(ctx, out)
    }
}

internal class Fade : Effect() {
    private var idx = 0f
    private var forward = true
    override fun render(ctx: RenderContext, out: IntArray) {
        idx += ctx.dt * 0.09f * ctx.p.f("speed")
        if (idx > 1f) { idx = 0f; forward = !forward }
        out.fill(ctx.grad(if (forward) idx else 1 - idx))
    }
}

internal class Rainbow : Effect() {
    private var hue = 0f
    override fun render(ctx: RenderContext, out: IntArray) {
        val n = out.size
        val delta = ctx.p.f("frequency") / n
        for (i in 0 until n) out[i] = Rgb.hsv(hue + i * delta, 1f, 1f)
        hue = frac(hue + ctx.dt * 0.6f * ctx.p.f("speed") * 0.1f)
    }
}

internal class SingleColor : Effect() {
    private val mod = Modulator()
    override fun render(ctx: RenderContext, out: IntArray) {
        out.fill(ctx.p.color("color"))
        mod.apply(ctx, out)
    }
}

internal class RandomFlash : Effect() {
    private var last = -100.0
    private var hitFrom = 0
    private var hitTo = 0
    override fun render(ctx: RenderContext, out: IntArray) {
        val n = out.size
        val p = ctx.p
        val duration = p.f("hit_duration")
        val passed = ctx.time - last
        out.fill(0)
        if (passed < duration) {
            val c = Rgb.scale(p.color("hit_color"), (1 - passed / duration).toFloat())
            for (i in hitFrom until hitTo) out[i] = c
        } else if (Random.nextFloat() < p.f("hit_probability_per_sec") * ctx.dt * 10f) {
            val size = max(1, n * p.i("hit_relative_size") / 100)
            hitFrom = Random.nextInt(max(1, n - size + 1))
            hitTo = min(n, hitFrom + size)
            last = ctx.time
        }
    }
}

// ---------------------------------------------------------------- extras

internal class Ripples : Effect() {
    private class Ripple(val center: Float, var radius: Float, var life: Float, val color: Int)
    private val ripples = ArrayList<Ripple>()
    private var colorPos = 0f

    override fun render(ctx: RenderContext, out: IntArray) {
        val n = out.size
        val speed = ctx.p.f("speed")
        if (ctx.audio.beat && ripples.size < 16) {
            colorPos += 0.13f
            ripples += Ripple(Random.nextFloat() * n, 0f, 1f, ctx.gradWrap(colorPos))
        }
        val grow = (10f + speed * 140f) * ctx.dt
        val width = max(2f, n / 25f)
        out.fill(0)
        val it = ripples.iterator()
        while (it.hasNext()) {
            val r = it.next()
            r.radius += grow
            r.life -= ctx.dt * (0.5f + speed * 0.8f)
            if (r.life <= 0f) { it.remove(); continue }
            for (i in 0 until n) {
                val d = abs(abs(i - r.center) - r.radius)
                if (d < width) out[i] = Rgb.add(out[i], Rgb.scale(r.color, (1 - d / width) * r.life))
            }
        }
        val bed = ctx.audio.lows * 0.3f * ctx.p.f("glow")
        if (bed > 0.01f) for (i in 0 until n) out[i] = Rgb.add(out[i], Rgb.scale(ctx.gradWrap(colorPos), bed))
    }
}

internal class Sparkle : Effect() {
    private var level = FloatArray(0)
    private var color = IntArray(0)
    override fun render(ctx: RenderContext, out: IntArray) {
        val n = out.size
        if (level.size != n) { level = FloatArray(n); color = IntArray(n) }
        val decay = 0.95f - ctx.p.f("decay") * 0.2f
        for (i in 0 until n) level[i] *= decay
        val a = ctx.audio
        var spawn = a.highs * a.highs * n * 0.12f * (0.4f + ctx.p.f("density") * 1.2f)
        if (a.beat) spawn += n * 0.08f
        var count = spawn.toInt() + if (Random.nextFloat() < spawn - spawn.toInt()) 1 else 0
        while (count-- > 0) {
            val i = Random.nextInt(n)
            level[i] = 1f
            color[i] = ctx.gradWrap(Random.nextFloat())
        }
        for (i in 0 until n) out[i] = Rgb.scale(color[i], level[i])
    }
}
