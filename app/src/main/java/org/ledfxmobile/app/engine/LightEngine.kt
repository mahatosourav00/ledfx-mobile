package org.ledfxmobile.app.engine

import android.content.Context
import android.content.SharedPreferences
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.ledfxmobile.app.audio.AudioAnalyzer
import org.ledfxmobile.app.audio.AudioCapture
import org.ledfxmobile.app.audio.AudioFrame
import org.ledfxmobile.app.audio.AudioSourceType
import org.ledfxmobile.app.effects.Effect
import org.ledfxmobile.app.effects.Effects
import org.ledfxmobile.app.effects.Palettes
import org.ledfxmobile.app.effects.ParamValues
import org.ledfxmobile.app.effects.Preset
import org.ledfxmobile.app.effects.RenderContext
import org.ledfxmobile.app.effects.Rgb
import org.ledfxmobile.app.effects.blurChannel
import org.ledfxmobile.app.effects.frac
import org.ledfxmobile.app.model.DeviceConfig
import org.ledfxmobile.app.model.DeviceKind
import org.ledfxmobile.app.model.EffectSettings
import org.ledfxmobile.app.net.DdpSender
import java.net.InetAddress
import java.util.UUID
import kotlin.math.abs

/**
 * The light engine: every frame (60 fps) it analyses audio, renders each device's effect,
 * applies blur/mirror/flip/brightness, streams the pixels to WLED and publishes previews.
 */
object LightEngine {
    private const val TAG = "LightEngine"
    private const val FPS = 60

    private lateinit var prefs: SharedPreferences
    private lateinit var capture: AudioCapture
    private val analyzer = AudioAnalyzer()
    private val main = Handler(Looper.getMainLooper())

    private val _devices = MutableStateFlow<List<DeviceConfig>>(emptyList())
    val devices: StateFlow<List<DeviceConfig>> = _devices

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _audioSource = MutableStateFlow(AudioSourceType.MICROPHONE)
    val audioSource: StateFlow<AudioSourceType> = _audioSource

    private val _sensitivity = MutableStateFlow(0.5f)
    val sensitivity: StateFlow<Float> = _sensitivity

    /** App-wide brightness applied on top of every device's own brightness. */
    private val _masterBrightness = MutableStateFlow(1f)
    val masterBrightness: StateFlow<Float> = _masterBrightness

    private val _userPresets = MutableStateFlow<List<Preset>>(emptyList())
    val userPresets: StateFlow<List<Preset>> = _userPresets

    /** Devices not streamed to right now (e.g. while their WLED page is open). */
    private val _paused = MutableStateFlow<Set<String>>(emptySet())
    val paused: StateFlow<Set<String>> = _paused

    private val _audio = MutableStateFlow(AudioFrame.EMPTY)
    val audio: StateFlow<AudioFrame> = _audio

    private val _audioProblem = MutableStateFlow<String?>(null)
    val audioProblem: StateFlow<String?> = _audioProblem

    /** Latest pixels per device id, for on-screen previews (~30 fps). */
    private val _previews = MutableStateFlow<Map<String, IntArray>>(emptyMap())
    val previews: StateFlow<Map<String, IntArray>> = _previews

    @Volatile var projection: MediaProjection? = null
        private set

