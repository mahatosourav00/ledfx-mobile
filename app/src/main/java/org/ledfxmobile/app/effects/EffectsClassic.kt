package org.ledfxmobile.app.effects

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

// Ports of LedFx's "Classic" and "BPM" effects.

internal class Energy : Effect() {
    private var target = FloatPixels(0)
    private var shown = FloatPixels(0)
    private val cycled = intArrayOf(-1, -1, -1)
    private var cycler = 0

    override fun render(ctx: RenderContext, out: IntArray) {
        val n = out.size
        if (target.n != n) { target = FloatPixels(n); shown = FloatPixels(n) }
        val p = ctx.p
        val mult = 1.6f - p.f("blur") / 17f
        val m = ctx.audio.thirdsMean(false)
        val colors = intArrayOf(p.color("color_lows"), p.color("color_mids"), p.color("color_high"))
        if (p.b("color_cycler")) {
            if (ctx.audio.beat) {
                cycled[cycler] = ctx.gradWrap(Random.nextFloat())
                cycler = (cycler + 1) % 3
            }
            for (k in 0..2) if (cycled[k] >= 0) colors[k] = cycled[k]
        }
        val additive = p.s("mixing_mode") == "additive"
        target.clear()
        for (k in 0..2) {
            val idx = (mult * n * m[k] * 1.6f).toInt()
            target.fill(0, idx, colors[k], additive = additive && k > 0)
        }
        val sens = p.f("sensitivity")
        val rise = sens
        val decay = (sens - 0.1f) * 0.7f
        for (i in 0 until n) {
            shown.r[i] = smooth(shown.r[i], target.r[i], rise, decay)
            shown.g[i] = smooth(shown.g[i], target.g[i], rise, decay)
            shown.b[i] = smooth(shown.b[i], target.b[i], rise, decay)
        }
        shown.toInts(out)
    }

    private fun smooth(v: Float, x: Float, rise: Float, decay: Float): Float {
        val a = if (x > v) rise else decay
        return a * x + (1 - a) * v
    }
}

internal class Spectrum : Effect() {
    private var y = FloatArray(0)
    private var filtered = FloatArray(0)
    private var prev = FloatArray(0)
    private var slow = FloatArray(0)
    private val perms = arrayOf(
        intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2),
        intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0),
    )

    override fun render(ctx: RenderContext, out: IntArray) {
        val n = out.size
        if (y.size != n) { y = FloatArray(n); filtered = FloatArray(n); prev = FloatArray(n); slow = FloatArray(n) }
        ctx.audio.melbank(y, filtered = false)
        ctx.audio.melbank(filtered, filtered = true)
        val perm = perms[ctx.p.i("rgb_mix").coerceIn(0, 5)]
        val ch = FloatArray(3)
        for (i in 0 until n) {
            slow[i] += (y[i] - slow[i]) * (if (y[i] > slow[i]) 0.5f else 0.1f)
            ch[perm[0]] = filtered[i]
            ch[perm[1]] = abs(y[i] - prev[i])
            ch[perm[2]] = slow[i]
            prev[i] = y[i]
            out[i] = Rgb.of((ch[0] * 1000).toInt(), (ch[1] * 1000).toInt(), (ch[2] * 1000).toInt())
        }
    }
}

internal class Wavelength : Effect() {
    private var y = FloatArray(0)
    override fun render(ctx: RenderContext, out: IntArray) {
        val n = out.size
        if (y.size != n) y = FloatArray(n)
        ctx.audio.melbank(y)
        for (i in 0 until n) out[i] = Rgb.scale(ctx.grad(posOf(i, n)), y[i])
    }
}

