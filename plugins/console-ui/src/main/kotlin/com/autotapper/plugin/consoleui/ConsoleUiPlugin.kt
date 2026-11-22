package com.autotapper.plugin.consoleui

import com.example.autotapper.plugin.AutoTapperPlugin
import com.example.autotapper.plugin.PluginApi
import com.example.autotapper.plugin.PluginStepType

/**
 * 控制台自定义 —— 往控制台里加自己的格子。
 *
 * 骨架阶段只保证「能被宿主正确加载、并且不改变任何现有行为」。
 */
class ConsoleUiPlugin : AutoTapperPlugin {

    override val id: String = "console-ui"

    override val name: String = "控制台自定义"

    override val version: String = "0.1.0"

    /** 必须等于主程序的 PluginApi.VERSION。 */
    override val apiVersion: Int = PluginApi.VERSION

    /** 主程序 versionCode 低于这个值时不加载。骨架版本不依赖任何新宿主能力，保持 1。 */
    override val minHostVersionCode: Int = 1

    /**
     * TODO 待实现 —— 格子图标、格子文字、点击后执行的动作、是否需要确认。
     * 骨架阶段返回空列表：主程序装了这个插件也不会多出任何步骤类型。
     */
    override fun stepTypes(): List<PluginStepType> = emptyList()
}
