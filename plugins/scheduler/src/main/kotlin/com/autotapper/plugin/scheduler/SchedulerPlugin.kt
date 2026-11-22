package com.autotapper.plugin.scheduler

import com.example.autotapper.plugin.AutoTapperPlugin
import com.example.autotapper.plugin.Capability
import com.example.autotapper.plugin.Field
import com.example.autotapper.plugin.PluginApi
import com.example.autotapper.plugin.PluginContext
import com.example.autotapper.plugin.PluginStepType
import com.example.autotapper.plugin.ScreenSpec
import com.example.autotapper.plugin.Section
import com.example.autotapper.plugin.StepResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * 定时 —— 等到某个时刻再让规则继续。
 *
 * 设备时区不可信：一律用宿主的校准时间加 UTC+8 固定偏移换算北京时间。
 * 等待靠挂起 delay 分段进行，每段检查协程是否还活着，引擎 stop() 能立刻打断。
 */
class SchedulerPlugin : AutoTapperPlugin {

    override val id: String = "scheduler"
    override val name: String = "定时"
    override val version: String = "0.1.0"
    override val apiVersion: Int = PluginApi.VERSION
    override val minHostVersionCode: Int = 1

    override fun stepTypes(): List<PluginStepType> = listOf(WaitUntilStep())

    /** typeId=scheduler.wait-until：按时刻 / 星期 / 固定区间算出要等多久，再分段等待。 */
    private class WaitUntilStep : PluginStepType {

        override val typeId: String = "scheduler.wait-until"
        override val displayName: String = "等到时刻"
        override val requires: Set<Capability> = emptySet()

        override fun defaultConfig(): JSONObject = JSONObject()
            .put(KEY_MODE, MODE_CLOCK)
            .put(KEY_TIME, DEFAULT_TIME)
            .put(KEY_WEEKDAYS, JSONArray().put("1"))
            .put(KEY_DELAY_MS, JSONObject().put("min", 0L).put("max", 0L))
            .put(KEY_MAX_WAIT_MIN, DEFAULT_MAX_WAIT_MIN)

        override fun editorSpec(config: JSONObject): ScreenSpec = ScreenSpec(
            sections = listOf(
                Section(
                    eyebrow = "// MODE",
                    title = "等待方式",
                    fields = listOf(
                        Field.EnumField(
                            KEY_MODE, "等待条件",
                            options = listOf("每天某时刻", "指定星期某时刻", "固定等待"),
                            values = listOf(MODE_CLOCK, MODE_WEEKDAY, MODE_DELAY)
                        ),
                        Field.Text(KEY_TIME, "目标时刻（HH:mm）", placeholder = DEFAULT_TIME),
                        Field.TextListField(KEY_WEEKDAYS, "星期（1=周一，7=周日）", maxItems = 7, addLabel = "增加一天")
                    )
                ),
                Section(
                    eyebrow = "// DELAY",
                    title = "固定等待区间",
                    fields = listOf(
                        Field.RangeField(
                            KEY_DELAY_MS, "等待时长（随机）",
                            min = MIN_DELAY_MS, max = MAX_DELAY_MS, unit = "ms"
                        )
                    )
                ),
                Section(
                    eyebrow = "// LIMIT",
                    title = "最长等待",
                    fields = listOf(
                        Field.IntField(
                            KEY_MAX_WAIT_MIN, "最长等待分钟数",
                            min = MIN_MAX_WAIT_MIN, max = MAX_MAX_WAIT_MIN, unit = "分钟"
                        )
                    )
                )
            )
        )

        override fun summarize(config: JSONObject): String {
            val time = config.optString(KEY_TIME, DEFAULT_TIME).ifBlank { DEFAULT_TIME }
            return "等到 " + time + " 再继续"
        }