internal class Scroll : Effect() {
    private var px = FloatPixels(0)
    override fun render(ctx: RenderContext, out: IntArray) {
        val n = out.size
        if (px.n != n) px = FloatPixels(n)
        val p = ctx.p
        val th = p.f("threshold")
        val inten = ctx.audio.thirdsMax().map { min(1f, it * it) }.toFloatArray()
        if (inten[0] < th / 10) inten[0] = 0f
        if (inten[1] < th / 8) inten[1] = 0f
        if (inten[2] < th / 7) inten[2] = 0f
        val speed = p.i("speed").coerceIn(1, n)
        px.shift(speed)
        px.scale(p.f("decay"))
        for (i in 0 until speed) {
            px.set(i, p.color("color_lows"), inten[0])
            px.add(i, p.color("color_mids"), inten[1])
            px.add(i, p.color("color_high"), inten[2])
        }
        px.toInts(out)
    }
}

internal class ScrollPlus : Effect() {
    private var px = FloatPixels(0)
    private var acc = 0f
    override fun render(ctx: RenderContext, out: IntArray) {
        val n = out.size
        if (px.n != n) px = FloatPixels(n)
        val p = ctx.p
        val th = p.f("threshold")
        val inten = ctx.audio.thirdsMax().map { min(1f, it * it) }.toFloatArray()
        if (inten[0] < th / 10) inten[0] = 0f
        if (inten[1] < th / 8) inten[1] = 0f
        if (inten[2] < th / 7) inten[2] = 0f
        acc += ctx.dt * p.f("scroll_per_sec") * n
        val shift = min(acc.toInt(), n)
        acc -= acc.toInt()
        if (shift > 0) px.shift(shift)
        px.scale((1 - ctx.dt * p.f("decay_per_sec") * 3).coerceIn(0f, 1f))
        for (i in 0 until shift) {
            px.set(i, p.color("color_lows"), inten[0])
            px.add(i, p.color("color_mids"), inten[1])
            px.add(i, p.color("color_high"), inten[2])
        }
        px.toInts(out)
    }
}

internal class Power : Effect() {
    private var sparks = FloatPixels(0)
    private var bass = FloatPixels(0)
    private var y = FloatArray(0)
    private val bassFilter = ExpFilter(0.1f, 0.99f)

    override fun render(ctx: RenderContext, out: IntArray) {
        val n = out.size
        if (y.size != n) { sparks = FloatPixels(n); bass = FloatPixels(n); y = FloatArray(n) }
        val p = ctx.p
        sparks.scale(p.f("sparks_decay_rate"))
        if (ctx.audio.onset) repeat(max(1, n / 20)) { sparks.set(Random.nextInt(n), p.color("sparks_color")) }
        bass.scale(p.f("bass_decay_rate"))
        val b = bassFilter.update(ctx.audio.lowsPower(false))
        bass.fill(0, (b * n).toInt(), ctx.grad(b))
        ctx.audio.melbank(y)
        for (i in 0 until n) {
            val bg = Rgb.scale(ctx.grad(posOf(i, n)), y[i])
            out[i] = Rgb.of(
                (Rgb.r(bg) + bass.r[i] + sparks.r[i]).toInt(),
                (Rgb.g(bg) + bass.g[i] + sparks.g[i]).toInt(),
                (Rgb.b(bg) + bass.b[i] + sparks.b[i]).toInt(),
            )
        }
    }
}

internal class Magnitude : Effect() {
    override fun render(ctx: RenderContext, out: IntArray) {
        val m = ctx.audio.power(ctx.p.s("frequency_range"))
        for (i in out.indices) out[i] = Rgb.scale(ctx.grad(posOf(i, out.size)), m)
    }
}

internal class PitchSpectrum : Effect() {
    private var px = FloatPixels(0)
    private var y = FloatArray(0)
    private var avgMidi = 0f
    private val minMidi = 21f
    private val maxMidi = 108f

