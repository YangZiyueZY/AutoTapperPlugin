package com.autotapper.plugin.keepalive

import com.example.autotapper.plugin.AutoTapperPlugin
import com.example.autotapper.plugin.PluginApi
import com.example.autotapper.plugin.PluginStepType

/**
 * 后台保活 —— 降低被系统回收的概率，并如实说明手段。
 *
 * 骨架阶段只保证「能被宿主正确加载、并且不改变任何现有行为」。
 */
class KeepalivePlugin : AutoTapperPlugin {

    override val id: String = "keepalive"

    override val name: String = "后台保活"

    override val version: String = "0.1.0"

    /** 必须等于主程序的 PluginApi.VERSION。 */
    override val apiVersion: Int = PluginApi.VERSION

    /** 主程序 versionCode 低于这个值时不加载。骨架版本不依赖任何新宿主能力，保持 1。 */
    override val minHostVersionCode: Int = 1

    /**
     * TODO 待实现 —— 保活手段（前台服务/定时唤醒）、心跳间隔、失败告警。
     * 骨架阶段返回空列表：主程序装了这个插件也不会多出任何步骤类型。
     */
    override fun stepTypes(): List<PluginStepType> = emptyList()
}
