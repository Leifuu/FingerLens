# FingerLens · 观纹

一个面向 Android App 视角的设备指纹采集与本地对比工具。

项目由三部分组成：

- Android 采集 APK：以普通 App 身份采集设备公开信息，导出原始 JSON 或 JSON gzip。
- Python ADB 网关：通过 ADB、BroadcastReceiver 和临时任务文件触发采集并传输结果。
- React 对比工作台：在浏览器本地解析、格式化和比较两份指纹，不上传指纹文件。

项目用于设备差异研究和采集链路验证，不生成“完美云机”，也不把差异直接解释为某个 App 的风控结论。

## 功能

### Android 采集 APK

采集 APK 的包名为 `com.life.fingerprint`，业务代码使用 Java，支持 Android API 24 至 37。

页面顶部提供“切换为横屏 / 切换为竖屏”按钮，记住上次手动选择的方向。切屏保留当前页面、采集进度和导出操作；首次使用前跟随系统方向。

采集内容包括：

- 应用清单、版本、UID、组件和公开签名信息；
- 输入设备、屏幕、分辨率、密度、刷新率和显示模式；
- Build、系统版本、App UID 可读的系统属性；
- 普通 App 身份下可见的 Binder 服务和拒绝信息；
- 进程、命名空间、挂载、分区、容量及有限的 proc/sys 信息；
- CPU、ABI、内存、GPU、EGL/OpenGL/Vulkan、摄像头和编解码能力；
- 公开音频设备能力及低延迟输出采样率/缓冲帧数、近超声与未处理音源支持声明（保留平台原始字符串，不做录放音测试）；
- App 可见 USB 设备、接口及端点描述符（保留原始整数、原序、截断与完整集合哈希，不打开设备或传输数据）；
- NFC 适配器能力及 API35+ Observe Mode 支持/启用查询（分别保存状态和值，不切换模式或读取标签）；
- API34+ NFC 天线毫米坐标及设备几何声明（有界明细和完整原序哈希，保留未提供/空列表/失败，不测量或变换坐标）；
- 传感器、电池、SIM、网络接口、IP、路由、DNS、代理、VPN、Wi-Fi 和蓝牙可见信息；
- 定位总开关、provider 状态和 GNSS 硬件/能力元数据（不读取坐标）；
- 时区、语言、区域、开发与调试状态；
- 补丁、SELinux 上下文、Capabilities、公开安全状态和系统证书；
- 本 App 临时密钥、随机挑战、公开认证链和挑战签名；
- 常见模拟器或云机线索及其证据；
- 系统、App 自身和获授权共享目录的元数据清单。

采集结果保持原始字符串、换行、数组顺序和重复项。原始文件不会因为 Web 格式化而被改写。

### Web 对比工作台

工作台使用 React、TypeScript、Vite 和 Ant Design，默认运行在 `http://127.0.0.1:5173`。

支持：

- 采集或导入任意一侧 `.json` 或 `.json.gz` 指纹即可查看数据，未采集侧显示“等待采集”；第二侧载入后自动进行双侧对比，报告导出和离线认证检查在双侧均载入后可用；
- 原始值、状态、数据类型、错误和截断信息对比；
- 按应用包名、输入设备 descriptor、Binder 服务名、目录路径等业务键进行语义对齐；
- 按模块导航、搜索、状态筛选和仅看差异；
- WebView 独立导航集中显示提供方与 User-Agent；Root 检测导航汇总已有路径、构建属性、SELinux、挂载及进程映射证据，相关条目仍保留在原模块中，不生成 Root 判定；旧 JSON 无需转换。
- 对屏幕、网络、应用清单等模块进行专门的格式化展示；
- 对数组和多行 proc/storage 文本使用表格行对比，保留顺序和重复项；
- 表格详情中的“只展示差异”；
- Root 固定路径检查提供逐路径概览，集中展示 A/B 状态、errno 和类型；完整逐字段结果可展开，原始顺序、重复路径和未知字段差异继续保留。
- USB 设备、接口和端点逐层展开，分别每页5/5/10项；保留重复、原始类型、旧报告未采集与失败状态，完整字段可展开核对。
- 双击设备值复制当前显示内容；
- 下载未改写的原始文件和导出差异报告；
- 导出独立 HTML 差异报告：包含摘要、采集条件、模块统计、A/B 原始值、来源和变化路径，默认忽略目录元数据批次及 `PackageManager.getInstalledPackages` 产生的包对比项，支持离线打开与打印；报告右下角可切换“全部对比项 / 仅值不同”，同时保留 JSON 摘要导出。
- 本地证书链、挑战签名和认证扩展检查。

