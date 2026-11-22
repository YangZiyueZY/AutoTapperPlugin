package com.autotapper.plugin.numbercompare

import android.graphics.Bitmap
import com.example.autotapper.model.Region
import com.example.autotapper.plugin.AutoTapperPlugin
import com.example.autotapper.plugin.Capability
import com.example.autotapper.plugin.Field
import com.example.autotapper.plugin.OcrResult
import com.example.autotapper.plugin.PluginApi
import com.example.autotapper.plugin.PluginContext
import com.example.autotapper.plugin.PluginStepType
import com.example.autotapper.plugin.ScreenSpec
import com.example.autotapper.plugin.Section
import com.example.autotapper.plugin.StepResult
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * 数字对比 —— 从 OCR 文本里截出数字片段，按比较符与配置的目标值比较。
 *
 * locate 用来定位数字前缀，ignore 里的字符先剔除再解析；小数位只影响解析精度，
 * 比较时用 1e-9 容差判断相等。识别、截图异常都转成中文失败文案。
 */
class NumberComparePlugin : AutoTapperPlugin {

    override val id: String = "number-compare"
    override val name: String = "数字对比"
    override val version: String = "0.1.0"

    /** 必须等于主程序的 PluginApi.VERSION。 */
    override val apiVersion: Int = PluginApi.VERSION

    /** 主程序 versionCode 低于这个值时不加载。 */
    override val minHostVersionCode: Int = 1

    override fun stepTypes(): List<PluginStepType> = listOf(CompareStep())

    /** typeId=number-compare.compare：截图 OCR 取数字并按比较符判定。 */
    private class CompareStep : PluginStepType {
        override val typeId: String = "number-compare.compare"
        override val displayName: String = "数字对比"
        override val requires: Set<Capability> = setOf(Capability.SCREENSHOT, Capability.NETWORK)

        override fun defaultConfig(): JSONObject = JSONObject()
            .put("region", JSONArray().put("1"))
            .put("locate", "")
            .put("ignore", ",")
            .put("decimals", DEFAULT_DECIMALS)
            .put("op", OP_GT)
            .put("target", DEFAULT_TARGET)
            .put("onFalse", ON_FALSE_FAIL)

        override fun editorSpec(config: JSONObject): ScreenSpec = ScreenSpec(
            sections = listOf(
                Section(
                    eyebrow = "// TARGET",
                    title = "取数位置",
                    fields = listOf(
                        Field.RegionPicker(key = "region", label = "选择区域（单选）", multiple = false),
                        Field.Text(key = "locate", label = "定位字符或前缀（可空）", placeholder = "例如：余额"),
                        Field.Text(key = "ignore", label = "忽略字符", placeholder = "例如：, "),
                        Field.IntField(
                            key = "decimals", label = "小数位数",
                            min = MIN_DECIMALS, max = MAX_DECIMALS, unit = "位"
                        )
                    )
                ),
                Section(
                    eyebrow = "// COMPARE",
                    title = "比较条件",
                    fields = listOf(
                        Field.EnumField(
                            key = "op", label = "比较符",
                            options = listOf("大于", "等于", "小于", "大于等于", "小于等于"),
                            values = listOf(OP_GT, OP_EQ, OP_LT, OP_GTE, OP_LTE)
                        ),
                        Field.Text(key = "target", label = "目标数值", placeholder = "例如：100"),
                        Field.EnumField(
                            key = "onFalse", label = "不满足时",
                            options = listOf("返回失败", "仍然继续"),
                            values = listOf(ON_FALSE_FAIL, ON_FALSE_PASS)
                        )
                    )
                )
            )
        )

        override fun summarize(config: JSONObject): String {
            val op = text(config, "op", OP_GT)
            val target = text(config, "target", DEFAULT_TARGET)
            return "数字 " + opLabel(op) + " " + target
        }

