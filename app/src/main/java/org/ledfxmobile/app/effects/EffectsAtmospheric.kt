package org.ledfxmobile.app.effects

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * LedFx's HSVEffect: subclasses fill hue / saturation / value per LED. Hue picks a colour from
 * the device's gradient (so palettes work), saturation fades towards white, value is brightness.
 */
internal abstract class HsvEffect : Effect() {
    protected var n = 0
    protected var h = FloatArray(0)
    protected var s = FloatArray(0)
    protected var v = FloatArray(0)
    private var start = -1.0
    /** Seconds since this effect started. */
    protected var elapsed = 0.0

    /** LedFx time(): sawtooth 0..1 looping every 65.536/modifier seconds. */
    protected fun time(modifier: Float, timestep: Double = elapsed): Float {
        val period = 65.536 / max(1e-6f, modifier)
        return frac(timestep / period).toFloat()
    }

    protected open fun resized() {}
    protected abstract fun renderHsv(ctx: RenderContext)

    override fun render(ctx: RenderContext, out: IntArray) {
        if (out.size != n) {
            n = out.size
            h = FloatArray(n); s = FloatArray(n) { 1f }; v = FloatArray(n)
            resized()
        }
        if (start < 0) start = ctx.time
        elapsed = ctx.time - start
        renderHsv(ctx)
        for (i in 0 until n) {
            val c = ctx.grad(frac(h[i]))
            var r = Rgb.r(c).toFloat(); var g = Rgb.g(c).toFloat(); var b = Rgb.b(c).toFloat()
            val mx = max(r, max(g, b))
            val desat = 1 - s[i].coerceIn(0f, 1f)
            r += (mx - r) * desat; g += (mx - g) * desat; b += (mx - b) * desat
            val value = v[i].coerceIn(0f, 1f)
            out[i] = Rgb.of((r * value).toInt(), (g * value).toInt(), (b * value).toInt())
        }
    }
}

/** Python-style modulo (result has the sign of the divisor). */
private fun pmod(a: Float, b: Float): Float = if (b == 0f) 0f else ((a % b) + b) % b

internal class BladePowerPlus : HsvEffect() {
    override fun renderHsv(ctx: RenderContext) {
        val p = ctx.p
        val bar = ctx.audio.power(p.s("frequency_range")) * p.f("multiplier") * 2
        val idx = (bar * n).toInt()
        val decay = p.f("decay") / 2 + 0.45f
        for (i in 0 until n) {
            h[i] = posOf(i, n); s[i] = 1f
            v[i] = if (i < idx) 1f else v[i] * decay
        }
    }

    override fun render(ctx: RenderContext, out: IntArray) {
        super.render(ctx, out)
        val bg = ctx.p.color("background_color")
        if (bg != 0) for (i in out.indices) out[i] = Rgb.add(out[i], bg)
    }
}

internal class Fire : HsvEffect() {
    private var heat = FloatArray(0)
    private var sparks = FloatArray(0)
    private var sparkX = FloatArray(0)
    private val lows = ExpFilter(0.05f, 0.99f)

    override fun resized() { heat = FloatArray(n) }

    override fun renderHsv(ctx: RenderContext) {
        val p = ctx.p
        val count = p.i("intensity")
        if (sparks.size != count) {
            sparks = FloatArray(count); sparkX = FloatArray(count) { Random.nextFloat() * 5 }
        }
        val lp = lows.update(ctx.audio.lowsPower(false))
        val cooling = 0.75f + lp * 0.25f
        val speed = p.f("speed") + lp * 0.01f
        val deltaScaled = ctx.dt * 1000f * speed
        for (i in 0 until n) heat[i] *= cooling
        if (n > 5) {
            val old = heat.copyOf()
            for (i in 5 until n) heat[i] = (old[i - 1] + old[i - 2] + old[i - 3] * 2 + old[i - 4] * 3) / 7f
        }
        val fadeChance = p.f("fade_chance") / 10f
        for (k in 0 until count) {
            if (sparks[k] <= 0f) { sparks[k] = 0.5f + Random.nextFloat() * 0.5f; sparkX[k] = Random.nextFloat() * 5 }
            val step = sparks[k] * sparks[k] * deltaScaled * (n / 100f)
            sparkX[k] += step
            if (sparkX[k] >= n || Random.nextFloat() < fadeChance) { sparks[k] = 0f; sparkX[k] = 0f; continue }
            val from = (sparkX[k] - step).toInt()
            val to = sparkX[k].toInt()
            val add = (1 - sparks[k] * 0.4f).coerceIn(0f, 1f) * 0.5f
            for (j in from until to) heat[j.coerceIn(0, n - 1)] += add
        }
        val shift = p.f("color_shift")
        for (i in 0 until n) {
            h[i] = min(1f, heat[i] * heat[i]) * 0.1f + shift
            s[i] = 1 - (heat[i] - 1) * 2
            v[i] = heat[i]
        }
    }
}

