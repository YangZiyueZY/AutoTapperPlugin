# 新插件模板

这是 `template/new-plugin/`，一个最小可编译、可运行的插件。三步就能改成你自己的：

## 1. 复制

把整个 `template/new-plugin/` 目录复制成 `plugins/<你的插件 id>/`。
目录名就是插件 id，只能用小写字母、数字和连字符，例如 `tap-styles`、`ocr-text`。

## 2. 改 id

打开 `src/main/kotlin/com/autotapper/plugin/template/ExamplePlugin.kt`：

- 把 `package com.autotapper.plugin.template` 改成 `com.autotapper.plugin.<id 去掉连字符>`
  （`tap-styles` → `com.autotapper.plugin.tapstyles`）
- 把类名 `ExamplePlugin` 改成 `<PascalName>Plugin`（`tap-styles` → `TapStylesPlugin`）
- 改 `override val id` / `name` / `version`，`apiVersion` 保持 `PluginApi.VERSION`

入口类必须是 **公开的、带无参构造函数的 class**，类名与 `index.json` 里的
`entryClass` 完全一致，否则宿主找不到入口。

## 3. 实现 stepTypes()

`stepTypes()` 返回这个插件提供的全部步骤类型。每个 `PluginStepType` 需要：

| 成员 | 说明 |
| --- | --- |
| `typeId` | 全局唯一，约定 `"<插件 id>.<能力>"`，例如 `"tap-styles.longpress"` |
| `displayName` | 步骤列表里显示的名字 |
| `requires` | 用到的宿主能力，安装前会原样展示给用户 |
| `defaultConfig()` | 新建步骤时的初始 config（`org.json.JSONObject`） |
| `editorSpec(config)` | 声明式表单：返回 `ScreenSpec`，宿主用终末地风味的部件渲染，插件不画界面 |
| `summarize(config)` | 步骤列表里的一行摘要，可省略 |
| `execute(ctx, config)` | `suspend`，拿到能力后真正干活，返回 `StepResult` |

模板里的 `example.delay`（等待 N 毫秒）就是一个完整实现，照抄它改即可。

## 构建

在仓库根目录：

```bash
gradle :plugins:<你的插件 id>:jar
```

产物在 `plugins/<你的插件 id>/build/libs/<你的插件 id>-0.1.0.jar`。
推上 `main` 之后 CI 会自动构建、签名、发 Release 并回写 `index.json`。
