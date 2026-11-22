# view-id —— 识ID

通过无障碍节点树按 view id 找控件并点击。

- 入口类：`com.autotapper.plugin.viewid.ViewIdPlugin`
- 需要的能力：`ACCESSIBILITY`
- 状态：**骨架**。`stepTypes()` 目前返回空列表，装上后不会新增任何步骤类型，主程序行为保持不变。
- 待实现：资源 id、文本、索引、等待出现超时、找到后点击还是只记录。

实现方式见仓库根目录 README 的「怎么加一个插件」，或直接抄 `template/new-plugin/`。
