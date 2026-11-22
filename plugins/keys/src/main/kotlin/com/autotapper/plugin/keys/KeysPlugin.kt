package com.autotapper.plugin.keys

import com.example.autotapper.plugin.AutoTapperPlugin
import com.example.autotapper.plugin.Capability
import com.example.autotapper.plugin.Field
import com.example.autotapper.plugin.GlobalAction
import com.example.autotapper.plugin.KeyCode
import com.example.autotapper.plugin.PluginApi
import com.example.autotapper.plugin.PluginContext
import com.example.autotapper.plugin.PluginStepType
import com.example.autotapper.plugin.ScreenSpec
import com.example.autotapper.plugin.Section
import com.example.autotapper.plugin.StepResult
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * 按键 —— 发送返回键、桌面键、菜单键、回车键、删除键。
 *
 * 覆盖"返回上一级、回桌面、确认、删除输入"这类不需要点屏幕的操作。
 * 全局动作与按键事件都挂起等待宿主回调；菜单键失败时给出切通道的具体建议。
 */
class KeysPlugin : AutoTapperPlugin {

    override val id: String = "keys"

    override val name: String = "按键"

    override val version: String = "0.1.0"

    /** 必须等于主程序的 PluginApi.VERSION。 */
    override val apiVersion: Int = PluginApi.VERSION

    /** 主程序 versionCode 低于这个值时不加载。 */
    override val minHostVersionCode: Int = 1

    override fun stepTypes(): List<PluginStepType> = listOf(PressStep())

    /** typeId=keys.press：按配置发送一次全局动作或按键事件。 */
    private class PressStep : PluginStepType {
        override val typeId: String = "keys.press"
        override val displayName: String = "按一次按键"
        override val requires: Set<Capability> = setOf(Capability.ACCESSIBILITY)

        override fun defaultConfig(): JSONObject = JSONObject().put("action", ACTION_BACK)

        override fun editorSpec(config: JSONObject): ScreenSpec = ScreenSpec(
            sections = listOf(
                Section(
                    eyebrow = "// ACTION",
                    title = "按键动作",
                    fields = listOf(
                        Field.EnumField(
                            key = "action", label = "要按的键",
                            options = listOf("返回键", "桌面键", "菜单键", "回车键", "删除键"),
                            values = listOf(ACTION_BACK, ACTION_HOME, ACTION_MENU, ACTION_ENTER, ACTION_DEL)
                        )
                    )
                )
            )
        )

        override fun summarize(config: JSONObject): String {
            val action = try { config.optString("action", ACTION_BACK) } catch (t: Throwable) { ACTION_BACK }
            return "按" + labelOf(action)
        }

        override suspend fun execute(ctx: PluginContext, config: JSONObject): StepResult {
            val action = try { config.optString("action", ACTION_BACK) } catch (t: Throwable) { ACTION_BACK }
            val block: ((Boolean) -> Unit) -> Unit = when (action) {
                ACTION_BACK -> { cb -> ctx.executor.globalAction(GlobalAction.BACK, cb) }
                ACTION_HOME -> { cb -> ctx.executor.globalAction(GlobalAction.HOME, cb) }
                ACTION_MENU -> { cb -> ctx.executor.keyEvent(KeyCode.MENU, cb) }
                ACTION_ENTER -> { cb -> ctx.executor.keyEvent(KeyCode.ENTER, cb) }
                ACTION_DEL -> { cb -> ctx.executor.keyEvent(KeyCode.DEL, cb) }
                else -> return StepResult.Fail("未知的按键动作：" + action)
            }
            val ok = withTimeoutOrNull(CALLBACK_TIMEOUT_MS) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    try {
                        block { result -> if (cont.isActive) cont.resume(result) {} }
                    } catch (t: Throwable) {
                        ctx.log.warn("下发按键失败：" + (t.message ?: t.javaClass.simpleName))
                        if (cont.isActive) cont.resume(false) {}
                    }
                }
            }
            if (ok == null) ctx.log.warn("按键回调超时（" + CALLBACK_TIMEOUT_MS + " ms）")
            if (ok != true) return StepResult.Fail(failureText(action))
            return StepResult.Success()
        }

        companion object {
            const val ACTION_BACK = "back"
            const val ACTION_HOME = "home"
            const val ACTION_MENU = "menu"
            const val ACTION_ENTER = "enter"
            const val ACTION_DEL = "del"

            fun labelOf(action: String): String = when (action) {
                ACTION_HOME -> "桌面键"
                ACTION_MENU -> "菜单键"
                ACTION_ENTER -> "回车键"
                ACTION_DEL -> "删除键"
                else -> "返回键"
            }

            /** 菜单键在无障碍通道上常常发不出去，失败文案里明确告诉用户怎么换通道。 */
            fun failureText(action: String): String = if (action == ACTION_MENU) {
                "菜单键下发失败：无障碍通道不支持菜单键全局动作，请在设置里切到 Shizuku 通道"
            } else {
                labelOf(action) + "下发失败或回调超时"
            }
        }
    }

    companion object {
        /** 宿主回调可能永远不来，超过这个时间就按失败处理。 */
        const val CALLBACK_TIMEOUT_MS = 5000L
    }
}
