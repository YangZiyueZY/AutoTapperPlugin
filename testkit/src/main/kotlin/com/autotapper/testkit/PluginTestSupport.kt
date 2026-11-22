package com.autotapper.testkit

import com.example.autotapper.model.Region
import com.example.autotapper.model.TapCoord
import com.example.autotapper.plugin.Field
import com.example.autotapper.plugin.PluginStepType
import com.example.autotapper.plugin.StepResult
import org.json.JSONArray
import org.json.JSONObject

/** 插件测试的共用断言小工具，避免 17 个测试类各写一套。 */

/** 编辑器里出现的 key 必须都能在默认配置里找到，防配置漂移。 */
fun assertEditorKeysMatchDefaults(step: PluginStepType) {
    val defaults = step.defaultConfig()
    val keys = step.editorSpec(defaults).sections.flatMap { it.fields }.map(Field::key)
    check(keys.isNotEmpty()) { "editorSpec 没有字段" }
    keys.forEach { key ->
        check(defaults.has(key)) { "默认配置缺少字段 key: " + key }
    }
}

/** 摘要非空，并且至少包含一个阿拉伯数字（关键参数值）。 */
fun assertSummaryHasNumber(step: PluginStepType) {
    val summary = step.summarize(step.defaultConfig())
    check(summary.isNotBlank()) { "summarize 返回空串" }
    check(summary.any { it.isDigit() }) { "summarize 没有关键数字：[" + summary + "]" }
}

/** 断言点落在指定区域内。 */
fun assertPointInRegion(point: TapCoord, region: Region) {
    check(region.contains(point.x, point.y)) {
        "坐标 (" + point.x + "," + point.y + ") 不在区域 " + region.id + " 内"
    }
}

/** 断言所有点都落在各自的区域内，并校验时长闭区间。 */
fun assertPointsInRegions(points: List<TapCoord>, regions: List<Region>) {
    check(points.size == regions.size) { "触点数量与区域数量不一致" }
    points.forEachIndexed { index, point -> assertPointInRegion(point, regions[index]) }
}

/** 断言时长在闭区间内。 */
fun assertDurationInRange(value: Long, min: Long, max: Long) {
    check(value in min..max) { "时长 " + value + " 不在 " + min + ".." + max + " 内" }
}

/** 读取失败原因文案；断言是中文人话而不是空串或异常类名。 */
fun failReason(result: StepResult): String {
    check(result is StepResult.Fail) { "期望 Fail，实际 " + result }
    val reason = result.reason
    check(reason.isNotBlank()) { "失败原因不能为空" }
    check(reason.any { it.code in 0x4E00..0x9FFF }) { "失败原因不是中文：" + reason }
    return reason
}

/** 构造 RegionPicker 存盘值：区域序号字符串数组（1 基）。 */
fun regionArray(vararg indices: Int): JSONArray {
    val array = JSONArray()
    indices.forEach { array.put(it.toString()) }
    return array
}

/** 构造 RangeField 存盘值。 */
fun rangeObject(min: Long, max: Long = min): JSONObject =
    JSONObject().put("min", min).put("max", max)
