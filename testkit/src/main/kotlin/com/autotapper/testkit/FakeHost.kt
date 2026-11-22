package com.autotapper.testkit

import android.content.SharedPreferences
import android.graphics.Bitmap
import com.example.autotapper.model.Region
import com.example.autotapper.model.TapCoord
import com.example.autotapper.plugin.CaptureApi
import com.example.autotapper.plugin.ClockApi
import com.example.autotapper.plugin.ExecutorApi
import com.example.autotapper.plugin.GlobalAction
import com.example.autotapper.plugin.HostSoundApi
import com.example.autotapper.plugin.KeyCode
import com.example.autotapper.plugin.LogApi
import com.example.autotapper.plugin.NodeApi
import com.example.autotapper.plugin.OcrApi
import com.example.autotapper.plugin.OcrResult
import com.example.autotapper.plugin.PluginContext
import com.example.autotapper.plugin.RecordApi
import com.example.autotapper.plugin.RecordedGesture
import com.example.autotapper.plugin.RegionApi

/** 内存版 SharedPreferences。只实现插件会用到的读写，apply() 立即生效。 */
class FakePrefs : SharedPreferences {
    private val values = LinkedHashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

    override fun getString(key: String, defValue: String?): String? =
        values[key] as? String ?: defValue

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
        val value = values[key] ?: return defValues
        @Suppress("UNCHECKED_CAST")
        return (value as? MutableSet<String>) ?: defValues
    }

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor(this)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private class Editor(private val prefs: FakePrefs) : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private var cleared = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
            pending[key] = values?.let { LinkedHashSet(it) }
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            pending[key] = REMOVED
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            cleared = true
            pending.clear()
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (cleared) prefs.values.clear()
            for ((key, value) in pending) {
                if (value === REMOVED) prefs.values.remove(key) else prefs.values[key] = value
            }
            pending.clear()
        }

        private object REMOVED
    }
}

/** 一次注入调用的快照，字段尽量保留原参数，方便断言坐标与时长。 */
data class TapCall(val x: Int, val y: Int, val durationMs: Long)
data class SwipeCall(val from: TapCoord, val to: TapCoord, val durationMs: Long)
data class MultiTouchCall(val points: List<TapCoord>, val holdMs: Long)
data class GlobalActionCall(val action: GlobalAction)
data class KeyEventCall(val key: KeyCode)
data class InputTextCall(val text: String)

/**
 * 可编程注入器：默认同步回调 true。把 [result] 设为 false 可测失败分支；
 * [autoCallback] 设为 false 可测"宿主永不回调"的超时兜底（超时由插件自己实现）。
 */
class FakeExecutor(
    var result: Boolean = true,
    var autoCallback: Boolean = true,
    var maxTps: Int = 60,
    var throwOnCall: Throwable? = null
) : ExecutorApi {
    val taps = ArrayList<TapCall>()
    val swipes = ArrayList<SwipeCall>()
    val multiTouches = ArrayList<MultiTouchCall>()
    val globalActions = ArrayList<GlobalActionCall>()
    val keyEvents = ArrayList<KeyEventCall>()
    val inputTexts = ArrayList<InputTextCall>()

    /** 注入被调用时触发，测试可在此手动回调 false/true。 */
    var onCall: ((Boolean) -> Unit)? = null

    override fun tap(x: Int, y: Int, durationMs: Long, onResult: (Boolean) -> Unit) {
        taps.add(TapCall(x, y, durationMs))
        dispatch(onResult)
    }

    override fun swipe(from: TapCoord, to: TapCoord, durationMs: Long, onResult: (Boolean) -> Unit) {
        swipes.add(SwipeCall(from, to, durationMs))
        dispatch(onResult)
    }

    override fun multiTouch(points: List<TapCoord>, holdMs: Long, onResult: (Boolean) -> Unit) {
        multiTouches.add(MultiTouchCall(points.toList(), holdMs))
        dispatch(onResult)
    }

    override fun globalAction(action: GlobalAction, onResult: (Boolean) -> Unit) {
        globalActions.add(GlobalActionCall(action))
        dispatch(onResult)
    }

    override fun keyEvent(key: KeyCode, onResult: (Boolean) -> Unit) {
        keyEvents.add(KeyEventCall(key))
        dispatch(onResult)
    }

    override fun inputText(text: String, onResult: (Boolean) -> Unit) {
        inputTexts.add(InputTextCall(text))
        dispatch(onResult)
    }

    override fun maxTapsPerSecond(): Int = maxTps

    private fun dispatch(onResult: (Boolean) -> Unit) {
        throwOnCall?.let { throw it }
        onCall?.invoke(result)
        if (autoCallback && onCall == null) onResult(result)
    }
}

/** 假 Bitmap：插件会读宽高、getPixels、recycle，这里给一块固定颜色的小图。 */
class FakeBitmap(
    override val width: Int = 2,
    override val height: Int = 2,
    private val argb: Int = 0xFF000000.toInt()
) : Bitmap() {
    private var recycled = false

    override fun getPixel(x: Int, y: Int): Int = argb

    override fun getPixels(
        pixels: IntArray, offset: Int, stride: Int,
        x: Int, y: Int, width: Int, height: Int
    ) {
        var row = 0
        while (row < height) {
            var col = 0
            while (col < width) {
                pixels[offset + row * stride + col] = argb
                col++
            }
            row++
        }
    }

    override fun isRecycled(): Boolean = recycled

    override fun recycle() {
        recycled = true
    }
}