        override suspend fun execute(ctx: PluginContext, config: JSONObject): StepResult {
            val mode = config.optString(KEY_MODE, MODE_CLOCK)
            // 配置里存的是"分钟"，读取时按 Long 处理，和 IntField 的 min/max 类型保持一致
            val maxWaitMin = config.optLong(KEY_MAX_WAIT_MIN, DEFAULT_MAX_WAIT_MIN)
                .coerceIn(MIN_MAX_WAIT_MIN, MAX_MAX_WAIT_MIN)
            val maxWaitMs = maxWaitMin * 60L * 1000L

            val nowMs = runCatching { ctx.clock.nowMs() }
                .onFailure { safeInfo(ctx, "读取宿主时间失败：" + (it.message ?: it.javaClass.simpleName)) }
                .getOrNull()
                ?: return StepResult.Fail("无法读取当前时间")

            val waitMs: Long
            val targetText: String
            try {
                when (mode) {
                    MODE_CLOCK -> {
                        val target = parseTime(config.optString(KEY_TIME, DEFAULT_TIME))
                            ?: return badTime(ctx, config.optString(KEY_TIME, DEFAULT_TIME))
                        val now = beijingNow(nowMs)
                        var targetDateTime = LocalDateTime.of(now.toLocalDate(), target)
                        // 今天的时刻已经过了（或正好相等）就顺延到明天
                        if (!targetDateTime.isAfter(now)) targetDateTime = targetDateTime.plusDays(1L)
                        waitMs = durationUntil(nowMs, targetDateTime)
                        targetText = "明天 " + timeText(target)
                    }

                    MODE_WEEKDAY -> {
                        val target = parseTime(config.optString(KEY_TIME, DEFAULT_TIME))
                            ?: return badTime(ctx, config.optString(KEY_TIME, DEFAULT_TIME))
                        val days = readWeekdays(config)
                        if (days.isEmpty()) return StepResult.Fail("还没有选择星期")
                        val now = beijingNow(nowMs)
                        // 从今天起最多找 7 天，一定撞上所选星期之一
                        var targetDateTime: LocalDateTime? = null
                        var foundName = ""
                        for (offset in 0..7) {
                            val date = now.toLocalDate().plusDays(offset.toLong())
                            if (date.dayOfWeek.value !in days) continue
                            val candidate = LocalDateTime.of(date, target)
                            if (candidate.isAfter(now)) {
                                targetDateTime = candidate
                                foundName = if (offset == 0) "今天" else if (offset == 1) "明天" else "周" + date.dayOfWeek.value
                                break
                            }
                        }
                        if (targetDateTime == null) return StepResult.Fail("找不到匹配的星期，请检查配置")
                        waitMs = durationUntil(nowMs, targetDateTime)
                        targetText = foundName + " " + timeText(target)
                    }

                    MODE_DELAY -> {
                        waitMs = sample(readRange(config))
                        targetText = "固定等待 " + waitMs + " ms"
                    }

                    else -> return StepResult.Fail("未知的定时方式")
                }
            } catch (t: Throwable) {
                safeInfo(ctx, "计算等待时间失败：" + (t.message ?: t.javaClass.simpleName))
                return StepResult.Fail("无法计算等待时间")
            }

            if (waitMs <= 0L) {
                safeInfo(ctx, "目标时刻 " + targetText + " 已到，本次不等")
                return StepResult.Success()
            }
            if (waitMs > maxWaitMs) {
                return StepResult.Fail("等待超过 " + maxWaitMin + " 分钟，已放弃")
            }

            safeInfo(ctx, "准备等待到 " + targetText + "（北京时间），约 " + waitMs + " ms")

            var remaining = waitMs
            // 每 30 秒一段：只有挂起等待，不占线程；每段都看一眼取消状态
            while (remaining > 0L && currentCoroutineContext().isActive) {
                val slice = minOf(SEGMENT_MS, remaining)
                delay(slice)
                remaining -= slice
            }
            if (!currentCoroutineContext().isActive) {
                safeInfo(ctx, "等待被取消")
                return StepResult.Success()
            }
            safeInfo(ctx, "等待结束，继续执行")
            return StepResult.Success()
        }

