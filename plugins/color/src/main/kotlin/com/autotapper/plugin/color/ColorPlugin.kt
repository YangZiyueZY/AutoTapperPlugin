package com.autotapper.plugin.color

import android.graphics.Bitmap
import com.example.autotapper.model.Region
import com.example.autotapper.plugin.AutoTapperPlugin
import com.example.autotapper.plugin.Capability
import com.example.autotapper.plugin.Field
import com.example.autotapper.plugin.PluginApi
import com.example.autotapper.plugin.PluginContext
import com.example.autotapper.plugin.PluginStepType
import com.example.autotapper.plugin.ScreenSpec
import com.example.autotapper.plugin.Section
import com.example.autotapper.plugin.StepResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * 识色 —— 在截图区域里按颜色容差统计命中像素，按 any/all 模式判定。
 *
 * 只读取宿主 CaptureApi 的裁剪副本，不申请权限、不碰 Android 取色实现；
 * 裁剪图用完立刻 recycle，异常一律转成中文失败文案，不往界面抛。
 */
class ColorPlugin : AutoTapperPlugin {

    override val id: String = "color"
    override val name: String = "识色"
    override val version: String = "0.1.0"

    /** 必须等于主程序的 PluginApi.VERSION。 */
    override val apiVersion: Int = PluginApi.VERSION

    /** 主程序 versionCode 低于这个值时不加载。 */
    override val minHostVersionCode: Int = 1

    override fun stepTypes(): List<PluginStepType> = listOf(ColorMatchStep())

    /** typeId=color.match：统计截图区域里目标颜色的命中像素数并判定。 */
    private class ColorMatchStep : PluginStepType {
        override val typeId: String = "color.match"
        override val displayName: String = "识色匹配"
        override val requires: Set<Capability> = setOf(Capability.SCREENSHOT)

        override fun defaultConfig(): JSONObject = JSONObject()
            .put("regions", JSONArray().put("1"))
            .put("colors", JSONArray().put(DEFAULT_COLOR))
            .put("tolerance", DEFAULT_TOLERANCE)
            .put("minHits", DEFAULT_MIN_HITS)
            .put("mode", MODE_ANY)

        override fun editorSpec(config: JSONObject): ScreenSpec = ScreenSpec(
            sections = listOf(
                Section(
                    eyebrow = "// TARGET",
                    title = "取样位置",
                    fields = listOf(
                        Field.RegionPicker(key = "regions", label = "选择区域（多选）", multiple = true)
                    )
                ),
                Section(
                    eyebrow = "// COLOR",
                    title = "颜色条件",
                    fields = listOf(
                        Field.TextListField(key = "colors", label = "目标颜色列表（每行 #RRGGBB）"),
                        Field.IntField(
                            key = "tolerance", label = "每通道允许偏差",
                            min = MIN_TOLERANCE, max = MAX_TOLERANCE, unit = ""
                        ),
                        Field.IntField(
                            key = "minHits", label = "最少命中像素数",
                            min = MIN_MIN_HITS, max = MAX_MIN_HITS, unit = "个"
                        ),
                        Field.EnumField(
                            key = "mode", label = "匹配方式",
                            options = listOf("任意一个", "全部都要"),
                            values = listOf(MODE_ANY, MODE_ALL)
                        )
                    )
                )
            )
        )

        override fun summarize(config: JSONObject): String {
            val selectedColors = colors(config)
            val tolerance = intValue(config, "tolerance", MIN_TOLERANCE, MAX_TOLERANCE, DEFAULT_TOLERANCE)
            val minHits = intValue(config, "minHits", MIN_MIN_HITS, MAX_MIN_HITS, DEFAULT_MIN_HITS)
            val modeText = if (mode(config) == MODE_ALL) "全部都要" else "任意一个"
            val colorText = if (selectedColors.isEmpty()) "未设置" else selectedColors.joinToString("、") { it.hex }
            return "识色 " + colorText + " 容差 " + tolerance + "，最少 " + minHits + " 像素，" + modeText
        }

