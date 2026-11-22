package com.autotapper.plugin.bell

import com.example.autotapper.plugin.AutoTapperPlugin
import com.example.autotapper.plugin.Capability
import com.example.autotapper.plugin.Field
import com.example.autotapper.plugin.PluginApi
import com.example.autotapper.plugin.PluginContext
import com.example.autotapper.plugin.PluginStepType
import com.example.autotapper.plugin.ScreenSpec
import com.example.autotapper.plugin.Section
import com.example.autotapper.plugin.StepResult
import org.json.JSONObject
import kotlin.random.Random

/**
 * 响铃 —— 到点提醒用户：宿主统一铃声，可选短震动。
 *
 * 宿主只暴露 HostSoundApi（ring/vibrate/stop），插件不碰 android 类；
 * 所有跨边界调用都做异常兜底，只写日志，不把异常抛给引擎。
 */
class BellPlugin : AutoTapperPlugin {

    override val id: String = "bell"
    override val name: String = "响铃"
    override val version: String = "0.1.0"
    override val apiVersion: Int = PluginApi.VERSION
    override val minHostVersionCode: Int = 1

    override fun stepTypes(): List<PluginStepType> = listOf(RingStep())

    /** typeId=bell.ring：响一声，然后按配置决定继续、暂停还是停止。 */
    private class RingStep : PluginStepType {

        override val typeId: String = "bell.ring"
        override val displayName: String = "响铃"
        override val requires: Set<Capability> = emptySet()

        override fun defaultConfig(): JSONObject = JSONObject()
            .put(KEY_DURATION, JSONObject().put("min", DEFAULT_DURATION_MS).put("max", DEFAULT_DURATION_MS))
            .put(KEY_VIBRATE, true)
            .put(KEY_AFTER, AFTER_PAUSE)
            .put(KEY_LOG_ONLY, false)

        override fun editorSpec(config: JSONObject): ScreenSpec = ScreenSpec(
            sections = listOf(
                Section(
                    eyebrow = "// SOUND",
                    title = "提醒方式",
                    fields = listOf(
                        Field.RangeField(
                            key = KEY_DURATION, label = "响铃时长",
                            min = MIN_DURATION_MS, max = MAX_DURATION_MS, unit = "ms"
                        ),
                        Field.SwitchField(KEY_VIBRATE, "同时短震动", defaultValue = true),
                        Field.SwitchField(KEY_LOG_ONLY, "只写日志不响铃", defaultValue = false)
                    )
                ),
                Section(
                    eyebrow = "// FLOW",
                    title = "响铃之后",
                    fields = listOf(
                        Field.EnumField(
                            KEY_AFTER, "响铃后",
                            options = listOf("继续", "暂停", "停止"),
                            values = listOf(AFTER_CONTINUE, AFTER_PAUSE, AFTER_STOP)
                        )
                    )
                )
            )
        )

        override fun summarize(config: JSONObject): String {
            val range = readRange(config)
            val durationText = if (range.first == range.second) {
                secondsText(range.first)
            } else {
                secondsText(range.first) + "–" + secondsText(range.second)
            }
            return "响铃 " + durationText + " 秒后" + afterText(config)
        }

        override suspend fun execute(ctx: PluginContext, config: JSONObject): StepResult {
            val range = readRange(config)
            val durationMs = sample(range)
            val vibrate = config.optBoolean(KEY_VIBRATE, true)
            val logOnly = config.optBoolean(KEY_LOG_ONLY, false)
            val after = config.optString(KEY_AFTER, AFTER_PAUSE)
            val action = afterText(config)

            if (logOnly) {
                // 只写日志模式：绝不碰铃声和震动，但仍按 after 决定引擎下一步
                safeInfo(ctx, "只写日志模式：本应响铃 " + durationMs + " ms" +
                    (if (vibrate) " 并震动 400 ms" else "") + "，随后" + action)
            } else {
                runCatching { ctx.sound.ring(durationMs.toInt()) }
                    .onFailure { safeInfo(ctx, "响铃调用失败：" + (it.message ?: it.javaClass.simpleName)) }
                if (vibrate) {
                    runCatching { ctx.sound.vibrate(VIBRATE_MS) }
                        .onFailure { safeInfo(ctx, "震动调用失败：" + (it.message ?: it.javaClass.simpleName)) }
                }
                safeInfo(ctx, "响铃 " + durationMs + " ms" +
                    (if (vibrate) "，已短震动" else "") + "，随后" + action)
            }

            return when (after) {
                AFTER_STOP -> StepResult.Stop
                AFTER_PAUSE -> StepResult.Pause
                else -> StepResult.Success()
            }
        }

        /** 摘要里的动作文案；未知值按暂停处理，与默认值一致。 */
        private fun afterText(config: JSONObject): String = when (config.optString(KEY_AFTER, AFTER_PAUSE)) {
            AFTER_CONTINUE -> "继续"
            AFTER_STOP -> "停止"
            else -> "暂停"
        }

        /** RangeField 存 {"min":..,"max":..}，兼容旧的单数字写法；越界一律夹回合法区间。 */
        private fun readRange(config: JSONObject): Pair<Long, Long> {
            var lo = DEFAULT_DURATION_MS
            var hi = DEFAULT_DURATION_MS
            try {
                val obj = config.optJSONObject(KEY_DURATION)
                if (obj != null) {
                    lo = obj.optLong("min", lo)
                    hi = obj.optLong("max", hi)
                } else {
                    lo = config.optLong(KEY_DURATION, lo)
                    hi = lo
                }
            } catch (t: Throwable) {
                lo = DEFAULT_DURATION_MS
                hi = DEFAULT_DURATION_MS
            }
            lo = lo.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
            hi = hi.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
            return if (hi < lo) Pair(lo, lo) else Pair(lo, hi)
        }

        private fun sample(range: Pair<Long, Long>): Long =
            if (range.second <= range.first) range.first
            else Random.nextLong(range.first, range.second + 1L)

        /** 摘要用秒表述：整秒只写整数，半秒之类保留一位小数。 */
        private fun secondsText(ms: Long): String {
            val whole = ms / 1000L
            val frac = (ms % 1000L) / 100L
            return if (frac == 0L) whole.toString() else whole.toString() + "." + frac.toString()
        }

        private fun safeInfo(ctx: PluginContext, message: String) {
            runCatching { ctx.log.info(message) }
        }

        private companion object {
            const val KEY_DURATION = "durationMs"
            const val KEY_VIBRATE = "vibrate"
            const val KEY_AFTER = "after"
            const val KEY_LOG_ONLY = "logOnly"

            const val MIN_DURATION_MS = 100L
            const val MAX_DURATION_MS = 10_000L
            const val DEFAULT_DURATION_MS = 2_000L
            const val VIBRATE_MS = 400L

            const val AFTER_CONTINUE = "continue"
            const val AFTER_PAUSE = "pause"
            const val AFTER_STOP = "stop"
        }
    }
}
