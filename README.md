# AutoTapperPlugin

AutoTapper（自动点击器）的官方插件仓库。

主程序只保留最基础的能力——画区域、点、循环。**别的所有功能都做成这里的插件**：
长按连击、滑动、多点手势、识色识字、数字/时间对比、识 ID、计数、文本输入、响铃、按键、
录制回放、定时、日志、规则管理、控制台自定义、后台保活……共 20 个模块（现在都是骨架）。

- 插件用 Kotlin/JVM 写，**不需要 Android SDK**，编译期只依赖本仓库的 `sdk` 模块。
- 插件不画界面，只声明要哪些字段，主程序用终末地风味的部件渲染。
- 插件仓库读一个文件：`index.json`。CI 构建完自动更新它，主程序插件面板从
  GitHub 拉这份清单。

前端读者看第一节就行，写插件的跳到第二节。

---

## 一、给用户：怎么在主程序里装插件

1. 打开 AutoTapper → **设置 → 插件**。
2. 在插件源里添加：
   ```
   https://github.com/YangZiyueZY/AutoTapperPlugin
   ```
   主程序会自动去找这个仓库的 `index.json`（默认 `main` 分支）。
3. 点「刷新」，列表里会出现仓库当前发布的插件。每个插件卡片上会写清楚：
   版本、体积、**需要哪些能力**（截图 / 联网 / 无障碍 / Shizuku / 悬浮窗）。
4. 点「安装」下载 jar。主程序会核对 `sha256`；仓库配了签名的话还会验一次
   `SHA256withRSA` 签名。哈希或签名对不上会直接拒装，别绕过这一步。
   jar 本身放在本仓库的 `dist/` 里（不是 Release 附件），所以国内可以走静态镜像下载，
   主程序会按插件源自动拼地址，你不用管。
5. 装完回到规则编辑页，点「加一步」就能看到插件提供的步骤类型了。

**关于能力授权**：插件本身拿不到任何系统权限，它只能用主程序给它的能力对象。
比如「识色」插件声明需要 `SCREENSHOT`，你没在设置里开截图，它就只会返回失败，
不会偷偷截你的屏。装之前先看清卡片上的能力清单。

**卸载**：插件面板里点卸载即可。插件自己存的配置在宿主的分区里，
卸载会一并清掉；已经写进规则里的插件步骤会显示成「插件缺失」，不会让主程序崩溃。

> 说明：仓库刚建好时 `index.json` 的 `plugins` 是空数组——因为 index.json 只由 CI 按**真实产物**回写。
> **CI 跑过一次之后，这里会自动填入 20 条记录**，刷新插件面板就能看到。
> 没联网时也可以用插件面板的「改用本地文件安装」直接装本机的 jar。

---

## 二、给作者：怎么加一个插件

### 三步

1. **复制** `template/new-plugin/` → `plugins/<你的插件 id>/`。
   目录名就是插件 id：小写字母、数字、连字符，例如 `tap-styles`、`ocr-text`。
2. **改 id**：改 `package`、类名、`override val id/name/version`。
   包名约定 `com.autotapper.plugin.<id 去掉连字符>`，类名 `<PascalName>Plugin`。
3. **实现 `stepTypes()`**：返回这个插件提供的步骤类型。每个步骤类型要给出
   `typeId` / `displayName` / `requires` / `defaultConfig()` / `editorSpec()` /
   `summarize()` / `execute()`。

完整的模板实现在 `template/new-plugin/src/main/kotlin/.../ExamplePlugin.kt`，
它实现了「等待 N 毫秒」，从 `defaultConfig` 到 `execute` 全都有，照抄改就行。

### 契约在哪

`sdk/src/main/kotlin/com/example/autotapper/plugin/Api.kt`，**与主程序逐字一致**
（只多一段文件头注释标明「由主程序同步」）。主程序那一份是运行时实现，这一份只用于编译。

改契约的流程：先改主程序 → 再原样同步到 `sdk/` → 所有插件重新编译。
两边签名有任何差异，插件运行时会直接 `NoSuchMethodError`。

