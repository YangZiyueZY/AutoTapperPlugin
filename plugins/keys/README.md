# keys —— 按键

发送返回、回车、删除等全局按键。

- 入口类：`com.autotapper.plugin.keys.KeysPlugin`
- 需要的能力：`ACCESSIBILITY`
- 状态：**骨架**。`stepTypes()` 目前返回空列表，装上后不会新增任何步骤类型，主程序行为保持不变。
- 待实现：按键种类、按几次、每次之间间隔、长按。

实现方式见仓库根目录 README 的「怎么加一个插件」，或直接抄 `template/new-plugin/`。
