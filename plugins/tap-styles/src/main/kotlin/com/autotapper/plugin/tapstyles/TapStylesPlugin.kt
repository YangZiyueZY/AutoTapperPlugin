package com.autotapper.plugin.tapstyles

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * 高级点击方式 —— 长按、连击、频率三种细分点击玩法。
 *
 * 覆盖"按住一会儿再松手""快速连点""按固定频率持续点击"三类玩法。
 * 点击只能走宿主 executor 的回调 API；回调不来时用挂起超时兜底，绝不阻塞主线程。
 */
class TapStylesPlugin : AutoTapperPlugin {

    override val id: String = "tap-styles"

    override val name: String = "高级点击方式"

    override val version: String = "0.1.0"

    /** 必须等于主程序的 PluginApi.VERSION。 */
    override val apiVersion: Int = PluginApi.VERSION

    /** 主程序 versionCode 低于这个值时不加载。 */
    override val minHostVersionCode: Int = 1

    override fun stepTypes(): List<PluginStepType> = listOf(
        LongPressStep(),
        BurstStep(),
        FrequencyStep()
    )

    /** typeId=tap-styles.longpress：在一个随机区域点上按住一段时长再松开。 */
    private class LongPressStep : PluginStepType {
        override val typeId: String = "tap-styles.longpress"
        override val displayName: String = "长按"
        override val requires: Set<Capability> = setOf(Capability.ACCESSIBILITY)

        override fun defaultConfig(): JSONObject = JSONObject()
            .put("regions", JSONArray().put("1"))
            .put("durationMs", JSONObject().put("min", DEFAULT_HOLD_MS).put("max", DEFAULT_HOLD_MS))

        override fun editorSpec(config: JSONObject): ScreenSpec = ScreenSpec(
            sections = listOf(
                Section(
                    eyebrow = "// TARGET",
                    title = "长按位置",
                    fields = listOf(
                        Field.RegionPicker(key = "regions", label = "选择区域（多选）", multiple = true)
                    )
                ),
                Section(
                    eyebrow = "// TIMING",
                    title = "按住时长",
                    fields = listOf(
                        Field.RangeField(
                            key = "durationMs", label = "按住时长",
                            min = MIN_HOLD_MS, max = MAX_HOLD_MS, unit = "ms"
                        )
                    )
                )
            )
        )

        override fun summarize(config: JSONObject): String {
            val range = Helpers.range(config, "durationMs", MIN_HOLD_MS, MAX_HOLD_MS, DEFAULT_HOLD_MS, DEFAULT_HOLD_MS)
            return "长按 " + Helpers.rangeText(range) + " ms @区域 " + Helpers.regionNumbers(config, "regions")
        }

        override suspend fun execute(ctx: PluginContext, config: JSONObject): StepResult {
            val range = Helpers.range(config, "durationMs", MIN_HOLD_MS, MAX_HOLD_MS, DEFAULT_HOLD_MS, DEFAULT_HOLD_MS)
            val regions = Helpers.regions(ctx, config, "regions")
            if (regions.isEmpty()) return StepResult.Fail("所选区域已不存在，请重新选")
            val point = regions[Random.nextInt(regions.size)].randomPoint()
            val hold = Helpers.sample(range)
            if (!Helpers.awaitInjection(ctx) { cb -> ctx.executor.tap(point, hold, cb) }) {
                return StepResult.Fail("长按点击下发失败或回调超时")
            }
            return StepResult.Success(point)
        }

        companion object {
            const val MIN_HOLD_MS = 500L
            const val MAX_HOLD_MS = 3000L
            const val DEFAULT_HOLD_MS = 800L
        }
    }

    /** typeId=tap-styles.burst：在随机区域点上快速连击 N 次，每次间隔取区间随机值。 */
    private class BurstStep : PluginStepType {
        override val typeId: String = "tap-styles.burst"
        override val displayName: String = "连击"
        override val requires: Set<Capability> = setOf(Capability.ACCESSIBILITY)

        override fun defaultConfig(): JSONObject = JSONObject()
            .put("regions", JSONArray().put("1"))
            .put("times", DEFAULT_TIMES)
            .put("intervalMs", JSONObject().put("min", DEFAULT_INTERVAL_MIN).put("max", DEFAULT_INTERVAL_MAX))

        override fun editorSpec(config: JSONObject): ScreenSpec = ScreenSpec(
            sections = listOf(
                Section(
                    eyebrow = "// TARGET",
                    title = "连击位置",
                    fields = listOf(
                        Field.RegionPicker(key = "regions", label = "选择区域（多选）", multiple = true)
                    )
                ),
                Section(
                    eyebrow = "// BURST",
                    title = "连击参数",
                    fields = listOf(
                        Field.IntField(key = "times", label = "连击次数", min = MIN_TIMES, max = MAX_TIMES, unit = "次"),
                        Field.RangeField(
                            key = "intervalMs", label = "每次间隔（随机）",
                            min = MIN_INTERVAL_MS, max = MAX_INTERVAL_MS, unit = "ms"
                        )
                    )
                )
            )
        )

