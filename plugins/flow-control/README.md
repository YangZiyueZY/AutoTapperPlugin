# flow-control —— 流程控制

分支、跳步、条件判断与时机控制。

- 入口类：`com.autotapper.plugin.flowcontrol.FlowControlPlugin`
- 需要的能力：无
- 状态：**骨架**。`stepTypes()` 目前返回空列表，装上后不会新增任何步骤类型，主程序行为保持不变。
- 待实现：按步骤结果分支、跳到第 N 步、终止规则、条件等待直到超时。

实现方式见仓库根目录 README 的「怎么加一个插件」，或直接抄 `template/new-plugin/`。
