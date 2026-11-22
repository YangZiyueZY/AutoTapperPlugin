package com.autotapper.plugin.tapstyles

import com.autotapper.testkit.FakeContext
import com.autotapper.testkit.FakeExecutor
import com.autotapper.testkit.FakeRegions
import com.autotapper.testkit.assertEditorKeysMatchDefaults
import com.autotapper.testkit.assertPointInRegion
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

/** 长按 / 连击 / 频率三种点击步骤的契约与执行分支测试。 */
class TapStylesPluginTest {

    private fun step(typeId: String): PluginStepType {
        val steps = TapStylesPlugin().stepTypes().filter { it.typeId == typeId }
        assertEquals("应只注册一个 " + typeId + " 步骤", 1, steps.size)
        return steps[0]
    }

    private fun regions() = listOf(Region("1", "按钮", 10, 20, 30, 40))

    @Test(timeout = 10_000)
    fun 长按编辑器字段与摘要符合默认配置() {
        val s = step("tap-styles.longpress")
        assertEditorKeysMatchDefaults(s)
        assertSummaryHasNumber(s)
    }

    @Test(timeout = 10_000)
    fun 长按成功时点击区域内并按配置时长() = runBlocking {
        val executor = FakeExecutor()
        val ctx = FakeContext(executor = executor, regions = FakeRegions(regions()))
        val config = JSONObject().put("regions", regionArray(1)).put("durationMs", rangeObject(800L))
        val result = step("tap-styles.longpress").execute(ctx, config)
        assertTrue("长按应成功：" + result, result is StepResult.Success)
        assertEquals("应下发一次点击", 1, executor.taps.size)
        assertEquals("按住时长应取配置值", 800L, executor.taps[0].durationMs)
        assertPointInRegion(
            com.example.autotapper.model.TapCoord(executor.taps[0].x, executor.taps[0].y),
            regions()[0]
        )
    }

    @Test(timeout = 10_000)
    fun 长按注入失败时返回中文原因() = runBlocking {
        val ctx = FakeContext(executor = FakeExecutor(result = false), regions = FakeRegions(regions()))
        val config = JSONObject().put("regions", regionArray(1)).put("durationMs", rangeObject(800L))
        val reason = failReason(step("tap-styles.longpress").execute(ctx, config))
        assertTrue("失败原因应说明下发失败：" + reason, reason.contains("长按点击下发失败"))
    }

    @Test(timeout = 10_000)
    fun 连击编辑器字段与摘要符合默认配置() {
        val s = step("tap-styles.burst")
        assertEditorKeysMatchDefaults(s)
        assertSummaryHasNumber(s)
    }

    @Test(timeout = 10_000)
    fun 连击成功时按配置次数点击() = runBlocking {
        val executor = FakeExecutor()
        val ctx = FakeContext(executor = executor, regions = FakeRegions(regions()))
        val config = JSONObject()
            .put("regions", regionArray(1))
            .put("times", 2)
            .put("intervalMs", rangeObject(20L))
        val result = step("tap-styles.burst").execute(ctx, config)
        assertTrue("连击应成功：" + result, result is StepResult.Success)
        assertEquals("应下发两次点击", 2, executor.taps.size)
    }

    @Test(timeout = 10_000)
    fun 连击缺少有效区域时失败() = runBlocking {
        val ctx = FakeContext(regions = FakeRegions(emptyList()))
        val config = JSONObject()
            .put("regions", regionArray(1))
            .put("times", 2)
            .put("intervalMs", rangeObject(20L))
        val reason = failReason(step("tap-styles.burst").execute(ctx, config))
        assertTrue("失败原因应说明区域问题：" + reason, reason.contains("区域"))
    }

    @Test(timeout = 10_000)
    fun 频率编辑器字段与摘要符合默认配置() {
        val s = step("tap-styles.frequency")
        assertEditorKeysMatchDefaults(s)
        assertSummaryHasNumber(s)
    }

    @Test(timeout = 10_000)
    fun 频率成功时至少下发一次点击() = runBlocking {
        val executor = FakeExecutor(maxTps = 60)
        val ctx = FakeContext(executor = executor, regions = FakeRegions(regions()))
        val config = JSONObject()
            .put("regions", regionArray(1))
            .put("durationMs", rangeObject(50L))
            .put("timesPerSecond", 10)
            .put("totalMs", 200L)
        val result = step("tap-styles.frequency").execute(ctx, config)
        assertTrue("频率模式应成功：" + result, result is StepResult.Success)
        assertTrue("应至少下发一次点击", executor.taps.isNotEmpty())
    }

    @Test(timeout = 10_000)
    fun 频率注入全失败时返回中文原因() = runBlocking {
        val ctx = FakeContext(executor = FakeExecutor(result = false), regions = FakeRegions(regions()))
        val config = JSONObject()
            .put("regions", regionArray(1))
            .put("durationMs", rangeObject(50L))
            .put("timesPerSecond", 10)
            .put("totalMs", 200L)
        val reason = failReason(step("tap-styles.frequency").execute(ctx, config))
        assertTrue("失败原因应说明限速或成功率：" + reason, reason.contains("成功率"))
    }
}
