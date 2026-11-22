package com.autotapper.plugin.viewid

import com.example.autotapper.model.TapCoord
import com.example.autotapper.plugin.AutoTapperPlugin
import com.example.autotapper.plugin.Capability
import com.example.autotapper.plugin.Field
import com.example.autotapper.plugin.PluginApi
import com.example.autotapper.plugin.PluginContext
import com.example.autotapper.plugin.PluginStepType
import com.example.autotapper.plugin.ScreenSpec
import com.example.autotapper.plugin.Section
import com.example.autotapper.plugin.StepResult
import kotlin.coroutines.resume
import kotlin.random.Random
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * 识ID —— 通过无障碍节点树按 view id 找控件并点击。
 *
 * 为什么值得做：按坐标点（core.tap / region-random）换一台手机、换个字号就会点偏；
 * 按 view id 找的是"控件本身"，宿主的 [com.example.autotapper.plugin.NodeApi]
 * 会把它换算成当前屏幕上的坐标，所以规则能跨分辨率复用。
 *
 * 插件编译期只有纯 JVM 的 SDK，**没有 android 类可用**：所有节点查找都只能经 ctx.nodes，
 * 自己不能去碰 AccessibilityNodeInfo（那是宿主的活）。
 *
 * 两个步骤类型：
 * - view-id.click：找到就点（可配按住时长、可按文本兜底），找不到如实失败；
 * - view-id.exists：只判断存在与否，配合 flow-control 的策略做"等界面出现"。
 */
class ViewIdPlugin : AutoTapperPlugin {

    override val id: String = "view-id"

    override val name: String = "识ID"

    override val version: String = "0.1.0"

    /** 必须等于主程序的 PluginApi.VERSION。 */
    override val apiVersion: Int = PluginApi.VERSION

    /** 主程序 versionCode 低于这个值时不加载。 */
    override val minHostVersionCode: Int = 1

    override fun stepTypes(): List<PluginStepType> = listOf(
        ClickStep(),
        ExistsStep()
    )

    /** typeId=view-id.click：按 view id（找不到就按文本）定位控件并点击。 */
    private class ClickStep : PluginStepType {
        override val typeId: String = "view-id.click"
        override val displayName: String = "识ID点击"
        override val requires: Set<Capability> = setOf(Capability.ACCESSIBILITY)

        override fun defaultConfig(): JSONObject = JSONObject()
            .put("viewId", "")
            .put("packageName", "")
            .put("holdMs", JSONObject().put("min", DEFAULT_HOLD_MIN_MS).put("max", DEFAULT_HOLD_MAX_MS))
            .put("fallbackToText", "")

        override fun editorSpec(config: JSONObject): ScreenSpec = ScreenSpec(
            sections = listOf(
                Section(
                    eyebrow = "// TARGET",
                    title = "找哪个控件",
                    fields = listOf(
                        Field.Text(
                            key = "viewId",
                            label = "控件 ID",
                            placeholder = "com.tencent.mm:id/btn"
                        ),
                        Field.Text(
                            key = "packageName",
                            label = "包名（可留空：当前窗口任意包）",
                            placeholder = "com.tencent.mm"
                        ),
                        Field.Text(
                            key = "fallbackToText",
                            label = "兜底文本（可留空：按 ID 找不到就用这段文字找）",
                            placeholder = "确定"
                        )
                    )
                ),
                Section(
                    eyebrow = "// TIMING",
                    title = "按住时长",
                    fields = listOf(
                        Field.RangeField(
                            key = "holdMs",
                            label = "按住时长",
                            min = MIN_HOLD_MS,
                            max = MAX_HOLD_MS,
                            unit = "ms"
                        )
                    )
                )
            )
        )

        override fun summarize(config: JSONObject): String {
            val viewId = Helpers.text(config, "viewId")
            val fallback = Helpers.text(config, "fallbackToText")
            val range = Helpers.range(
                config, "holdMs", MIN_HOLD_MS, MAX_HOLD_MS,
                DEFAULT_HOLD_MIN_MS, DEFAULT_HOLD_MAX_MS
            )
            val target = if (viewId.isEmpty()) "（未填 ID）" else "「" + viewId + "」"
            val suffix = if (fallback.isEmpty()) "" else "，找不到改点「" + fallback + "」"
            return "点 ID " + target + "，按住 " + Helpers.rangeText(range) + " ms" + suffix
        }

