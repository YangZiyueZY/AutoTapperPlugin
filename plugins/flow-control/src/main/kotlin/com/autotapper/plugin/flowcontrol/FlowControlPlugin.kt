package com.autotapper.plugin.flowcontrol

import com.example.autotapper.plugin.AutoTapperPlugin
import com.example.autotapper.plugin.Capability
import com.example.autotapper.plugin.Field
import com.example.autotapper.plugin.HostExtension
import com.example.autotapper.plugin.PluginApi
import com.example.autotapper.plugin.PluginContext
import com.example.autotapper.plugin.PluginStepType
import com.example.autotapper.plugin.PolicyCall
import com.example.autotapper.plugin.PolicyDecision
import com.example.autotapper.plugin.PolicyPhase
import com.example.autotapper.plugin.PolicyResult
import com.example.autotapper.plugin.ScreenSpec
import com.example.autotapper.plugin.Section
import com.example.autotapper.plugin.StepPolicyPlugin
import com.example.autotapper.plugin.StepResult
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.random.Random

/**
 * 流程控制 —— 把"时机与分支"做成插件里的策略。
 *
 * 两副面孔：
 * 1. **策略（[StepPolicyPlugin]）**：宿主引擎在每一步前后各问一次，回答延迟 / 超时 / 重复 / 分支；
 * 2. **步骤**：「流程设置」写全局策略、「延迟」等一会儿、「跳转」直接去第 N 步。
 *
 * 线程：decide() 跑在宿主的协程里（主线程调度器），所以它只读内存快照 [settings]，
 * 一次 IO 都不做。真正读 prefs 的只有 attach() 与「流程设置」步骤执行完的那一刻。
 */
class FlowControlPlugin : AutoTapperPlugin, StepPolicyPlugin {

    override val id: String = "flow-control"

    override val name: String = "流程控制"

    override val version: String = "0.1.0"

    /** 必须等于主程序的 PluginApi.VERSION。 */
    override val apiVersion: Int = PluginApi.VERSION

    /** 主程序 versionCode 低于这个值时不加载。 */
    override val minHostVersionCode: Int = 1

    /** 宿主经 attach() 交进来的上下文；没有它就读不到配置，一切按默认值。 */
    @Volatile
    private var hostContext: PluginContext? = null

    /** 配置快照。decide() 只读它，所以策略插件永远不会因为读盘把引擎卡住。 */
    @Volatile
    private var settings: FlowSettings = FlowSettings.DEFAULT

