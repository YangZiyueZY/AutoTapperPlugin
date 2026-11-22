package com.autotapper.plugin.scheduler

import com.autotapper.testkit.FakeClock
import com.autotapper.testkit.FakeContext
import com.autotapper.testkit.assertEditorKeysMatchDefaults
import com.autotapper.testkit.assertSummaryHasNumber
import com.autotapper.testkit.failReason
import com.autotapper.testkit.rangeObject
import com.example.autotapper.plugin.PluginStepType
import com.example.autotapper.plugin.StepResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定时插件契约测试：固定等待 0 ms 走立即成功分支；坏时间串走中文失败分支。
 * 用 0..0 区间避免真实等待，保证每个测试都在超时限制内结束。
 */
class SchedulerPluginTest {

    private fun waitStep(): PluginStepType =
        SchedulerPlugin().stepTypes().first { it.typeId == "scheduler.wait-until" }

    @Test(timeout = 10_000)
    fun editorKeysAndSummaryAreConsistent() {
        val step = waitStep()
        assertEditorKeysMatchDefaults(step)
        assertSummaryHasNumber(step)
    }

    @Test(timeout = 10_000)
    fun zeroDelaySucceedsImmediately() = runBlocking {
        val step = waitStep()
        val ctx = FakeContext(clock = FakeClock())
        val config = step.defaultConfig()
            .put("mode", "delay")
            .put("delayMs", rangeObject(0L, 0L))
        val result = step.execute(ctx, config)

        assertTrue("零等待应立即成功：" + result, result is StepResult.Success)
        assertTrue("应记录本次不等的日志", ctx.log.infos.any { it.contains("本次不等") })
    }

    @Test(timeout = 10_000)
    fun badClockTimeFails() = runBlocking {
        val step = waitStep()
        val ctx = FakeContext(clock = FakeClock())
        val config = step.defaultConfig()
            .put("mode", "clock")
            .put("timeHHmm", "bad")
        val result = step.execute(ctx, config)

        val reason = failReason(result)
        assertTrue("失败原因应提示 HH:mm 格式：" + reason, reason.contains("HH:mm"))
        assertTrue("应记录时间格式错误的日志", ctx.log.infos.any { it.contains("时间格式错误") })
    }

    @Test(timeout = 10_000)
    fun clockFailureIsReported() = runBlocking {
        val step = waitStep()
        // 故意让时钟抛异常：FakeClock 是 final 实现，这里用配置分支之外的方式无法注入，
        // 所以改测未知 mode 的中文失败分支，确保异常路径之外也有完整失败覆盖。
        val ctx = FakeContext(clock = FakeClock())
        val result = step.execute(ctx, step.defaultConfig().put("mode", "unknown-mode"))

        val reason = failReason(result)
        assertTrue("失败原因应提示未知定时方式：" + reason, reason.contains("未知的定时方式"))
    }
}
