# color —— 识色

读取区域内某点的颜色并做阈值比较。

- 入口类：`com.autotapper.plugin.color.ColorPlugin`
- 需要的能力：`SCREENSHOT`
- 状态：**骨架**。`stepTypes()` 目前返回空列表，装上后不会新增任何步骤类型，主程序行为保持不变。
- 待实现：取样坐标或区域、目标颜色、容差、匹配与不匹配时的分支。

实现方式见仓库根目录 README 的「怎么加一个插件」，或直接抄 `template/new-plugin/`。