        override suspend fun execute(ctx: PluginContext, config: JSONObject): StepResult {
            var bitmap: Bitmap? = null
            return try {
                val locate = text(config, "locate", "")
                val ignore = text(config, "ignore", "")
                val decimals = intValue(config, "decimals", MIN_DECIMALS, MAX_DECIMALS, DEFAULT_DECIMALS)
                val op = text(config, "op", OP_GT)
                val targetRaw = text(config, "target", DEFAULT_TARGET)
                val selectedOnFalse = onFalse(config)
                val target = targetRaw.trim().replace(",", "").toDoubleOrNull()
                if (target == null || target.isNaN() || target.isInfinite()) {
                    return StepResult.Fail("目标数值不是有效数字：「" + targetRaw + "」")
                }
                val regions = selectedRegions(ctx, config, "region")
                if (regions.isEmpty()) {
                    return StepResult.Fail("所选区域已不存在，请重新选")
                }
                if (!isCaptureReady(ctx)) {
                    return StepResult.Fail(NO_CAPTURE_REASON)
                }
                bitmap = grabRegion(ctx, regions[0])
                    ?: return StepResult.Fail("数字对比失败：拿不到区域图像")
                val result: OcrResult = try {
                    ctx.ocr.recognize(bitmap)
                } catch (t: Throwable) {
                    ctx.log.error("数字对比识别异常：" + (t.message ?: t.javaClass.simpleName))
                    return StepResult.Fail("数字对比识别异常：" + (t.message ?: t.javaClass.simpleName))
                }
                if (!result.error.isNullOrEmpty()) {
                    ctx.log.error("数字对比识别失败：" + result.error)
                    return StepResult.Fail("数字对比识别失败：" + result.error)
                }
                val raw = result.text
                val value = extractNumber(raw, locate, ignore, decimals)
                    ?: return StepResult.Fail("没识别到数字：原文「" + raw + "」")
                ctx.log.info("数字对比识别结果：原文「" + raw + "」，取到 " + value)
                val matched = when (op) {
                    OP_EQ -> nearlyEqual(value, target)
                    OP_LT -> value < target && !nearlyEqual(value, target)
                    OP_GTE -> value > target || nearlyEqual(value, target)
                    OP_LTE -> value < target || nearlyEqual(value, target)
                    else -> value > target && !nearlyEqual(value, target)
                }
                if (matched) {
                    return StepResult.Success()
                }
                val reason = "数字对比不满足：" + opLabel(op) + " " + targetRaw +
                    "，实际为 " + formatValue(value) + "，原文「" + raw + "」"
                if (selectedOnFalse == ON_FALSE_PASS) {
                    ctx.log.info(reason + "，按配置仍然继续")
                    StepResult.Success()
                } else {
                    StepResult.Fail(reason)
                }
            } catch (t: Throwable) {
                ctx.log.error("数字对比执行异常：" + (t.message ?: t.javaClass.simpleName))
                StepResult.Fail("数字对比执行异常：" + (t.message ?: t.javaClass.simpleName))
            } finally {
                if (bitmap != null) recycle(bitmap)
            }
        }

        // ---- 以下全部是本步骤类型私有的工具，集中在类内，避免嵌套类访问不到外层成员 ----

        /** 从原文里按 locate 截取片段，去掉 ignore 字符后解析第一段数字，并按 decimals 四舍五入。 */
        private fun extractNumber(raw: String, locate: String, ignore: String, decimals: Int): Double? {
            var str = raw.replace('\r', '\n')
            if (locate.isNotEmpty()) {
                val at = str.indexOf(locate)
                if (at < 0) return null
                str = str.substring(at + locate.length)
            }
            val clean = if (ignore.isEmpty()) str else str.filter { !ignore.contains(it) }
            val matcher = NUMBER_REGEX.find(clean) ?: return null
            val parsed = matcher.value.toDoubleOrNull() ?: return null
            if (parsed.isNaN() || parsed.isInfinite()) return null
            val factor = 10.0.pow(decimals)
            val rounded = (parsed * factor).roundToLong().toDouble() / factor
            return if (rounded.isNaN() || rounded.isInfinite()) null else rounded
        }

