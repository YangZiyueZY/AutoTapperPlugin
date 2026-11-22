# text-input —— 文本输入

向当前焦点输入文本，支持变量替换。

- 入口类：`com.autotapper.plugin.textinput.TextInputPlugin`
- 需要的能力：`ACCESSIBILITY`
- 状态：**骨架**。`stepTypes()` 目前返回空列表，装上后不会新增任何步骤类型，主程序行为保持不变。
- 待实现：要输入的内容、是否先清空、变量占位符（如计数、时间）。

实现方式见仓库根目录 README 的「怎么加一个插件」，或直接抄 `template/new-plugin/`。
