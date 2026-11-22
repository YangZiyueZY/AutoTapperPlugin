package com.autotapper.plugin.flowcontrol

import com.autotapper.testkit.FakeContext
import com.autotapper.testkit.FakePrefs
import com.autotapper.testkit.assertEditorKeysMatchDefaults
import com.autotapper.testkit.assertSummaryHasNumber
import com.autotapper.testkit.failReason
import com.example.autotapper.plugin.PolicyCall
import com.example.autotapper.plugin.PolicyDecision
import com.example.autotapper.plugin.PolicyPhase
import com.example.autotapper.plugin.PolicyResult
import com.example.autotapper.plugin.PluginStepType
import com.example.autotapper.plugin.StepResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** flow-control 三种步骤与策略扩展点的测试。 */
class FlowControlPluginTest {

    private val plugin = FlowControlPlugin()

    private fun step(typeId: String): PluginStepType {
        val steps = plugin.stepTypes().filter { it.typeId == typeId }
        assertEquals("应只注册一个 " + typeId + " 步骤", 1, steps.size)
        return steps[0]
    }

    @Test(timeout = 10_000)
    fun 三个步骤的编辑器字段与摘要都符合默认配置() {
        plugin.stepTypes().forEach {
            assertEditorKeysMatchDefaults(it)
            // 流程设置的默认摘要是「无额外延迟 · 失败继续」，源码里本来就没有数字，
            // 只对有真实参数的延迟/跳转步骤断言关键数字。
            if (it.typeId == "flow-control.setup") {
                assertTrue("流程设置摘要不能为空", it.summarize(it.defaultConfig()).isNotBlank())
            } else {
                assertSummaryHasNumber(it)
            }
        }
    }

    @Test(timeout = 10_000)
    fun 流程设置写入prefs并生效() = runBlocking {
        val prefs = FakePrefs()
        val ctx = FakeContext(prefs = prefs)
        val config = step("flow-control.setup").defaultConfig()
            .put("delayMin", 10L)
            .put("delayMax", 20L)
            .put("onFail", "stop")
        val result = step("flow-control.setup").execute(ctx, config)
        assertTrue("流程设置应成功：" + result, result is StepResult.Success)
        assertEquals(10L, prefs.getLong("delayMin", 0L))
        assertTrue(ctx.log.infos.any { it.contains("流程设置已生效") })
        val call = PolicyCall(0, 1, "core.tap", null, JSONObject(), PolicyPhase.AFTER, 1, PolicyResult.FAIL)
        assertEquals(PolicyDecision.Stop, plugin.decide(call))
    }

    @Test(timeout = 10_000)
    fun 零延迟步骤直接成功() = runBlocking {
        val config = JSONObject().put("delayMin", 0L).put("delayMax", 0L)
        val result = step("flow-control.delay").execute(FakeContext(), config)
        assertTrue("零延迟应成功：" + result, result is StepResult.Success)
    }

    @Test(timeout = 10_000)
    fun 跳转步骤返回Goto() = runBlocking {
        val config = JSONObject().put("step", 3)
        val result = step("flow-control.goto").execute(FakeContext(), config)
        assertTrue("跳转应返回 Goto：" + result, result is StepResult.Goto)
        assertEquals(2, (result as StepResult.Goto).stepIndex)
    }

    @Test(timeout = 10_000)
    fun 跳转目标小于一时返回中文失败原因() = runBlocking {
        val reason = failReason(step("flow-control.goto").execute(FakeContext(), JSONObject().put("step", 0)))
        assertTrue("失败原因应说明从 1 开始：" + reason, reason.contains("从 1 开始"))
    }

    @Test(timeout = 10_000)
    fun 默认策略不干预宿主() {
        val call = PolicyCall(0, 1, "core.tap", null, JSONObject(), PolicyPhase.BEFORE, 1)
        assertEquals(PolicyDecision.Proceed, plugin.decide(call))
    }
}
