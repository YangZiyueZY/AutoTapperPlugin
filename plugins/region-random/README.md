# region-random —— 区域随机

在已画好的区域里均匀随机取点。

- 入口类：`com.autotapper.plugin.regionrandom.RegionRandomPlugin`
- 需要的能力：`ACCESSIBILITY`
- 状态：**骨架**。`stepTypes()` 目前返回空列表，装上后不会新增任何步骤类型，主程序行为保持不变。
- 待实现：选择区域、取点次数、是否避开边缘、与上一次点的最小距离。

实现方式见仓库根目录 README 的「怎么加一个插件」，或直接抄 `template/new-plugin/`。
