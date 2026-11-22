package com.autotapper.plugin.swipe

import com.autotapper.testkit.FakeContext
import com.autotapper.testkit.FakeExecutor
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

/** swipe.line 的契约与执行分支测试。 */
class SwipePluginTest {

    private fun step(): PluginStepType {
        val steps = SwipePlugin().stepTypes().filter { it.typeId == "swipe.line" }
        assertEquals("应只有一个 swipe.line 步骤", 1, steps.size)
        return steps[0]
    }

    private fun config(): JSONObject = JSONObject()
        .put("fromRegion", regionArray(1))
        .put("toRegion", regionArray(2))
        .put("mode", "normal")
        .put("durationMs", rangeObject(300L))

    private fun regions() = listOf(
        Region("1", "起点", 0, 0, 10, 10),
        Region("2", "终点", 100, 100, 10, 10)
    )

    @Test(timeout = 10_000)
    fun 编辑器字段与摘要符合默认配置() {
        val s = step()
        assertEditorKeysMatchDefaults(s)
        assertSummaryHasNumber(s)
    }

    @Test(timeout = 10_000)
    fun 成功时按区域取起止点并滑动() = runBlocking {
        val executor = FakeExecutor(result = true)
        val ctx = FakeContext(executor = executor, regions = com.autotapper.testkit.FakeRegions(regions()))
        val result = step().execute(ctx, config())
        assertTrue("滑动成功应返回 Success：" + result, result is StepResult.Success)
        assertEquals("应下发一次滑动", 1, executor.swipes.size)
        val call = executor.swipes[0]
        assertEquals("滑动时长应取配置值", 300L, call.durationMs)
        assertPointInRegion(call.from, regions()[0])
        assertPointInRegion(call.to, regions()[1])
    }

    @Test(timeout = 10_000)
    fun 起点区域失效时失败() = runBlocking {
        val ctx = FakeContext(regions = com.autotapper.testkit.FakeRegions(regions()))
        val bad = config().put("fromRegion", regionArray(9))
        val reason = failReason(step().execute(ctx, bad))
        assertTrue("失败原因应说明起点区域问题：" + reason, reason.contains("起点区域"))
    }

    @Test(timeout = 10_000)
    fun 注入回调失败时返回中文失败原因() = runBlocking {
        val ctx = FakeContext(
            executor = FakeExecutor(result = false),
            regions = com.autotapper.testkit.FakeRegions(regions())
        )
        val reason = failReason(step().execute(ctx, config()))
        assertTrue("失败原因应说明滑动失败：" + reason, reason.contains("滑动下发失败"))
    }
}