        override suspend fun execute(ctx: PluginContext, config: JSONObject): StepResult {
            return try {
                val tolerance = intValue(config, "tolerance", MIN_TOLERANCE, MAX_TOLERANCE, DEFAULT_TOLERANCE)
                val minHits = intValue(config, "minHits", MIN_MIN_HITS, MAX_MIN_HITS, DEFAULT_MIN_HITS)
                val selectedMode = mode(config)
                val selectedColors = colors(config)
                if (selectedColors.isEmpty()) {
                    return StepResult.Fail("识色未命中：没有配置目标颜色，请在编辑器里填写 #RRGGBB")
                }
                if (!isCaptureReady(ctx)) {
                    return StepResult.Fail(NO_CAPTURE_REASON)
                }
                val regions = selectedRegions(ctx, config, "regions")
                if (regions.isEmpty()) {
                    return StepResult.Fail("所选区域已不存在，请重新选")
                }

                val stats = ArrayList<ColorStat>(selectedColors.size)
                for (color in selectedColors) {
                    stats.add(ColorStat(color, 0, null))
                }
                for (region in regions) {
                    val bitmap = grabRegion(ctx, region)
                        ?: return StepResult.Fail("截图区域失败，拿不到区域图像")
                    try {
                        for (i in stats.indices) {
                            val stat = stats[i]
                            val result = countHits(bitmap, stat.color, tolerance, minHits)
                            stats[i] = stat.copy(hits = stat.hits + result.hits, closest = result.closest)
                        }
                    } finally {
                        recycle(bitmap)
                    }
                }

                val passed = if (selectedMode == MODE_ALL) {
                    stats.all { it.hits >= minHits }
                } else {
                    stats.any { it.hits >= minHits }
                }
                if (passed) {
                    ctx.log.info("识色命中：" + stats.joinToString("、") { it.color.hex + "=" + it.hits + "像素" })
                    return StepResult.Success()
                }
                val worst = if (selectedMode == MODE_ALL) stats.minByOrNull { it.hits } ?: stats[0]
                            else stats.maxByOrNull { distanceRank(it, minHits) } ?: stats[0]
                val closest = worst.closest ?: "未知"
                StepResult.Fail(
                    "识色未命中：期望 " + worst.color.hex + "±" + tolerance +
                    "，实际最接近 " + closest + "，命中 " + worst.hits + " 像素，需要至少 " + minHits + " 像素"
                )
            } catch (t: Throwable) {
                ctx.log.error("识色执行异常：" + (t.message ?: t.javaClass.simpleName))
                StepResult.Fail("识色执行异常：" + (t.message ?: t.javaClass.simpleName))
            }
        }

        private fun distanceRank(stat: ColorStat, minHits: Int): Long {
            val deficit = (minHits - stat.hits).coerceAtLeast(0).toLong()
            val dist = stat.closest?.let { closestColor ->
                val parsed = parseColor(closestColor)
                if (parsed != null) colorDistance(parsed, stat.color) else 0L
            } ?: 0L
            return deficit * 1000L + dist
        }

        // ---- 以下全部是本步骤类型私有的工具，集中在类内，避免嵌套类访问不到外层成员 ----

        private fun countHits(bitmap: Bitmap, color: Rgb, tolerance: Int, minHits: Int): HitResult {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            var hits = 0
            var bestDistance = Long.MAX_VALUE
            var closest: String? = null
            for (argb in pixels) {
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                val dr = r - color.r
                val dg = g - color.g
                val db = b - color.b
                val maxChannel = maxOf(absValue(dr), absValue(dg), absValue(db))
                if (maxChannel <= tolerance) {
                    hits++
                    if (hits >= minHits) break
                } else {
                    val sum = dr.toLong() * dr + dg.toLong() * dg + db.toLong() * db
                    if (sum < bestDistance) {
                        bestDistance = sum
                        closest = "#" + hex2(r) + hex2(g) + hex2(b)
                    }
                }
            }
            return HitResult(hits, closest)
        }

