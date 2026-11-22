# counter —— 计数

跑圈计数、条件计数与计数上限控制。

- 入口类：`com.autotapper.plugin.counter.CounterPlugin`
- 需要的能力：无
- 状态：**骨架**。`stepTypes()` 目前返回空列表，装上后不会新增任何步骤类型，主程序行为保持不变。
- 待实现：计数器名称、自增步长、重置条件、达到上限后的动作。

实现方式见仓库根目录 README 的「怎么加一个插件」，或直接抄 `template/new-plugin/`。