internal class BlockReflections : HsvEffect() {
    private val lows = ExpFilter(0.05f, 0.05f)
    override fun renderHsv(ctx: RenderContext) {
        val p = ctx.p
        val speed = p.f("speed"); val react = p.f("reactivity")
        val lp = lows.update(ctx.audio.lowsPower(false))
        val t2 = time(speed) * (PI * PI).toFloat() + 0.8f * react * lp
        val t1 = time(speed)
        val t3 = time(5 * speed) + react * lp
        val t4 = time(2 * speed) * (PI * PI).toFloat()
        val m = 0.3f + tri(t1) * 0.2f
        val c = tri(t3) * 10 + 4 * sin01(t4)
        for (i in 0 until n) {
            val hh = pmod((i - n / 2f) / n * c, m) + sin01(t2)
            h[i] = hh; s[i] = 1f
            v[i] = frac(abs(hh) + abs(m) + t1).pow(2)
        }
    }
}

internal class Crawler : HsvEffect() {
    private val lows = ExpFilter(0.1f, 0.1f)
    private var timestep = 0.0
    override fun renderHsv(ctx: RenderContext) {
        val p = ctx.p
        val speed = p.f("speed"); val react = p.f("reactivity")
        val lp = lows.update(ctx.audio.lowsPower(false))
        timestep += ctx.dt + lp * react * speed
        val t1 = time(speed * p.f("sway"), timestep)
        val t3 = time(speed * p.f("chop") + lp * react)
        val stretch = p.f("stretch")
        for (i in 0 until n) {
            // (LedFx computes a "chop" term here but discards it, so it's left out.)
            val hh = pmod((i + t3 * n) / n * stretch, stretch / 10) + posOf(i, n) + sin01(t1)
            h[i] = hh; s[i] = 1f
            v[i] = sin01(hh).pow(2)
        }
    }
}

internal class Energy2 : HsvEffect() {
    private val lows = ExpFilter(0.1f, 0.1f)
    override fun renderHsv(ctx: RenderContext) {
        val p = ctx.p
        val react = p.f("reactivity")
        val lp = lows.update(ctx.audio.lowsPower(false))
        val t1 = time(p.f("speed"))
        val shift = 2f * sin01(t1 + react * lp)
        val cut = 0.9f - (react + 0.3f) * lp
        for (i in 0 until n) {
            val vv = tri(frac(posOf(i, n) + shift)).pow(2)
            v[i] = vv
            s[i] = if (vv < cut) 1f else 0f
            h[i] = lp + t1
        }
    }
}

internal class Glitch : HsvEffect() {
    private var timestep = 0.0
    override fun renderHsv(ctx: RenderContext) {
        val p = ctx.p
        val speed = p.f("speed"); val react = p.f("reactivity")
        val lp = ctx.audio.lowsPower()
        timestep += ctx.dt + lp * react / speed
        val t1 = time(speed * 0.5f, timestep) * 2 * PI.toFloat()
        val t2 = time(speed * 0.5f, timestep)
        val t3 = time(speed * 2.5f, timestep)
        val t4 = time(speed, timestep) * 2 * PI.toFloat()
        val t5 = time(speed * 0.25f, timestep)
        val t6 = time(speed * 10f, timestep)
        val m = 0.3f + tri(t2) * 0.2f
        val c = tri(t3) * 10 + 4 * sin(t4)
        val satMin = p.f("saturation_threshold")
        for (i in 0 until n) {
            h[i] = pmod(i * c, m) + sin(t1)
            val i2 = if (n <= 1) 0f else 5f * i / (n - 1)
            val i3 = if (n <= 1) -1f else -1f + i.toFloat() / (n - 1)
            var s1 = tri(frac(t5 + i2)).pow(2)
            val s2 = tri(frac(t6 - i3)).pow(4)
            s1 = 1 - tri(s1 * s2)
            s[i] = s1.coerceIn(satMin, 1f)
            v[i] = 1f
        }
    }
}