### 几条硬规矩

- **不新增第三方依赖**。JSON 一律用 `org.json`（Android 内置，SDK 里是编译期桩）。
- `sdk` 必须用 `compileOnly` 引用。写成 `implementation` 会把 `Api.kt` 打进插件 jar，
  运行时和宿主的类重复定义，插件直接崩。
- 入口类必须是**公开、无参构造**的 class，且与 `index.json` 里的 `entryClass` 完全一致。
- 插件不画界面。要用户填什么，就用 `editorSpec()` 里的 `Field` 声明；
  要往控制台加格子，就实现 `ConsoleCellExtension` 并从 `extensions()` 返回。
- **不要在插件里阻塞线程**。`execute()` 跑在宿主的主线程调度器上，而 `ctx.executor.tap()` 这类
  注入回调也由主线程投递——用 `Thread.sleep` / `CountDownLatch.await` / `runBlocking` 等回调，
  必然卡到超时并 ANR。等回调用 `withTimeoutOrNull + suspendCancellableCoroutine`，节拍用 `delay()`：

  ```kotlin
  private suspend fun awaitInjection(timeoutMs: Long = 5_000L, block: ((Boolean) -> Unit) -> Unit): Boolean =
      withTimeoutOrNull(timeoutMs) {
          suspendCancellableCoroutine { cont -> block { ok -> if (cont.isActive) cont.resume(ok) } }
      } ?: false
  ```

  （`import kotlin.coroutines.resume`，不是 `kotlinx.coroutines.resume`。）
- 协程库是 `compileOnly`：运行时用宿主 APK 里那份 `kotlinx-coroutines-android`，
  **绝对不要**把它打进插件 jar。
- 数字必须是真的。界面上的频率、耗时、体积一律来自真实测量，不要凑整。

### 本地构建

```bash
# 1) 编译（需要 JDK 17）。纯 JVM 工程，不需要 Android SDK。
./gradlew build

# 2) 收产物
mkdir -p dist && cp plugins/*/build/libs/*.jar dist/

# 3) 转成 Android 能加载的 dex jar（需要 Android SDK build-tools 里的 d8）
python3 tools/dex-plugins.py --dist dist --min-api 26

# 4) 生成 / 校验清单
#    相对路径模式（CI 用这个）：downloadUrl 写成 dist/<id>-<版本>.jar，可走镜像
python3 tools/update-index.py --dist dist --url-mode relative \
    --repo YangZiyueZY/AutoTapperPlugin --version 0.1.0
python3 tools/verify-index.py --dist dist --strict

#    想生成 GitHub Release 直链（旧写法）就把模式换成 release，并补上 --tag：
# python3 tools/update-index.py --dist dist --url-mode release \
#     --tag v$(date -u +%Y%m%d) --repo YangZiyueZY/AutoTapperPlugin --version 0.1.0
```

> **第 3 步不能省。** Gradle 的 `jar` 任务产出的是 JVM 字节码（`.class`），
> 而 Android 的 `DexClassLoader` 只认 zip 里名为 `classes.dex` 的条目。
> 直接下发 `.class` 的 jar，装载时必然报 `no classes.dex`，插件一个都跑不起来。
> `dex-plugins.py` 用 d8 转换后重新打包，产物里只有 `classes.dex` 与
> `META-INF/autotapper/plugin.json` 两个条目（CI 里还会校验这一点）。

### 目录结构

