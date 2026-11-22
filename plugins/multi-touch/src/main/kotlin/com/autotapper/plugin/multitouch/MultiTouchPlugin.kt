package com.autotapper.plugin.multitouch

import com.example.autotapper.model.Region
import com.example.autotapper.model.TapCoord
import com.example.autotapper.plugin.AutoTapperPlugin
import com.example.autotapper.plugin.Capability
import com.example.autotapper.plugin.Field
import com.example.autotapper.plugin.PluginApi
import com.example.autotapper.plugin.PluginContext
import com.example.autotapper.plugin.PluginStepType
import com.example.autotapper.plugin.ScreenSpec
import com.example.autotapper.plugin.Section
import com.example.autotapper.plugin.StepResult
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * 多点手势 —— 多个触点同时按下、保持、一起松开。
 *
 * 覆盖"双指缩放、三指截屏"这类需要多指同时落下的玩法。
 * 多指注入挂起等待宿主回调，不阻塞主线程；回调不来时用超时兜底。
 */
class MultiTouchPlugin : AutoTapperPlugin {

    override val id: String = "multi-touch"

    override val name: String = "多点手势"

    override val version: String = "0.1.0"

    /** 必须等于主程序的 PluginApi.VERSION。 */
    override val apiVersion: Int = PluginApi.VERSION

    /** 主程序 versionCode 低于这个值时不加载。 */
    override val minHostVersionCode: Int = 1

    override fun stepTypes(): List<PluginStepType> = listOf(PressStep())

    /** typeId=multi-touch.press：选中区域各取一点，最多 maxPoints 个，同时按下保持 holdMs。 */
    private class PressStep : PluginStepType {
        override val typeId: String = "multi-touch.press"
        override val displayName: String = "多指同时按下"
        override val requires: Set<Capability> = setOf(Capability.ACCESSIBILITY)

        override fun defaultConfig(): JSONObject = JSONObject()
            .put("regions", JSONArray().put("1").put("2"))
            .put("holdMs", JSONObject().put("min", DEFAULT_HOLD_MS).put("max", DEFAULT_HOLD_MS))
            .put("maxPoints", DEFAULT_MAX_POINTS)

        override fun editorSpec(config: JSONObject): ScreenSpec = ScreenSpec(
            sections = listOf(
                Section(
                    eyebrow = "// TARGET",
                    title = "触点区域",
                    fields = listOf(
                        Field.RegionPicker(key = "regions", label = "选择区域（多选，每个区域取一点）", multiple = true)
                    )
                ),
                Section(
                    eyebrow = "// GESTURE",
                    title = "手势参数",
                    fields = listOf(
                        Field.RangeField(
                            key = "holdMs", label = "同时保持时长",
                            min = MIN_HOLD_MS, max = MAX_HOLD_MS, unit = "ms"
                        ),
                        Field.IntField(
                            key = "maxPoints", label = "最多触点数",
                            min = MIN_POINTS, max = MAX_POINTS, unit = "指"
                        )
                    )
                )
            )
        )

        override fun summarize(config: JSONObject): String {
            val holdRange = Helpers.range(config, "holdMs", MIN_HOLD_MS, MAX_HOLD_MS, DEFAULT_HOLD_MS, DEFAULT_HOLD_MS)
            val maxPoints = Helpers.longValue(config, "maxPoints", MIN_POINTS, MAX_POINTS, DEFAULT_MAX_POINTS)
            val selected = try {
                val arr = config.optJSONArray("regions")
                if (arr == null) 0 else {
                    var n = 0
                    for (i in 0 until arr.length()) {
                        if ((try { arr.optString(i, "") } catch (t: Throwable) { "" }).toIntOrNull() != null) n++
                    }
                    n
                }
            } catch (t: Throwable) { 0 }
            val points = if (selected == 0) maxPoints else Math.min(selected.toLong(), maxPoints)
            return points.toString() + " 指同时按下 " + Helpers.rangeText(holdRange) + " ms"
        }

