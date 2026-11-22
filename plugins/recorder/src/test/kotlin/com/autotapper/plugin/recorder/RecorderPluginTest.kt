package com.autotapper.plugin.recorder

import com.autotapper.testkit.FakeContext
import com.autotapper.testkit.FakeRecord
import com.autotapper.testkit.assertEditorKeysMatchDefaults
import com.autotapper.testkit.assertSummaryHasNumber
import com.autotapper.testkit.failReason
import com.example.autotapper.model.TapCoord
import com.example.autotapper.plugin.PluginStepType
import com.example.autotapper.plugin.RecordedGesture
import com.example.autotapper.plugin.StepResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 录制插件契约测试：点击手势转成 core.tap 并存盘，录制能力缺失/空录制如实失败。
 * 录制是挂起调用，FakeRecord 同步返回，所有测试都在 10 秒超时内结束。
 */
class RecorderPluginTest {

    private fun recordStep(): PluginStepType =
        RecorderPlugin().stepTypes().first { it.typeId == "recorder.record" }

    private fun oneTapGesture(): RecordedGesture =
        RecordedGesture(
            RecordedGesture.Kind.TAP,
            TapCoord(5, 5),
            TapCoord(5, 5),
            10L,
            50L
        )

    @Test(timeout = 10_000)
    fun editorKeysAndSummaryAreConsistent() {
        val step = recordStep()
        assertEditorKeysMatchDefaults(step)
        assertSummaryHasNumber(step)
    }

    @Test(timeout = 10_000)
    fun recordedTapIsConvertedAndSaved() = runBlocking {
        val step = recordStep()
        val recorder = FakeRecord(gestures = listOf(oneTapGesture()))
        val ctx = FakeContext(recorder = recorder)
        val result = step.execute(ctx, step.defaultConfig())

        assertTrue("录到点击应成功：" + result, result is StepResult.Success)
        assertEquals("应调用录制一次", 1, recorder.recordCalls)
        val saved = ctx.prefs.getString("lastRecorded", "")
        assertTrue("录到的步骤应存盘：" + saved, saved!!.contains("core.tap"))
        assertTrue(
            "应记录保存日志：" + ctx.log.all.joinToString(" | "),
            ctx.log.all.any { it.contains("已保存到插件配置") }
        )
    }

    @Test(timeout = 10_000)
    fun unsupportedRecorderFails() = runBlocking {
        val step = recordStep()
        val ctx = FakeContext(recorder = FakeRecord(supported = false))
        val result = step.execute(ctx, step.defaultConfig())

        val reason = failReason(result)
        assertTrue("失败原因应提示悬浮窗权限：" + reason, reason.contains("悬浮窗"))
    }

    @Test(timeout = 10_000)
    fun emptyRecordingFails() = runBlocking {
        val step = recordStep()
        val ctx = FakeContext(recorder = FakeRecord(supported = true, gestures = emptyList()))
        val result = step.execute(ctx, step.defaultConfig())

        val reason = failReason(result)
        assertTrue("失败原因应提示没录到操作：" + reason, reason.contains("没有录到"))
    }

    @Test(timeout = 10_000)
    fun swipeOnlyRecordingFails() = runBlocking {
        val step = recordStep()
        val swipe = RecordedGesture(
            RecordedGesture.Kind.SWIPE,
            TapCoord(1, 1),
            TapCoord(9, 9),
            100L,
            0L
        )
        val ctx = FakeContext(recorder = FakeRecord(gestures = listOf(swipe)))
        val result = step.execute(ctx, step.defaultConfig())

        val reason = failReason(result)
        assertTrue("失败原因应提示都是滑动：" + reason, reason.contains("都是滑动"))
        assertTrue("应记录跳过滑动的日志", ctx.log.warns.any { it.contains("已跳过") })
    }
}