internal class LavaLamp : HsvEffect() {
    private val lows = ExpFilter(0.05f, 0.3f)
    override fun renderHsv(ctx: RenderContext) {
        val p = ctx.p
        lows.alphaRise = p.f("reactivity")
        val lp = lows.update(ctx.audio.lowsPower(false))
        val contrast = 1 - p.f("contrast")
        val speed = p.f("speed")
        val t1 = time(speed * max(1f, 1 + lp * 0.004f))
        val t2 = time(speed * 2 * max(1f, 1 + lp * 0.007f))
        for (i in 0 until n) {
            val il = posOf(i, n)
            var w1 = sin01(t1 + il)
            var w2 = sin01(t2 - il)
            var w3 = sin01(frac(il + w1 + w2))
            h[i] = t1 + il
            w1 += 0.1f; w2 += lp * 0.7f; w3 += lp * 0.9f
            val prod = w1 * w2 * w3
            h[i] += prod * 0.1f
            s[i] = 1f
            v[i] = (prod + contrast).pow(2)
        }
    }
}

internal class Marching : HsvEffect() {
    private val lows = ExpFilter(0.05f, 0.2f)
    override fun renderHsv(ctx: RenderContext) {
        val p = ctx.p
        val speed = p.f("speed")
        val lp = lows.update(ctx.audio.lowsPower(false))
        val t1 = time(speed * 20)
        val t2 = time(speed) + p.f("reactivity") * lp * 20
        for (i in 0 until n) {
            val w2base = posOf(i, n)
            val w1 = sin01(t1 + w2base)
            h[i] = sin01(sin01(w1 - w2base))
            s[i] = 1f
            v[i] = w1 - sin01(t2 + 2 - w2base * 10)
        }
    }
}

internal class Melt : HsvEffect() {
    private val lows = ExpFilter(0.1f, 0.1f)
    private var timestep = 0.0
    override fun renderHsv(ctx: RenderContext) {
        val p = ctx.p
        val speed = p.f("speed")
        val lp = lows.update(ctx.audio.lowsPower(false))
        timestep += ctx.dt + lp * p.f("reactivity") / speed
        val t1 = time(speed * 5, timestep)
        val t2 = time(speed * 6.5f, timestep)
        for (i in 0 until n) {
            val c1 = 1 - posOf(i, n)
            var vv = sin01(c1)
            vv = sin01(vv + t1)
            vv = sin01(vv + t1)
            h[i] = c1 + t2; s[i] = 1f; v[i] = vv * vv
        }
    }
}

internal class MeltAndSparkle : HsvEffect() {
    private val lows = ExpFilter(0.1f, 0.1f)
    private val mids = ExpFilter(0.1f, 0.1f)
    private var timestep = 0.0
    private var direction = 1.0
    private var strobe = FloatArray(0)
    private var tmp = FloatArray(0)
    private var lastStrobe = -10.0

    override fun resized() { strobe = FloatArray(n); tmp = FloatArray(n) }