        /** 北京时间 = 宿主校准时间 + UTC+8 固定偏移，再按 UTC 解读成本地挂钟。 */
        private fun beijingNow(nowMs: Long): LocalDateTime =
            LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs + BEIJING_OFFSET_MS), ZoneOffset.UTC)

        /** 目标挂钟时刻距离现在多少毫秒：把时刻按 UTC+8 反推成 epoch 再相减。 */
        private fun durationUntil(nowMs: Long, target: LocalDateTime): Long =
            target.toInstant(ZoneOffset.UTC).toEpochMilli() - BEIJING_OFFSET_MS - nowMs

        /** 只认 HH:mm；越界或写错都按解析失败处理，避免静默变成 00:00。 */
        private fun parseTime(raw: String): LocalTime? {
            val text = raw.trim()
            if (text.length != 5 || text[2] != ':') return null
            val hour = text.substring(0, 2).toIntOrNull() ?: return null
            val minute = text.substring(3, 5).toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return runCatching { LocalTime.of(hour, minute) }.getOrNull()
        }

        private fun timeText(target: LocalTime): String = pad(target.hour) + ":" + pad(target.minute)

        private fun badTime(ctx: PluginContext, raw: String): StepResult {
            safeInfo(ctx, "时间格式错误：" + raw)
            return StepResult.Fail("时间格式应为 HH:mm，现在是「" + raw + "」")
        }

        /** 星期存盘值是 JSONArray 字符串数组，元素 1..7 对应周一到周日。 */
        private fun readWeekdays(config: JSONObject): List<Int> {
            val array = try { config.optJSONArray(KEY_WEEKDAYS) } catch (t: Throwable) { null } ?: return emptyList()
            val result = ArrayList<Int>()
            for (i in 0 until array.length()) {
                val value = array.optString(i, "").trim().toIntOrNull() ?: continue
                if (value in 1..7 && value !in result) result.add(value)
            }
            return result
        }

        /** RangeField 存 {"min":..,"max":..}，兼容旧的单数字写法。 */
        private fun readRange(config: JSONObject): Pair<Long, Long> {
            var lo = 0L
            var hi = 0L
            try {
                val obj = config.optJSONObject(KEY_DELAY_MS)
                if (obj != null) {
                    lo = obj.optLong("min", lo)
                    hi = obj.optLong("max", hi)
                } else {
                    lo = config.optLong(KEY_DELAY_MS, lo)
                    hi = lo
                }
            } catch (t: Throwable) {
                lo = 0L
                hi = 0L
            }
            lo = lo.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
            hi = hi.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
            return if (hi < lo) Pair(lo, lo) else Pair(lo, hi)
        }

        private fun sample(range: Pair<Long, Long>): Long =
            if (range.second <= range.first) range.first
            else Random.nextLong(range.first, range.second + 1L)

        private fun pad(value: Int): String = if (value < 10) "0" + value else value.toString()

        private fun safeInfo(ctx: PluginContext, message: String) {
            runCatching { ctx.log.info(message) }
        }

        private companion object {
            const val KEY_MODE = "mode"
            const val KEY_TIME = "timeHHmm"
            const val KEY_WEEKDAYS = "weekdays"
            const val KEY_DELAY_MS = "delayMs"
            const val KEY_MAX_WAIT_MIN = "maxWaitMin"

            const val MODE_CLOCK = "clock"
            const val MODE_WEEKDAY = "weekday"
            const val MODE_DELAY = "delay"

            const val DEFAULT_TIME = "09:30"
            const val MIN_DELAY_MS = 0L
            const val MAX_DELAY_MS = 600_000L
            // Field.IntField 的 min/max 是 Long，这里统一用 Long，避免构造字段时类型不匹配
            const val MIN_MAX_WAIT_MIN = 1L
            const val MAX_MAX_WAIT_MIN = 1440L
            const val DEFAULT_MAX_WAIT_MIN = 720L
            const val SEGMENT_MS = 30_000L
            const val BEIJING_OFFSET_MS = 8L * 60L * 60L * 1000L
        }
    }
}
