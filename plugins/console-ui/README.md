# console-ui —— 控制台自定义

往控制台里加自己的格子。

- 入口类：`com.autotapper.plugin.consoleui.ConsoleUiPlugin`
- 需要的能力：`OVERLAY`
- 状态：**骨架**。`stepTypes()` 目前返回空列表，装上后不会新增任何步骤类型，主程序行为保持不变。
- 待实现：格子图标、格子文字、点击后执行的动作、是否需要确认。

实现方式见仓库根目录 README 的「怎么加一个插件」，或直接抄 `template/new-plugin/`。
