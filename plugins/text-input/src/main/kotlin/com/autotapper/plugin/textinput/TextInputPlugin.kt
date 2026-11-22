package com.autotapper.plugin.textinput

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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * 文本输入插件 —— 按固定文本、顺序列表、随机列表或字符集生成字符串，注入当前焦点。
 *
 * 列表模式的游标存在插件私有 prefs 里，跨步骤与跨运行保持；注入回调必须有超时兜底。
 */
class TextInputPlugin : AutoTapperPlugin {

    override val id: String = "text-input"
    override val name: String = "文本输入"
    override val version: String = "0.1.0"
    override val apiVersion: Int = PluginApi.VERSION
    override val minHostVersionCode: Int = 1

    override fun stepTypes(): List<PluginStepType> = listOf(InputTextStep())

    /** 生成一段文本并通过宿主的注入器输入；空白输入直接失败，不打扰界面。 */
    private class InputTextStep : PluginStepType {

        override val typeId: String = "text-input.type"
        override val displayName: String = "输入文本"
        override val requires: Set<Capability> = setOf(Capability.ACCESSIBILITY)

        override fun defaultConfig(): JSONObject = JSONObject()
            .put(KEY_MODE, MODE_FIXED)
            .put(KEY_TEXT, "")
            .put(KEY_LIST, JSONArray())
            .put(KEY_CHARSET, CHARSET_DIGITS)
            .put(KEY_LENGTH, 6)
            .put(KEY_DELAY_MS, 0)

        override fun editorSpec(config: JSONObject): ScreenSpec = ScreenSpec(
            sections = listOf(
                Section(
                    eyebrow = "// SOURCE",
                    title = "输入内容",
                    fields = listOf(
                        Field.EnumField(
                            KEY_MODE,
                            "生成方式",
                            options = listOf("固定文本", "顺序列表", "随机列表", "字符集随机"),
                            values = listOf(MODE_FIXED, MODE_SEQUENCE, MODE_RANDOM, MODE_LIST_RANDOM)
                        ),
                        Field.Text(KEY_TEXT, "固定文本", placeholder = "例如：订单 123"),
                        Field.TextListField(KEY_LIST, "候选列表", maxItems = 50, addLabel = "增加一条"),
                        Field.EnumField(
                            KEY_CHARSET,
                            "字符集",
                            options = listOf("数字", "小写字母", "大写字母", "字母加数字"),
                            values = listOf(CHARSET_DIGITS, CHARSET_LOWER, CHARSET_UPPER, CHARSET_ALNUM)
                        ),
                        Field.IntField(KEY_LENGTH, "随机长度", min = 1L, max = 64L, unit = "字符")
                    )
                ),
                Section(
                    eyebrow = "// TIMING",
                    title = "输入节奏",
                    fields = listOf(
                        Field.IntField(KEY_DELAY_MS, "输入前等待", min = 0L, max = 5000L, unit = "ms")
                    )
                )
            )
        )

        override fun summarize(config: JSONObject): String {
            val mode = config.optString(KEY_MODE, MODE_FIXED)
            val text = config.optString(KEY_TEXT, "").trim()
            return when (mode) {
                MODE_FIXED -> "输入「" + text + "」"
                MODE_SEQUENCE -> "顺序输入列表（共 " + readList(config).size + " 条）"
                MODE_RANDOM -> "随机输入列表（共 " + readList(config).size + " 条）"
                MODE_LIST_RANDOM -> "输入 " + config.optInt(KEY_LENGTH, 6).coerceIn(1, 64) + " 位"
                else -> "输入「" + text + "」"
            }
        }

