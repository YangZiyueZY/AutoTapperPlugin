# rules —— 规则管理

规则的导入导出、复制与批量整理。

- 入口类：`com.autotapper.plugin.rules.RulesPlugin`
- 需要的能力：无
- 状态：**骨架**。`stepTypes()` 目前返回空列表，装上后不会新增任何步骤类型，主程序行为保持不变。
- 待实现：导出为文本/文件、从剪贴板导入、复制、重命名、批量启停。

实现方式见仓库根目录 README 的「怎么加一个插件」，或直接抄 `template/new-plugin/`。
