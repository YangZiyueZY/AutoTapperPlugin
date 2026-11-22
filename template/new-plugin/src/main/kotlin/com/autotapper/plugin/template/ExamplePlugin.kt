package com.autotapper.plugin.template

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
import org.json.JSONObject

/**
 * 模板插件 —— 一个能跑的最小插件：只提供一个「等待 N 毫秒」的步骤类型。
 *
 * 复制到 plugins/<id>/ 之后要做的事见同目录 README.md。
 * 这里刻意把所有可选成员都留成默认值，方便看出一个插件最少需要写什么。
 */
class ExamplePlugin : AutoTapperPlugin {

    override val id: String = "example"

    override val name: String = "示例插件"

    override val version: String = "0.1.0"

    /** 必须等于主程序的 PluginApi.VERSION，否则宿主拒绝加载。 */
    override val apiVersion: Int = PluginApi.VERSION

    /** 主程序 versionCode 低于这个值就不装，用于挡住老宿主。 */
    override val minHostVersionCode: Int = 1

    override fun stepTypes(): List<PluginStepType> = listOf(DelayStep())

    /** 等待固定毫秒数。不需要任何宿主能力，所以 requires 是空集。 */
    private class DelayStep : PluginStepType {

        override val typeId: String = "example.delay"

        override val displayName: String = "等待"

        override val requires: Set<Capability> = emptySet()

        override fun defaultConfig(): JSONObject = JSONObject().put("ms", 500L)

        override fun editorSpec(config: JSONObject): ScreenSpec = ScreenSpec(
            sections = listOf(
                Section(
                    eyebrow = "// DELAY",
                    title = "等待时长",
                    fields = listOf(
                        Field.IntField(
                            key = "ms",
                            label = "毫秒",
                            min = 0L,
                            max = 600_000L,
                            unit = "ms"
                        )
                    )
                )
            )
        )

        override fun summarize(config: JSONObject): String =
            "等待 ${config.optLong("ms", 500L)} ms"

        override suspend fun execute(ctx: PluginContext, config: JSONObject): StepResult {
            val ms = config.optLong("ms", 500L).coerceIn(0L, 600_000L)
            delay(ms)
            // 摘要由宿主用 summarize() 现算，这里不需要回写任何东西
            return StepResult.Success()
        }
    }
}