    override fun renderHsv(ctx: RenderContext) {
        val p = ctx.p
        val a = ctx.audio
        val speed = p.f("speed"); val react = p.f("reactivity")
        val lp = lows.update(a.lowsPower(false))
        val cutoff = p.f("strobe_threshold") / 10f
        if (lp > cutoff && Random.nextInt(200) == 0) direction *= -1
        val mp = mids.update(a.midsPower())
        val highs = a.thirdsMax()[2].let { min(1f, it * it) }
        val now = ctx.time
        var fire = false
        if (a.onset && now - lastStrobe > 1 - p.f("strobe_rate") && highs > cutoff) {
            fire = true; lastStrobe = now
        }
        timestep += (ctx.dt + lp * react * speed * 0.5f) * direction
        val t1 = time(speed * 20, timestep)
        val bass = lp * react * 0.5f
        val widthFactor = (1 - p.f("lava_width")).pow(2)
        val power = max(0.1f, 30 * widthFactor - mp * widthFactor)
        val bg = p.f("bg_bright")
        for (i in 0 until n) {
            val base = sin01(1 - posOf(i, n))
            h[i] = tri(tri(base + bass * speed * 5))
            var vv = sin01(base)
            vv = sin01(vv + t1)
            vv = tri(vv + (1 - t1))
            vv = tri(vv + bass)
            v[i] = max(0f, vv).pow(power) * bg
            s[i] = 1f
        }
        if (fire && n > 2) {
            val w = (p.f("strobe_width").pow(3) * n).toInt().coerceIn(1, n - 1)
            val pos = Random.nextInt(n - w)
            for (i in pos until pos + w) strobe[i] = 1f
        }
        for (i in 0 until n) {
            s[i] *= 1 - strobe[i]
            v[i] = min(1f, v[i] + strobe[i])
            strobe[i] *= 1 - p.f("strobe_decay_rate")
        }
        blurChannel(strobe, tmp, p.f("strobe_blur"))
    }
}

internal class Water : HsvEffect() {
    private var buf = arrayOf(FloatArray(0), FloatArray(0))
    private var cur = 0
    private var tmp = FloatArray(0)
    private val mids = floatArrayOf(0.25f, 0.75f)
    private val midSpeed = floatArrayOf(1f, -1f)
    private val highs = floatArrayOf(0.1f, 0.4f, 0.6f, 0.9f)
    private val highSpeed = floatArrayOf(1.5f, -1f, 1f, -1.5f)

    override fun resized() { buf = arrayOf(FloatArray(n), FloatArray(n)); tmp = FloatArray(n) }

    private fun drop(pos: Int, height: Float) {
        if (pos < 1 || pos > n - 2) return
        for (b in buf) { b[pos] = height; b[pos - 1] = height; b[pos + 1] = height }
    }

    override fun renderHsv(ctx: RenderContext) {
        if (n < 5) { v.fill(0f); return }
        val p = ctx.p
        val damp = 2f.pow(p.f("viscosity"))
        repeat(p.f("speed").toInt().coerceAtLeast(1)) {
            cur = 1 - cur
            val src = buf[1 - cur]; val dst = buf[cur]
            for (i in 1 until n - 1) dst[i] = (src[i - 1] + src[i + 1] + src[i] * 2) / 2 - dst[i]
            blurChannel(dst, tmp, 1f)
            for (i in 0 until n) dst[i] -= dst[i] / damp
        }
        val inten = ctx.audio.thirdsMax().map { min(1f, it * it) }
        val speed = p.f("speed")
        drop(1, inten[0] * p.f("bass_size")); drop(n / 2, inten[0] * p.f("bass_size")); drop(n - 2, inten[0] * p.f("bass_size"))
        for (k in mids.indices) {
            drop(1 + (mids[k] * (n - 2)).toInt(), inten[1] * p.f("mids_size"))
            mids[k] = frac(mids[k] + 0.0002f * midSpeed[k] * speed)
        }
        for (k in highs.indices) {
            drop(1 + (highs[k] * (n - 2)).toInt(), inten[2] * p.f("high_size"))
            highs[k] = frac(highs[k] + 0.0002f * highSpeed[k] * speed)
        }
        val shift = p.f("vertical_shift")
        val c = buf[cur]
        for (i in 0 until n) {
            h[i] = tri(c[i])
            val vv = (c[i] + shift) / (1 + shift)
            s[i] = (2f - (vv + shift)).coerceIn(0f, 1f)
            v[i] = vv.coerceIn(0f, 1f)
        }
    }
}
