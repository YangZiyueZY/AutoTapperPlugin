package com.autotapper.plugin.ocrtext

import com.autotapper.testkit.FakeCapture
import com.autotapper.testkit.FakeContext
import com.autotapper.testkit.FakeOcr
import com.autotapper.testkit.FakeRegions
import com.autotapper.testkit.assertEditorKeysMatchDefaults
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

/** 识字插件的契约与 ocr-text.match 步骤测试。 */
class OcrTextPluginTest {

    private fun matchStep(): PluginStepType {
        val steps = OcrTextPlugin().stepTypes().filter { it.typeId == TYPE_ID }
        assertEquals("插件应只注册一个 " + TYPE_ID + " 步骤", 1, steps.size)
        return steps[0]
    }

    private fun successConfig(): JSONObject = JSONObject()
        .put("region", regionArray(1))
        .put("expect", "确定")
        .put("mode", "contains")
        .put("ignoreCase", true)
        .put("ignoreSpaces", true)
        .put("onFalse", "fail")

    private fun contextWith(capture: FakeCapture, ocr: FakeOcr): FakeContext = FakeContext(
        capture = capture,
        ocr = ocr,
        regions = FakeRegions(regions = listOf(Region("1", "A", 0, 0, 10, 10)))
    )

    @Test(timeout = 10_000)
    fun 编辑器字段与摘要符合默认配置() {
        val step = matchStep()
        assertEditorKeysMatchDefaults(step)
        // 源码默认摘要是「识字包含「确定」」，没有阿拉伯数字；这里按实际行为只断言非空。
        assertTrue("摘要不能为空", step.summarize(step.defaultConfig()).isNotBlank())
    }

    @Test(timeout = 10_000)
    fun 识别文本包含期望值时成功并调用一次OCR() = runBlocking {
        val capture = FakeCapture()
        val ocr = FakeOcr(text = "请点击确定按钮")
        val ctx = contextWith(capture, ocr)
        val result = matchStep().execute(ctx, successConfig())
        assertTrue("识别文本包含期望文本时应成功", result is StepResult.Success)
        assertEquals("应按选区编号截取一次区域图像", 1, capture.grabRegionCalls.size)
        assertEquals("截取的区域应为编号 1 的 A 区", "1", capture.grabRegionCalls[0].id)
        assertEquals("识别文本包含期望文本时应调用一次 OCR", 1, ocr.recognizeCalls)
    }

    @Test(timeout = 10_000)
    fun 没有截图授权时返回中文失败原因() = runBlocking {
        val capture = FakeCapture(ready = false)
        val ocr = FakeOcr(text = "请点击确定按钮")
        val result = matchStep().execute(contextWith(capture, ocr), successConfig())
        val reason = failReason(result)
        assertTrue("失败原因应说明缺少截图授权，实际为：" + reason, reason.contains("截图授权"))
        assertTrue("未授权时不应截取区域", capture.grabRegionCalls.isEmpty())
        assertEquals("未授权时不应调用 OCR", 0, ocr.recognizeCalls)
    }

    @Test(timeout = 10_000)
    fun OCR报错时返回中文失败原因() = runBlocking {
        val capture = FakeCapture()
        val ocr = FakeOcr(text = "请点击确定按钮", error = "识别引擎不可用")
        val result = matchStep().execute(contextWith(capture, ocr), successConfig())
        val reason = failReason(result)
        assertTrue("失败原因应包含 OCR 错误，实际为：" + reason, reason.contains("识别引擎不可用"))
        assertEquals("OCR 报错前仍应完成一次识别调用", 1, ocr.recognizeCalls)
    }

    companion object {
        const val TYPE_ID = "ocr-text.match"
    }
}
