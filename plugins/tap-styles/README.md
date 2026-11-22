# tap-styles —— 高级点击方式

长按、连击、按压时长与点击频率的细分玩法。

- 入口类：`com.autotapper.plugin.tapstyles.TapStylesPlugin`
- 需要的能力：`SHIZUKU`、`ACCESSIBILITY`
- 状态：**骨架**。`stepTypes()` 目前返回空列表，装上后不会新增任何步骤类型，主程序行为保持不变。
- 待实现：长按按住时长、连击次数、连击间隔、按下抬起分步。

实现方式见仓库根目录 README 的「怎么加一个插件」，或直接抄 `template/new-plugin/`。