    /** 已经提示过的「配置优先级」警告，按"步骤类型 + 步骤序号"去重，避免每步刷屏。 */
    private val warnedKeys: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())

    // -------------------------------------------------------------------
    // 插件入口
    // -------------------------------------------------------------------

    /** 三个步骤类型 + 自己这一份策略扩展。 */
    override fun stepTypes(): List<PluginStepType> = listOf(SetupStep(), DelayStep(), GotoStep())

    override fun extensions(): List<HostExtension> = listOf(this)

    // -------------------------------------------------------------------
    // 策略扩展点
    // -------------------------------------------------------------------

    /**
     * 宿主装载成功后调一次：把上下文存好，并读一份配置快照。
     *
     * 为什么这里就把 prefs 读进内存：decide() 被每一步调用两次，不能在里面读盘。
     */
    override fun attach(context: PluginContext) {
        hostContext = context
        refreshSettings()
    }

    override fun decide(call: PolicyCall): PolicyDecision {
        val snapshot = settings
        return when (call.phase) {
            PolicyPhase.BEFORE -> decideBefore(call, snapshot)
            PolicyPhase.AFTER -> decideAfter(call, snapshot)
        }
    }

    /**
     * 执行前：延迟 / 超时 / 重复。
     *
     * 一次 decide() 只能回一个决定（[PolicyDecision] 是单值），所以按
     * 「节奏 > 安全 > 重试」排序：先保证每一步的随机延迟（这是本插件最常用的配置），
     * 再谈单步超时，最后才是重试次数。同时配了多项时提示一次，免得用户以为没生效。
     */
    private fun decideBefore(call: PolicyCall, snapshot: FlowSettings): PolicyDecision {
        if (snapshot.delayMax > 0L) {
            if (snapshot.timeoutMs > 0L || snapshot.repeatTimes > 1) {
                warnOnce(
                    call,
                    "同时配置了延迟与超时/重复，但宿主一次只接受一个决定，本次按延迟生效（超时/重复未生效）"
                )
            }
            return PolicyDecision.Delay(randomBetween(snapshot.delayMin.coerceAtMost(snapshot.delayMax), snapshot.delayMax))
        }
        if (snapshot.timeoutMs > 0L) return PolicyDecision.Timeout(snapshot.timeoutMs)
        if (snapshot.repeatTimes > 1) return PolicyDecision.Repeat(snapshot.repeatTimes)
        return PolicyDecision.Proceed
    }

    /**
     * 拿到结果后：失败 / 成功分支。
     *
     * STOP / PAUSE / GOTO 是步骤自己给出的结果，宿主引擎已经处理过，这里不再叠加决定。
     */
    private fun decideAfter(call: PolicyCall, snapshot: FlowSettings): PolicyDecision =
        when (call.result) {
            PolicyResult.FAIL -> when (snapshot.onFail) {
                ON_FAIL_STOP -> PolicyDecision.Stop
                ON_FAIL_PAUSE -> PolicyDecision.Pause
                ON_FAIL_GOTO -> gotoDecision(call, snapshot)
                else -> PolicyDecision.Proceed
            }

            PolicyResult.SUCCESS ->
                if (snapshot.onSuccess == ON_SUCCESS_GOTO) gotoDecision(call, snapshot) else PolicyDecision.Proceed

            else -> PolicyDecision.Proceed
        }

    /** 分支目标从 1 开始（界面上第几步就是第几步），[PolicyDecision.Goto] 要 0 基。 */
    private fun gotoDecision(call: PolicyCall, snapshot: FlowSettings): PolicyDecision {
        if (snapshot.branchStep < 1) {
            warnOnce(call, "分支目标步骤配置为 " + snapshot.branchStep + "，无效，本次按继续处理")
            return PolicyDecision.Proceed
        }
        return PolicyDecision.Goto(snapshot.branchStep - 1)
    }

    // -------------------------------------------------------------------
    // 配置
    // -------------------------------------------------------------------

    /** 从 prefs 读一份快照。attach 与「流程设置」步骤执行完都会调它。 */
    private fun refreshSettings() {
        val context = hostContext ?: return
        settings = runCatching {
            FlowSettings(
                delayMin = context.prefs.getLong(KEY_DELAY_MIN, 0L).coerceIn(0L, MAX_DELAY_MS),
                delayMax = context.prefs.getLong(KEY_DELAY_MAX, 0L).coerceIn(0L, MAX_DELAY_MS),
                timeoutMs = context.prefs.getLong(KEY_TIMEOUT, 0L).coerceIn(0L, MAX_TIMEOUT_MS),
                repeatTimes = context.prefs.getInt(KEY_REPEAT, 1).coerceIn(1, MAX_REPEAT),
                onFail = context.prefs.getString(KEY_ON_FAIL, ON_FAIL_NONE).orEmpty().ifBlank { ON_FAIL_NONE },
                onSuccess = context.prefs.getString(KEY_ON_SUCCESS, ON_SUCCESS_NONE).orEmpty()
                    .ifBlank { ON_SUCCESS_NONE },
                branchStep = context.prefs.getInt(KEY_BRANCH_STEP, 1).coerceIn(0, MAX_STEP)
            )
        }.getOrElse { t ->
            context.log.warn("读取流程控制配置失败，沿用上次的值：" + (t.message ?: t.javaClass.simpleName))
            settings
        }
    }

    private fun warnOnce(call: PolicyCall, message: String) {
        val key = call.typeId + "#" + call.stepIndex
        if (!warnedKeys.add(key)) return
        val context = hostContext ?: return
        runCatching { context.log.warn(message) }
    }

    // -------------------------------------------------------------------
    // 步骤：流程设置
    // -------------------------------------------------------------------

    /**
     * 「流程设置」：把界面上的 6 项策略写进插件私有 prefs，并立刻刷新内存快照。
     * 放在规则第一步即可 —— 宿主随后问 decide() 读到的就是新值，不需要重启规则。
     */
    private inner class SetupStep : PluginStepType {

        override val typeId: String = "flow-control.setup"

        override val displayName: String = "流程设置"

        override val requires: Set<Capability> = emptySet()

        override fun defaultConfig(): JSONObject = JSONObject()
            .put(KEY_DELAY_MIN, 0L)
            .put(KEY_DELAY_MAX, 0L)
            .put(KEY_TIMEOUT, 0L)
            .put(KEY_REPEAT, 1)
            .put(KEY_ON_FAIL, ON_FAIL_NONE)
            .put(KEY_ON_SUCCESS, ON_SUCCESS_NONE)
            .put(KEY_BRANCH_STEP, 1)

        override fun editorSpec(config: JSONObject): ScreenSpec = ScreenSpec(
            sections = listOf(
                Section(
                    eyebrow = "// TIMING",
                    title = "每步之前的延迟",
                    fields = listOf(
                        Field.IntField(KEY_DELAY_MIN, "最小延迟", min = 0L, max = MAX_DELAY_MS, unit = "ms"),
                        Field.IntField(KEY_DELAY_MAX, "最大延迟（0 = 不延迟）", min = 0L, max = MAX_DELAY_MS, unit = "ms")
                    )
                ),
                Section(
                    eyebrow = "// LIMIT",
                    title = "单步保护",
                    fields = listOf(
                        Field.IntField(KEY_TIMEOUT, "单步超时（0 = 不限）", min = 0L, max = MAX_TIMEOUT_MS, unit = "ms"),
                        Field.IntField(KEY_REPEAT, "每步执行次数", min = 1L, max = MAX_REPEAT.toLong(), unit = "次")
                    )
                ),
                Section(
                    eyebrow = "// BRANCH",
                    title = "成功与失败的分支",
                    fields = listOf(
                        Field.EnumField(
                            KEY_ON_FAIL,
                            "步骤失败后",
                            options = listOf("继续", "停止", "暂停", "跳到指定步骤"),
                            values = listOf(ON_FAIL_NONE, ON_FAIL_STOP, ON_FAIL_PAUSE, ON_FAIL_GOTO)
                        ),
                        Field.EnumField(
                            KEY_ON_SUCCESS,
                            "步骤成功后",
                            options = listOf("继续", "跳到指定步骤"),
                            values = listOf(ON_SUCCESS_NONE, ON_SUCCESS_GOTO)
                        ),
                        Field.StepIndexField(KEY_BRANCH_STEP, "跳到第几步（从 1 开始）")
                    )
                )
            )
        )

        override fun summarize(config: JSONObject): String = FlowSettings.from(config).describe()

        override suspend fun execute(ctx: PluginContext, config: JSONObject): StepResult {
            val snapshot = FlowSettings.from(config)

            val saved = runCatching {
                ctx.prefs.edit()
                    .putLong(KEY_DELAY_MIN, snapshot.delayMin)
                    .putLong(KEY_DELAY_MAX, snapshot.delayMax)
                    .putLong(KEY_TIMEOUT, snapshot.timeoutMs)
                    .putInt(KEY_REPEAT, snapshot.repeatTimes)
                    .putString(KEY_ON_FAIL, snapshot.onFail)
                    .putString(KEY_ON_SUCCESS, snapshot.onSuccess)
                    .putInt(KEY_BRANCH_STEP, snapshot.branchStep)
                    .apply()
                true
            }.getOrElse { t ->
                ctx.log.warn("流程设置写入本地失败：" + (t.message ?: t.javaClass.simpleName))
                false
            }

            // 宿主下一次 decide() 读的就是这份快照，所以无论落盘成功与否都要立刻刷新：
            // 落盘失败只是"下次启动会回到旧配置"，本次运行必须按新配置走。
            hostContext = ctx
            settings = snapshot

            if (saved) {
                ctx.log.info("流程设置已生效：" + snapshot.describe())
            } else {
                ctx.log.warn("流程设置已生效（仅本次运行，本地没写成功）：" + snapshot.describe())
            }
            return StepResult.Success()
        }
    }

    // -------------------------------------------------------------------
    // 步骤：延迟
    // -------------------------------------------------------------------

    /** 「延迟」：随机等一会儿。写规则时想在两步之间插一段停顿就用它。 */
    private class DelayStep : PluginStepType {

        override val typeId: String = "flow-control.delay"

        override val displayName: String = "延迟"

        override val requires: Set<Capability> = emptySet()

        override fun defaultConfig(): JSONObject = JSONObject()
            .put(KEY_DELAY_MIN, 200L)
            .put(KEY_DELAY_MAX, 500L)

        override fun editorSpec(config: JSONObject): ScreenSpec = ScreenSpec(
            sections = listOf(
                Section(
                    eyebrow = "// DELAY",
                    title = "等待时长",
                    fields = listOf(
                        Field.IntField(KEY_DELAY_MIN, "最小延迟", min = 0L, max = MAX_DELAY_MS, unit = "ms"),
                        Field.IntField(KEY_DELAY_MAX, "最大延迟", min = 0L, max = MAX_DELAY_MS, unit = "ms")
                    )
                )
            )
        )

        override fun summarize(config: JSONObject): String = "延迟 " + describeRange(
            config.optLong(KEY_DELAY_MIN, 0L).coerceIn(0L, MAX_DELAY_MS),
            config.optLong(KEY_DELAY_MAX, 0L).coerceIn(0L, MAX_DELAY_MS)
        )

        override suspend fun execute(ctx: PluginContext, config: JSONObject): StepResult {
            val min = config.optLong(KEY_DELAY_MIN, 0L).coerceIn(0L, MAX_DELAY_MS)
            val max = config.optLong(KEY_DELAY_MAX, 0L).coerceIn(0L, MAX_DELAY_MS)
            val ms = randomBetween(min.coerceAtMost(max), max)

            if (ms <= 0L) {
                ctx.log.info("延迟配置为 0，本次不等")
                return StepResult.Success()
            }
            if (ms >= LONG_DELAY_LOG_MS) {
                ctx.log.info("等待 " + ms + " ms")
            }
            delay(ms)
            return StepResult.Success()
        }
    }

    // -------------------------------------------------------------------
    // 步骤：跳转
    // -------------------------------------------------------------------

    /**
     * 「跳转」：直接去第 N 步。
     * 返回 [StepResult.Goto]，宿主的策略适配器会把它翻译成"跳到第 N-1 步"。
     */
    private class GotoStep : PluginStepType {

        override val typeId: String = "flow-control.goto"

        override val displayName: String = "跳转"

        override val requires: Set<Capability> = emptySet()

        override fun defaultConfig(): JSONObject = JSONObject().put(KEY_STEP, 1)

        override fun editorSpec(config: JSONObject): ScreenSpec = ScreenSpec(
            sections = listOf(
                Section(
                    eyebrow = "// GOTO",
                    title = "跳转目标",
                    fields = listOf(
                        Field.StepIndexField(KEY_STEP, "跳到第几步（从 1 开始）")
                    )
                )
            )
        )

        override fun summarize(config: JSONObject): String =
            "跳转到第 " + config.optInt(KEY_STEP, 1).coerceIn(1, MAX_STEP) + " 步"

        override suspend fun execute(ctx: PluginContext, config: JSONObject): StepResult {
            val step = config.optInt(KEY_STEP, 1)
            if (step < 1) {
                ctx.log.warn("跳转目标配置为 " + step + "，无效")
                return StepResult.Fail("跳转目标必须从 1 开始")
            }
            if (step > MAX_STEP) {
                ctx.log.warn("跳转目标 " + step + " 过大，按 " + MAX_STEP + " 处理")
            }
            val target = step.coerceIn(1, MAX_STEP)
            ctx.log.info("跳到第 " + target + " 步")
            return StepResult.Goto(target - 1)
        }
    }
}