/** 截图能力假实现：ready 关掉、grab 返回 null，可覆盖失败分支。 */
class FakeCapture(
    var ready: Boolean = true,
    var regionResult: Bitmap? = FakeBitmap(),
    var fullscreenResult: Bitmap? = FakeBitmap()
) : CaptureApi {
    val grabRegionCalls = ArrayList<Region>()
    var grabFullscreenCalls = 0

    override fun isReady(): Boolean = ready

    override fun grabRegion(region: Region): Bitmap? {
        grabRegionCalls.add(region)
        return regionResult
    }

    override fun grabFullscreen(): Bitmap? {
        grabFullscreenCalls++
        return fullscreenResult
    }
}

/** OCR 假实现：错误优先；成功时返回 text / lines。 */
class FakeOcr(
    var text: String = "",
    var lines: List<String> = emptyList(),
    var error: String? = null,
    var configured: Boolean = true
) : OcrApi {
    var recognizeCalls = 0

    override fun isConfigured(): Boolean = configured

    override suspend fun recognize(bitmap: Bitmap): OcrResult {
        recognizeCalls++
        return OcrResult(text, lines.ifEmpty { listOf(text) }, 1L, error)
    }
}

/** 区域存取假实现。 */
class FakeRegions(var regions: List<Region> = emptyList()) : RegionApi {
    var pickResult: Region? = null
    var pickCalls = 0

    override fun load(): List<Region> = regions

    override fun save(regions: List<Region>) {
        this.regions = regions.toList()
    }

    override suspend fun pick(label: String): Region? {
        pickCalls++
        return pickResult
    }
}

/** 日志假实现：按等级收集，测试直接断言文案。 */
class FakeLog : LogApi {
    val infos = ArrayList<String>()
    val warns = ArrayList<String>()
    val errors = ArrayList<String>()

    val all: List<String> get() = infos + warns + errors

    override fun info(message: String) { infos.add(message) }
    override fun warn(message: String) { warns.add(message) }
    override fun error(message: String) { errors.add(message) }

    fun clear() {
        infos.clear(); warns.clear(); errors.clear()
    }
}

/** 可控时钟：默认北京时间 2024-01-01 12:00:00（UTC+8）。 */
class FakeClock(var now: Long = 1_704_081_600_000L) : ClockApi {
    override fun nowMs(): Long = now
    override fun offsetMs(): Long = 0L
    override fun isBeijingTime(): Boolean = true
}

/** 节点树假实现：按 viewId / 文本 / 描述返回坐标。 */
class FakeNodes(
    var ready: Boolean = true,
    var viewIdMap: Map<String, TapCoord> = emptyMap(),
    var textMap: Map<String, TapCoord> = emptyMap(),
    var descriptionMap: Map<String, TapCoord> = emptyMap(),
    var throwOnFind: Throwable? = null
) : NodeApi {
    override fun findByViewId(viewId: String, packageName: String?): TapCoord? {
        throwOnFind?.let { throw it }
        return viewIdMap[viewId]
    }

    override fun findByText(text: String, exact: Boolean): TapCoord? {
        throwOnFind?.let { throw it }
        return textMap[text] ?: if (!exact) {
            textMap.entries.firstOrNull { it.key.contains(text) }?.value
        } else null
    }

    override fun findByDescription(description: String): TapCoord? {
        throwOnFind?.let { throw it }
        return descriptionMap[description]
    }

    override fun isReady(): Boolean = ready
}

/** 录制能力假实现。 */
class FakeRecord(
    var supported: Boolean = true,
    var gestures: List<RecordedGesture> = emptyList(),
    var throwOnRecord: Throwable? = null
) : RecordApi {
    var recordCalls = 0
    var lastMaxDurationMs = 0L

    override fun isSupported(): Boolean = supported

    override suspend fun record(maxDurationMs: Long): List<RecordedGesture> {
        recordCalls++
        lastMaxDurationMs = maxDurationMs
        throwOnRecord?.let { throw it }
        return gestures
    }
}

/** 响铃/震动假实现：记录参数，不碰真实设备。 */
class FakeSound : HostSoundApi {
    val rings = ArrayList<Int>()
    val vibrations = ArrayList<Long>()
    var stops = 0

    override fun ring(durationMs: Int) { rings.add(durationMs) }
    override fun vibrate(durationMs: Long) { vibrations.add(durationMs) }
    override fun stop() { stops++ }
}

/**
 * 假宿主上下文：把插件用到的全部能力组装起来。构造参数都有默认值，
 * 测试只覆盖当前用例关心的能力。
 */
class FakeContext(
    override val pluginId: String = "fake-plugin",
    override val prefs: FakePrefs = FakePrefs(),
    override val executor: FakeExecutor = FakeExecutor(),
    override val capture: FakeCapture = FakeCapture(),
    override val ocr: FakeOcr = FakeOcr(),
    override val regions: FakeRegions = FakeRegions(),
    override val log: FakeLog = FakeLog(),
    override val clock: FakeClock = FakeClock(),
    override val nodes: FakeNodes = FakeNodes(),
    override val recorder: FakeRecord = FakeRecord(),
    override val sound: FakeSound = FakeSound()
) : PluginContext
