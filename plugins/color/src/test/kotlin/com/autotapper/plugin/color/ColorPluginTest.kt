package com.autotapper.plugin.color

import com.autotapper.testkit.FakeBitmap
import com.autotapper.testkit.FakeCapture
import com.autotapper.testkit.FakeContext
import com.autotapper.testkit.FakeRegions
import com.autotapper.testkit.assertEditorKeysMatchDefaults
import com.autotapper.testkit.assertSummaryHasNumber
import com.autotapper.testkit.failReason
import com.autotapper.testkit.regionArray
import com.example.autotapper.model.Region
import com.example.autotapper.plugin.PluginStepType
import com.example.autotapper.plugin.StepResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 识色插件的契约与 color.match 步骤测试。 */
class ColorPluginTest {

    private fun matchStep(): PluginStepType {
        val steps = ColorPlugin().stepTypes().filter { it.typeId == TYPE_ID }
        assertEquals("插件应只注册一个 " + TYPE_ID + " 步骤", 1, steps.size)
        return steps[0]
    }

    /** 固定命中像素，避免让测试依赖真实截图内容。 */
    private fun successConfig(): JSONObject = JSONObject()
        .put("regions", regionArray(1))
        .put("colors", JSONArray().put("#FF3B30"))
        .put("tolerance", 0)
        .put("minHits", 1)
        .put("mode", "any")

    private fun contextWith(capture: FakeCapture): FakeContext = FakeContext(
        capture = capture,
        regions = FakeRegions(regions = listOf(Region("1", "A", 0, 0, 10, 10)))
    )

    @Test(timeout = 10_000)
    fun 编辑器字段与摘要符合默认配置() {
        val step = matchStep()
        assertEditorKeysMatchDefaults(step)
        assertSummaryHasNumber(step)
    }

    @Test(timeout = 10_000)
    fun 命中目标颜色时成功并截取所选区域() = runBlocking {
        val capture = FakeCapture(regionResult = FakeBitmap(width = 2, height = 2, argb = 0xFFFF3B30.toInt()))
        val ctx = contextWith(capture)
        val result = matchStep().execute(ctx, successConfig())
        assertTrue("颜色一致且命中数足够时应成功", result is StepResult.Success)
        assertEquals("应按选区编号截取一次区域图像", 1, capture.grabRegionCalls.size)
        assertEquals("截取的区域应为编号 1 的 A 区", "1", capture.grabRegionCalls[0].id)
        assertEquals("截取的区域标签应为 A", "A", capture.grabRegionCalls[0].label)
    }

    @Test(timeout = 10_000)
    fun 没有截图授权时返回中文失败原因() = runBlocking {
        // 未授权时插件不应调用截图能力，直接返回可读原因。
        val capture = FakeCapture(ready = false)
        val result = matchStep().execute(contextWith(capture), successConfig())
        val reason = failReason(result)
        assertTrue("失败原因应说明缺少截图授权，实际为：" + reason, reason.contains("截图授权"))
        assertTrue("未授权时不应截取区域", capture.grabRegionCalls.isEmpty())
    }

    companion object {
        const val TYPE_ID = "color.match"
    }
}
