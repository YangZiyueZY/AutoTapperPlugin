package com.autotapper.plugin.logs

import com.autotapper.testkit.FakeContext
import com.autotapper.testkit.assertEditorKeysMatchDefaults
import com.example.autotapper.plugin.PluginStepType
import com.example.autotapper.plugin.StepResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实时日志插件契约测试：占位符替换、等级分流和计数递增。
 * 默认文案为空，摘要不含数字；按约定用带数字的文案改写配置后再断言。
 */
class LogsPluginTest {

    private fun writeStep(): PluginStepType =
        LogsPlugin().stepTypes().first { it.typeId == "logs.write" }

    @Test(timeout = 10_000)
    fun editorKeysAndSummaryAreConsistent() {
        val step = writeStep()
        assertEditorKeysMatchDefaults(step)
        val summary = step.summarize(step.defaultConfig().put("message", "第 20 次"))
        assertTrue("摘要不能为空", summary.isNotBlank())
        assertTrue("摘要应含数字：" + summary, summary.any { it.isDigit() })
    }

    @Test(timeout = 10_000)
    fun countPlaceholderIsReplaced() = runBlocking {
        val step = writeStep()
        val ctx = FakeContext()
        val config = step.defaultConfig().put("message", "第 {count} 次")
        val result = step.execute(ctx, config)

        assertTrue("写日志应成功：" + result, result is StepResult.Success)
        assertTrue("日志应含替换后的次数", ctx.log.infos.any { it == "第 1 次" })
        assertEquals("计数应写入 prefs", 1, ctx.prefs.getInt("count", 0))
    }

    @Test(timeout = 10_000)
    fun levelsRouteToCorrectBuckets() = runBlocking {
        val step = writeStep()
        val ctx = FakeContext()
        step.execute(ctx, step.defaultConfig().put("level", "warn").put("message", "警告 2"))
        step.execute(ctx, step.defaultConfig().put("level", "error").put("message", "错误 3"))
        step.execute(ctx, step.defaultConfig().put("level", "result").put("message", "结果 4"))

        assertTrue("warn 应进入 warns", ctx.log.warns.any { it.contains("警告 2") })
        assertTrue("error 应进入 errors", ctx.log.errors.any { it.contains("错误 3") })
        assertTrue("result 借 info 承载并加标记", ctx.log.infos.any { it.contains("【结果】结果 4") })
    }

    @Test(timeout = 10_000)
    fun timePlaceholderIsReplaced() = runBlocking {
        val step = writeStep()
        val ctx = FakeContext()
        val config = step.defaultConfig().put("message", "时间 {time}")
        step.execute(ctx, config)

        assertTrue("时间占位符应被替换", ctx.log.infos.any { it.startsWith("时间 ") && !it.contains("{time}") })
    }
}