        override suspend fun execute(ctx: PluginContext, config: JSONObject): StepResult {
            val viewId = Helpers.text(config, "viewId")
            if (viewId.isEmpty()) {
                return StepResult.Fail("请先填控件 ID（形如 com.tencent.mm:id/btn）")
            }
            val range = Helpers.range(
                config, "holdMs", MIN_HOLD_MS, MAX_HOLD_MS,
                DEFAULT_HOLD_MIN_MS, DEFAULT_HOLD_MAX_MS
            )
            val holdMs = Helpers.sample(range)
            // 配置先校验完再碰能力：无障碍没开时要给出的是一句准确的提示，而不是"配置错"
            val packageName = Helpers.text(config, "packageName").takeIf { it.isNotEmpty() }
            val fallback = Helpers.text(config, "fallbackToText")

            if (!Helpers.nodesReady(ctx)) {
                return StepResult.Fail("无障碍服务没有连上，识 ID 需要在设置里打开无障碍")
            }

            var point = Helpers.findByViewId(ctx, viewId, packageName)
            if (point == null && fallback.isNotEmpty()) {
                // 同一个控件在部分系统版本上会丢掉 viewIdResourceName，这时文本是唯一的锚
                ctx.log.info("按 ID「" + viewId + "」没找到，改按文本「" + fallback + "」找")
                point = Helpers.findByText(ctx, fallback)
            }
            if (point == null) {
                return StepResult.Fail("没找到节点「" + viewId + "」，可能不在当前页面")
            }

            if (!Helpers.awaitTap(ctx, point, holdMs)) {
                return StepResult.Fail("在「" + viewId + "」上下发点击失败或回调超时")
            }
            return StepResult.Success(point)
        }

        private companion object {
            const val MIN_HOLD_MS = 20L
            const val MAX_HOLD_MS = 500L
            const val DEFAULT_HOLD_MIN_MS = 60L
            const val DEFAULT_HOLD_MAX_MS = 120L
        }
    }

    /** typeId=view-id.exists：判断控件是否已经在当前窗口上，用于"等界面出现"。 */
    private class ExistsStep : PluginStepType {
        override val typeId: String = "view-id.exists"
        override val displayName: String = "控件是否存在"
        override val requires: Set<Capability> = setOf(Capability.ACCESSIBILITY)

        override fun defaultConfig(): JSONObject = JSONObject()
            .put("viewId", "")
            .put("packageName", "")
            .put("fallbackToText", "")

        override fun editorSpec(config: JSONObject): ScreenSpec = ScreenSpec(
            sections = listOf(
                Section(
                    eyebrow = "// TARGET",
                    title = "等哪个控件",
                    fields = listOf(
                        Field.Text(
                            key = "viewId",
                            label = "控件 ID",
                            placeholder = "com.tencent.mm:id/btn"
                        ),
                        Field.Text(
                            key = "packageName",
                            label = "包名（可留空：当前窗口任意包）",
                            placeholder = "com.tencent.mm"
                        ),
                        Field.Text(
                            key = "fallbackToText",
                            label = "兜底文本（可留空：按 ID 找不到就用这段文字找）",
                            placeholder = "确定"
                        )
                    )
                )
            )
        )

        override fun summarize(config: JSONObject): String {
            val viewId = Helpers.text(config, "viewId")
            val fallback = Helpers.text(config, "fallbackToText")
            val target = if (viewId.isEmpty()) "（未填 ID）" else "「" + viewId + "」"
            val suffix = if (fallback.isEmpty()) "" else "，或出现文本「" + fallback + "」"
            return "等控件 " + target + " 出现" + suffix
        }

        override suspend fun execute(ctx: PluginContext, config: JSONObject): StepResult {
            val viewId = Helpers.text(config, "viewId")
            if (viewId.isEmpty()) {
                return StepResult.Fail("请先填控件 ID（形如 com.tencent.mm:id/btn）")
            }
            val packageName = Helpers.text(config, "packageName").takeIf { it.isNotEmpty() }
            val fallback = Helpers.text(config, "fallbackToText")

            if (!Helpers.nodesReady(ctx)) {
                return StepResult.Fail("无障碍服务没有连上，识 ID 需要在设置里打开无障碍")
            }

            var point = Helpers.findByViewId(ctx, viewId, packageName)
            if (point == null && fallback.isNotEmpty()) {
                point = Helpers.findByText(ctx, fallback)
            }
            if (point == null) {
                // 这里是"还没出现"，不是"哪里写错了"，所以文案要能被 strategy 当成正常的等待失败
                return StepResult.Fail("没找到节点「" + viewId + "」，可能还没出现")
            }
            return StepResult.Success(point)
        }
    }

