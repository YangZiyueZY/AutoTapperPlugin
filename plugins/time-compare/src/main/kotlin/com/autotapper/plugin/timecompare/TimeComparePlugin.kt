package com.autotapper.plugin.timecompare

import com.example.autotapper.plugin.AutoTapperPlugin
import com.example.autotapper.plugin.Capability
import com.example.autotapper.plugin.Field
import com.example.autotapper.plugin.PluginApi
import com.example.autotapper.plugin.PluginContext
import com.example.autotapper.plugin.PluginStepType
import com.example.autotapper.plugin.ScreenSpec
import com.example.autotapper.plugin.Section
import com.example.autotapper.plugin.StepResult
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * 时间对比插件 —— 用宿主的校准时间判断时刻、时间段或星期条件是否满足。
 *
 * 宿主时区不可信：默认按 UTC+8 固定偏移换算成本地时间；条件不满足时按 onFalse 决定失败还是继续。
 */
class TimeComparePlugin : AutoTapperPlugin {

    override val id: String = "time-compare"
    override val name: String = "时间对比"
    override val version: String = "0.1.0"
    override val apiVersion: Int = PluginApi.VERSION
    override val minHostVersionCode: Int = 1

    override fun stepTypes(): List<PluginStepType> = listOf(CompareStep())

    /** 读取配置、解析时间、判断条件；解析错误一律返回中文 Fail，不抛异常。 */
    private class CompareStep : PluginStepType {

        override val typeId: String = "time-compare.compare"
        override val displayName: String = "时间对比"
        override val requires: Set<Capability> = emptySet()

        override fun defaultConfig(): JSONObject = JSONObject()
            .put(KEY_MODE, MODE_AFTER)
            .put(KEY_TIME, "09:30")
            .put(KEY_START, "09:00")
            .put(KEY_END, "18:00")
            .put(KEY_WEEKDAYS, JSONArray())
            .put(KEY_USE_BEIJING, true)
            .put(KEY_ON_FALSE, ON_FALSE_FAIL)

        override fun editorSpec(config: JSONObject): ScreenSpec = ScreenSpec(
            sections = listOf(
                Section(
                    eyebrow = "// CONDITION",
                    title = "判断条件",
                    fields = listOf(
                        Field.EnumField(
                            KEY_MODE,
                            "对比方式",
                            options = listOf("时刻之后", "时刻之前", "时间段内", "星期几"),
                            values = listOf(MODE_AFTER, MODE_BEFORE, MODE_BETWEEN, MODE_WEEKDAY)
                        ),
                        Field.Text(KEY_TIME, "时刻（HH:mm）", placeholder = "09:30"),
                        Field.Text(KEY_START, "开始时刻（HH:mm）", placeholder = "09:00"),
                        Field.Text(KEY_END, "结束时刻（HH:mm）", placeholder = "18:00"),
                        Field.TextListField(KEY_WEEKDAYS, "星期（1=周一，7=周日）", maxItems = 7, addLabel = "增加一天")
                    )
                ),
                Section(
                    eyebrow = "// CLOCK",
                    title = "时间来源与分支",
                    fields = listOf(
                        Field.SwitchField(KEY_USE_BEIJING, "按北京时间（UTC+8）", defaultValue = true),
                        Field.EnumField(
                            KEY_ON_FALSE,
                            "条件不满足时",
                            options = listOf("返回失败", "仍然继续"),
                            values = listOf(ON_FALSE_FAIL, ON_FALSE_PASS)
                        )
                    )
                )
            )
        )

        override fun summarize(config: JSONObject): String {
            val mode = config.optString(KEY_MODE, MODE_AFTER)
            return when (mode) {
                MODE_AFTER -> "时刻在 " + config.optString(KEY_TIME, "09:30") + " 之后"
                MODE_BEFORE -> "时刻在 " + config.optString(KEY_TIME, "09:30") + " 之前"
                MODE_BETWEEN -> "时间在 " + config.optString(KEY_START, "09:00") + "–" + config.optString(KEY_END, "18:00") + " 内"
                MODE_WEEKDAY -> "星期在 " + readWeekdays(config).joinToString(",") + " 内"
                else -> "时间条件"
            }
        }