```
AutoTapperPlugin/
├── index.json                 # 插件清单（CI 自动回写，手改会被覆盖）
├── dist/                      # 下发用的 dex jar + 可选 .jar.sig，**要入库**（索引按相对路径引用它）
├── settings.gradle            # 自动 include plugins/* 与 template/new-plugin
├── build.gradle               # 统一 Kotlin 版本 / JDK 17 / 可复现 jar
├── gradle.properties
├── sdk/                       # 契约：Api.kt + model/Region.kt
│   └── src/main/kotlin/
│       ├── com/example/autotapper/plugin/Api.kt    # 与主程序同步
│       ├── com/example/autotapper/model/Region.kt  # Region / TapCoord
│       └── stubs/             # android.* 与 org.json.* 编译期桩（不进 jar）
├── template/new-plugin/       # 可编译的最小插件模板
├── plugins/<id>/              # 20 个插件模块，目录名 = 插件 id
├── tools/
│   ├── update-index.py        # CI 回写 index.json（元数据表在这里，--url-mode 选写法）
│   ├── dex-plugins.py         # d8：JVM jar → Android dex jar（下发前必跑）
│   └── verify-index.py        # 校验 schema / sha256 / sizeBytes / dist 里的文件
└── .github/workflows/build.yml # 构建 → d8 → 校验 → 签名 → Release（备份）→ 相对路径回写 → 提交 dist 与 index
```

### 20 个插件模块

| 目录（= id） | 职责 | 入口类 |
| --- | --- | --- |
| `tap-styles` | 高级点击方式：长按、连击 | `com.autotapper.plugin.tapstyles.TapStylesPlugin` |
| `swipe` | 滑动 | `com.autotapper.plugin.swipe.SwipePlugin` |
| `multi-touch` | 多点手势 | `com.autotapper.plugin.multitouch.MultiTouchPlugin` |
| `region-random` | 区域随机取点 | `com.autotapper.plugin.regionrandom.RegionRandomPlugin` |
| `flow-control` | 流程控制：分支与时机 | `com.autotapper.plugin.flowcontrol.FlowControlPlugin` |
| `color` | 识色 | `com.autotapper.plugin.color.ColorPlugin` |
| `ocr-text` | 识字 | `com.autotapper.plugin.ocrtext.OcrTextPlugin` |
| `number-compare` | 数字对比 | `com.autotapper.plugin.numbercompare.NumberComparePlugin` |
| `time-compare` | 时间对比 | `com.autotapper.plugin.timecompare.TimeComparePlugin` |
| `view-id` | 识 ID | `com.autotapper.plugin.viewid.ViewIdPlugin` |
| `counter` | 计数 | `com.autotapper.plugin.counter.CounterPlugin` |
| `text-input` | 文本输入 | `com.autotapper.plugin.textinput.TextInputPlugin` |
| `bell` | 响铃 | `com.autotapper.plugin.bell.BellPlugin` |
| `keys` | 按键 | `com.autotapper.plugin.keys.KeysPlugin` |
| `recorder` | 录制回放 | `com.autotapper.plugin.recorder.RecorderPlugin` |
| `scheduler` | 定时 | `com.autotapper.plugin.scheduler.SchedulerPlugin` |
| `logs` | 实时日志 | `com.autotapper.plugin.logs.LogsPlugin` |
| `rules` | 规则管理 | `com.autotapper.plugin.rules.RulesPlugin` |
| `console-ui` | 控制台自定义 | `com.autotapper.plugin.consoleui.ConsoleUiPlugin` |
| `keepalive` | 后台保活 | `com.autotapper.plugin.keepalive.KeepalivePlugin` |

**当前状态**：17 个模块已经有真实实现，共 22 个步骤类型：
tap-styles(长按/连击/频率)、swipe、multi-touch、keys、text-input、counter、time-compare、region-random、
color、ocr-text、number-compare、view-id(点击/存在)、bell、scheduler、logs、recorder、
flow-control(流程设置/延迟/跳转 + 策略扩展点)。

剩下 3 个按计划**留作主程序内置能力**，它们的 `stepTypes()` 返回空列表，文件头写清了原因：
rules（规则列表与回收站在主程序「规则」页）、console-ui（透明度滑杆在主界面「点击配置」）、
keepalive（注入服务本身就是前台服务）。

每个插件模块下还有一个 `plugin.json`，构建时会被打进 jar 的
`META-INF/autotapper/plugin.json`。主程序「从本地文件安装」时读它确认 id 与入口类，
用户不需要手输任何东西。加新插件时**必须**照着模板写一份。

---

