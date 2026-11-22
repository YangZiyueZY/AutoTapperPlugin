package com.autotapper.plugin.ocrtext

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

/**
 * 识字 —— 截取区域后调用宿主 OCR，按全等/包含模式比对识别文本。
 *
 * OCR 密钥只在宿主设置里，插件看不到；识别失败按 OcrResult.error 原样回传。
 * 所有跨边界调用都包住异常，裁剪图用完 recycle。
 */
class OcrTextPlugin : AutoTapperPlugin {

    override val id: String = "ocr-text"
    override val name: String = "识字"
    override val version: String = "0.1.0"

    /** 必须等于主程序的 PluginApi.VERSION。 */
    override val apiVersion: Int = PluginApi.VERSION

    /** 主程序 versionCode 低于这个值时不加载。 */
    override val minHostVersionCode: Int = 1

    override fun stepTypes(): List<PluginStepType> = listOf(OcrMatchStep())

    /** typeId=ocr-text.match：截图识字后按模式与期望文本比对。 */
    private class OcrMatchStep : PluginStepType {
        override val typeId: String = "ocr-text.match"
        override val displayName: String = "识字匹配"
        override val requires: Set<Capability> = setOf(Capability.SCREENSHOT, Capability.NETWORK)

        override fun defaultConfig(): JSONObject = JSONObject()
            .put("region", JSONArray().put("1"))
            .put("expect", DEFAULT_EXPECT)
            .put("mode", MODE_CONTAINS)
            .put("ignoreCase", true)
            .put("ignoreSpaces", true)
            .put("onFalse", ON_FALSE_FAIL)

        override fun editorSpec(config: JSONObject): ScreenSpec = ScreenSpec(
            sections = listOf(
                Section(
                    eyebrow = "// TARGET",
                    title = "识别位置",
                    fields = listOf(
                        Field.RegionPicker(key = "region", label = "选择区域（单选）", multiple = false)
                    )
                ),
                Section(
                    eyebrow = "// MATCH",
                    title = "比对条件",
                    fields = listOf(
                        Field.Text(key = "expect", label = "期望文本", placeholder = "例如：确定"),
                        Field.EnumField(
                            key = "mode", label = "比对方式",
                            options = listOf("全等", "包含", "包含全部"),
                            values = listOf(MODE_EQUALS, MODE_CONTAINS, MODE_CONTAINS_ALL)
                        ),
                        Field.SwitchField(key = "ignoreCase", label = "忽略大小写", defaultValue = true),
                        Field.SwitchField(key = "ignoreSpaces", label = "忽略空格和换行", defaultValue = true)
                    )
                ),
                Section(
                    eyebrow = "// RESULT",
                    title = "不匹配时",
                    fields = listOf(
                        Field.EnumField(
                            key = "onFalse", label = "不匹配时",
                            options = listOf("返回失败", "仍然继续"),
                            values = listOf(ON_FALSE_FAIL, ON_FALSE_PASS)
                        )
                    )
                )
            )
        )

        override fun summarize(config: JSONObject): String {
            val expect = text(config, "expect", DEFAULT_EXPECT)
            val selectedMode = mode(config)
            val label = when (selectedMode) {
                MODE_EQUALS -> "全等"
                MODE_CONTAINS_ALL -> "包含全部"
                else -> "包含"
            }
            return "识字" + label + "「" + expect + "」"
        }

