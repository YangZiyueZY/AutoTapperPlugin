# time-compare —— 时间对比

用校准过的宿主时间做时间窗口判断。

- 入口类：`com.autotapper.plugin.timecompare.TimeComparePlugin`
- 需要的能力：无
- 状态：**骨架**。`stepTypes()` 目前返回空列表，装上后不会新增任何步骤类型，主程序行为保持不变。
- 待实现：时间点或时间窗口、是否用北京时间、在窗口内/外的分支。

实现方式见仓库根目录 README 的「怎么加一个插件」，或直接抄 `template/new-plugin/`。