        private fun absValue(v: Int): Int = if (v < 0) -v else v

        private fun hex2(v: Int): String {
            val digits = "0123456789ABCDEF"
            return digits[v shr 4].toString() + digits[v and 0x0F].toString()
        }

        private fun intValue(config: JSONObject, key: String, min: Long, max: Long, fallback: Long): Int {
            val value = try { config.optLong(key, fallback) } catch (t: Throwable) { fallback }
            return value.coerceIn(min, max).toInt()
        }

        private fun mode(config: JSONObject): String {
            val value = try { config.optString("mode", MODE_ANY) } catch (t: Throwable) { MODE_ANY }
            return if (value == MODE_ALL) MODE_ALL else MODE_ANY
        }

        private fun colors(config: JSONObject): List<Rgb> {
            val arr = try { config.optJSONArray("colors") } catch (t: Throwable) { null } ?: return emptyList()
            val out = ArrayList<Rgb>()
            for (i in 0 until arr.length()) {
                val raw = try { arr.optString(i, "") } catch (t: Throwable) { "" }
                val rgb = parseColor(raw.trim()) ?: continue
                if (!out.contains(rgb)) out.add(rgb)
            }
            return out
        }

        private fun parseColor(raw: String): Rgb? {
            if (raw.length != 7 || raw[0] != '#') return null
            val r = hexValue(raw[1], raw[2]) ?: return null
            val g = hexValue(raw[3], raw[4]) ?: return null
            val b = hexValue(raw[5], raw[6]) ?: return null
            return Rgb(r, g, b)
        }

        private fun hexValue(high: Char, low: Char): Int? {
            val h = hexDigit(high) ?: return null
            val l = hexDigit(low) ?: return null
            return h * 16 + l
        }

        private fun hexDigit(c: Char): Int? = when (c) {
            in '0'..'9' -> c - '0'
            in 'A'..'F' -> c - 'A' + 10
            in 'a'..'f' -> c - 'a' + 10
            else -> null
        }

        private fun colorDistance(a: Rgb, b: Rgb): Long {
            val dr = (a.r - b.r).toLong()
            val dg = (a.g - b.g).toLong()
            val db = (a.b - b.b).toLong()
            return dr * dr + dg * dg + db * db
        }

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
            val out = ArrayList<Region>()
            for (i in 0 until arr.length()) {
                val raw = try { arr.optString(i, "") } catch (t: Throwable) { "" }
                val index = raw.toIntOrNull() ?: continue
                if (index >= 1 && index <= all.size) {
                    val region = all[index - 1]
                    if (!out.contains(region)) out.add(region)
                }
            }
            return out
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
                // 宿主给的副本回收失败只影响内存，不影响判定结果，忽略即可
            }
        }

        /** 一条目标颜色的统计结果；closest 记录离目标最近的实际像素颜色，用于失败文案。 */
        private data class ColorStat(val color: Rgb, val hits: Int, val closest: String?)

        /** 单次扫描的命中数与最近颜色。 */
        private data class HitResult(val hits: Int, val closest: String?)

        /** #RRGGBB 解析结果。 */
        private data class Rgb(val r: Int, val g: Int, val b: Int) {
            val hex: String get() = "#" + hex2(r) + hex2(g) + hex2(b)

            private fun hex2(v: Int): String {
                val digits = "0123456789ABCDEF"
                return digits[v shr 4].toString() + digits[v and 0x0F].toString()
            }
        }

        companion object {
            const val MODE_ANY = "any"
            const val MODE_ALL = "all"
            const val MIN_TOLERANCE = 0L
            const val MAX_TOLERANCE = 100L
            const val DEFAULT_TOLERANCE = 16L
            const val MIN_MIN_HITS = 1L
            const val MAX_MIN_HITS = 1000L
            const val DEFAULT_MIN_HITS = 1L
            const val DEFAULT_COLOR = "#FF3B30"
            const val NO_CAPTURE_REASON = "还没有截图授权，请先在设置里授权屏幕截图"
        }
    }
}