        override suspend fun execute(ctx: PluginContext, config: JSONObject): StepResult {
            val mode = config.optString(KEY_MODE, MODE_FIXED)
            val length = config.optInt(KEY_LENGTH, 6).coerceIn(1, 64)
            val delayMs = config.optLong(KEY_DELAY_MS, 0L).coerceIn(0L, 5000L)
            val list = readList(config)

            val text = when (mode) {
                MODE_FIXED -> config.optString(KEY_TEXT, "")
                MODE_SEQUENCE -> {
                    if (list.isEmpty()) "" else {
                        val cursor = runCatching { ctx.prefs.getInt(PREF_CURSOR, 0).coerceAtLeast(0) }
                            .onFailure { ctx.log.warn("读取列表游标失败，从第一条开始") }
                            .getOrDefault(0)
                        val index = cursor % list.size
                        val next = (cursor + 1) % list.size
                        val saved = runCatching {
                            ctx.prefs.edit().putInt(PREF_CURSOR, next).apply()
                            true
                        }.getOrDefault(false)
                        if (!saved) ctx.log.warn("保存列表游标失败，下次可能重复当前条目")
                        list[index]
                    }
                }
                MODE_RANDOM -> if (list.isEmpty()) "" else list[Random.nextInt(list.size)]
                MODE_LIST_RANDOM -> randomChars(config.optString(KEY_CHARSET, CHARSET_DIGITS), length)
                else -> config.optString(KEY_TEXT, "")
            }

            if (text.isEmpty()) return StepResult.Fail("没有可输入的文本")

            if (delayMs > 0L) {
                // 输入前等待用非阻塞 delay，引擎停止时能立即响应取消
                delay(delayMs)
            }

            val label = if (text.length > 20) text.substring(0, 20) + "…" else text
            ctx.log.info("输入文本：" + label)

            // 注入回调可能永远不来，用协程超时兜底；异常和超时都在插件内消化
            val ok = runCatching { awaitInjection { cb -> ctx.executor.inputText(text, cb) } }
                .onFailure { ctx.log.warn("输入文本调用异常：" + (it.message ?: "未知错误")) }
                .getOrDefault(false)

            return if (ok) StepResult.Success() else StepResult.Fail("文本输入失败")
        }

        /** 把回调式注入包装成可取消的挂起等待；回调不回来就超时返回 false。 */
        private suspend fun awaitInjection(block: ((Boolean) -> Unit) -> Unit): Boolean =
            withTimeoutOrNull(CALLBACK_TIMEOUT_MS) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    runCatching {
                        block { ok -> if (cont.isActive) cont.resume(ok) {} }
                    }.onFailure {
                        if (cont.isActive) cont.resume(false) {}
                    }
                }
            } ?: false

        /** 解析列表字段：存盘值是 JSONArray 字符串数组，这里兼容手改过的脏数据。 */
        private fun readList(config: JSONObject): List<String> {
            val array = try { config.optJSONArray(KEY_LIST) } catch (t: Throwable) { null } ?: return emptyList()
            val result = ArrayList<String>()
            for (i in 0 until array.length()) {
                val item = array.optString(i, "").trim()
                if (item.isNotEmpty()) result.add(item)
            }
            return result
        }

        /** 按字符集随机生成定长字符串；未知字符集退回数字。 */
        private fun randomChars(charset: String, length: Int): String {
            val alphabet = when (charset) {
                CHARSET_LOWER -> "abcdefghijklmnopqrstuvwxyz"
                CHARSET_UPPER -> "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                CHARSET_ALNUM -> "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
                else -> "0123456789"
            }
            val sb = StringBuilder(length)
            repeat(length) { sb.append(alphabet[Random.nextInt(alphabet.length)]) }
            return sb.toString()
        }

        private companion object {
            const val KEY_MODE = "mode"
            const val KEY_TEXT = "text"
            const val KEY_LIST = "list"
            const val KEY_CHARSET = "charset"
            const val KEY_LENGTH = "length"
            const val KEY_DELAY_MS = "delayMs"

            const val MODE_FIXED = "fixed"
            const val MODE_SEQUENCE = "sequence"
            const val MODE_RANDOM = "random"
            const val MODE_LIST_RANDOM = "list-random"

            const val CHARSET_DIGITS = "digits"
            const val CHARSET_LOWER = "lower"
            const val CHARSET_UPPER = "upper"
            const val CHARSET_ALNUM = "alnum"

            const val PREF_CURSOR = "cursor"
            const val CALLBACK_TIMEOUT_MS = 5000L
        }
    }
}
