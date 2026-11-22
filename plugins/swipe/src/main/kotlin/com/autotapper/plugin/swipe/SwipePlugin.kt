package com.autotapper.plugin.swipe

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
 * 滑动 —— 从起点区域的随机点到终点区域的随机点，按指定时长滑过去。
 *
 * 覆盖"上下翻页、左右切换"这类拖拽玩法；快速/常规/慢速只决定默认时长，
 * 用户改了 durationMs 就以用户值为准。注入挂起等待宿主回调，不阻塞主线程。
 */
class SwipePlugin : AutoTapperPlugin {

    override val id: String = "swipe"

    override val name: String = "滑动"

    override val version: String = "0.1.0"

    /** 必须等于主程序的 PluginApi.VERSION。 */
    override val apiVersion: Int = PluginApi.VERSION

    /** 主程序 versionCode 低于这个值时不加载。 */
    override val minHostVersionCode: Int = 1

    override fun stepTypes(): List<PluginStepType> = listOf(LineSwipeStep())

    /** typeId=swipe.line：两点之间滑动，起止点各从一个区域里随机取。 */
    private class LineSwipeStep : PluginStepType {
        override val typeId: String = "swipe.line"
        override val displayName: String = "直线滑动"
        override val requires: Set<Capability> = setOf(Capability.ACCESSIBILITY)

        override fun defaultConfig(): JSONObject = JSONObject()
            .put("fromRegion", JSONArray().put("1"))
            .put("toRegion", JSONArray().put("2"))
            .put("mode", MODE_NORMAL)
            .put("durationMs", JSONObject().put("min", DEFAULT_MS_NORMAL).put("max", DEFAULT_MS_NORMAL))

        override fun editorSpec(config: JSONObject): ScreenSpec = ScreenSpec(
            sections = listOf(
                Section(
                    eyebrow = "// ROUTE",
                    title = "滑动路径",
                    fields = listOf(
                        Field.RegionPicker(key = "fromRegion", label = "起点区域（单选）", multiple = false),
                        Field.RegionPicker(key = "toRegion", label = "终点区域（单选）", multiple = false)
                    )
                ),
                Section(
                    eyebrow = "// MOTION",
                    title = "滑动方式",
                    fields = listOf(
                        Field.EnumField(
                            key = "mode", label = "滑动模式",
                            options = listOf("快速", "常规", "慢速"),
                            values = listOf(MODE_FAST, MODE_NORMAL, MODE_SLOW)
                        ),
                        Field.RangeField(
                            key = "durationMs", label = "滑动时长（随模式给默认值）",
                            min = MIN_MS, max = MAX_MS, unit = "ms"
                        )
                    )
                )
            )
        )

        override fun summarize(config: JSONObject): String {
            val mode = try { config.optString("mode", MODE_NORMAL) } catch (t: Throwable) { MODE_NORMAL }
            val range = Helpers.range(config, "durationMs", MIN_MS, MAX_MS, defaultMsFor(mode), defaultMsFor(mode))
            return "滑动 区域 " + Helpers.regionNumbers(config, "fromRegion") +
                " → 区域 " + Helpers.regionNumbers(config, "toRegion") +
                "，" + Helpers.rangeText(range) + " ms（" + modeLabel(mode) + "）"
        }

        override suspend fun execute(ctx: PluginContext, config: JSONObject): StepResult {
            val mode = try { config.optString("mode", MODE_NORMAL) } catch (t: Throwable) { MODE_NORMAL }
            val range = Helpers.range(config, "durationMs", MIN_MS, MAX_MS, defaultMsFor(mode), defaultMsFor(mode))
            val duration = Helpers.sample(range)
            val from = Helpers.firstRegion(ctx, config, "fromRegion")
                ?: return StepResult.Fail("起点区域已不存在，请重新选")
            val to = Helpers.firstRegion(ctx, config, "toRegion")
                ?: return StepResult.Fail("终点区域已不存在，请重新选")

            val fromPoint: TapCoord = from.randomPoint()
            val toPoint: TapCoord = to.randomPoint()
            val ok = Helpers.awaitInjection(ctx) { cb ->
                ctx.executor.swipe(fromPoint, toPoint, duration, cb)
            }
            if (!ok) return StepResult.Fail("滑动下发失败或回调超时")
            return StepResult.Success(toPoint)
        }

        companion object {
            const val MIN_MS = 80L
            const val MAX_MS = 2000L
            const val MODE_FAST = "fast"
            const val MODE_NORMAL = "normal"
            const val MODE_SLOW = "slow"
            const val DEFAULT_MS_FAST = 120L
            const val DEFAULT_MS_NORMAL = 300L
            const val DEFAULT_MS_SLOW = 700L

            /** 模式只给"没改过时长"的用户提供默认值，不强行覆盖用户设置。 */
            fun defaultMsFor(mode: String): Long = when (mode) {
                MODE_FAST -> DEFAULT_MS_FAST
                MODE_SLOW -> DEFAULT_MS_SLOW
                else -> DEFAULT_MS_NORMAL
            }

            fun modeLabel(mode: String): String = when (mode) {
                MODE_FAST -> "快速"
                MODE_SLOW -> "慢速"
                else -> "常规"
            }
        }
    }

    /** 滑动步骤共用的小工具：区域解析与挂起等待注入回调。 */
    private object Helpers {
        const val CALLBACK_TIMEOUT_MS = 5000L

        /** 读 RangeField：约定 {"min":..,"max":..}，兼容旧单数字写法。 */
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

        /** 单选 RegionPicker：只认数组里的第一个有效序号。 */
        fun firstRegion(ctx: PluginContext, config: JSONObject, key: String): Region? {
            val all = try { ctx.regions.load() } catch (t: Throwable) {
                ctx.log.warn("读取区域失败：" + (t.message ?: t.javaClass.simpleName))
                return null
            }
            val arr = try { config.optJSONArray(key) } catch (t: Throwable) {
                ctx.log.warn("区域配置格式错误：" + (t.message ?: t.javaClass.simpleName))
                return null
            } ?: return null
            for (i in 0 until arr.length()) {
                val raw = try { arr.optString(i, "") } catch (t: Throwable) { "" }
                val index = raw.toIntOrNull() ?: continue
                if (index >= 1 && index <= all.size) return all[index - 1]
            }
            return null
        }

        fun regionNumbers(config: JSONObject, key: String): String {
            val arr = try { config.optJSONArray(key) } catch (t: Throwable) { null } ?: return "未选"
            if (arr.length() == 0) return "未选"
            val parts = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val raw = try { arr.optString(i, "") } catch (t: Throwable) { "" }
                if (raw.isNotBlank()) parts.add(raw)
            }
            return if (parts.isEmpty()) "未选" else parts.joinToString(",")
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
                        ctx.log.warn("下发滑动失败：" + (t.message ?: t.javaClass.simpleName))
                        if (cont.isActive) cont.resume(false) {}
                    }
                }
            }
            if (ok == null) ctx.log.warn("滑动回调超时（" + CALLBACK_TIMEOUT_MS + " ms）")
            return ok ?: false
        }
    }
}