导航选择“全部信息”时显示文件导入、统计和对照区域；选择具体分类时，右侧只显示该分类的结果卡片。等待文件区域中的 ADB 设备选择框、采集按钮、刷新按钮和状态标签统一为 44px 高度。

### ADB 自动采集

页面打开时读取一次 `adb devices -l`，不会自动轮询设备列表；需要时点击刷新按钮。

点击某一侧的“采集”后：

1. 浏览器向本机 Python 网关创建任务；
2. 网关使用显式 ADB 参数唤起 APK 并发送采集广播；
3. 网关按任务 UUID 读取进度和结果文件；
4. 网关校验文件大小和 SHA-256；
5. 浏览器将结果交给原有本地导入和对比流程。

ADB 只负责部署、触发、进度和文件传输，指纹正文由 APK 在普通 App UID 下采集。

## 项目结构

```text
fingerprint/
├─ app/                       # Android Gradle 工程
│  └─ app/                   # Android 应用模块
├─ ant-frontend/              # 当前 React + Ant Design 前端
├─ backend/                   # Python ADB 网关
├─ shared/
│  ├─ fingerprint.schema.json # Android 与 Web 共用的数据契约
│  ├─ trust-roots.json        # 固定的公开信任根快照
│  ├─ fingerprint-d796074a-b5d1-4d0a-a65f-798e303b3def.json # 示例指纹 A
│  └─ fingerprint-b19d58d7-8360-4803-8587-56c5d458cbbf.json # 示例指纹 B
└─ .codex/scripts/            # 构建、启动、采集和回归脚本
```

## 环境要求

- Windows 10 或更新版本；
- PowerShell 7；
- Android SDK、platform-tools（`adb`）和 Android SDK Platform 37；
- Android Studio JDK 或兼容的 JDK；
- Node.js 24；
- 可选：已安装 Google Chrome，用于 Playwright 浏览器回归。

从 GitHub 获取代码：

```powershell
git clone git@github.com:Leifuu/FingerLens.git
Set-Location FingerLens
```

首次安装前端依赖：

```powershell
Set-Location .\ant-frontend
npm ci
Set-Location ..
```

如果使用仓库提供的 Node 运行时，请将 Node 24 目录放在当前进程的 `PATH` 首部。

## 启动 Web 工作台

在仓库根目录打开两个 PowerShell 终端。在第一个终端启动本地 ADB 网关：

```powershell
python .\backend\fingerprint_backend.py --port 8765
```

网关只监听 `127.0.0.1:8765`。如果 `python` 未加入 PATH，请替换为本机 Python 解释器的完整路径。

ADB 路径在 `backend/config.json` 中设置，例如：

```json
{
  "adb_path": "D:/AndroidSDK/platform-tools/adb.exe"
}
```

默认值为 `null`。设为 `null`、空字符串或纯空白时，优先使用已有环境变量 `FINGERLENS_ADB`，未设置或为空则从 `PATH` 查找 `adb`。路径中可以有空格，不要在字符串内再加引号；相对路径以配置文件所在目录为基准。修改后重启后端生效。

原有 `--adb` 参数仍可覆盖配置；也可用 `--config 配置文件路径` 指定另一份 JSON。优先级：`--adb` → 配置文件非空 `adb_path` → `FINGERLENS_ADB` → `PATH`。无效 JSON、非字符串路径或配置文件不存在会在启动时明确报错。

在第二个终端安装依赖并启动 Vite：

```powershell
Set-Location .\ant-frontend
npm ci
npm run dev
```