    override fun render(ctx: RenderContext, out: IntArray) {
        val n = out.size
        if (y.size != n) { px = FloatPixels(n); y = FloatArray(n) }
        ctx.audio.melbank(y, filtered = false)
        val midi = ctx.audio.pitchMidi
        val r = ctx.p.f("responsiveness")
        if (avgMidi == 0f) avgMidi = midi
        if (midi >= minMidi) avgMidi = avgMidi * (1 - r) + midi * r
        val note = if (avgMidi >= minMidi) ctx.grad(((avgMidi - minMidi) / (maxMidi - minMidi)).coerceIn(0f, 1f)) else 0
        val keep = 1 - ctx.p.f("fade_rate")
        for (i in 0 until n) {
            val a = y[i].coerceIn(0f, 1f)
            px.r[i] = (px.r[i] * (1 - a) + Rgb.r(note) * a) * keep
            px.g[i] = (px.g[i] * (1 - a) + Rgb.g(note) * a) * keep
            px.b[i] = (px.b[i] * (1 - a) + Rgb.b(note) * a) * keep
        }
        px.toInts(out)
    }
}

/** Drops grow and fade where lows / mids / highs suddenly rise. */
internal class Rain : Effect() {
    private class Drop(val pos: Int, val color: Int, var frame: Int = 0)
    private val drops = ArrayList<Drop>()
    private var pulse = FloatPixels(0)
    private val filtered = Array(3) { ExpFilter(alphaDecay = 0.5f, alphaRise = 0.1f) }
    private val frames = 24

    override fun render(ctx: RenderContext, out: IntArray) {
        val n = out.size
        if (pulse.n != n) pulse = FloatPixels(n)
        val p = ctx.p
        val inten = ctx.audio.thirdsMax()
        val keys = listOf("lows", "mids", "high")
        val names = listOf("Lows", "Mids", "Highs")
        for (k in 0..2) {
            val color = p.color("${keys[k]}_color")
            if (inten[k] - filtered[k].value > p.f("${keys[k]}_sensitivity")) {
                if (p.s("pulse_strip") == names[k]) pulse.fill(0, n, color)
                else if (drops.size < 64) drops += Drop(Random.nextInt(n), color)
            }
            filtered[k].update(inten[k])
        }
        val acc = FloatPixels(n)
        val it = drops.iterator()
        while (it.hasNext()) {
            val d = it.next()
            val t = d.frame.toFloat() / frames
            val radius = 1f + t * max(2f, n / 30f)
            val bright = (1 - t).pow(1.5f)
            val r = radius.toInt() + 1
            for (o in -r..r) {
                val i = d.pos + o
                if (i in 0 until n) {
                    val w = max(0f, 1 - abs(o) / (radius + 0.5f))
                    acc.add(i, d.color, w * bright)
                }
            }
            if (++d.frame >= frames) it.remove()
        }
        for (i in 0 until n) {
            acc.r[i] += pulse.r[i]; acc.g[i] += pulse.g[i]; acc.b[i] += pulse.b[i]
        }
        pulse.scale(0.9f)
        acc.toInts(out)
    }
}

internal class RealStrobe : Effect() {
    private var bass = FloatPixels(0)
    private var strobe = FloatPixels(0)
    private var colorIdx = 0f
    private var bassColor = 0
    private var lastShift = -10.0
    private var lastBass = -10.0

    override fun render(ctx: RenderContext, out: IntArray) {
        val n = out.size
        if (bass.n != n) { bass = FloatPixels(n); strobe = FloatPixels(n) }
        val p = ctx.p
        val now = ctx.time
        if (now - lastShift > p.f("color_shift_delay")) {
            colorIdx = frac(colorIdx + p.f("color_step"))
            bassColor = ctx.gradWrap(colorIdx)
            lastShift = now
        }
        val bassDecay = p.f("bass_strobe_decay_rate")
        if (ctx.audio.beat && now - lastBass > 0.2 && bassDecay > 0f) {
            bass.fill(0, n, bassColor); lastBass = now
        }
        if (ctx.audio.onset) {
            val w = min(p.i("strobe_width"), n)
            val pos = if (n - w <= 0) 0 else Random.nextInt(n - w)
            strobe.fill(pos, pos + w, p.color("strobe_color"))
        }
        for (i in 0 until n) out[i] = Rgb.of(
            (bass.r[i] + strobe.r[i]).toInt(), (bass.g[i] + strobe.g[i]).toInt(), (bass.b[i] + strobe.b[i]).toInt(),
        )
        strobe.scale(p.f("strobe_decay_rate"))
        bass.scale(bassDecay)
    }
}

