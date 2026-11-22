# scheduler —— 定时

按时间点或周期启动规则。

- 入口类：`com.autotapper.plugin.scheduler.SchedulerPlugin`
- 需要的能力：无
- 状态：**骨架**。`stepTypes()` 目前返回空列表，装上后不会新增任何步骤类型，主程序行为保持不变。
- 待实现：启动时间点、周期间隔、生效星期、错过时间点是否补跑。

实现方式见仓库根目录 README 的「怎么加一个插件」，或直接抄 `template/new-plugin/`。