        override suspend fun execute(ctx: PluginContext, config: JSONObject): StepResult {
            val mode = config.optString(KEY_MODE, MODE_AFTER)
            val useBeijing = config.optBoolean(KEY_USE_BEIJING, true)
            val onFalse = config.optString(KEY_ON_FALSE, ON_FALSE_FAIL)

            // 宿主时区不可信，按固定 UTC+8 偏移换算；拿不到时间就如实失败
            val nowMs = runCatching { ctx.clock.nowMs() }
                .onFailure { ctx.log.warn("读取宿主时间失败") }
                .getOrNull()
                ?: return StepResult.Fail("无法读取当前时间")
            val offsetMs = if (useBeijing) 8L * 60L * 60L * 1000L else 0L
            val local: LocalDateTime
            try {
                local = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(nowMs + offsetMs),
                    ZoneOffset.UTC
                )
            } catch (t: Throwable) {
                ctx.log.warn("时间换算异常：" + (t.message ?: "未知错误"))
                return StepResult.Fail("无法换算当前时间")
            }

            val matched = when (mode) {
                MODE_AFTER -> {
                    val raw = config.optString(KEY_TIME, "09:30")
                    val target = parseTime(raw) ?: return badTime(ctx, raw)
                    local.toLocalTime() >= target
                }
                MODE_BEFORE -> {
                    val raw = config.optString(KEY_TIME, "09:30")
                    val target = parseTime(raw) ?: return badTime(ctx, raw)
                    local.toLocalTime() <= target
                }
                MODE_BETWEEN -> {
                    val startRaw = config.optString(KEY_START, "09:00")
                    val endRaw = config.optString(KEY_END, "18:00")
                    val start = parseTime(startRaw) ?: return badTime(ctx, startRaw)
                    val end = parseTime(endRaw) ?: return badTime(ctx, endRaw)
                    val current = local.toLocalTime()
                    if (start <= end) current in start..end else current >= start || current <= end
                }
                MODE_WEEKDAY -> {
                    val days = readWeekdays(config)
                    if (days.isEmpty()) {
                        return StepResult.Fail("还没有选择星期")
                    }
                    local.dayOfWeek.value in days
                }
                else -> return StepResult.Fail("未知的时间对比方式")
            }

            val nowText = pad(local.hour) + ":" + pad(local.minute)
            if (!matched) {
                val reason = when (mode) {
                    MODE_AFTER -> "现在 " + nowText + "，未到 " + config.optString(KEY_TIME, "09:30")
                    MODE_BEFORE -> "现在 " + nowText + "，已过 " + config.optString(KEY_TIME, "09:30")
                    MODE_BETWEEN -> "现在 " + nowText + "，不在 " + config.optString(KEY_START, "") + "–" + config.optString(KEY_END, "") + " 内"
                    MODE_WEEKDAY -> "现在 " + nowText + "，不是所选星期"
                    else -> "现在 " + nowText + "，时间条件不满足"
                }
                if (onFalse == ON_FALSE_PASS) {
                    ctx.log.info(reason + "，按配置仍然继续")
                    return StepResult.Success()
                }
                return StepResult.Fail(reason)
            }

            ctx.log.info("时间条件满足（" + nowText + "）")
            return StepResult.Success()
        }

        /** 只认 HH:mm；越界或写错都按解析失败处理，避免静默变成 00:00。 */
        private fun parseTime(raw: String): LocalTime? {
            val text = raw.trim()
            if (text.length != 5 || text[2] != ':') return null
            val hour = text.substring(0, 2).toIntOrNull() ?: return null
            val minute = text.substring(3, 5).toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return runCatching { LocalTime.of(hour, minute) }.getOrNull()
        }

        private fun badTime(ctx: PluginContext, raw: String): StepResult {
            ctx.log.warn("时间格式错误：" + raw)
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

        private fun pad(value: Int): String = if (value < 10) "0" + value else value.toString()

        private companion object {
            const val KEY_MODE = "mode"
            const val KEY_TIME = "timeHHmm"
            const val KEY_START = "startHHmm"
            const val KEY_END = "endHHmm"
            const val KEY_WEEKDAYS = "weekdays"
            const val KEY_USE_BEIJING = "useBeijing"
            const val KEY_ON_FALSE = "onFalse"

            const val MODE_AFTER = "after"
            const val MODE_BEFORE = "before"
            const val MODE_BETWEEN = "between"
            const val MODE_WEEKDAY = "weekday"

            const val ON_FALSE_FAIL = "fail"
            const val ON_FALSE_PASS = "pass"
        }
    }
}
