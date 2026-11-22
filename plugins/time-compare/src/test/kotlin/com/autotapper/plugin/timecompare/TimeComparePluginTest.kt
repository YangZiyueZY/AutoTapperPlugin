package com.autotapper.plugin.timecompare

import com.autotapper.testkit.FakeClock
import com.autotapper.testkit.FakeContext
import com.autotapper.testkit.assertEditorKeysMatchDefaults
import com.autotapper.testkit.assertSummaryHasNumber
import com.autotapper.testkit.failReason
import com.example.autotapper.plugin.PluginStepType
import com.example.autotapper.plugin.StepResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 时间对比插件的契约与 time-compare.compare 步骤测试。 */
class TimeComparePluginTest {

    private fun compareStep(): PluginStepType {
        val steps = TimeComparePlugin().stepTypes().filter { it.typeId == TYPE_ID }
        assertEquals("插件应只注册一个 " + TYPE_ID + " 步骤", 1, steps.size)
        return steps[0]
    }

    /** 北京时间 2024-01-01 12:00，默认时钟正好落在成功用例的午后时段。 */
    private fun configWith(mode: String, timeHHmm: String): JSONObject = JSONObject()
        .put("mode", mode)
        .put("timeHHmm", timeHHmm)
        .put("startHHmm", "09:00")
        .put("endHHmm", "18:00")
        .put("weekdays", JSONArray())
        .put("useBeijing", true)
        .put("onFalse", "fail")

    @Test(timeout = 10_000)
    fun 编辑器字段与摘要符合默认配置() {
        val step = compareStep()
        assertEditorKeysMatchDefaults(step)
        assertSummaryHasNumber(step)
    }

    @Test(timeout = 10_000)
    fun 当前时间晚于目标时刻时成功() = runBlocking {
        val clock = FakeClock()
        val ctx = FakeContext(clock = clock)
        val result = compareStep().execute(ctx, configWith("after", "09:30"))
        assertTrue("北京时间 12:00 晚于 09:30 时应成功", result is StepResult.Success)
        assertEquals("成功用例应读取一次宿主时钟", DEFAULT_NOW, clock.now)
    }

    @Test(timeout = 10_000)
    fun 时间格式错误时返回中文失败原因() = runBlocking {
        val result = compareStep().execute(FakeContext(), configWith("after", "25:00"))
        val reason = failReason(result)
        assertTrue("失败原因应说明时间格式错误，实际为：" + reason, reason.contains("时间格式"))
    }

    @Test(timeout = 10_000)
    fun 条件不满足时返回中文失败原因() = runBlocking {
        val result = compareStep().execute(FakeContext(), configWith("before", "09:30"))
        val reason = failReason(result)
        assertTrue("失败原因应包含当前时刻，实际为：" + reason, reason.contains("12:00"))
        assertTrue("失败原因应说明已过目标时刻，实际为：" + reason, reason.contains("已过"))
    }

    companion object {
        const val TYPE_ID = "time-compare.compare"
        const val DEFAULT_NOW = 1_704_081_600_000L
    }
}
