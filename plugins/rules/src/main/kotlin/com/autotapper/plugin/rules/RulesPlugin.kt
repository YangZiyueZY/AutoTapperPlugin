package com.autotapper.plugin.rules

import com.example.autotapper.plugin.AutoTapperPlugin
import com.example.autotapper.plugin.PluginApi
import com.example.autotapper.plugin.PluginStepType

/**
 * 规则管理（多开/回收站/导入导出）已经在主程序内置（RulesActivity），这里保留模块只是为了让
 * index.json 里的 20 条记录仍然成立；本插件不提供任何步骤类型与扩展点。
 */
class RulesPlugin : AutoTapperPlugin {

    override val id: String = "rules"
    override val name: String = "规则管理"
    override val version: String = "0.1.0"
    override val apiVersion: Int = PluginApi.VERSION
    override val minHostVersionCode: Int = 1

    override fun stepTypes(): List<PluginStepType> = emptyList()
}
