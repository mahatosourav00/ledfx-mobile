package org.ledfxmobile.app.effects

import kotlin.math.abs
import kotlin.math.floor

/** Pixels are packed 0xRRGGBB ints. */
object Rgb {
    fun of(r: Int, g: Int, b: Int): Int =
        (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun r(c: Int) = (c shr 16) and 0xFF
    fun g(c: Int) = (c shr 8) and 0xFF
    fun b(c: Int) = c and 0xFF

    fun scale(c: Int, f: Float): Int {
        if (f <= 0f) return 0
        if (f >= 1f) return c
        return of((r(c) * f).toInt(), (g(c) * f).toInt(), (b(c) * f).toInt())
    }

    fun add(x: Int, y: Int): Int = of(r(x) + r(y), g(x) + g(y), b(x) + b(y))

    fun lerp(x: Int, y: Int, t: Float): Int = of(
        (r(x) + (r(y) - r(x)) * t).toInt(),
        (g(x) + (g(y) - g(x)) * t).toInt(),
        (b(x) + (b(y) - b(x)) * t).toInt(),
    )

    /** h, s, v in 0..1. */
    fun hsv(h: Float, s: Float, v: Float): Int {
        val hh = (h - floor(h)) * 6f
        val c = v * s
        val x = c * (1 - abs(hh % 2 - 1))
        val m = v - c
        val (r, g, b) = when (hh.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return of(((r + m) * 255).toInt(), ((g + m) * 255).toInt(), ((b + m) * 255).toInt())
    }
}

/** A colour gradient that effects sample with a position in 0..1. */
class Palette(val id: String, val name: String, stops: List<Int>) {
    private val colors = stops.toIntArray()
    private val lut = IntArray(256) { sampleSlow(it / 255f) }

    /** Gradient colour at [t] (wraps around, so animated palettes loop smoothly). */
    fun at(t: Float): Int {
        val w = t - floor(t)
        return lut[(w * 255f).toInt().coerceIn(0, 255)]
    }

    /** Gradient colour at [t] clamped to the ends (no wrap). */
    fun clamped(t: Float): Int = lut[(t.coerceIn(0f, 1f) * 255f).toInt()]

    val previewColors: List<Int> get() = colors.toList()

    private fun sampleSlow(t: Float): Int {
        if (colors.size == 1) return colors[0]
        val pos = t * (colors.size - 1)
        val i = pos.toInt().coerceAtMost(colors.size - 2)
        return Rgb.lerp(colors[i], colors[i + 1], pos - i)
    }
}

object Palettes {
    val all = listOf(
        // LedFx's built-in gradients
        Palette("rainbow", "Rainbow", listOf(0xFF0000, 0xFF7800, 0xFFC800, 0x00FF00, 0x00C78C, 0x0000FF, 0x800080, 0xFF00B2)),
        Palette("dancefloor", "Dancefloor", listOf(0xFF0000, 0xFF00B2, 0x0000FF)),
        Palette("plasma", "Plasma", listOf(0x0000FF, 0x800080, 0xFF0000, 0xFF2800, 0xFFC800)),
        Palette("ocean", "Ocean", listOf(0x00FFFF, 0x0000FF)),
        Palette("viridis", "Viridis", listOf(0x800080, 0x0000FF, 0x008080, 0x00FF00, 0xFFC800)),
        Palette("jungle", "Jungle", listOf(0x00FF00, 0x228B22, 0xFF7800)),
        Palette("spring", "Spring", listOf(0xFF00B2, 0xFF2800, 0xFFC800)),
        Palette("winter", "Winter", listOf(0x00C78C, 0x00FF32)),
        Palette("frost", "Frost", listOf(0x0000FF, 0x00FFFF, 0x800080, 0xFF00B2)),
        Palette("sunset", "Sunset", listOf(0x000080, 0xFF7800, 0xFF0000)),
        Palette("borealis", "Borealis", listOf(0xFF2800, 0x800080, 0x00C78C, 0x00FF00)),
        Palette("rust", "Rust", listOf(0xFF2800, 0xFF0000)),
        Palette("winamp", "Winamp", listOf(0x00FF00, 0xFFC800, 0xFF7800, 0xFF2800, 0xFF0000)),
        // Extras
        Palette("fire", "Fire", listOf(0x200000, 0xC00000, 0xFF5000, 0xFFC000, 0xFFF0A0)),
        Palette("neon", "Neon", listOf(0xFF00C8, 0x8000FF, 0x00E0FF)),
        Palette("ice", "Ice", listOf(0x0020FF, 0x60C0FF, 0xFFFFFF)),
        Palette("party", "Party", listOf(0xFF0050, 0xFFD000, 0x00FF80, 0x0080FF, 0xFF0050)),
        Palette("candy", "Candy", listOf(0xFF80C0, 0xC080FF, 0x80C0FF, 0xFF80C0)),
        Palette("warm", "Warm white", listOf(0xFF8C30, 0xFFC890)),
        Palette("red", "Red", listOf(0xFF0000)),
        Palette("blue", "Blue", listOf(0x0030FF)),
    )

    private val byId = all.associateBy { it.id }
    private val customCache = HashMap<List<Int>, Palette>()

    fun get(id: String): Palette = byId[id] ?: all[0]

    /** Palette for a device: a custom gradient from a preset wins over the chosen palette. */
    fun resolve(id: String, custom: List<Int>?): Palette {
        if (custom.isNullOrEmpty()) return get(id)
        synchronized(customCache) {
            return customCache.getOrPut(custom) { Palette("custom", "Custom", custom) }
        }
    }
}