/** Scan, and Scan and Flare when [flare] is true. */
internal class Scan(private val flare: Boolean) : Effect() {
    private class Sparkle(var pos: Float, val width: Int, val speed: Float, val dieOff: Float, val born: Double) {
        var health = 1f
    }
    private var pos = 0f
    private var returning = false
    private val sparkles = ArrayList<Sparkle>()

    override fun render(ctx: RenderContext, out: IntArray) {
        val n = out.size
        val p = ctx.p
        val power = ctx.audio.power(p.s("frequency_range")) * 2
        val bar = power * p.f("multiplier")
        val widthPx = max(1, n * p.i("scan_width") / 100)
        val stepPerSec = n / 100f * p.i("speed")
        val step = ctx.dt * stepPerSec * bar
        val bounce = p.b("bounce")
        pos += if (returning) -step else step
        if (bounce) {
            if (pos > n - widthPx) { returning = true; pos = (n - widthPx).toFloat() }
            if (pos < 0) { returning = false; pos = 0f }
        } else {
            if (pos > n) pos %= n.toFloat()
            if (pos < 0) returning = false
        }

        var color = if (p.b("use_grad")) ctx.gradWrap(pos / n) else p.color("color_scan")
        if (p.b("color_intensity")) color = Rgb.scale(color, min(1f, power))
        val fullGrad = !flare && p.b("full_grad")
        val count = if (flare) 1 else p.i("count").coerceIn(1, 10)
        out.fill(0)
        for (k in 0 until count) {
            val start = pos.toInt() + k * n / count
            for (j in 0 until widthPx) {
                val i = if (bounce && count == 1) start + j else Math.floorMod(start + j, n)
                if (i in 0 until n) {
                    out[i] = if (fullGrad) Rgb.scale(ctx.grad(posOf(i, n)), if (p.b("color_intensity")) min(1f, power) else 1f)
                    else color
                }
            }
        }

        if (flare) {
            val now = ctx.time
            sparkles.removeAll { now - it.born > it.dieOff }
            if (sparkles.size < p.i("sparkles_max") && power > p.f("sparkles_threshold") * 2) {
                val w = max(1, (widthPx * p.f("sparkles_size")).toInt())
                val dir = if (returning) -1f else 1f
                val front = if (returning) pos else pos + widthPx
                sparkles += Sparkle(front, w, dir * stepPerSec * 1.5f, p.f("sparkles_time"), now)
            }
            for (s in sparkles) {
                s.health = (1 - ((now - s.born) / s.dieOff).toFloat()).coerceIn(0f, 1f)
                s.pos = Math.floorMod((s.pos + s.speed * ctx.dt * s.health).toInt(), n).toFloat() +
                    frac(s.pos + s.speed * ctx.dt * s.health)
                val white = Rgb.scale(0xFFFFFF, s.health)
                for (j in 0 until s.width) {
                    val i = Math.floorMod(s.pos.toInt() + j, n)
                    out[i] = Rgb.add(out[i], white)
                }
            }
        }
    }
}

internal class ScanMulti : Effect() {
    private val pos = FloatArray(3)
    private val returning = BooleanArray(3)
    private val filters = Array(3) { ExpFilter(0.7f, 0.9f) }

