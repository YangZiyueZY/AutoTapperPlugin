package com.autotapper.plugin.numbercompare

import com.autotapper.testkit.FakeCapture
import com.autotapper.testkit.FakeContext
import com.autotapper.testkit.FakeOcr
import com.autotapper.testkit.FakeRegions
import com.autotapper.testkit.assertEditorKeysMatchDefaults
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

/** 数字对比插件的契约与 number-compare.compare 步骤测试。 */
class NumberComparePluginTest {

    private fun compareStep(): PluginStepType {
        val steps = NumberComparePlugin().stepTypes().filter { it.typeId == TYPE_ID }
        assertEquals("插件应只注册一个 " + TYPE_ID + " 步骤", 1, steps.size)
        return steps[0]
    }

    private fun configWith(target: String): JSONObject = JSONObject()
        .put("region", regionArray(1))
        .put("locate", "余额")
        .put("ignore", ",")
        .put("decimals", 0)
        .put("op", "gt")
        .put("target", target)
        .put("onFalse", "fail")

    private fun contextWith(capture: FakeCapture, ocr: FakeOcr): FakeContext = FakeContext(
        capture = capture,
        ocr = ocr,
        regions = FakeRegions(regions = listOf(Region("1", "A", 0, 0, 10, 10)))
    )

    @Test(timeout = 10_000)
    fun 编辑器字段与摘要符合默认配置() {
        val step = compareStep()
        assertEditorKeysMatchDefaults(step)
        assertSummaryHasNumber(step)
    }

    @Test(timeout = 10_000)
    fun 识别数字大于目标值时成功并调用一次OCR() = runBlocking {
        val capture = FakeCapture()
        val ocr = FakeOcr(text = "余额 200")
        val result = compareStep().execute(contextWith(capture, ocr), configWith("100"))
        assertTrue("余额 200 大于目标 100 时应成功", result is StepResult.Success)
        assertEquals("应按选区编号截取一次区域图像", 1, capture.grabRegionCalls.size)
        assertEquals("截取的区域应为编号 1 的 A 区", "1", capture.grabRegionCalls[0].id)
        assertEquals("应调用一次 OCR 读取数字", 1, ocr.recognizeCalls)
    }

    @Test(timeout = 10_000)
    fun 没有截图授权时返回中文失败原因() = runBlocking {
        val ocr = FakeOcr(text = "余额 200")
        val ctx = FakeContext(
            capture = FakeCapture(ready = false),
            ocr = ocr,
            regions = FakeRegions(regions = listOf(Region("1", "A", 0, 0, 10, 10)))
        )
        val result = compareStep().execute(ctx, configWith("100"))
        val reason = failReason(result)
        assertTrue("失败原因应说明缺少截图授权，实际为：" + reason, reason.contains("截图授权"))
        assertEquals("未授权时不应调用 OCR", 0, ocr.recognizeCalls)
    }

    @Test(timeout = 10_000)
    fun OCR报错时返回中文失败原因() = runBlocking {
        val ocr = FakeOcr(text = "余额 200", error = "识别引擎不可用")
        val capture = FakeCapture()
        val result = compareStep().execute(contextWith(capture, ocr), configWith("100"))
        val reason = failReason(result)
        assertTrue("失败原因应包含 OCR 错误，实际为：" + reason, reason.contains("识别引擎不可用"))
        assertEquals("OCR 报错前应完成一次区域截取", 1, capture.grabRegionCalls.size)
        assertEquals("OCR 报错前仍应完成一次识别调用", 1, ocr.recognizeCalls)
    }

    @Test(timeout = 10_000)
    fun 原文没有数字时返回中文失败原因() = runBlocking {
        val ocr = FakeOcr(text = "余额暂无")
        val capture = FakeCapture()
        val result = compareStep().execute(contextWith(capture, ocr), configWith("100"))
        val reason = failReason(result)
        assertTrue("失败原因应说明没识别到数字，实际为：" + reason, reason.contains("没识别到数字"))
        assertEquals("无数字场景仍应完成一次区域截取", 1, capture.grabRegionCalls.size)
        assertEquals("无数字场景仍应完成一次识别调用", 1, ocr.recognizeCalls)
    }

    @Test(timeout = 10_000)
    fun 识别数字不满足目标时返回中文失败原因() = runBlocking {
        val ocr = FakeOcr(text = "余额 200")
        val capture = FakeCapture()
        val result = compareStep().execute(contextWith(capture, ocr), configWith("999"))
        val reason = failReason(result)
        assertTrue("失败原因应说明数字对比不满足，实际为：" + reason, reason.contains("数字对比不满足"))
        assertEquals("不满足场景仍应完成一次区域截取", 1, capture.grabRegionCalls.size)
        assertEquals("不满足场景仍应完成一次识别调用", 1, ocr.recognizeCalls)
    }

    companion object {
        const val TYPE_ID = "number-compare.compare"
    }
}
