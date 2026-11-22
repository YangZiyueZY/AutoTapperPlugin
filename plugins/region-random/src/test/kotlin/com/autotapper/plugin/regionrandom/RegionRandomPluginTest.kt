package com.autotapper.plugin.regionrandom

import com.autotapper.testkit.FakeContext
import com.autotapper.testkit.FakeExecutor
import com.autotapper.testkit.FakePrefs
import com.autotapper.testkit.FakeRegions
import com.autotapper.testkit.assertEditorKeysMatchDefaults
import com.autotapper.testkit.assertPointInRegion
import com.autotapper.testkit.assertSummaryHasNumber
import com.autotapper.testkit.failReason
import com.autotapper.testkit.regionArray
import com.example.autotapper.model.Region
import com.example.autotapper.plugin.PluginStepType
import com.example.autotapper.plugin.StepResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** region-random.tap 的契约与执行分支测试。 */
class RegionRandomPluginTest {

    private fun step(): PluginStepType {
        val steps = RegionRandomPlugin().stepTypes().filter { it.typeId == "region-random.tap" }
        assertEquals("应只注册一个 region-random.tap 步骤", 1, steps.size)
        return steps[0]
    }

    private fun regions() = listOf(Region("1", "区域一", 5, 5, 20, 20))

    private fun config(): JSONObject = JSONObject()
        .put("regions", regionArray(1))
        .put("mode", "from-first")
        .put("holdMs", 50)

    @Test(timeout = 10_000)
    fun 编辑器字段与摘要符合默认配置() {
        val s = step()
        assertEditorKeysMatchDefaults(s)
        assertSummaryHasNumber(s)
    }

    @Test(timeout = 10_000)
    fun 成功时在所选区域内点击() = runBlocking {
        val executor = FakeExecutor()
        val ctx = FakeContext(
            executor = executor,
            regions = FakeRegions(regions()),
            prefs = FakePrefs()
        )
        val result = step().execute(ctx, config())
        assertTrue("区域点击应成功：" + result, result is StepResult.Success)
        assertEquals("应下发一次点击", 1, executor.taps.size)
        assertEquals("按住时长应取配置值", 50L, executor.taps[0].durationMs)
        assertPointInRegion(
            com.example.autotapper.model.TapCoord(executor.taps[0].x, executor.taps[0].y),
            regions()[0]
        )
    }

    @Test(timeout = 10_000)
    fun 没有选择区域时失败() = runBlocking {
        val ctx = FakeContext(regions = FakeRegions(regions()))
        val empty = JSONObject().put("regions", regionArray()).put("mode", "from-first").put("holdMs", 50)
        val reason = failReason(step().execute(ctx, empty))
        assertTrue("失败原因应提示未选区域：" + reason, reason.contains("还没有选择区域"))
    }

    @Test(timeout = 10_000)
    fun 区域序号失效时失败() = runBlocking {
        val ctx = FakeContext(regions = FakeRegions(regions()))
        val stale = JSONObject().put("regions", regionArray(9)).put("mode", "from-first").put("holdMs", 50)
        val reason = failReason(step().execute(ctx, stale))
        assertTrue("失败原因应提示区域不存在：" + reason, reason.contains("区域已不存在"))
    }
}
