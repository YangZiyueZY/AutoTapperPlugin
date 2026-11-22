package com.autotapper.plugin.recorder

import com.example.autotapper.model.TapCoord
import com.example.autotapper.plugin.AutoTapperPlugin
import com.example.autotapper.plugin.Capability
import com.example.autotapper.plugin.Field
import com.example.autotapper.plugin.PluginApi
import com.example.autotapper.plugin.PluginContext
import com.example.autotapper.plugin.PluginStepType
import com.example.autotapper.plugin.RecordedGesture
import com.example.autotapper.plugin.ScreenSpec
import com.example.autotapper.plugin.Section
import com.example.autotapper.plugin.StepResult
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * 录制回放 —— 录下人工操作，把"点哪里"翻译成可运行的步骤。
 *
 * 为什么只转点击：内置的 core.tap 就是"在固定坐标按住 durationMs"，录到的点击能一比一对应；
 * 而滑动在这个版本里没有等价的 (x, y, durationMs) 表达，硬凑（比如拆成三次点击）会让回放
 * 跟用户录的完全不是一回事。所以滑动一律跳过并写进日志：宁可少一条，也不编一条假的。
 *
 * 为什么只存到插件配置：插件没有写规则的权限（规则文件归宿主的 RuleStore 管，插件只拿得到
 * 自己的 prefs）。所以这里是"录好 + 存起来 + 告诉用户去哪手动加"，不假装已经写进规则。
 */
class RecorderPlugin : AutoTapperPlugin {

    override val id: String = "recorder"

    override val name: String = "录制回放"

    override val version: String = "0.1.0"

    /** 必须等于主程序的 PluginApi.VERSION。 */
    override val apiVersion: Int = PluginApi.VERSION

    /** 主程序 versionCode 低于这个值时不加载。录制能力缺失时 execute 里会给出明确原因。 */
    override val minHostVersionCode: Int = 1

    override fun stepTypes(): List<PluginStepType> = listOf(RecordStep())

    /** typeId=recorder.record：拉起宿主遮层录一段操作，把点击转成 core.tap 步骤存起来。 */
    private class RecordStep : PluginStepType {
        override val typeId: String = "recorder.record"

        override val displayName: String = "录制操作"

        /** 录制要悬浮遮层（OVERLAY）；录到的点击最终仍由宿主注入下发（ACCESSIBILITY）。 */
        override val requires: Set<Capability> = setOf(Capability.OVERLAY, Capability.ACCESSIBILITY)

        override fun defaultConfig(): JSONObject = JSONObject()
            .put(KEY_MAX_SECONDS, DEFAULT_MAX_SECONDS)
            .put(KEY_TARGET_RULE, TARGET_NEW)
            .put(KEY_NOTE, "")

        override fun editorSpec(config: JSONObject): ScreenSpec = ScreenSpec(
            sections = listOf(
                Section(
                    eyebrow = "// CAPTURE",
                    title = "录制窗口",
                    fields = listOf(
                        Field.IntField(
                            key = KEY_MAX_SECONDS,
                            label = "最长录制时长",
                            min = MIN_SECONDS,
                            max = MAX_SECONDS,
                            unit = "秒"
                        ),
                        Field.EnumField(
                            key = KEY_TARGET_RULE,
                            label = "录完放到哪",
                            options = listOf("新建规则", "追加到当前规则"),
                            values = listOf(TARGET_NEW, TARGET_APPEND)
                        )
                    )
                ),
                Section(
                    eyebrow = "// NOTE",
                    title = "备注",
                    fields = listOf(
                        Field.Text(
                            key = KEY_NOTE,
                            label = "给这批录制起个名字（可空）",
                            placeholder = "例如：每日签到"
                        )
                    )
                )
            )
        )

        override fun summarize(config: JSONObject): String {
            val seconds = readSeconds(config)
            return "录制最多 " + seconds + " 秒（" + targetLabel(readTarget(config)) + "）"
        }

