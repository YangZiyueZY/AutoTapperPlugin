package com.autotapper.plugin.logs

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
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 实时日志 —— 在规则执行过程中按等级写一条日志。
 *
 * message 支持两个占位符：{count} 是本步已执行次数（存插件私有 prefs），
 * {time} 是当前北京时间 HH:mm:ss。宿主时区不可信，按 UTC+8 固定偏移换算。
 */
class LogsPlugin : AutoTapperPlugin {

    override val id: String = "logs"
    override val name: String = "实时日志"
    override val version: String = "0.1.0"
    override val apiVersion: Int = PluginApi.VERSION
    override val minHostVersionCode: Int = 1

    override fun stepTypes(): List<PluginStepType> = listOf(WriteStep())

    /** typeId=logs.write：按配置等级写一条日志，随后继续。 */
    private class WriteStep : PluginStepType {

        override val typeId: String = "logs.write"
        override val displayName: String = "写一条日志"
        override val requires: Set<Capability> = emptySet()

        override fun defaultConfig(): JSONObject = JSONObject()
            .put(KEY_LEVEL, LEVEL_INFO)
            .put(KEY_MESSAGE, "")

        override fun editorSpec(config: JSONObject): ScreenSpec = ScreenSpec(
            sections = listOf(
                Section(
                    eyebrow = "// LOG",
                    title = "日志内容",
                    fields = listOf(
                        Field.EnumField(
                            KEY_LEVEL, "日志等级",
                            options = listOf("信息", "警告", "错误", "结果"),
                            values = listOf(LEVEL_INFO, LEVEL_WARN, LEVEL_ERROR, LEVEL_RESULT)
                        ),
                        Field.Text(
                            KEY_MESSAGE, "内容（{count}=次数，{time}=时间）",
                            placeholder = "第 {count} 次，当前 {time}", multiline = true
                        )
                    )
                )
            )
        )

        override fun summarize(config: JSONObject): String {
            val raw = config.optString(KEY_MESSAGE, "").ifBlank { "..." }
            return "记一条" + levelText(config) + "：" + short(raw)
        }

        override suspend fun execute(ctx: PluginContext, config: JSONObject): StepResult {
            val level = config.optString(KEY_LEVEL, LEVEL_INFO)
            val raw = config.optString(KEY_MESSAGE, "")

            // 每执行一次就加一，写入失败也用内存值继续，绝不打断引擎
            val previous = runCatching { ctx.prefs.getInt(PREF_COUNT, 0).coerceAtLeast(0) }
                .onFailure { safeInfo(ctx, "读取日志次数失败，按 0 处理") }
                .getOrDefault(0)
            val count = previous + 1
            val wrote = runCatching {
                ctx.prefs.edit().putInt(PREF_COUNT, count).apply()
                true
            }.getOrDefault(false)
            if (!wrote) safeInfo(ctx, "日志次数写入失败，本次按内存值 " + count + " 继续")

            val timeText = currentTimeText(ctx)
            val message = raw.replace("{count}", count.toString()).replace("{time}", timeText)
            val safeMessage = runCatching { message }.getOrDefault(raw)

            when (level) {
                LEVEL_WARN -> runCatching { ctx.log.warn(safeMessage) }
                LEVEL_ERROR -> runCatching { ctx.log.error(safeMessage) }
                LEVEL_RESULT -> {
                    // LogApi 没有 result()，结果等级借 info 承载，文案前加结果标记便于辨认
                    runCatching { ctx.log.info("【结果】" + safeMessage) }
                }
                else -> runCatching { ctx.log.info(safeMessage) }
            }.onFailure { safeInfo(ctx, "写日志失败：" + (it.message ?: it.javaClass.simpleName)) }

            return StepResult.Success()
        }

        /** 北京时间 = 宿主校准时间 + UTC+8 固定偏移，再按 UTC 解读成挂钟。 */
        private fun currentTimeText(ctx: PluginContext): String {
            val nowMs = runCatching { ctx.clock.nowMs() }.getOrNull()
                ?: return "??:??:??"
            return try {
                val local = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(nowMs + BEIJING_OFFSET_MS),
                    ZoneOffset.UTC
                )
                pad(local.hour) + ":" + pad(local.minute) + ":" + pad(local.second)
            } catch (t: Throwable) {
                "??:??:??"
            }
        }

        private fun levelText(config: JSONObject): String = when (config.optString(KEY_LEVEL, LEVEL_INFO)) {
            LEVEL_WARN -> "警告"
            LEVEL_ERROR -> "错误"
            LEVEL_RESULT -> "结果"
            else -> "信息"
        }

        /** 摘要只留一行：多行内容压成一行，过长截断。 */
        private fun short(raw: String): String {
            val oneLine = raw.replace('\n', ' ').replace('\r', ' ').trim()
            return if (oneLine.length > MAX_SUMMARY_CHARS) oneLine.substring(0, MAX_SUMMARY_CHARS) + "..." else oneLine
        }

        private fun pad(value: Int): String = if (value < 10) "0" + value else value.toString()

        private fun safeInfo(ctx: PluginContext, message: String) {
            runCatching { ctx.log.info(message) }
        }

        private companion object {
            const val KEY_LEVEL = "level"
            const val KEY_MESSAGE = "message"

            const val LEVEL_INFO = "info"
            const val LEVEL_WARN = "warn"
            const val LEVEL_ERROR = "error"
            const val LEVEL_RESULT = "result"

            const val PREF_COUNT = "count"
            const val MAX_SUMMARY_CHARS = 20
            const val BEIJING_OFFSET_MS = 8L * 60L * 60L * 1000L
        }
    }
}