    override fun render(ctx: RenderContext, out: IntArray) {
        val n = out.size
        val p = ctx.p
        val a = ctx.audio
        val powers = if (p.s("input_source") == "Melbank") {
            a.thirdsMean(false).map { it * 2 }.toFloatArray()
        } else floatArrayOf(a.lowsPower() * 2, a.midsPower() * 2, a.highPower() * 2)
        val colors = intArrayOf(p.color("color_low"), p.color("color_mid"), p.color("color_high"))
        val widthPx = max(1, n * p.i("scan_width") / 100)
        val stepPerSec = n / 100f * p.i("speed")
        out.fill(0)
        for (k in 0..2) {
            var power = powers[k]
            if (p.b("filter")) {
                filters[k].alphaRise = p.f("attack"); filters[k].alphaDecay = p.f("decay")
                power = filters[k].update(power)
            }
            val step = ctx.dt * stepPerSec * power * p.f("multiplier")
            pos[k] += if (returning[k]) -step else step
            if (p.b("bounce")) {
                if (pos[k] > n - widthPx) { returning[k] = true; pos[k] = (n - widthPx).toFloat() }
                if (pos[k] < 0) { returning[k] = false; pos[k] = 0f }
            } else {
                if (pos[k] > n) pos[k] = 0f
                if (pos[k] < 0) returning[k] = false
            }
            var c = if (p.b("use_grad")) ctx.gradWrap(pos[k] / n) else colors[k]
            if (p.b("color_intensity")) c = Rgb.scale(c, min(1f, power))
            for (j in 0 until widthPx) {
                val i = Math.floorMod(pos[k].toInt() + j, n)
                out[i] = Rgb.add(out[i], c)
            }
        }
    }
}

internal class Spotlight : Effect() {
    private class Spot(val center: Int, val born: Double, val inner: Int, val outer: Int)
    private val spots = ArrayList<Spot>()
    private val activity = ExpFilter(0.08f, 0.5f)
    private var lastPower = 0f
    private var spawnAcc = 0f
    private var lastSpawn = 0.0
    private var phase = 0f

    override fun render(ctx: RenderContext, out: IntArray) {
        val n = out.size
        val p = ctx.p
        val a = ctx.audio
        val now = ctx.time
        val fade = p.f("fade_time")
        if (p.b("use_gradient")) phase = frac(phase + ctx.dt * p.f("gradient_speed"))

        val weighted = 0.5f * a.lowsPower() + 0.3f * a.midsPower() + 0.2f * a.highPower()
        val delta = max(0f, weighted - lastPower)
        lastPower = weighted
        val level = activity.update((weighted + delta * 1.2f).coerceIn(0f, 2f)).coerceIn(0f, 1f)
        val maxSpots = p.i("max_active_spots")
        val cap = min(3, maxSpots) + ((maxSpots - min(3, maxSpots)) * level).toInt()

        spots.removeAll { now - it.born >= fade }
        var rate = 2.5f + 14f * level
        if (a.onset) rate *= 1.8f
        spawnAcc += rate * ctx.dt
        var spawned = 0
        while (spawnAcc >= 1f && spots.size < cap && spawned < 6 && now - lastSpawn >= 0.02) {
            spawnAcc -= 1f; spawned++; lastSpawn = now
            val (inner, outer) = if (p.b("use_gradient")) {
                val c = ctx.gradWrap(phase + (Random.nextFloat() - 0.5f) * p.f("spot_color_span"))
                Rgb.lerp(c, 0xFFFFFF, 0.35f) to c
            } else p.color("center_color") to p.color("edge_color")
            spots += Spot(Random.nextInt(n), now, inner, outer)
        }
        spawnAcc = min(spawnAcc, 3f)

        val half = max(1f, n * p.f("spot_width") / 100f / 2f)
        val acc = FloatPixels(n)
        for (s in spots) {
            val life = (1 - (now - s.born) / fade).toFloat().coerceIn(0f, 1f).pow(1.4f)
            val r = half.toInt() + 1
            for (o in -r..r) {
                val t = abs(o) / half
                if (t > 1f) continue
                val falloff = (1 - t * t).pow(1.5f)
                acc.add(Math.floorMod(s.center + o, n), Rgb.lerp(s.inner, s.outer, t), falloff * life)
            }
        }
        acc.toInts(out)
    }
}

