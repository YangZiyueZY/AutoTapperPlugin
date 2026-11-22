package com.autotapper.plugin.bell

import com.autotapper.testkit.FakeContext
import com.autotapper.testkit.FakeSound
import com.autotapper.testkit.assertEditorKeysMatchDefaults
import com.autotapper.testkit.assertSummaryHasNumber
import com.example.autotapper.plugin.PluginStepType
import com.example.autotapper.plugin.StepResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 响铃插件契约测试：验证摘要参数、只写日志模式不响铃、默认暂停与响铃/震动记录。
 */
class BellPluginTest {

    private fun ringStep(): PluginStepType =
        BellPlugin().stepTypes().first { it.typeId == "bell.ring" }

    @Test(timeout = 10_000)
    fun editorKeysAndSummaryAreConsistent() {
        val step = ringStep()
        assertEditorKeysMatchDefaults(step)
        assertSummaryHasNumber(step)
        assertTrue("默认摘要应含时长数字", step.summarize(step.defaultConfig()).contains("2"))
    }

    @Test(timeout = 10_000)
    fun logOnlyDoesNotRingButLogs() = runBlocking {
        val step = ringStep()
        val sound = FakeSound()
        val ctx = FakeContext(sound = sound)
        val config = step.defaultConfig()
            .put("after", "continue")
            .put("logOnly", true)
            .put("vibrate", false)
        val result = step.execute(ctx, config)

        assertTrue("after=continue 应继续：" + result, result is StepResult.Success)
        assertTrue("只写日志模式不应响铃", sound.rings.isEmpty())
        assertTrue("只写日志模式不应震动", sound.vibrations.isEmpty())
        assertTrue("应写下本应响铃的日志", ctx.log.infos.any { it.contains("只写日志模式") && it.contains("ms") })
    }

    @Test(timeout = 10_000)
    fun defaultConfigPauses() = runBlocking {
        val step = ringStep()
        val sound = FakeSound()
        val ctx = FakeContext(sound = sound)
        val result = step.execute(ctx, step.defaultConfig())

        assertTrue("默认 after=pause 应暂停：" + result, result === StepResult.Pause)
        assertEquals("默认配置应响铃一次", 1, sound.rings.size)
        assertEquals("默认配置应震动一次", 1, sound.vibrations.size)
        assertEquals("默认震动时长 400 ms", 400L, sound.vibrations[0])
    }

    @Test(timeout = 10_000)
    fun stopAfterRinging() = runBlocking {
        val step = ringStep()
        val ctx = FakeContext()
        val config = step.defaultConfig().put("after", "stop").put("vibrate", false)
        val result = step.execute(ctx, config)

        assertTrue("after=stop 应停止：" + result, result === StepResult.Stop)
        assertTrue("不震动时不应调用震动", (ctx.sound as FakeSound).vibrations.isEmpty())
    }
}