打开 [http://127.0.0.1:5173](http://127.0.0.1:5173)。Vite 已将 `/api` 请求代理到 `127.0.0.1:8765`。停止服务时，在对应终端按 `Ctrl+C`。

仓库也提供 `.codex/scripts/Start-FingerprintBackend.ps1` 和 `.codex/scripts/Manage-Fingerprint.ps1` 作为自动化辅助脚本，但不依赖这些脚本即可启动 Web 工作台。

## 构建和安装 Android APK

构建 release APK 和 lint：

```powershell
Push-Location .\app
.\gradlew.bat :app:assembleRelease :app:lintRelease
Pop-Location
```

APK 输出路径：

```text
app/app/build/outputs/apk/release/app-release.apk
```

连接设备并安装：

```powershell
adb devices
adb connect <host>:<port>       # 网络 ADB 时使用
adb install -r .\app\app\build\outputs\apk\release\app-release.apk
adb shell am force-stop com.life.fingerprint
adb shell am start -n com.life.fingerprint/.MainActivity
```

在设备上完成应用内的权限准备后，使用真实界面开始采集：

```powershell
# 在设备界面点击“开始采集”，再通过应用内导出或分享保存 JSON。
```

采集必须通过 APK 的真实界面完成。不要使用 root、`pm grant`、`appops` 或 ADB shell 输出补充指纹。

## 数据格式

外层格式固定为：

```json
{
  "data": {},
  "message": "",
  "code": 0
}
```

`data.items` 中的每个采集项包含稳定 `key`、分类、名称、来源、数据类型、采集状态、原始内容、错误和截断信息。

采集状态包括：

`ok`、`denied`、`unsupported`、`error`、`timeout`、`truncated`、`skipped`。

完整 JSON Schema 见 [shared/fingerprint.schema.json](shared/fingerprint.schema.json)。

仓库中的 `shared/fingerprint-*.json` 是可直接导入 Web 工作台的示例指纹。它们保留采集时的原始内容和 `partial` 状态，仅用于演示和回归测试，不代表所有设备的完整信息。

## 采集边界

- 不使用 root、系统签名、隐藏 API 绕过、注入或未知 Binder 交易；
- 不主动扫描网络、不触发定位、不调用第三方认证服务；
- 权限被拒绝或接口不支持时继续采集，并在对应采集项中记录状态；
- 普通模块、认证、目录和整轮采集均有独立超时；
- 目录遍历有深度、条目数量和符号链接限制，只读取元数据；
- 单个原始文件和整份 JSON 有大小上限，触限会明确记录截断原因；
- 浏览器只把导入内容当作文本处理，不执行 HTML、脚本或其中的 URL；
- 本地认证检查不等同于 Play Integrity，也不执行实时撤销检查；
- 本地指纹是设备当前状态的采样，不是防篡改的远程证明。

## 测试

前端单元测试和生产构建：

```powershell
Push-Location .\ant-frontend
npm test
npm run build
Pop-Location
```

Android 构建、lint、测试 APK 和仪器测试：

```powershell
Push-Location .\app
.\gradlew.bat :app:assembleRelease :app:lintRelease
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
Pop-Location
adb install -r .\app\app\build\outputs\apk\debug\app-debug.apk
adb install -r .\app\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w -r -e class com.life.fingerprint.CollectionChecks com.life.fingerprint.test/androidx.test.runner.AndroidJUnitRunner
```

Ant Design 浏览器回归：

```powershell
node .\.codex\scripts\ant-browser-qa.mjs `
  --left '<设备A.json>' `
  --right '<设备B.json>' `
  --output './.codex/tmp/browser-check-<新目录>'
```

回归覆盖文件导入、原件字节保持、格式化、分类导航、状态筛选、表格差异过滤、复制、详情滚动固定、三种窗口宽度和外发请求检查。

## 相关文件

- [Android 数据契约](shared/fingerprint.schema.json)
- [Android 风控指纹公开来源与字段映射](docs/android-risk-fingerprint-sources.md)
- [Android 风控信号矩阵与后续缺口](docs/android-risk-signal-matrix.md)
- [公开信任根快照](shared/trust-roots.json)
- [当前前端](ant-frontend/)
- [ADB 网关](backend/fingerprint_backend.py)
- [Android 应用](app/app/)

## 许可

仓库当前未附带开源许可证。若要公开发布，请在仓库根目录补充与项目授权范围一致的 LICENSE 文件。
