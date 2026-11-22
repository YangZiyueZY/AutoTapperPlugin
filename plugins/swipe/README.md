# swipe —— 滑动

两点之间的滑动，可带贝塞尔曲线与时长抖动。

- 入口类：`com.autotapper.plugin.swipe.SwipePlugin`
- 需要的能力：`SHIZUKU`
- 状态：**骨架**。`stepTypes()` 目前返回空列表，装上后不会新增任何步骤类型，主程序行为保持不变。
- 待实现：起止点（可直接用区域随机取点）、滑动时长、滑动曲线、中途停顿。

实现方式见仓库根目录 README 的「怎么加一个插件」，或直接抄 `template/new-plugin/`。