        override suspend fun execute(ctx: PluginContext, config: JSONObject): StepResult {
            val seconds = readSeconds(config)
            val target = readTarget(config)
            val note = readNote(config)

            // 老宿主里连 recorder 这个成员都没有，取用会抛 AbstractMethodError；
            // 那不是"没权限"，得说清楚，别让用户白找半天悬浮窗开关
            val supported = try {
                ctx.recorder.isSupported()
            } catch (t: Throwable) {
                ctx.log.error("主程序没有录制能力（版本过旧）：" + (t.message ?: t.javaClass.simpleName))
                return StepResult.Fail("当前主程序版本不支持录制，请更新主程序")
            }
            if (!supported) return StepResult.Fail("需要悬浮窗权限才能录制")

            ctx.log.info("开始录制，最长 " + seconds + " 秒。点屏幕记位置，点顶部「结束」交回。")
            ctx.log.info("注意：录制期间触摸由遮层接管，下层应用不会响应这一次点击，录到的是你点的位置。")

            val gestures = try {
                ctx.recorder.record(seconds * 1000L)
            } catch (cancelled: CancellationException) {
                // 引擎停止 / 步骤超时走的是协程取消，必须原样抛出去：
                // 宿主靠它触发"关掉遮层"，吞掉就会留下一个抓不住的录制窗口
                throw cancelled
            } catch (t: Throwable) {
                ctx.log.error("录制过程出错：" + (t.message ?: t.javaClass.simpleName))
                return StepResult.Fail("录制过程出错，已中止")
            }
            if (gestures.isEmpty()) return StepResult.Fail("没有录到任何操作")

            val steps = JSONArray()
            var tapCount = 0
            var skippedSwipes = 0
            for (gesture in gestures) {
                when (gesture.kind) {
                    RecordedGesture.Kind.TAP -> {
                        steps.put(toCoreTap(gesture, note))
                        tapCount++
                    }

                    RecordedGesture.Kind.SWIPE -> {
                        skippedSwipes++
                        // 诚实做法：滑动没有对应内置步骤，跳过并如实写清，不硬凑成假步骤
                        ctx.log.warn(
                            "滑动这一条没有对应内置步骤，已跳过（" +
                                pointText(gesture.from) + " → " + pointText(gesture.to) +
                                "，" + gesture.durationMs + " ms）"
                        )
                    }
                }
            }
            if (tapCount == 0) {
                return StepResult.Fail("录到的 " + gestures.size + " 条都是滑动，没有能转成内置点击的步骤")
            }

            val saved = try {
                ctx.prefs.edit().putString(KEY_LAST_RECORDED, steps.toString()).commit()
            } catch (t: Throwable) {
                ctx.log.error("保存录到的步骤失败：" + (t.message ?: t.javaClass.simpleName))
                false
            }
            if (!saved) {
                return StepResult.Fail("录到了 " + tapCount + " 条，但写进插件配置失败")
            }

            ctx.log.info(
                "共录到 " + gestures.size + " 条：点击 " + tapCount + " 条已转成步骤，滑动 " +
                    skippedSwipes + " 条已跳过。"
            )
            ctx.log.info(
                "已保存到插件配置（key=" + KEY_LAST_RECORDED + "）。请到主程序「规则」页把它们加进去——" +
                    "当前版本还没有一键导入。"
            )
            ctx.log.info("目标：" + targetLabel(target) + "（当前版本只记下你的选择，不会自动改写规则。）")
            return StepResult.Success()
        }

        /** 一条点击 → 一条 CoreStep 风格 JSON，字段与宿主 StepJson 的 core.tap 编码对齐。 */
        private fun toCoreTap(gesture: RecordedGesture, note: String): JSONObject {
            val obj = JSONObject()
                .put("type", CORE_TAP_TYPE)
                .put("id", UUID.randomUUID().toString())
                .put("enabled", true)
                .put("x", gesture.from.x)
                .put("y", gesture.from.y)
                // 录到的按住时长就是回放时要按的时长；夹到宿主允许的区间里，避免 0 ms 点空
                .put("durationMs", gesture.holdMs.coerceIn(MIN_TAP_HOLD_MS, MAX_TAP_HOLD_MS))
            if (note.isNotBlank()) obj.put("note", note)
            return obj
        }

        private fun readSeconds(config: JSONObject): Int {
            val raw = try {
                config.optLong(KEY_MAX_SECONDS, DEFAULT_MAX_SECONDS)
            } catch (t: Throwable) {
                DEFAULT_MAX_SECONDS
            }
            return raw.coerceIn(MIN_SECONDS, MAX_SECONDS).toInt()
        }

        private fun readTarget(config: JSONObject): String {
            val raw = try {
                config.optString(KEY_TARGET_RULE, TARGET_NEW)
            } catch (t: Throwable) {
                TARGET_NEW
            }
            return if (raw == TARGET_APPEND) TARGET_APPEND else TARGET_NEW
        }

        private fun readNote(config: JSONObject): String {
            val raw = try {
                config.optString(KEY_NOTE, "")
            } catch (t: Throwable) {
                ""
            }
            return raw.trim()
        }

        private fun targetLabel(target: String): String =
            if (target == TARGET_APPEND) "追加到当前规则" else "新建规则"

        private fun pointText(point: TapCoord): String = point.x.toString() + "," + point.y.toString()

        private companion object {
            const val KEY_MAX_SECONDS = "maxSeconds"
            const val KEY_TARGET_RULE = "targetRule"
            const val KEY_NOTE = "note"

            const val MIN_SECONDS = 10L
            const val MAX_SECONDS = 300L
            const val DEFAULT_MAX_SECONDS = 60L

            const val TARGET_NEW = "new"
            const val TARGET_APPEND = "append"

            /** 录到的步骤就存在这个 prefs key 里，等主程序做出"一键导入"再接上。 */
            const val KEY_LAST_RECORDED = "lastRecorded"

            const val CORE_TAP_TYPE = "core.tap"
            const val MIN_TAP_HOLD_MS = 20L
            const val MAX_TAP_HOLD_MS = 2_000L
        }
    }
}
