package org.ledfxmobile.app.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * One analysed slice of audio, produced once per rendered frame (60 fps).
 * Level values are 0..1. Mirrors what LedFx effects read from its audio source.
 */
class AudioFrame(
    /** Log-spaced frequency bands ("melbank"), smoothed. */
    val bands: FloatArray,
    /** Same bands without smoothing. */
    val bandsRaw: FloatArray,
    val volume: Float,
    /** Smoothed powers: [0]=beat (<100 Hz), [1]=bass, [2]=mids, [3]=highs. */
    val power: FloatArray,
    val powerRaw: FloatArray,
    /** Kick detected on this frame (LedFx "volume_beat_now"). */
    val beat: Boolean,
    /** Sharp change in the spectrum on this frame (LedFx "onset"). */
    val onset: Boolean,
    /** 1.0 at a kick, decaying towards 0. */
    val beatPulse: Float,
    /** Position inside the current beat, 0..1 (LedFx "beat_oscillator"). */
    val beatPhase: Float,
    /** Position inside the bar of 4 beats, 0..4 (LedFx "bar_oscillator"). */
    val barPhase: Float,
    /** Beats counted by the tempo tracker. */
    val beatCount: Int,
    /** True on the frame a tempo beat starts (LedFx "bpm_beat_now"). */
    val tempoBeat: Boolean,
    val bpm: Float,
    /** Dominant pitch as a MIDI note number, or 0 when there isn't one. */
    val pitchMidi: Float,
    val silent: Boolean,
) {
    val lows get() = min(1f, (power[0] + power[1]) * 0.75f)
    val mids get() = power[2]
    val highs get() = power[3]

    /** LedFx frequency_range names -> power. */
    fun power(range: String, filtered: Boolean = true): Float {
        val p = if (filtered) power else powerRaw
        return when (range) {
            "Beat" -> p[0]
            "Bass" -> p[1]
            "Mids" -> p[2]
            "High" -> p[3]
            else -> min(1f, (p[0] + p[1]) * 0.75f) // "Lows (beat+bass)"
        }
    }

    fun lowsPower(filtered: Boolean = true) = power("Lows (beat+bass)", filtered)
    fun midsPower(filtered: Boolean = true) = if (filtered) power[2] else powerRaw[2]
    fun highPower(filtered: Boolean = true) = if (filtered) power[3] else powerRaw[3]

    /** Max of the low / mid / high thirds of the bands (LedFx "melbank_thirds"). */
    fun thirdsMax(filtered: Boolean = true): FloatArray {
        val b = if (filtered) bands else bandsRaw
        val n = b.size
        return FloatArray(3) { t ->
            var m = 0f
            for (i in t * n / 3 until (t + 1) * n / 3) m = max(m, b[i])
            m
        }
    }

    fun thirdsMean(filtered: Boolean = true): FloatArray {
        val b = if (filtered) bands else bandsRaw
        val n = b.size
        return FloatArray(3) { t ->
            var s = 0f
            val from = t * n / 3
            val to = (t + 1) * n / 3
            for (i in from until to) s += b[i]
            s / (to - from)
        }
    }

    /** Bands stretched to [out].size values, like LedFx's melbank(size=pixel_count). */
    fun melbank(out: FloatArray, filtered: Boolean = true) {
        val b = if (filtered) bands else bandsRaw
        val n = out.size
        if (n == 1) { out[0] = b.average().toFloat(); return }
        for (i in 0 until n) {
            val f = i.toFloat() / (n - 1) * (b.size - 1)
            val j = f.toInt().coerceAtMost(b.size - 2)
            val t = f - j
            out[i] = b[j] * (1 - t) + b[j + 1] * t
        }
    }

    companion object {
        val EMPTY = AudioFrame(
            FloatArray(AudioAnalyzer.BAND_COUNT), FloatArray(AudioAnalyzer.BAND_COUNT), 0f,
            FloatArray(4), FloatArray(4), false, false, 0f, 0f, 0f, 0, false, 120f, 0f, true,
        )
    }
}

/**
 * Turns raw PCM into [AudioFrame]s: windowed FFT -> log-spaced bands with adaptive gain,
 * frequency-range powers, kick/beat detection, spectral-flux onsets, a tempo tracker for
 * beat-synced effects, and a simple pitch estimate.
 *
 * [push] is called from the capture thread, [analyze] from the render thread.
 */