        override fun summarize(config: JSONObject): String {
            val times = Helpers.longValue(config, "times", MIN_TIMES, MAX_TIMES, DEFAULT_TIMES)
            val range = Helpers.range(
                config, "intervalMs", MIN_INTERVAL_MS, MAX_INTERVAL_MS,
                DEFAULT_INTERVAL_MIN, DEFAULT_INTERVAL_MAX
            )
            return "连击 " + times + " 次，间隔 " + range.first + "–" + range.second + " ms"
        }

        override suspend fun execute(ctx: PluginContext, config: JSONObject): StepResult {
            val times = Helpers.longValue(config, "times", MIN_TIMES, MAX_TIMES, DEFAULT_TIMES)
            val range = Helpers.range(
                config, "intervalMs", MIN_INTERVAL_MS, MAX_INTERVAL_MS,
                DEFAULT_INTERVAL_MIN, DEFAULT_INTERVAL_MAX
            )
            val regions = Helpers.regions(ctx, config, "regions")
            if (regions.isEmpty()) return StepResult.Fail("所选区域已不存在，请重新选")

            ctx.log.info("开始连击 " + times + " 次，间隔 " + range.first + "–" + range.second + " ms")
            var i = 0L
            var failure: String? = null
            // isActive 让引擎 stop() 能立刻打断连击，不必等整轮跑完
            while (i < times && currentCoroutineContext().isActive && failure == null) {
                val point = regions[Random.nextInt(regions.size)].randomPoint()
                if (!Helpers.awaitInjection(ctx) { cb -> ctx.executor.tap(point, TAP_HOLD_MS, cb) }) {
                    failure = "第 " + (i + 1) + " 次连击下发失败或回调超时"
                } else {
                    if (i < times - 1) delay(Helpers.sample(range))
                    i++
                }
            }
            if (failure != null) return StepResult.Fail(failure)
            return StepResult.Success()
        }

        companion object {
            const val MIN_TIMES = 2L
            const val MAX_TIMES = 50L
            const val DEFAULT_TIMES = 5L
            const val MIN_INTERVAL_MS = 20L
            const val MAX_INTERVAL_MS = 500L
            const val DEFAULT_INTERVAL_MIN = 60L
            const val DEFAULT_INTERVAL_MAX = 120L
            const val TAP_HOLD_MS = 10L
        }
    }

    /** typeId=tap-styles.frequency：按 1000/timesPerSecond 的节拍持续 totalMs 毫秒。 */
    private class FrequencyStep : PluginStepType {
        override val typeId: String = "tap-styles.frequency"
        override val displayName: String = "频率模式"
        override val requires: Set<Capability> = setOf(Capability.ACCESSIBILITY)

        override fun defaultConfig(): JSONObject = JSONObject()
            .put("regions", JSONArray().put("1"))
            .put("durationMs", JSONObject().put("min", DEFAULT_HOLD_MS).put("max", DEFAULT_HOLD_MS))
            .put("timesPerSecond", DEFAULT_TPS)
            .put("totalMs", DEFAULT_TOTAL_MS)

        override fun editorSpec(config: JSONObject): ScreenSpec = ScreenSpec(
            sections = listOf(
                Section(
                    eyebrow = "// TARGET",
                    title = "点击位置",
                    fields = listOf(
                        Field.RegionPicker(key = "regions", label = "选择区域（多选）", multiple = true)
                    )
                ),
                Section(
                    eyebrow = "// FREQUENCY",
                    title = "频率参数",
                    fields = listOf(
                        Field.RangeField(
                            key = "durationMs", label = "单次按住时长",
                            min = MIN_HOLD_MS, max = MAX_HOLD_MS, unit = "ms"
                        ),
                        Field.IntField(
                            key = "timesPerSecond", label = "每秒点击次数",
                            min = MIN_TPS, max = MAX_TPS, unit = "次/秒"
                        ),
                        Field.IntField(
                            key = "totalMs", label = "总持续时间",
                            min = MIN_TOTAL_MS, max = MAX_TOTAL_MS, unit = "ms"
                        )
                    )
                )
            )
        )