// ---------------------------------------------------------------------------
// 配置快照
// ---------------------------------------------------------------------------

/**
 * 一份只读的策略配置。默认值 = "什么都不做"，所以插件装上来但没配「流程设置」时，
 * 引擎行为与没装插件时一致（延迟 0、不限时、执行 1 次、失败继续）。
 */
private data class FlowSettings(
    val delayMin: Long,
    val delayMax: Long,
    val timeoutMs: Long,
    val repeatTimes: Int,
    val onFail: String,
    val onSuccess: String,
    val branchStep: Int
) {

    /** 一句话说清当前配置，步骤列表里直接看得懂。 */
    fun describe(): String {
        val parts = ArrayList<String>()
        parts.add(
            when {
                delayMax <= 0L -> "无额外延迟"
                delayMin.coerceAtMost(delayMax) == delayMax -> "延迟 " + delayMax + "ms"
                else -> "延迟 " + delayMin.coerceAtMost(delayMax) + "–" + delayMax + "ms"
            }
        )
        if (timeoutMs > 0L) parts.add("超时 " + describeMs(timeoutMs))
        if (repeatTimes > 1) parts.add("每步执行 " + repeatTimes + " 次")
        parts.add(
            when (onFail) {
                ON_FAIL_STOP -> "失败停止"
                ON_FAIL_PAUSE -> "失败暂停"
                ON_FAIL_GOTO -> "失败跳第 " + branchStep.coerceIn(1, MAX_STEP) + " 步"
                else -> "失败继续"
            }
        )
        if (onSuccess == ON_SUCCESS_GOTO) {
            parts.add("成功跳第 " + branchStep.coerceIn(1, MAX_STEP) + " 步")
        }
        return parts.joinToString(" · ")
    }

    companion object {

        val DEFAULT: FlowSettings = FlowSettings(
            delayMin = 0L,
            delayMax = 0L,
            timeoutMs = 0L,
            repeatTimes = 1,
            onFail = ON_FAIL_NONE,
            onSuccess = ON_SUCCESS_NONE,
            branchStep = 1
        )

        /** 从步骤配置读一份快照；面板可能被手输成任意数，所以全部 coerce。 */
        fun from(config: JSONObject): FlowSettings = FlowSettings(
            delayMin = config.optLong(KEY_DELAY_MIN, 0L).coerceIn(0L, MAX_DELAY_MS),
            delayMax = config.optLong(KEY_DELAY_MAX, 0L).coerceIn(0L, MAX_DELAY_MS),
            timeoutMs = config.optLong(KEY_TIMEOUT, 0L).coerceIn(0L, MAX_TIMEOUT_MS),
            repeatTimes = config.optInt(KEY_REPEAT, 1).coerceIn(1, MAX_REPEAT),
            onFail = config.optString(KEY_ON_FAIL, ON_FAIL_NONE).ifBlank { ON_FAIL_NONE },
            onSuccess = config.optString(KEY_ON_SUCCESS, ON_SUCCESS_NONE).ifBlank { ON_SUCCESS_NONE },
            branchStep = config.optInt(KEY_BRANCH_STEP, 1).coerceIn(0, MAX_STEP)
        )
    }
}

