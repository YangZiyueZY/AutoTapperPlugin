# ocr-text —— 识字

截图后调用宿主 OCR 拿到文字并判断。

- 入口类：`com.autotapper.plugin.ocrtext.OcrTextPlugin`
- 需要的能力：`SCREENSHOT`、`NETWORK`
- 状态：**骨架**。`stepTypes()` 目前返回空列表，装上后不会新增任何步骤类型，主程序行为保持不变。
- 待实现：截取区域、关键字、包含/正则匹配、识别失败重试次数。

实现方式见仓库根目录 README 的「怎么加一个插件」，或直接抄 `template/new-plugin/`。