class AudioAnalyzer(
    private val sampleRate: Int = SAMPLE_RATE,
    private val fftSize: Int = 2048,
) {
    companion object {
        const val SAMPLE_RATE = 44100
        const val BAND_COUNT = 32
        private const val MIN_FREQ = 35.0
        private const val MAX_FREQ = 16000.0
        private const val DYNAMIC_RANGE_DB = 42f
        private const val PEAK_FLOOR_DB = -48f
        private const val BEAT_HISTORY = 43
        private const val FLUX_HISTORY = 40
        /** Upper edge (Hz) of beat, bass, mids; highs go to MAX_FREQ. */
        private val RANGE_EDGES = doubleArrayOf(100.0, 250.0, 4000.0)
    }

    /** User sensitivity, 0..1 (0.5 = neutral). */
    @Volatile var sensitivity = 0.5f

    private val ring = FloatArray(fftSize)
    private var writePos = 0
    private val ringLock = Any()

    private val fft = Fft(fftSize)
    private val window = FloatArray(fftSize) { (0.5 - 0.5 * cos(2 * PI * it / (fftSize - 1))).toFloat() }
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val magDb = FloatArray(fftSize / 2)
    private val binHz = sampleRate.toDouble() / fftSize

    private val bandStart = IntArray(BAND_COUNT)
    private val bandEnd = IntArray(BAND_COUNT)
    private val bandRange = IntArray(BAND_COUNT) // which power range each band feeds
    private val smoothed = FloatArray(BAND_COUNT)
    private val prevRaw = FloatArray(BAND_COUNT)
    private val powerFiltered = FloatArray(4)
    private var peakDb = PEAK_FLOOR_DB
    private var smoothVolume = 0f

    // Kick detection
    private val bassHistory = FloatArray(BEAT_HISTORY)
    private var bassIndex = 0
    private var bassFilled = 0
    private var lastKickNanos = 0L
    private var beatPulse = 0f

    // Onsets
    private val fluxHistory = FloatArray(FLUX_HISTORY)
    private var fluxIndex = 0
    private var fluxFilled = 0
    private var lastOnsetNanos = 0L

    // Tempo tracking
    private val intervals = ArrayDeque<Double>()
    private var periodSec = 0.5
    private var lastTempoBeat = 0.0
    private var nextTempoBeat = 0.5
    private var beatCount = 0
    private var lastKickSec = -10.0

    init {
        for (b in 0 until BAND_COUNT) {
            val f0 = MIN_FREQ * (MAX_FREQ / MIN_FREQ).pow(b.toDouble() / BAND_COUNT)
            val f1 = MIN_FREQ * (MAX_FREQ / MIN_FREQ).pow((b + 1).toDouble() / BAND_COUNT)
            val s = max(1, (f0 / binHz).toInt())
            val e = max(s + 1, (f1 / binHz).toInt())
            bandStart[b] = min(s, fftSize / 2 - 1)
            bandEnd[b] = min(e, fftSize / 2)
            val center = sqrt(f0 * f1)
            bandRange[b] = RANGE_EDGES.indexOfFirst { center < it }.let { if (it < 0) 3 else it }
        }
    }

    fun push(samples: FloatArray, count: Int) {
        synchronized(ringLock) {
            for (i in 0 until count) {
                ring[writePos] = samples[i]
                writePos = (writePos + 1) % fftSize
            }
        }
    }

    fun reset() {
        synchronized(ringLock) { ring.fill(0f) }
        smoothed.fill(0f)
        prevRaw.fill(0f)
        powerFiltered.fill(0f)
        peakDb = PEAK_FLOOR_DB
        bassFilled = 0
        fluxFilled = 0
        beatPulse = 0f
        intervals.clear()
    }

    fun analyze(nowNanos: Long = System.nanoTime()): AudioFrame {
        val nowSec = nowNanos / 1e9
        var sumSq = 0f
        synchronized(ringLock) {
            for (i in 0 until fftSize) {
                val s = ring[(writePos + i) % fftSize]
                sumSq += s * s
                re[i] = s * window[i]
                im[i] = 0f
            }
        }
        val rms = sqrt(sumSq / fftSize)
        val silent = rms < 0.0005f
        fft.transform(re, im)

        val norm = fftSize / 4f
        for (k in 1 until fftSize / 2) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k]) / norm
            magDb[k] = 20f * log10(mag + 1e-9f)
        }

        // ---- bands with adaptive gain
        val gainDb = (sensitivity - 0.5f) * 36f
        val bandsDb = FloatArray(BAND_COUNT)
        var frameMaxDb = -120f
        for (b in 0 until BAND_COUNT) {
            var best = -120f
            var sum = 0f
            for (k in bandStart[b] until bandEnd[b]) {
                best = max(best, magDb[k]); sum += magDb[k]
            }
            val avg = sum / (bandEnd[b] - bandStart[b])
            val tilt = 24f * b / BAND_COUNT // music energy falls ~3 dB/octave
            bandsDb[b] = 0.6f * best + 0.4f * avg + tilt + gainDb
            frameMaxDb = max(frameMaxDb, bandsDb[b])
        }
        peakDb = if (frameMaxDb > peakDb) frameMaxDb else max(PEAK_FLOOR_DB + gainDb, peakDb - 0.08f)
        val floorDb = peakDb - DYNAMIC_RANGE_DB

        val bands = FloatArray(BAND_COUNT)
        val raw = FloatArray(BAND_COUNT)
        for (b in 0 until BAND_COUNT) {
            val target = ((bandsDb[b] - floorDb) / DYNAMIC_RANGE_DB).coerceIn(0f, 1f).let { it * it }
            raw[b] = if (silent) 0f else target
            val coeff = if (raw[b] > smoothed[b]) 0.65f else 0.18f
            smoothed[b] += (raw[b] - smoothed[b]) * coeff
            bands[b] = smoothed[b]
        }

        // ---- frequency range powers (beat, bass, mids, highs)
        val powerRaw = FloatArray(4)
        val counts = IntArray(4)
        for (b in 0 until BAND_COUNT) { powerRaw[bandRange[b]] += raw[b]; counts[bandRange[b]]++ }
        for (r in 0 until 4) {
            powerRaw[r] = if (counts[r] > 0) min(1f, powerRaw[r] / counts[r] * 1.3f) else 0f
            val a = if (powerRaw[r] > powerFiltered[r]) 0.97f else 0.2f
            powerFiltered[r] += (powerRaw[r] - powerFiltered[r]) * a
        }

        // ---- volume
        val volTarget = if (silent) 0f
        else ((20f * log10(rms + 1e-9f) + 50f + gainDb) / 45f).coerceIn(0f, 1f)
        smoothVolume += (volTarget - smoothVolume) * (if (volTarget > smoothVolume) 0.5f else 0.1f)

        // ---- kick
        var bassEnergy = 0f
        for (k in bandStart[0] until bandEnd[5]) bassEnergy += 10f.pow(magDb[k] / 10f)
        val kick = detectKick(bassEnergy, nowNanos, silent)
        beatPulse = if (kick) 1f else beatPulse * 0.88f

        // ---- onset (spectral flux)
        var flux = 0f
        for (b in 0 until BAND_COUNT) { flux += max(0f, raw[b] - prevRaw[b]); prevRaw[b] = raw[b] }
        val onset = detectOnset(flux, nowNanos, silent)

        // ---- tempo
        val tempoBeat = trackTempo(nowSec, kick)
        val phase = ((nowSec - lastTempoBeat) / periodSec).toFloat().coerceIn(0f, 0.999f)

        return AudioFrame(
            bands = bands, bandsRaw = raw, volume = smoothVolume,
            power = powerFiltered.copyOf(), powerRaw = powerRaw,
            beat = kick, onset = onset, beatPulse = beatPulse,
            beatPhase = phase, barPhase = (beatCount % 4) + phase, beatCount = beatCount,
            tempoBeat = tempoBeat, bpm = (60.0 / periodSec).toFloat(),
            pitchMidi = if (silent) 0f else pitch(floorDb - gainDb),
            silent = silent,
        )
    }

    private fun detectKick(energy: Float, now: Long, silent: Boolean): Boolean {
        var mean = 0f
        val n = max(1, bassFilled)
        for (i in 0 until bassFilled) mean += bassHistory[i]
        mean /= n
        var variance = 0f
        for (i in 0 until bassFilled) { val d = bassHistory[i] - mean; variance += d * d }
        variance /= n

        bassHistory[bassIndex] = energy
        bassIndex = (bassIndex + 1) % BEAT_HISTORY
        if (bassFilled < BEAT_HISTORY) bassFilled++
        if (silent || bassFilled < BEAT_HISTORY / 2 || mean <= 1e-9f) return false

        val normVar = variance / (mean * mean)
        val c = (1.55f - 0.15f * ln(1f + normVar)).coerceIn(1.15f, 1.6f) - (sensitivity - 0.5f) * 0.3f
        if (energy > c * mean && now - lastKickNanos > 280_000_000L) {
            lastKickNanos = now
            return true
        }
        return false
    }

    private fun detectOnset(flux: Float, now: Long, silent: Boolean): Boolean {
        var mean = 0f
        val n = max(1, fluxFilled)
        for (i in 0 until fluxFilled) mean += fluxHistory[i]
        mean /= n
        var sd = 0f
        for (i in 0 until fluxFilled) { val d = fluxHistory[i] - mean; sd += d * d }
        sd = sqrt(sd / n)
        fluxHistory[fluxIndex] = flux
        fluxIndex = (fluxIndex + 1) % FLUX_HISTORY
        if (fluxFilled < FLUX_HISTORY) fluxFilled++
        if (silent || fluxFilled < 10) return false
        val threshold = mean + (2.2f - sensitivity) * sd + 0.08f
        if (flux > threshold && now - lastOnsetNanos > 90_000_000L) {
            lastOnsetNanos = now
            return true
        }
        return false
    }

    /** Keeps a beat grid locked to detected kicks. Returns true when a grid beat starts. */
    private fun trackTempo(now: Double, kick: Boolean): Boolean {
        if (kick) {
            val dt = now - lastKickSec
            lastKickSec = now
            if (dt in 0.3..1.5) {
                // Fold double/half-time intervals towards the current period.
                var d = dt
                while (d < periodSec * 0.7 && d < 0.75) d *= 2
                while (d > periodSec * 1.4 && d > 0.6) d /= 2
                intervals.addLast(d)
                if (intervals.size > 8) intervals.removeFirst()
                val sorted = intervals.sorted()
                val median = sorted[sorted.size / 2]
                periodSec = (periodSec * 0.7 + median * 0.3).coerceIn(60.0 / 190, 60.0 / 70)
            }
        }
        var fired = false
        if (kick) {
            val toNext = nextTempoBeat - now
            val sinceLast = now - lastTempoBeat
            when {
                toNext < periodSec * 0.35 -> { // a little early: start the beat now
                    beatCount++; fired = true
                    lastTempoBeat = now; nextTempoBeat = now + periodSec
                }
                sinceLast < periodSec * 0.35 -> { // a little late: nudge the grid
                    lastTempoBeat = now; nextTempoBeat = now + periodSec
                }
                else -> { // off the grid: re-anchor
                    beatCount++; fired = true
                    lastTempoBeat = now; nextTempoBeat = now + periodSec
                }
            }
        } else if (now >= nextTempoBeat) {
            beatCount++; fired = true
            lastTempoBeat = nextTempoBeat
            nextTempoBeat += periodSec
            if (now > nextTempoBeat) { lastTempoBeat = now; nextTempoBeat = now + periodSec }
        }
        return fired
    }

    /** Strongest peak between 80 Hz and 2 kHz, as a MIDI note number (0 if too weak). */
    private fun pitch(floorDb: Float): Float {
        val k0 = max(2, (80 / binHz).toInt())
        val k1 = min(fftSize / 2 - 2, (2000 / binHz).toInt())
        var best = k0
        for (k in k0..k1) if (magDb[k] > magDb[best]) best = k
        if (magDb[best] < floorDb + 12f) return 0f
        val a = magDb[best - 1]; val b = magDb[best]; val c = magDb[best + 1]
        val denom = a - 2 * b + c
        val offset = if (abs(denom) > 1e-6f) 0.5f * (a - c) / denom else 0f
        val freq = (best + offset) * binHz
        return (69 + 12 * log2(freq / 440.0)).toFloat()
    }
}