    /** 两个步骤共用的小工具：配置读取、节点查找、挂起等待注入回调。 */
    private object Helpers {
        const val CALLBACK_TIMEOUT_MS = 5000L

        fun text(config: JSONObject, key: String): String =
            try {
                config.optString(key, "").trim()
            } catch (t: Throwable) {
                ""
            }

        /** 读 RangeField：约定 {"min":..,"max":..}；兼容旧的单个数字写法；返回夹好的闭区间。 */
        fun range(
            config: JSONObject, key: String, min: Long, max: Long,
            fallbackMin: Long, fallbackMax: Long
        ): Pair<Long, Long> {
            var lo = fallbackMin.coerceIn(min, max)
            var hi = fallbackMax.coerceIn(min, max)
            try {
                val obj = config.optJSONObject(key)
                if (obj != null) {
                    lo = obj.optLong("min", lo)
                    hi = obj.optLong("max", hi)
                } else {
                    val single = config.optLong(key, lo)
                    lo = single
                    hi = single
                }
            } catch (t: Throwable) {
                lo = fallbackMin.coerceIn(min, max)
                hi = fallbackMax.coerceIn(min, max)
            }
            lo = lo.coerceIn(min, max)
            hi = hi.coerceIn(min, max)
            return if (hi < lo) Pair(lo, lo) else Pair(lo, hi)
        }

        fun sample(range: Pair<Long, Long>): Long =
            if (range.second <= range.first) range.first
            else Random.nextLong(range.first, range.second + 1)

        fun rangeText(range: Pair<Long, Long>): String =
            if (range.second <= range.first) range.first.toString()
            else range.first.toString() + "–" + range.second.toString()

        /**
         * 无障碍是否连上。宿主没注册节点能力时这里会返回 false（宿主给的是空实现），
         * 老版本宿主上调用 ctx.nodes 甚至可能抛 NoSuchMethodError，所以整段包 try/catch。
         */
        fun nodesReady(ctx: PluginContext): Boolean =
            try {
                ctx.nodes.isReady()
            } catch (t: Throwable) {
                ctx.log.warn("查询无障碍服务状态失败：" + describe(t))
                false
            }

        fun findByViewId(ctx: PluginContext, viewId: String, packageName: String?): TapCoord? =
            try {
                ctx.nodes.findByViewId(viewId, packageName)
            } catch (t: Throwable) {
                ctx.log.warn("按 ID 查找节点失败：" + describe(t))
                null
            }

        fun findByText(ctx: PluginContext, text: String): TapCoord? =
            try {
                ctx.nodes.findByText(text, false)
            } catch (t: Throwable) {
                ctx.log.warn("按文本查找节点失败：" + describe(t))
                null
            }

        /**
         * 挂起等宿主注入回调，不占着线程；5 秒没回调就按失败处理。
         * 引擎和回调都跑在 Dispatchers.Main 上，阻塞等待必然死锁，这里只挂起。
         */
        suspend fun awaitTap(ctx: PluginContext, point: TapCoord, holdMs: Long): Boolean {
            val ok = withTimeoutOrNull(CALLBACK_TIMEOUT_MS) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    try {
                        ctx.executor.tap(point, holdMs) { result ->
                            // 用 kotlin.coroutines.resume 这个扩展：CancellableContinuation 自带的
                            // resume(value, onCancellation) 需要 opt-in，依赖取消回调反而更啰嗦
                            if (cont.isActive) cont.resume(result)
                        }
                    } catch (t: Throwable) {
                        ctx.log.warn("下发点击失败：" + describe(t))
                        if (cont.isActive) cont.resume(false)
                    }
                }
            }
            if (ok == null) ctx.log.warn("点击回调超时（" + CALLBACK_TIMEOUT_MS + " ms）")
            return ok ?: false
        }

        fun describe(t: Throwable): String =
            t.javaClass.simpleName + (t.message?.let { ": " + it } ?: "")
    }
}
