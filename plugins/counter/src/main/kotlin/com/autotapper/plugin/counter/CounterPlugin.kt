package com.autotapper.plugin.counter

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

/**
 * 计数插件 —— 跨步骤、跨运行累计次数，达到目标后按配置决定停止、暂停、跳步或只记日志。
 *
 * 计数值存在插件私有 prefs 里；所有跨边界读写都做异常兜底，绝不把异常抛给宿主界面。
 */
class CounterPlugin : AutoTapperPlugin {

    override val id: String = "counter"
    override val name: String = "计数"
    override val version: String = "0.1.0"
    override val apiVersion: Int = PluginApi.VERSION
    override val minHostVersionCode: Int = 1

    override fun stepTypes(): List<PluginStepType> = listOf(CountStep())

    /** 每执行一次本步骤就把计数加一；达到目标后按 onReach 决定引擎的下一步动作。 */
    private class CountStep : PluginStepType {

        override val typeId: String = "counter.count"
        override val displayName: String = "计数"
        override val requires: Set<Capability> = emptySet()

        override fun defaultConfig(): JSONObject = JSONObject()
            .put(KEY_TARGET, 10)
            .put(KEY_ON_REACH, "stop")
            .put(KEY_GOTO_STEP, 1)
            .put(KEY_RESET_ON_REACH, true)

        override fun editorSpec(config: JSONObject): ScreenSpec = ScreenSpec(
            sections = listOf(
                Section(
                    eyebrow = "// COUNT",
                    title = "计数目标",
                    fields = listOf(
                        Field.IntField(KEY_TARGET, "目标次数", min = 1L, max = 100_000L, unit = "次"),
                        Field.SwitchField(KEY_RESET_ON_REACH, "到达后归零", defaultValue = true)
                    )
                ),
                Section(
                    eyebrow = "// ACTION",
                    title = "达到目标后",
                    fields = listOf(
                        Field.EnumField(
                            KEY_ON_REACH,
                            "动作",
                            options = listOf("继续", "停止", "暂停", "跳到指定步骤", "只记日志"),
                            values = listOf("continue", "stop", "pause", "goto", "log")
                        ),
                        Field.StepIndexField(KEY_GOTO_STEP, "跳到第几步")
                    )
                )
            )
        )

        override fun summarize(config: JSONObject): String {
            val target = config.optInt(KEY_TARGET, 10).coerceIn(1, 100_000)
            return "计数 0 / " + target
        }

        override suspend fun execute(ctx: PluginContext, config: JSONObject): StepResult {
            val target = config.optInt(KEY_TARGET, 10).coerceIn(1, 100_000)
            val action = config.optString(KEY_ON_REACH, "stop")
            val gotoStep = config.optInt(KEY_GOTO_STEP, 1)
            val resetOnReach = config.optBoolean(KEY_RESET_ON_REACH, true)

            val previous = runCatching { ctx.prefs.getInt(PREF_COUNT, 0).coerceAtLeast(0) }
                .onFailure { ctx.log.warn("读取计数失败，按 0 处理") }
                .getOrDefault(0)
            val n = previous + 1

            // prefs 写入失败不能打断引擎主流程，只记日志后继续用内存值判断
            val wrote = runCatching {
                ctx.prefs.edit().putInt(PREF_COUNT, n).apply()
                true
            }.getOrDefault(false)
            if (!wrote) ctx.log.warn("计数写入失败，本次按内存值 " + n + " 继续")

            val reached = n >= target
            if (reached && resetOnReach) {
                val cleared = runCatching {
                    ctx.prefs.edit().putInt(PREF_COUNT, 0).apply()
                    true
                }.getOrDefault(false)
                if (!cleared) ctx.log.warn("计数归零失败，下次执行可能仍视为已到达目标")
            }

            ctx.log.info(n.toString() + " / " + target)

            if (!reached) return StepResult.Success()

            return when (action) {
                "stop" -> StepResult.Stop
                "pause" -> StepResult.Pause
                "goto" -> StepResult.Goto((gotoStep - 1).coerceAtLeast(0))
                "log" -> {
                    ctx.log.info("计数到达 " + target + " 次，只记日志继续")
                    StepResult.Success()
                }
                else -> StepResult.Success()
            }
        }

        private companion object {
            const val KEY_TARGET = "target"
            const val KEY_ON_REACH = "onReach"
            const val KEY_GOTO_STEP = "gotoStep"
            const val KEY_RESET_ON_REACH = "resetOnReach"
            const val PREF_COUNT = "count"
        }
    }
}
