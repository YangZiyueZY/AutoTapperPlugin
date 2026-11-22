package com.autotapper.plugin.viewid

import com.autotapper.testkit.FakeContext
import com.autotapper.testkit.FakeExecutor
import com.autotapper.testkit.FakeNodes
import com.autotapper.testkit.assertDurationInRange
import com.autotapper.testkit.assertEditorKeysMatchDefaults
import com.autotapper.testkit.assertSummaryHasNumber
import com.autotapper.testkit.failReason
import com.example.autotapper.model.TapCoord
import com.example.autotapper.plugin.PluginStepType
import com.example.autotapper.plugin.StepResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识ID 插件契约测试：验证配置/摘要自洽，以及"找到就点、没连上就失败"两条主路。
 * 点击时长是区间随机，所以用闭区间断言而不是精确值，避免测试本身不稳定。
 */
class ViewIdPluginTest {

    private fun clickStep(): PluginStepType =
        ViewIdPlugin().stepTypes().first { it.typeId == "view-id.click" }

    private fun existsStep(): PluginStepType =
        ViewIdPlugin().stepTypes().first { it.typeId == "view-id.exists" }

    private fun configWithViewId(viewId: String): JSONObject =
        clickStep().defaultConfig().put("viewId", viewId)

    @Test(timeout = 10_000)
    fun clickEditorKeysAndSummaryAreConsistent() {
        val step = clickStep()
        assertEditorKeysMatchDefaults(step)
        assertSummaryHasNumber(step)
    }

    @Test(timeout = 10_000)
    fun existsEditorKeysAndSummaryAreConsistent() {
        val step = existsStep()
        assertEditorKeysMatchDefaults(step)
        // 默认摘要没有数字，按要求用带数字的 ID 改写配置后断言摘要含数字。
        val summary = step.summarize(step.defaultConfig().put("viewId", "com.test:id/btn2"))
        assertTrue("exists 摘要不能为空", summary.isNotBlank())
        assertTrue("exists 摘要应含数字：" + summary, summary.any { it.isDigit() })
        assertTrue("exists 摘要应提到目标 ID：" + summary, summary.contains("com.test:id/btn2"))
    }

    @Test(timeout = 10_000)
    fun clickFindsNodeAndTapsWithinHoldRange() = runBlocking {
        val step = clickStep()
        val executor = FakeExecutor()
        val ctx = FakeContext(
            executor = executor,
            nodes = FakeNodes(ready = true, viewIdMap = mapOf("com.test:id/btn" to TapCoord(15, 25)))
        )
        val result = step.execute(ctx, configWithViewId("com.test:id/btn"))

        assertTrue("找到节点应成功：" + result, result is StepResult.Success)
        assertEquals("应下发一次点击", 1, executor.taps.size)
        val tap = executor.taps[0]
        assertEquals("点击 x 应来自节点", 15, tap.x)
        assertEquals("点击 y 应来自节点", 25, tap.y)
        assertDurationInRange(tap.durationMs, 20L, 500L)
        assertTrue("成功结果应带上命中坐标", (result as StepResult.Success).at == TapCoord(15, 25))
    }

    @Test(timeout = 10_000)
    fun clickFailsWhenAccessibilityServiceNotReady() = runBlocking {
        val step = clickStep()
        val executor = FakeExecutor()
        val ctx = FakeContext(executor = executor, nodes = FakeNodes(ready = false))
        val result = step.execute(ctx, configWithViewId("com.test:id/btn"))

        val reason = failReason(result)
        assertTrue("失败原因应提到无障碍：" + reason, reason.contains("无障碍"))
        assertTrue("服务没连上不能下发点击", executor.taps.isEmpty())
    }

    @Test(timeout = 10_000)
    fun clickFailsWhenViewIdMissing() = runBlocking {
        val step = clickStep()
        val ctx = FakeContext(nodes = FakeNodes(ready = true))
        val result = step.execute(ctx, step.defaultConfig())

        val reason = failReason(result)
        assertTrue("失败原因应提示填控件 ID：" + reason, reason.contains("控件 ID"))
    }

    @Test(timeout = 10_000)
    fun existsSucceedsWhenNodePresent() = runBlocking {
        val step = existsStep()
        val ctx = FakeContext(
            nodes = FakeNodes(ready = true, viewIdMap = mapOf("com.test:id/btn" to TapCoord(7, 9)))
        )
        val config = step.defaultConfig().put("viewId", "com.test:id/btn")
        val result = step.execute(ctx, config)

        assertTrue("节点存在应成功：" + result, result is StepResult.Success)
        assertEquals("存在判断不应下发点击", 0, (ctx.executor as FakeExecutor).taps.size)
    }

    @Test(timeout = 10_000)
    fun existsFailsWhenNodeAbsent() = runBlocking {
        val step = existsStep()
        val ctx = FakeContext(nodes = FakeNodes(ready = true))
        val config = step.defaultConfig().put("viewId", "com.test:id/btn")
        val result = step.execute(ctx, config)

        val reason = failReason(result)
        assertTrue("失败原因应提示还没出现：" + reason, reason.contains("还没出现"))
    }
}
