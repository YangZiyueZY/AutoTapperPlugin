package com.autotapper.plugin.multitouch

import com.autotapper.testkit.FakeContext
import com.autotapper.testkit.FakeExecutor
import com.autotapper.testkit.FakeRegions
import com.autotapper.testkit.assertEditorKeysMatchDefaults
import com.autotapper.testkit.assertPointsInRegions
import com.autotapper.testkit.assertSummaryHasNumber
import com.autotapper.testkit.failReason
import com.autotapper.testkit.rangeObject
import com.autotapper.testkit.regionArray
import com.example.autotapper.model.Region
import com.example.autotapper.plugin.PluginStepType
import com.example.autotapper.plugin.StepResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** multi-touch.press 的契约与执行分支测试。 */
class MultiTouchPluginTest {

    private fun step(): PluginStepType {
        val steps = MultiTouchPlugin().stepTypes().filter { it.typeId == "multi-touch.press" }
        assertEquals("应只注册一个 multi-touch.press 步骤", 1, steps.size)
        return steps[0]
    }

    private fun regions() = listOf(
        Region("1", "A", 0, 0, 10, 10),
        Region("2", "B", 50, 50, 10, 10)
    )

    private fun config(): JSONObject = JSONObject()
        .put("regions", regionArray(1, 2))
        .put("holdMs", rangeObject(200L))
        .put("maxPoints", 2)

    @Test(timeout = 10_000)
    fun 编辑器字段与摘要符合默认配置() {
        val s = step()
        assertEditorKeysMatchDefaults(s)
        assertSummaryHasNumber(s)
    }

    @Test(timeout = 10_000)
    fun 成功时每个区域取一点并同时按下() = runBlocking {
        val executor = FakeExecutor()
        val ctx = FakeContext(executor = executor, regions = FakeRegions(regions()))
        val result = step().execute(ctx, config())
        assertTrue("多点手势应成功：" + result, result is StepResult.Success)
        assertEquals("应下发一次多指", 1, executor.multiTouches.size)
        val call = executor.multiTouches[0]
        assertEquals("保持时长应取配置值", 200L, call.holdMs)
        assertPointsInRegions(call.points, regions())
    }

    @Test(timeout = 10_000)
    fun 少于两个区域时失败() = runBlocking {
        val ctx = FakeContext(regions = FakeRegions(regions().take(1)))
        val reason = failReason(step().execute(ctx, config()))
        assertTrue("失败原因应说明至少两个区域：" + reason, reason.contains("至少"))
    }

    @Test(timeout = 10_000)
    fun 注入回调失败时返回中文原因() = runBlocking {
        val ctx = FakeContext(executor = FakeExecutor(result = false), regions = FakeRegions(regions()))
        val reason = failReason(step().execute(ctx, config()))
        assertTrue("失败原因应说明下发失败：" + reason, reason.contains("多点手势下发失败"))
    }
}
