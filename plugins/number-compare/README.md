# number-compare —— 数字对比

从 OCR 结果里取出数字再比较大小。

- 入口类：`com.autotapper.plugin.numbercompare.NumberComparePlugin`
- 需要的能力：`SCREENSHOT`、`NETWORK`
- 状态：**骨架**。`stepTypes()` 目前返回空列表，装上后不会新增任何步骤类型，主程序行为保持不变。
- 待实现：取数区域、正则、比较符（> < = !=）、目标值、不满足时的分支。

实现方式见仓库根目录 README 的「怎么加一个插件」，或直接抄 `template/new-plugin/`。