## 三、index.json 字段说明

`index.json` 是主程序插件面板唯一读取的文件，CI 每次构建后自动重写。

```json
{
  "schema": 1,
  "hostApiVersion": 1,
  "generatedAt": "2026-11-22T06:47:14Z",
  "plugins": []
}
```

顶层：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `schema` | int | 清单格式版本，当前固定 `1`。主程序认不出就整体拒读。 |
| `hostApiVersion` | int | 这份清单面向的宿主 API 版本，与 `PluginApi.VERSION` 一致。 |
| `generatedAt` | string | 生成时间，UTC，`2026-01-01T00:00:00Z` 形式。 |
| `plugins` | array | 插件条目列表，按 `id` 升序。 |

`plugins[]` 每一项：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | string | 插件 id，等于仓库里的目录名，小写字母/数字/连字符。 |
| `name` | string | 显示名，中文。 |
| `version` | string | 插件版本，`x.y.z`。 |
| `apiVersion` | int | 插件编译时用的 API 版本，**必须等于 `hostApiVersion`**。 |
| `minHostVersionCode` | int | 能装这个插件的最低主程序 versionCode。 |
| `entryClass` | string | 入口类全名，如 `com.autotapper.plugin.tapstyles.TapStylesPlugin`。 |
| `requires` | string[] | 需要的能力，取值 `SCREENSHOT` / `NETWORK` / `ACCESSIBILITY` / `SHIZUKU` / `OVERLAY`。 |
| `sizeBytes` | int | jar 字节数，用于下载进度与体积展示。 |
| `sha256` | string | jar 的 SHA-256（小写十六进制 64 位），下载后必校验。 |
| `downloadUrl` | string | jar 地址，两种写法之一：GitHub Release 直链，或仓库内相对路径 `dist/<id>-<版本>.jar`（见下节）。 |
| `sigUrl` | string | `.sig` 签名地址，写法跟 `downloadUrl` 一致（相对模式下是 `dist/<id>-<版本>.jar.sig`）；未配置签名密钥时为**空串**，主程序标记为未签名。 |
| `description` | string | 一句话职责，显示在插件卡片上。 |
| `changelog` | string | 这个版本的变更说明。 |

### downloadUrl 的两种写法（`--url-mode`）

`tools/update-index.py` 用 `--url-mode` 决定写哪一种，其余字段两种模式完全一样：

| | `--url-mode release`（默认，**备份渠道**） | `--url-mode relative`（**CI 用这个**） |
| --- | --- | --- |
| `downloadUrl` | `https://github.com/<owner>/<repo>/releases/download/<tag>/<id>-<版本>.jar` | `dist/<id>-<版本>.jar` |
| `sigUrl` | 上面那个地址加 `.sig` | `dist/<id>-<版本>.jar.sig` |
| 要不要 `--tag` | 要 | 不需要 |
| 主程序怎么取 | 直连 github.com / objects.githubusercontent.com | 按仓库根拼成 `https://raw.githubusercontent.com/<owner>/<repo>/<分支>/dist/...`，换成镜像域名也照样下 |

为什么默认走相对路径：

- Release 资产只挂在 GitHub 的下载域名上，jsDelivr 这类静态镜像**只镜像仓库文件、不镜像 Release 资产**，
  所以 release 模式的地址在国内经常下不动；`dist/<id>-<版本>.jar` 是仓库里的普通文件，镜像直接可用。
- 代价是 jar 必须真的提交进仓库：`.gitignore` 已放行 `dist/*.jar` 与 `dist/*.jar.sig`，
  其余中间产物（`*.part`、`build/`、`*.pem` 等）照样忽略。
  **`dist/` 与 `index.json` 必须是同一次提交**，少了任何一半都会变成死链。
- 主程序的约定（`runtime/PluginRepo.kt` 的 `RepoSource.resolve`）：`downloadUrl` 不是 `http(s)` 开头时，
  一律当成**相对仓库根**的路径拼到插件源地址后面。所以路径里不能有 `..`、不能以 `/` 开头、必须用正斜杠。