        override suspend fun execute(ctx: PluginContext, config: JSONObject): StepResult {
            val holdRange = Helpers.range(config, "holdMs", MIN_HOLD_MS, MAX_HOLD_MS, DEFAULT_HOLD_MS, DEFAULT_HOLD_MS)
            val hold = Helpers.sample(holdRange)
            val maxPoints = Helpers.longValue(config, "maxPoints", MIN_POINTS, MAX_POINTS, DEFAULT_MAX_POINTS)
            val regions = Helpers.regions(ctx, config, "regions")
            if (regions.size < 2) return StepResult.Fail("多点手势至少需要选 2 个区域")

            val points = ArrayList<TapCoord>()
            for (region in regions) {
                if (points.size >= maxPoints.toInt()) break
                points.add(region.randomPoint())
            }
            val ok = Helpers.awaitInjection(ctx) { cb ->
                ctx.executor.multiTouch(points, hold, cb)
            }
            if (!ok) return StepResult.Fail("多点手势下发失败或回调超时")
            return StepResult.Success()
        }

        companion object {
            const val MIN_HOLD_MS = 20L
            const val MAX_HOLD_MS = 2000L
            const val DEFAULT_HOLD_MS = 200L
            const val MIN_POINTS = 2L
            const val MAX_POINTS = 10L
            const val DEFAULT_MAX_POINTS = 3L
        }
    }

    /** 多指步骤共用的小工具：区域解析与挂起等待注入回调。 */
    private object Helpers {
        const val CALLBACK_TIMEOUT_MS = 5000L

        fun longValue(config: JSONObject, key: String, min: Long, max: Long, fallback: Long): Long {
            val value = try { config.optLong(key, fallback) } catch (t: Throwable) { fallback }
            return value.coerceIn(min, max)
        }

        fun range(
            config: JSONObject, key: String, min: Long, max: Long,
            fallbackMin: Long, fallbackMax: Long
        ): Pair<Long, Long> {
            var lo = fallbackMin.coerceIn(min, max)
            var hi = fallbackMax.coerceIn(min, max)
            try {
                val obj = config.optJSONObject(key)
                if (obj != null) {
                    lo = obj.optLong("min", lo)
                    hi = obj.optLong("max", hi)
                } else {
                    val single = config.optLong(key, lo)
                    lo = single
                    hi = single
                }
            } catch (t: Throwable) {
                lo = fallbackMin.coerceIn(min, max)
                hi = fallbackMax.coerceIn(min, max)
            }
            lo = lo.coerceIn(min, max)
            hi = hi.coerceIn(min, max)
            return if (hi < lo) Pair(lo, lo) else Pair(lo, hi)
        }

        fun sample(range: Pair<Long, Long>): Long =
            if (range.second <= range.first) range.first
            else Random.nextLong(range.first, range.second + 1)

        fun rangeText(range: Pair<Long, Long>): String =
            if (range.second <= range.first) range.first.toString()
            else range.first.toString() + "–" + range.second.toString()

        /** RegionPicker 存的是 1 基序号字符串，过滤已删除区域并按选中顺序保留。 */
        fun regions(ctx: PluginContext, config: JSONObject, key: String): List<Region> {
            val all = try { ctx.regions.load() } catch (t: Throwable) {
                ctx.log.warn("读取区域失败：" + (t.message ?: t.javaClass.simpleName))
                emptyList()
            }
            val arr: JSONArray? = try { config.optJSONArray(key) } catch (t: Throwable) {
                ctx.log.warn("区域配置格式错误：" + (t.message ?: t.javaClass.simpleName))
                null
            }
            if (arr == null || arr.length() == 0 || all.isEmpty()) return emptyList()
            val out = ArrayList<Region>()
            for (i in 0 until arr.length()) {
                val raw = try { arr.optString(i, "") } catch (t: Throwable) { "" }
                val index = raw.toIntOrNull() ?: continue
                if (index >= 1 && index <= all.size) {
                    val region = all[index - 1]
                    if (!out.contains(region)) out.add(region)
                }
            }
            return out
        }

        /** 挂起等待宿主回调，最多 5 秒；不占线程，不把异常抛给界面。 */
        suspend fun awaitInjection(
            ctx: PluginContext,
            block: ((Boolean) -> Unit) -> Unit
        ): Boolean {
            val ok = withTimeoutOrNull(CALLBACK_TIMEOUT_MS) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    try {
                        block { result -> if (cont.isActive) cont.resume(result) {} }
                    } catch (t: Throwable) {
                        ctx.log.warn("下发多指失败：" + (t.message ?: t.javaClass.simpleName))
                        if (cont.isActive) cont.resume(false) {}
                    }
                }
            }
            if (ok == null) ctx.log.warn("多指回调超时（" + CALLBACK_TIMEOUT_MS + " ms）")
            return ok ?: false
        }
    }
}
