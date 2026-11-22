package com.autotapper.plugin.regionrandom

import com.example.autotapper.model.Region
import com.example.autotapper.plugin.AutoTapperPlugin
import com.example.autotapper.plugin.Capability
import com.example.autotapper.plugin.Field
import com.example.autotapper.plugin.PluginApi
import com.example.autotapper.plugin.PluginContext
import com.example.autotapper.plugin.PluginStepType
import com.example.autotapper.plugin.ScreenSpec
import com.example.autotapper.plugin.Section
import com.example.autotapper.plugin.StepResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * 区域随机插件 —— 在主界面已画好的区域里按序或随机取点并点击。
 *
 * 区域存盘值是序号字符串数组（1 基）；区域被删掉后按当前区域数量过滤，全失效就返回失败。
 */
class RegionRandomPlugin : AutoTapperPlugin {

    override val id: String = "region-random"
    override val name: String = "区域随机"
    override val version: String = "0.1.0"
    override val apiVersion: Int = PluginApi.VERSION
    override val minHostVersionCode: Int = 1

    override fun stepTypes(): List<PluginStepType> = listOf(TapRegionStep())

    /** 选择一个有效区域、随机取点并下发点击；注入回调带 5 秒超时兜底。 */
    private class TapRegionStep : PluginStepType {

        override val typeId: String = "region-random.tap"
        override val displayName: String = "区域随机点击"
        override val requires: Set<Capability> = setOf(Capability.ACCESSIBILITY)

        override fun defaultConfig(): JSONObject = JSONObject()
            .put(KEY_REGIONS, JSONArray())
            .put(KEY_MODE, MODE_SEQUENTIAL)
            .put(KEY_HOLD_MS, 50)

        override fun editorSpec(config: JSONObject): ScreenSpec = ScreenSpec(
            sections = listOf(
                Section(
                    eyebrow = "// TARGET",
                    title = "点击区域",
                    fields = listOf(
                        Field.RegionPicker(KEY_REGIONS, "选择区域", multiple = true),
                        Field.EnumField(
                            KEY_MODE,
                            "选区方式",
                            options = listOf("顺序区域", "随机区域", "总是从第一个"),
                            values = listOf(MODE_SEQUENTIAL, MODE_RANDOM, MODE_FROM_FIRST)
                        )
                    )
                ),
                Section(
                    eyebrow = "// TOUCH",
                    title = "触摸时长",
                    fields = listOf(
                        Field.RangeField(KEY_HOLD_MS, "按住时长", min = 20L, max = 500L, unit = "ms")
                    )
                )
            )
        )

        override fun summarize(config: JSONObject): String {
            val mode = config.optString(KEY_MODE, MODE_SEQUENTIAL)
            val selected = readIndices(config).size
            val holdMs = config.optInt(KEY_HOLD_MS, 50).coerceIn(20, 500)
            val label = when (mode) {
                MODE_RANDOM -> "随机区域"
                MODE_FROM_FIRST -> "第一个区域"
                else -> "顺序区域"
            }
            return label + " " + selected + "/" + selected + " · 按住 " + holdMs + " ms"
        }

        override suspend fun execute(ctx: PluginContext, config: JSONObject): StepResult {
            val mode = config.optString(KEY_MODE, MODE_SEQUENTIAL)
            val holdMs = config.optLong(KEY_HOLD_MS, 50L).coerceIn(20L, 500L)
            val selected = readIndices(config)

            val regions = runCatching { ctx.regions.load() }
                .onFailure { ctx.log.warn("读取区域失败：" + (it.message ?: "未知错误")) }
                .getOrDefault(emptyList())
            // 序号 1 基，对应 load() 下标 + 1；删掉区域后失效的序号直接丢弃
            val available = selected.mapNotNull { index -> regions.getOrNull(index - 1) }
            if (available.isEmpty()) {
                val reason = if (selected.isEmpty())
                    "还没有选择区域，请先在主界面画一个区域"
                else
                    "所选区域已不存在，请重新选"
                ctx.log.warn(reason)
                return StepResult.Fail(reason)
            }

            val region: Region = when (mode) {
                MODE_FROM_FIRST -> available.first()
                MODE_RANDOM -> available[Random.nextInt(available.size)]
                MODE_SEQUENTIAL -> {
                    val cursor = runCatching { ctx.prefs.getInt(PREF_CURSOR, 0).coerceAtLeast(0) }
                        .onFailure { ctx.log.warn("读取区域游标失败，从第一个开始") }
                        .getOrDefault(0)
                    val next = (cursor + 1) % available.size
                    val saved = runCatching {
                        ctx.prefs.edit().putInt(PREF_CURSOR, next).apply()
                        true
                    }.getOrDefault(false)
                    if (!saved) ctx.log.warn("保存区域游标失败，下次可能重复当前区域")
                    available[cursor % available.size]
                }
                else -> available.first()
            }

            val point = region.randomPoint(Random.Default)
            ctx.log.info("区域点击：" + region.label + " (" + point.x + "," + point.y + ") 按住 " + holdMs + " ms")

            // 注入回调可能永远不来，用协程超时兜底；异常和超时都在插件内消化
            val ok = runCatching { awaitInjection { cb -> ctx.executor.tap(point.x, point.y, holdMs, cb) } }
                .onFailure { ctx.log.warn("区域点击调用异常：" + (it.message ?: "未知错误")) }
                .getOrDefault(false)

            return if (ok) StepResult.Success(point) else StepResult.Fail("区域点击失败")
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

        /** 存盘值是区域序号字符串数组（1 基）；这里只保留合法正整数，顺序不变。 */
        private fun readIndices(config: JSONObject): List<Int> {
            val array = try { config.optJSONArray(KEY_REGIONS) } catch (t: Throwable) { null } ?: return emptyList()
            val result = ArrayList<Int>()
            for (i in 0 until array.length()) {
                val value = array.optString(i, "").trim().toIntOrNull() ?: continue
                if (value > 0 && value !in result) result.add(value)
            }
            return result
        }

        private companion object {
            const val KEY_REGIONS = "regions"
            const val KEY_MODE = "mode"
            const val KEY_HOLD_MS = "holdMs"

            const val MODE_SEQUENTIAL = "sequential"
            const val MODE_RANDOM = "random"
            const val MODE_FROM_FIRST = "from-first"

            const val PREF_CURSOR = "cursor"
            const val CALLBACK_TIMEOUT_MS = 5000L
        }
    }
}
