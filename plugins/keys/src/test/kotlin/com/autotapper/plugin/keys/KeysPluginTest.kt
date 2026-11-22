package com.autotapper.plugin.keys

import com.autotapper.testkit.FakeContext
import com.autotapper.testkit.FakeExecutor
import com.autotapper.testkit.assertEditorKeysMatchDefaults
import com.autotapper.testkit.failReason
import com.example.autotapper.plugin.GlobalAction
import com.example.autotapper.plugin.KeyCode
import com.example.autotapper.plugin.PluginStepType
import com.example.autotapper.plugin.StepResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按键插件契约测试：覆盖全局动作、按键事件和未知动作失败分支。
 * 注意：本插件的 summarize 文案是纯按键名（如「按返回键」），不含任何数字，
 * 因此 assertSummaryHasNumber 在源码层面无法满足；这里改为断言摘要非空且含正确按键名。
 */
class KeysPluginTest {

    private fun pressStep(): PluginStepType =
        KeysPlugin().stepTypes().first { it.typeId == "keys.press" }

    @Test(timeout = 10_000)
    fun editorKeysAndSummaryAreConsistent() {
        val step = pressStep()
        assertEditorKeysMatchDefaults(step)
        val summary = step.summarize(step.defaultConfig())
        assertTrue("摘要不能为空", summary.isNotBlank())
        assertTrue("默认摘要应是返回键：" + summary, summary.contains("返回键"))
        val homeSummary = step.summarize(step.defaultConfig().put("action", "home"))
        assertTrue("桌面键摘要：" + homeSummary, homeSummary.contains("桌面键"))
    }

    @Test(timeout = 10_000)
    fun backActionDispatchesGlobalBack() = runBlocking {
        val step = pressStep()
        val executor = FakeExecutor()
        val ctx = FakeContext(executor = executor)
        val result = step.execute(ctx, step.defaultConfig().put("action", "back"))

        assertTrue("返回键应成功：" + result, result is StepResult.Success)
        assertEquals("应下发一次全局动作", 1, executor.globalActions.size)
        assertEquals("应下发 BACK", GlobalAction.BACK, executor.globalActions[0].action)
        assertTrue("不应误发按键事件", executor.keyEvents.isEmpty())
    }

    @Test(timeout = 10_000)
    fun menuActionDispatchesKeyEvent() = runBlocking {
        val step = pressStep()
        val executor = FakeExecutor()
        val ctx = FakeContext(executor = executor)
        val result = step.execute(ctx, step.defaultConfig().put("action", "menu"))

        assertTrue("菜单键应成功：" + result, result is StepResult.Success)
        assertEquals("应下发一次按键事件", 1, executor.keyEvents.size)
        assertEquals("应下发 MENU", KeyCode.MENU, executor.keyEvents[0].key)
    }

    @Test(timeout = 10_000)
    fun invalidActionFailsWithChineseReason() = runBlocking {
        val step = pressStep()
        val executor = FakeExecutor()
        val ctx = FakeContext(executor = executor)
        val result = step.execute(ctx, step.defaultConfig().put("action", "invalid"))

        val reason = failReason(result)
        assertTrue("失败原因应提示未知按键：" + reason, reason.contains("未知的按键动作"))
        assertTrue("未知按键不能下发任何动作", executor.globalActions.isEmpty() && executor.keyEvents.isEmpty())
    }

    @Test(timeout = 10_000)
    fun dispatchFailureIsReported() = runBlocking {
        val step = pressStep()
        val ctx = FakeContext(executor = FakeExecutor(result = false))
        val result = step.execute(ctx, step.defaultConfig().put("action", "back"))

        val reason = failReason(result)
        assertTrue("失败原因应提示下发失败：" + reason, reason.contains("下发失败"))
    }
}
