package com.autotapper.plugin.counter

import com.autotapper.testkit.FakeContext
import com.autotapper.testkit.FakePrefs
import com.autotapper.testkit.assertEditorKeysMatchDefaults
import com.autotapper.testkit.assertSummaryHasNumber
import com.example.autotapper.plugin.PluginStepType
import com.example.autotapper.plugin.StepResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计数插件契约测试：验证"累计到目标后按配置停/暂停/跳转"，以及到达后归零的持久化行为。
 * prefs 用同一个 FakePrefs 贯穿两次 execute，才能验证跨步骤累计。
 */
class CounterPluginTest {

    private fun countStep(): PluginStepType =
        CounterPlugin().stepTypes().first { it.typeId == "counter.count" }

    @Test(timeout = 10_000)
    fun editorKeysAndSummaryAreConsistent() {
        val step = countStep()
        assertEditorKeysMatchDefaults(step)
        assertSummaryHasNumber(step)
    }

    @Test(timeout = 10_000)
    fun countsToTargetThenStopsAndResets() = runBlocking {
        val step = countStep()
        val prefs = FakePrefs()
        val ctx = FakeContext(prefs = prefs)
        val config = step.defaultConfig().put("target", 2)

        val first = step.execute(ctx, config)
        assertTrue("第一次未到目标应继续：" + first, first is StepResult.Success)
        assertEquals("第一次后计数为 1", 1, prefs.getInt("count", 0))

        val second = step.execute(ctx, config)
        assertTrue("到达目标应停止：" + second, second === StepResult.Stop)
        assertEquals("到达目标并归零后计数为 0", 0, prefs.getInt("count", 0))
        assertTrue("日志应记录进度", ctx.log.infos.any { it.contains("2 / 2") })
    }

    @Test(timeout = 10_000)
    fun reachingTargetCanPause() = runBlocking {
        val step = countStep()
        val ctx = FakeContext()
        val config = step.defaultConfig().put("target", 1).put("onReach", "pause")
        val result = step.execute(ctx, config)

        assertTrue("onReach=pause 应暂停：" + result, result === StepResult.Pause)
    }

    @Test(timeout = 10_000)
    fun reachingTargetCanGotoStep() = runBlocking {
        val step = countStep()
        val ctx = FakeContext()
        val config = step.defaultConfig()
            .put("target", 1)
            .put("onReach", "goto")
            .put("gotoStep", 3)
        val result = step.execute(ctx, config)

        assertTrue("onReach=goto 应跳步：" + result, result is StepResult.Goto)
        assertEquals("gotoStep=3 对应 0 基下标 2", 2, (result as StepResult.Goto).stepIndex)
    }

    @Test(timeout = 10_000)
    fun logOnlyActionContinues() = runBlocking {
        val step = countStep()
        val ctx = FakeContext()
        val config = step.defaultConfig().put("target", 1).put("onReach", "log")
        val result = step.execute(ctx, config)

        assertTrue("只记日志应继续：" + result, result is StepResult.Success)
        assertTrue("应留下到达目标的日志", ctx.log.infos.any { it.contains("计数到达") })
    }
}