// ---------------------------------------------------------------- beat-synced (BPM)

private fun ease(method: String, x: Float): Float = when (method) {
    "ease_in_out" -> 0.5f * sin(PI.toFloat() * (x - 0.5f)) + 0.5f
    "ease_in" -> x * x
    "ease_out" -> -((x - 1) * (x - 1)) + 1
    else -> x
}

internal class Bar : Effect() {
    private var phase = 0
    private var colorIdx = 0f
    private var lastOsc = 0f

    override fun render(ctx: RenderContext, out: IntArray) {
        val n = out.size
        val p = ctx.p
        val osc = frac(ctx.audio.beatPhase + p.f("beat_offset"))
        if (lastOsc > 0.2f && osc < 0.1f) {
            phase = 1 - phase
            if (phase == 0) colorIdx = frac(colorIdx + p.f("color_step"))
        }
        lastOsc = osc
        var x = ease(p.s("ease_method"), osc)
        val barLen = 0.3f
        var start = 0f
        var end = 1f
        when (p.s("mode")) {
            "wipe" -> if (phase == 0) end = x else start = x
            "bounce" -> {
                x *= (1 - barLen)
                if (phase == 0) { start = x; end = x + barLen } else { end = 1 - x; start = 1 - (x + barLen) }
            }
            "in-out" -> if (phase == 0) end = x else end = 1 - x
        }
        var color = ctx.gradWrap(colorIdx)
        val skip = p.s("beat_skip")
        if (skip != "none") {
            val count = ctx.audio.beatCount % 4
            val hit = if (p.s("skip_every") == "2") (count == 0 || count == 1) else count % 2 == 0
            if (hit == (skip == "even")) color = 0
        }
        out.fill(0)
        for (i in (n * start).toInt().coerceAtLeast(0) until (n * end).toInt().coerceAtMost(n)) out[i] = color
    }
}

internal class MultiBar : Effect() {
    private var phase = 0
    private var colorIdx = 0f

    override fun render(ctx: RenderContext, out: IntArray) {
        val n = out.size
        val p = ctx.p
        val step = p.f("color_step")
        if (ctx.audio.tempoBeat) {
            phase = 1 - phase
            colorIdx = frac(colorIdx + step)
        }
        val x = ease(p.s("ease_method"), ctx.audio.beatPhase)
        var fg = ctx.gradWrap(colorIdx)
        var bg = ctx.gradWrap(colorIdx + step)
        val idx: Float
        if (p.s("mode") == "wipe" && phase == 1) {
            idx = 1 - x
            val t = fg; fg = bg; bg = t
        } else idx = x
        val cut = (n * idx).toInt()
        for (i in 0 until n) out[i] = if (i < cut) bg else fg
    }
}

internal class BpmStrobe : Effect() {
    override fun render(ctx: RenderContext, out: IntArray) {
        val p = ctx.p
        val freq = 1 shl Effects.STROBE_FREQUENCIES.indexOf(p.s("strobe_frequency")).coerceAtLeast(0)
        val o = ctx.audio.beatPhase
        val bar = ctx.audio.barPhase
        val color = ctx.gradWrap(bar / 4f)
        val period = 1f / freq
        val sinceStrobe = Math.floorMod((-o * 1e6).toLong(), (period * 1e6).toLong()) / 1e6f
        var bri = (sinceStrobe * freq).pow(p.f("strobe_decay")) * (1 - o).pow(p.f("beat_decay"))
        val pattern = p.s("strobe_pattern")
        if (pattern.getOrNull(bar.toInt().coerceIn(0, 3)) != '*') bri = 0f
        out.fill(Rgb.scale(color, (bri * bri).coerceIn(0f, 1f)))
    }
}