// ---------------------------------------------------------------------------
// 小工具与常量
// ---------------------------------------------------------------------------

/** 在 [min]..[max] 里取一个随机毫秒数（min 已保证不大于 max）。 */
private fun randomBetween(min: Long, max: Long): Long {
    if (max <= min) return min.coerceAtLeast(0L)
    return min.coerceAtLeast(0L) + Random.Default.nextLong(max - min.coerceAtLeast(0L) + 1L)
}

/** 毫秒转成人话：整秒就用秒，否则用毫秒。 */
private fun describeMs(ms: Long): String =
    if (ms >= 1000L && ms % 1000L == 0L) (ms / 1000L).toString() + "s" else ms.toString() + "ms"

/** 区间转人话。 */
private fun describeRange(min: Long, max: Long): String = when {
    max <= 0L -> "无延迟"
    min.coerceAtMost(max) == max -> max.toString() + "ms"
    else -> min.coerceAtMost(max).toString() + "–" + max + "ms"
}

private const val KEY_DELAY_MIN = "delayMin"
private const val KEY_DELAY_MAX = "delayMax"
private const val KEY_TIMEOUT = "timeoutMs"
private const val KEY_REPEAT = "repeatTimes"
private const val KEY_ON_FAIL = "onFail"
private const val KEY_ON_SUCCESS = "onSuccess"
private const val KEY_BRANCH_STEP = "branchStep"
private const val KEY_STEP = "step"

private const val ON_FAIL_NONE = "none"
private const val ON_FAIL_STOP = "stop"
private const val ON_FAIL_PAUSE = "pause"
private const val ON_FAIL_GOTO = "goto"
private const val ON_SUCCESS_NONE = "none"
private const val ON_SUCCESS_GOTO = "goto"

private const val MAX_DELAY_MS = 600_000L
private const val MAX_TIMEOUT_MS = 3_600_000L
private const val MAX_REPEAT = 100
private const val MAX_STEP = 10_000

/** 超过这个时长就写一条日志，免得用户以为卡死了。 */
private const val LONG_DELAY_LOG_MS = 2_000L
