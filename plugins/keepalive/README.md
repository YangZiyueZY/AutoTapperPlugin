# keepalive —— 后台保活

降低被系统回收的概率，并如实说明手段。

- 入口类：`com.autotapper.plugin.keepalive.KeepalivePlugin`
- 需要的能力：无
- 状态：**骨架**。`stepTypes()` 目前返回空列表，装上后不会新增任何步骤类型，主程序行为保持不变。
- 待实现：保活手段（前台服务/定时唤醒）、心跳间隔、失败告警。

实现方式见仓库根目录 README 的「怎么加一个插件」，或直接抄 `template/new-plugin/`。