        override suspend fun execute(ctx: PluginContext, config: JSONObject): StepResult {
            var bitmap: Bitmap? = null
            return try {
                val expect = text(config, "expect", DEFAULT_EXPECT)
                val selectedMode = mode(config)
                val ignoreCase = bool(config, "ignoreCase", true)
                val ignoreSpaces = bool(config, "ignoreSpaces", true)
                val onFalse = onFalse(config)
                val regions = selectedRegions(ctx, config, "region")
                if (regions.isEmpty()) {
                    return StepResult.Fail("所选区域已不存在，请重新选")
                }
                if (!isCaptureReady(ctx)) {
                    return StepResult.Fail(NO_CAPTURE_REASON)
                }
                bitmap = grabRegion(ctx, regions[0])
                    ?: return StepResult.Fail("截图识字失败：拿不到区域图像")
                val result: OcrResult = try {
                    ctx.ocr.recognize(bitmap)
                } catch (t: Throwable) {
                    ctx.log.error("识字识别异常：" + (t.message ?: t.javaClass.simpleName))
                    return StepResult.Fail("识字识别异常：" + (t.message ?: t.javaClass.simpleName))
                }
                if (!result.error.isNullOrEmpty()) {
                    ctx.log.error("识字识别失败：" + result.error)
                    return StepResult.Fail("识字识别失败：" + result.error)
                }
                val recognized = result.text
                ctx.log.info("识字识别结果：「" + recognized + "」")
                val normalizedActual = normalize(recognized, ignoreCase, ignoreSpaces)
                val matched = when (selectedMode) {
                    MODE_EQUALS -> normalizedActual == normalize(expect, ignoreCase, ignoreSpaces)
                    MODE_CONTAINS_ALL -> tokens(expect)
                        .map { normalize(it, ignoreCase, ignoreSpaces) }
                        .all { normalizedActual.contains(it) }
                    else -> {
                        val normalizedExpect = normalize(expect, ignoreCase, ignoreSpaces)
                        normalizedExpect.isEmpty() || normalizedActual.contains(normalizedExpect)
                    }
                }
                if (matched) {
                    return StepResult.Success()
                }
                val reason = "识字不匹配：期望「" + expect + "」，识别到「" + recognized + "」"
                if (onFalse == ON_FALSE_PASS) {
                    ctx.log.info(reason + "，按配置仍然继续")
                    StepResult.Success()
                } else {
                    StepResult.Fail(reason)
                }
            } catch (t: Throwable) {
                ctx.log.error("识字执行异常：" + (t.message ?: t.javaClass.simpleName))
                StepResult.Fail("识字执行异常：" + (t.message ?: t.javaClass.simpleName))
            } finally {
                if (bitmap != null) recycle(bitmap)
            }
        }

        // ---- 以下全部是本步骤类型私有的工具，集中在类内，避免嵌套类访问不到外层成员 ----

        /** 期望文本按换行拆成多个片段，供 contains-all 逐段比对。 */
        private fun tokens(raw: String): List<String> =
            raw.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }

        /** 规范化：按配置去空白和统一大小写。 */
        private fun normalize(raw: String, ignoreCase: Boolean, ignoreSpaces: Boolean): String {
            var out = raw.replace("\r", "\n")
            if (ignoreSpaces) {
                out = out.filter { !it.isWhitespace() }
            }
            if (ignoreCase) {
                out = out.lowercase()
            }
            return out
        }

        private fun text(config: JSONObject, key: String, fallback: String): String = try {
            config.optString(key, fallback)
        } catch (t: Throwable) {
            fallback
        }

        private fun bool(config: JSONObject, key: String, fallback: Boolean): Boolean = try {
            config.optBoolean(key, fallback)
        } catch (t: Throwable) {
            fallback
        }

        private fun mode(config: JSONObject): String {
            val value = text(config, "mode", MODE_CONTAINS)
            return when (value) {
                MODE_EQUALS, MODE_CONTAINS_ALL -> value
                else -> MODE_CONTAINS
            }
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
            const val MODE_EQUALS = "equals"
            const val MODE_CONTAINS = "contains"
            const val MODE_CONTAINS_ALL = "contains-all"
            const val ON_FALSE_FAIL = "fail"
            const val ON_FALSE_PASS = "pass"
            const val DEFAULT_EXPECT = "确定"
            const val NO_CAPTURE_REASON = "还没有截图授权，请先在设置里授权屏幕截图"
        }
    }
}