- 相对模式的目录段必须就是 `dist`（= `--dist` 那一层的名字），`verify-index.py` 会拿它和 `--dist` 对一遍。

校验：

```bash
python3 tools/verify-index.py                          # 只校验结构
python3 tools/verify-index.py --dist dist              # 结构 + 实际文件 sha256/size
python3 tools/verify-index.py --dist dist --strict     # CI 用：缺产物、多产物都算失败
```

`--strict` 在 `--dist` 的基础上多做三件事：相对路径的目录段必须是 `dist/`、
`sigUrl` 指向的 `.sig` 必须真的存在且非空、`dist/` 里不许有多余文件
（只允许 `<id>-<版本>.jar` 与 `<id>-<版本>.jar.sig`）。

---

## 四、CI 做什么

推送到 `main`（或手动 `workflow_dispatch`）后，`.github/workflows/build.yml`：

1. JDK 17 + Gradle 8.7，`gradle build` 编译 sdk / 模板 / 全部插件模块。
2. 把 20 个 jar 收进 `dist/`（先清掉上一版产物，避免旧版本 jar 越堆越多）。
3. `tools/dex-plugins.py` 用 d8 把 JVM jar 转成 dex jar，并逐个校验里面**只有**
   `classes.dex` 与 `META-INF/autotapper/plugin.json` 两个条目。
4. 有 `secrets.PLUGIN_SIGNING_KEY` 就对每个 jar 做 `SHA256withRSA` 签名，生成 `<jar>.sig`；
   **没有 secret 就跳过签名并在日志里打 warning**，`sigUrl` 留空。
5. 创建/更新 Release `v<日期>`：**备份渠道**，同一天多次推送会更新同一个 Release。
   主下发渠道不在这里（见下一条）。
6. `tools/update-index.py --url-mode relative` 回写 `index.json`
   （downloadUrl 写成 `dist/<id>-<版本>.jar`，其余字段不变）。
7. `tools/verify-index.py --dist dist --strict` 复核 schema、sha256、sizeBytes、
   `dist/` 里的文件是否齐、有没有多余文件。
8. 把 `dist/` 与 `index.json` **一起**提交回 `main`，
   commit message 是 `chore: 更新插件产物与 index.json [skip ci]`
   （带 `[skip ci]`，不会自己触发自己）。

配置签名：

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out plugin_signing_key.pem
# 把整个 PEM（含 BEGIN/END 行）粘进仓库 Secrets → PLUGIN_SIGNING_KEY
```

私钥绝不入库（`.gitignore` 已挡住 `*.pem`）。公钥随主程序发布，用于验签。

---

## 五、API 版本与兼容

- 每个插件在代码里声明 `override val apiVersion = PluginApi.VERSION`，
  `index.json` 里也带一份 `apiVersion`。
  **插件声明的 apiVersion 必须严格等于主程序的 `PluginApi.VERSION`，否则主程序拒绝加载**，
  不会「尽力兼容」——半兼容的插件崩在用户手机上比装不上更糟。
- `minHostVersionCode`：**低于主程序当前 versionCode 的插件才能装**。
  也就是说这个值写的是「宿主至少要有多新」；宿主 versionCode 比它小，插件面板会提示
  「需要升级主程序」，而不是硬装进去。
- `index.json` 顶层的 `hostApiVersion` 描述整份清单面向的宿主版本。
  主程序发现它比自己支持的 API 高，会提示升级主程序，而不是拿旧代码去跑新插件。
- 破坏性改动（删/改已有接口签名）必须抬 `PluginApi.VERSION`；
  只在末尾加可选成员、或给接口加带默认实现的方法，可以不动版本。
- 抬版本后，老插件的 `apiVersion` 会不匹配而被拒载。做法是：在 `sdk/` 保留一份
  `ApiV1.kt`（冻结）、新建 `Api.kt` 指向新版本，插件按需迁移。

---

## 许可

MIT，见 [LICENSE](LICENSE)。Copyright (c) 2026 YangZiyueZY。