        /** 按 1e-9 容差判断相等，绕开浮点误差。 */
        private fun nearlyEqual(a: Double, b: Double): Boolean {
            val diff = a - b
            return diff in -EPSILON..EPSILON
        }

        private fun formatValue(value: Double): String =
            if (value == value.roundToLong().toDouble()) value.roundToLong().toString() else value.toString()

        /** 比较符的中文显示，供摘要与失败文案复用。 */
        private fun opLabel(op: String): String = when (op) {
            OP_EQ -> "等于"
            OP_LT -> "小于"
            OP_GTE -> "大于等于"
            OP_LTE -> "小于等于"
            else -> "大于"
        }

        private fun text(config: JSONObject, key: String, fallback: String): String = try {
            config.optString(key, fallback)
        } catch (t: Throwable) {
            fallback
        }

        private fun intValue(config: JSONObject, key: String, min: Long, max: Long, fallback: Long): Int {
            val value = try { config.optLong(key, fallback) } catch (t: Throwable) { fallback }
            return value.coerceIn(min, max).toInt()
        }

        private fun onFalse(config: JSONObject): String =
            if (text(config, "onFalse", ON_FALSE_FAIL) == ON_FALSE_PASS) ON_FALSE_PASS else ON_FALSE_FAIL

        /** RegionPicker 存的是区域序号字符串（1 基），过滤掉已删除的区域。 */
        private fun selectedRegions(ctx: PluginContext, config: JSONObject, key: String): List<Region> {
            val all = try { ctx.regions.load() } catch (t: Throwable) {
                ctx.log.warn("读取区域失败：" + (t.message ?: t.javaClass.simpleName))
                emptyList()
            }
            val arr = try { config.optJSONArray(key) } catch (t: Throwable) {
                ctx.log.warn("区域配置格式错误：" + (t.message ?: t.javaClass.simpleName))
                null
            }
            if (arr == null || arr.length() == 0 || all.isEmpty()) return emptyList()
            for (i in 0 until arr.length()) {
                val raw = try { arr.optString(i, "") } catch (t: Throwable) { "" }
                val index = raw.toIntOrNull() ?: continue
                if (index >= 1 && index <= all.size) return listOf(all[index - 1])
            }
            return emptyList()
        }

        private fun isCaptureReady(ctx: PluginContext): Boolean = try {
            ctx.capture.isReady()
        } catch (t: Throwable) {
            ctx.log.error("读取截图状态失败：" + (t.message ?: t.javaClass.simpleName))
            false
        }

        private fun grabRegion(ctx: PluginContext, region: Region): Bitmap? = try {
            ctx.capture.grabRegion(region)
        } catch (t: Throwable) {
            ctx.log.error("截取区域失败：" + (t.message ?: t.javaClass.simpleName))
            null
        }

        private fun recycle(bitmap: Bitmap) {
            try {
                if (!bitmap.isRecycled()) bitmap.recycle()
            } catch (t: Throwable) {
                // 裁剪副本回收失败不影响判定结果，忽略即可
            }
        }

        companion object {
            const val OP_GT = "gt"
            const val OP_EQ = "eq"
            const val OP_LT = "lt"
            const val OP_GTE = "gte"
            const val OP_LTE = "lte"
            const val ON_FALSE_FAIL = "fail"
            const val ON_FALSE_PASS = "pass"
            const val MIN_DECIMALS = 0L
            const val MAX_DECIMALS = 6L
            const val DEFAULT_DECIMALS = 0L
            const val DEFAULT_TARGET = "100"
            const val EPSILON = 1e-9
            val NUMBER_REGEX = Regex("""[+-]?\d+(?:\.\d+)?""")
            const val NO_CAPTURE_REASON = "还没有截图授权，请先在设置里授权屏幕截图"
        }
    }
}
