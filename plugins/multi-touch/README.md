# multi-touch —— 多点手势

多指同时按下、保持与释放，最多十个点。

- 入口类：`com.autotapper.plugin.multitouch.MultiTouchPlugin`
- 需要的能力：`SHIZUKU`、`ACCESSIBILITY`
- 状态：**骨架**。`stepTypes()` 目前返回空列表，装上后不会新增任何步骤类型，主程序行为保持不变。
- 待实现：最多 10 个触点同时按下，可分别设置保持时长与释放顺序。

实现方式见仓库根目录 README 的「怎么加一个插件」，或直接抄 `template/new-plugin/`。
