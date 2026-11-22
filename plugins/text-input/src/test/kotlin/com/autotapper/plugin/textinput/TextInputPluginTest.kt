package com.autotapper.plugin.textinput

import com.autotapper.testkit.FakeContext
import com.autotapper.testkit.FakeExecutor
import com.autotapper.testkit.assertEditorKeysMatchDefaults
import com.autotapper.testkit.failReason
import com.example.autotapper.plugin.PluginStepType
import com.example.autotapper.plugin.StepResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文本输入插件契约测试：覆盖固定文本注入成功、宿主回调失败，以及默认空文本直接失败。
 * 默认摘要是「输入「」」不含数字，按约定改写配置成带数字的文本再断言。
 */
class TextInputPluginTest {

    private fun typeStep(): PluginStepType =
        TextInputPlugin().stepTypes().first { it.typeId == "text-input.type" }

    @Test(timeout = 10_000)
    fun editorKeysAndSummaryAreConsistent() {
        val step = typeStep()
        assertEditorKeysMatchDefaults(step)
        val summary = step.summarize(step.defaultConfig().put("text", "hello123"))
        assertTrue("摘要不能为空", summary.isNotBlank())
        assertTrue("摘要应含数字：" + summary, summary.any { it.isDigit() })
    }

    @Test(timeout = 10_000)
    fun fixedTextIsInjected() = runBlocking {
        val step = typeStep()
        val executor = FakeExecutor()
        val ctx = FakeContext(executor = executor)
        val config = step.defaultConfig().put("mode", "fixed").put("text", "hello")
        val result = step.execute(ctx, config)

        assertTrue("固定文本应注入成功：" + result, result is StepResult.Success)
        assertEquals("应调用一次输入", 1, executor.inputTexts.size)
        assertEquals("输入内容应为固定文本", "hello", executor.inputTexts[0].text)
        assertTrue("应记录输入日志", ctx.log.infos.any { it.contains("hello") })
    }

    @Test(timeout = 10_000)
    fun emptyDefaultTextFailsBeforeInjecting() = runBlocking {
        val step = typeStep()
        val executor = FakeExecutor()
        val ctx = FakeContext(executor = executor)
        val result = step.execute(ctx, step.defaultConfig())

        val reason = failReason(result)
        assertTrue("失败原因应提示没有文本：" + reason, reason.contains("没有可输入的文本"))
        assertTrue("没有文本时不能调用注入", executor.inputTexts.isEmpty())
    }

    @Test(timeout = 10_000)
    fun injectionFailureIsReported() = runBlocking {
        val step = typeStep()
        val ctx = FakeContext(executor = FakeExecutor(result = false))
        val config = step.defaultConfig().put("mode", "fixed").put("text", "hello")
        val result = step.execute(ctx, config)

        val reason = failReason(result)
        assertTrue("失败原因应提示输入失败：" + reason, reason.contains("文本输入失败"))
    }
}
