# logs —— 实时日志

把运行日志按等级过滤后实时展示。

- 入口类：`com.autotapper.plugin.logs.LogsPlugin`
- 需要的能力：无
- 状态：**骨架**。`stepTypes()` 目前返回空列表，装上后不会新增任何步骤类型，主程序行为保持不变。
- 待实现：日志等级过滤、关键字过滤、最大保留行数、导出。

实现方式见仓库根目录 README 的「怎么加一个插件」，或直接抄 `template/new-plugin/`。