    private var thread: Thread? = null
    @Volatile private var loopRunning = false

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("engine", Context.MODE_PRIVATE)
        capture = AudioCapture(context, analyzer)
        load()
    }

    // ---------------------------------------------------------------- lifecycle

    @Synchronized
    fun start() {
        if (loopRunning) return
        loopRunning = true
        _running.value = true
        restartCapture()
        thread = Thread({ loop() }, "light-engine").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    @Synchronized
    fun stop() {
        if (!loopRunning) return
        loopRunning = false
        thread?.join(1000)
        thread = null
        capture.stop()
        _running.value = false
        _audio.value = AudioFrame.EMPTY
        _previews.value = emptyMap()
    }

    // ---------------------------------------------------------------- settings

    fun setAudioSource(source: AudioSourceType) {
        _audioSource.value = source
        save()
        if (loopRunning) restartCapture()
    }

    fun setSensitivity(value: Float) {
        _sensitivity.value = value.coerceIn(0f, 1f)
        save()
    }

    fun setMasterBrightness(value: Float) {
        _masterBrightness.value = value.coerceIn(0f, 1f)
        save()
    }

    fun setPaused(id: String, paused: Boolean) {
        _paused.update { if (paused) it + id else it - id }
    }

    fun onProjectionGranted(p: MediaProjection) {
        projection?.stop()
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (projection === p) projection = null
                if (_audioSource.value == AudioSourceType.PHONE_AUDIO && loopRunning) restartCapture()
            }
        }, main)
        if (_audioSource.value == AudioSourceType.PHONE_AUDIO && loopRunning) restartCapture()
    }

    fun releaseProjection() {
        projection?.stop()
        projection = null
    }

    private fun restartCapture() {
        capture.start(_audioSource.value, projection)
        _audioProblem.value = capture.problem
    }

    // ---------------------------------------------------------------- devices

    fun addDevice(device: DeviceConfig) {
        _devices.update { it + device }
        save()
    }

    fun updateDevice(id: String, change: (DeviceConfig) -> DeviceConfig) {
        _devices.update { list -> list.map { if (it.id == id) change(it) else it } }
        save()
    }

    fun updateSettings(id: String, change: (EffectSettings) -> EffectSettings) =
        updateDevice(id) { it.copy(settings = change(it.settings)) }

    fun removeDevice(id: String) {
        _devices.update { list -> list.filterNot { it.id == id } }
        save()
    }

    // ---------------------------------------------------------------- presets

    /** Saves the device's current effect settings and colours as a named preset. */
    fun savePreset(name: String, settings: EffectSettings): Preset {
        val gradient = Palettes.resolve(settings.paletteId, settings.customGradient).previewColors
        val preset = Preset("user:" + UUID.randomUUID(), name, settings.effectId, settings.config(), gradient)
        _userPresets.update { it + preset }
        save()
        return preset
    }

    fun deletePreset(id: String) {
        _userPresets.update { list -> list.filterNot { it.id == id } }
        save()
    }

    // ---------------------------------------------------------------- render loop

    private class Runtime {
        var effectId = ""
        var effect: Effect? = null
        var values = ParamValues(emptyList())
        var work = IntArray(0)
        var out = IntArray(0)
        var r = FloatArray(0); var g = FloatArray(0); var b = FloatArray(0); var tmp = FloatArray(0)
        var gradientOffset = 0f
        var host = ""
        var address: InetAddress? = null
        var nextResolveNanos = 0L
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        val sender = DdpSender()
        val runtimes = HashMap<String, Runtime>()
        val ctx = RenderContext()
        val frameNanos = 1_000_000_000L / FPS
        val startNanos = System.nanoTime()
        var lastNanos = startNanos
        var next = startNanos
        var frameNo = 0L

        try {
            while (loopRunning) {
                val now = System.nanoTime()
                analyzer.sensitivity = _sensitivity.value
                val audio = analyzer.analyze(now)
                _audio.value = audio

                ctx.audio = audio
                ctx.time = (now - startNanos) / 1e9
                ctx.dt = ((now - lastNanos) / 1e9f).coerceIn(0.001f, 0.1f)
                lastNanos = now

                val devices = _devices.value
                val paused = _paused.value
                val master = _masterBrightness.value
                val publish = frameNo % 2 == 0L
                val previews = if (publish) HashMap<String, IntArray>(devices.size) else null

                for (dev in devices) {
                    val rt = runtimes.getOrPut(dev.id) { Runtime() }
                    renderDevice(dev, rt, ctx, master)
                    if (dev.enabled && dev.kind == DeviceKind.WLED && dev.id !in paused) sendDevice(dev, rt, sender, now)
                    previews?.put(dev.id, rt.out.copyOf())
                }
                if (runtimes.size > devices.size) {
                    val ids = devices.mapTo(HashSet()) { it.id }
                    runtimes.keys.retainAll(ids)
                }
                if (previews != null) _previews.value = previews

                frameNo++
                next += frameNanos
                val sleep = next - System.nanoTime()
                if (sleep > 0) Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt())
                else next = System.nanoTime()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Render loop crashed", e)
        } finally {
            sender.close()
        }
    }

    private fun renderDevice(dev: DeviceConfig, rt: Runtime, ctx: RenderContext, master: Float) {
        val s = dev.settings
        val info = Effects.info(s.effectId)
        if (rt.effectId != info.id || rt.effect == null) {
            rt.effectId = info.id
            rt.effect = info.create()
            rt.values = ParamValues(info.params)
            rt.gradientOffset = 0f
        }
        rt.values.values = s.config(info.id)
        val p = rt.values
        val mirror = p.b("mirror")
        val flip = p.b("flip")

        val n = dev.ledCount
        val renderN = if (mirror) (n + 1) / 2 else n
        if (rt.work.size != renderN) {
            rt.work = IntArray(renderN)
            rt.r = FloatArray(renderN); rt.g = FloatArray(renderN); rt.b = FloatArray(renderN); rt.tmp = FloatArray(renderN)
        }
        if (rt.out.size != n) rt.out = IntArray(n)

        // Gradient roll: LedFx shifts the gradient by `gradient_roll` LEDs per frame.
        val roll = p.f("gradient_roll")
        rt.gradientOffset = if (roll > 0f) frac(rt.gradientOffset + roll * ctx.dt * 60f / renderN) else 0f
        ctx.gradientOffset = rt.gradientOffset
        ctx.p = p
        ctx.palette = Palettes.resolve(s.paletteId, s.customGradient)
        try {
            rt.effect!!.render(ctx, rt.work)
        } catch (e: Exception) {
            Log.w(TAG, "Effect ${info.id} failed", e)
            rt.work.fill(0)
        }

        // Blur (LedFx sigma in LEDs).
        val blur = p.f("blur")
        if (blur >= 0.3f) {
            for (i in 0 until renderN) {
                val c = rt.work[i]
                rt.r[i] = Rgb.r(c).toFloat(); rt.g[i] = Rgb.g(c).toFloat(); rt.b[i] = Rgb.b(c).toFloat()
            }
            blurChannel(rt.r, rt.tmp, blur); blurChannel(rt.g, rt.tmp, blur); blurChannel(rt.b, rt.tmp, blur)
            for (i in 0 until renderN) rt.work[i] = Rgb.of(rt.r[i].toInt(), rt.g[i].toInt(), rt.b[i].toInt())
        }

        val bright = (s.brightness * master).coerceIn(0f, 1f)
        val out = rt.out
        for (i in 0 until n) {
            // Mirror: the effect's first pixel sits in the middle, spreading to both ends.
            val src = if (mirror) (abs(2 * i - (n - 1)) / 2).coerceAtMost(renderN - 1) else i
            val dst = if (flip) n - 1 - i else i
            out[dst] = Rgb.scale(rt.work[src], bright)
        }
    }

    private fun sendDevice(dev: DeviceConfig, rt: Runtime, sender: DdpSender, now: Long) {
        if (rt.host != dev.host) {
            rt.host = dev.host
            rt.address = null
            rt.nextResolveNanos = 0
        }
        if (rt.address == null && now >= rt.nextResolveNanos) {
            rt.address = runCatching { InetAddress.getByName(dev.host) }.getOrNull()
            if (rt.address == null) rt.nextResolveNanos = now + 5_000_000_000L
        }
        val addr = rt.address ?: return
        try {
            sender.send(addr, rt.out)
        } catch (e: Exception) {
            rt.address = null
            rt.nextResolveNanos = now + 2_000_000_000L
        }
    }

    // ---------------------------------------------------------------- persistence

    private fun load() {
        val json = prefs.getString("devices", null)
        val list = if (json == null) {
            listOf(DeviceConfig(name = "Phone preview", kind = DeviceKind.VIRTUAL, ledCount = 90))
        } else {
            runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).map { DeviceConfig.fromJson(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
        // Devices saved by older versions may name effects that no longer exist.
        _devices.value = list.map { d ->
            if (Effects.exists(d.settings.effectId)) d
            else d.copy(settings = d.settings.copy(effectId = "wavelength"))
        }
        _audioSource.value = runCatching {
            AudioSourceType.valueOf(prefs.getString("audioSource", "MICROPHONE")!!)
        }.getOrDefault(AudioSourceType.MICROPHONE)
        _sensitivity.value = prefs.getFloat("sensitivity", 0.5f)
        _masterBrightness.value = prefs.getFloat("masterBrightness", 1f)
        _userPresets.value = runCatching {
            val arr = JSONArray(prefs.getString("presets", "[]"))
            (0 until arr.length()).map { Preset.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun save() {
        val arr = JSONArray()
        _devices.value.forEach { arr.put(it.toJson()) }
        val presets = JSONArray()
        _userPresets.value.forEach { presets.put(it.toJson()) }
        prefs.edit()
            .putString("devices", arr.toString())
            .putString("audioSource", _audioSource.value.name)
            .putFloat("sensitivity", _sensitivity.value)
            .putFloat("masterBrightness", _masterBrightness.value)
            .putString("presets", presets.toString())
            .apply()
    }
}