        override fun summarize(config: JSONObject): String {
            val range = Helpers.range(config, "durationMs", MIN_HOLD_MS, MAX_HOLD_MS, DEFAULT_HOLD_MS, DEFAULT_HOLD_MS)
            val tps = Helpers.longValue(config, "timesPerSecond", MIN_TPS, MAX_TPS, DEFAULT_TPS)
            val total = Helpers.longValue(config, "totalMs", MIN_TOTAL_MS, MAX_TOTAL_MS, DEFAULT_TOTAL_MS)
            return "频率 " + tps + " 次/秒，按住 " + Helpers.rangeText(range) + " ms，持续 " + total + " ms"
        }

        override suspend fun execute(ctx: PluginContext, config: JSONObject): StepResult {
            val range = Helpers.range(config, "durationMs", MIN_HOLD_MS, MAX_HOLD_MS, DEFAULT_HOLD_MS, DEFAULT_HOLD_MS)
            val tps = Helpers.longValue(config, "timesPerSecond", MIN_TPS, MAX_TPS, DEFAULT_TPS)
            val total = Helpers.longValue(config, "totalMs", MIN_TOTAL_MS, MAX_TOTAL_MS, DEFAULT_TOTAL_MS)
            val regions = Helpers.regions(ctx, config, "regions")
            if (regions.isEmpty()) return StepResult.Fail("所选区域已不存在，请重新选")

            val channelMax = try { ctx.executor.maxTapsPerSecond() } catch (t: Throwable) {
                ctx.log.warn("读取通道频率上限失败：" + (t.message ?: t.javaClass.simpleName))
                0
            }
            val targetTotal = (total * tps / 1000L).coerceAtLeast(1L)
            val beatMs = 1000L / tps
            val started = System.currentTimeMillis()
            val deadline = started + total
            var successes = 0L
            var attempts = 0L

            ctx.log.info(
                "开始频率点击：目标 " + tps + " 次/秒，持续 " + total + " ms，通道上限 " + channelMax + " 次/秒"
            )
            while (attempts < targetTotal && System.currentTimeMillis() < deadline && currentCoroutineContext().isActive) {
                val point = regions[Random.nextInt(regions.size)].randomPoint()
                attempts++
                if (Helpers.awaitInjection(ctx) { cb ->
                        ctx.executor.tap(point, Helpers.sample(range), cb)
                    }) successes++
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) break
                delay(beatMs.coerceAtMost(remain))
            }
            val elapsed = System.currentTimeMillis() - started
            if (targetTotal > 0 && successes * 100 < targetTotal * 80) {
                val measured = if (elapsed > 0) successes * 1000L / elapsed else 0L
                val reported = if (channelMax > 0) channelMax else measured
                return StepResult.Fail(
                    "系统注入限速（当前通道实测 " + reported + " 次/秒），成功率 " +
                    (successes * 100 / targetTotal) + "%"
                )
            }
            return StepResult.Success()
        }

        companion object {
            const val MIN_HOLD_MS = 20L
            const val MAX_HOLD_MS = 200L
            const val DEFAULT_HOLD_MS = 50L
            const val MIN_TPS = 1L
            const val MAX_TPS = 60L
            const val DEFAULT_TPS = 10L
            const val MIN_TOTAL_MS = 100L
            const val MAX_TOTAL_MS = 600_000L
            const val DEFAULT_TOTAL_MS = 3000L
        }
    }

    /** 所有步骤共用的小工具：配置读取、区域过滤、挂起等待注入回调。 */
    private object Helpers {
        const val CALLBACK_TIMEOUT_MS = 5000L

        fun longValue(config: JSONObject, key: String, min: Long, max: Long, fallback: Long): Long {
            val value = try { config.optLong(key, fallback) } catch (t: Throwable) { fallback }
            return value.coerceIn(min, max)
        }

        /** 读 RangeField：约定 {"min":..,"max":..}；兼容旧的单个数字写法；返回夹好的闭区间。 */
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

        /** RegionPicker 存的是区域序号字符串（1 基），过滤掉已删除的区域。 */
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

        /**
         * 挂起等宿主注入回调，不占着线程；5 秒没回调就按失败处理。
         * 引擎和回调都跑在 Dispatchers.Main，阻塞等待必然死锁，这里只挂起。
         */
        suspend fun awaitInjection(
            ctx: PluginContext,
            block: ((Boolean) -> Unit) -> Unit
        ): Boolean {
            val ok = withTimeoutOrNull(CALLBACK_TIMEOUT_MS) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    try {
                        block { result -> if (cont.isActive) cont.resume(result) {} }
                    } catch (t: Throwable) {
                        ctx.log.warn("下发点击失败：" + (t.message ?: t.javaClass.simpleName))
                        if (cont.isActive) cont.resume(false) {}
                    }
                }
            }
            if (ok == null) ctx.log.warn("点击回调超时（" + CALLBACK_TIMEOUT_MS + " ms）")
            return ok ?: false
        }
    }
}
