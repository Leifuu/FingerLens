# Fingerprint 工程索引

- 目标：普通 Android App 单次指纹采集与双文件本地对比；禁止 root、系统签名、ADB 授权、注入、属性修改和未知 Binder 交易。ADB 仅部署、操作和导出，不把 shell 信息混入指纹。
- 环境：Windows 10.0.19044 / X64；PowerShell 7.6.5；文本 UTF-8 无 BOM。Get-Content 必须指定 -Encoding UTF8。
- Android：`app` 为 Gradle 工程根，`app/app` 为应用模块；Java 11 + JNI/C++，包名 `com.life.fingerprint`，SDK 24..37，AGP 9.4.0 / Gradle 9.6.0，NDK 28.2.13676358 / CMake 3.22.1，四 ABI。构建使用 Android Studio JBR 25，不使用异常 Oracle javapath。release 使用固定本地 Android debug 证书但 debuggable=false，只作测试发行。
- Web：当前使用 `ant-frontend`，React 19 / TypeScript / Vite 7 / Ant Design 5，中文深色主题。Web `127.0.0.1:5173` 通过 Vite proxy 调用 Python ADB backend `127.0.0.1:8765`，启动/停止用 `.codex/scripts/Start-FingerprintBackend.ps1`，数据库、CDN或大模型分析均无。捆绑Node24.19.0执行npm时还须将其目录置于进程PATH首部，避免npm子进程使用系统Node22.10。详情固定头部/关闭按钮，默认格式化；`src/display.ts`按模块分组，数组保持顺序与重复项并逐字段表格对比；屏幕拆5类，getprop仅stdout，packages/enumeration不展示固定visibility文案。原件下载保留输入字节；会话元数据单独归“采集状态”，不作为设备字段展开。值/状态不同筛选覆盖different和status。等待文件卡片内置 ADB 设备选择、进度、取消和自动导入，旧文件导入不变；设备列表首次读取后不自动轮询，可手动刷新。导航选中“全部信息”时显示导入、统计和对照区，选中分类时右侧内容仅显示 `.results-card`。
- 共享契约：`shared/fingerprint.schema.json`（1.0）；状态与原始值分开。原始文件保留字节，非法 UTF-8 用 Base64；int64 字符串或带类型对象；科学计数浮点数是合法 number，不能用 Number.isInteger 判定其原始数字类型。
- 包记录合并：Schema 1.0新增可选 `Item.installer`，完整保存原 `packages/<包名>/installer` 记录。主包 `value/source/status` 不变，安装来源保留自己的状态和身份。Web导入旧分散结构时在内存自动合并，原件下载和摘要仍基于导入原字节；重复来源冲突拒绝，孤立来源保留为独立项。
- 采集边界：单次快照；普通模块15秒、认证30秒、目录60秒、全程180秒；目录深度6/100000条，不跟随符号链接，只列元数据；单文件256KiB，JSON64MiB，gzip输入16MiB。partial 可能只是目录/文件截断或受限，不表示整个采集失败。
- 认证：仅本 App 临时密钥、挑战签名与公开证书；私钥不导出。只清理 `fp_collect_*` 前缀。强制终止时系统密钥操作可能仍未返回，未确认清理必须保留未知，后续认证先回收此前工具 alias。实时撤销与 Play Integrity 未接入。
- 公开信任根：`shared/trust-roots.json` 是2026-10-17 02:51:21 +08:00 官方端点历史缓存，2个根，原始响应SHA256 `b79beef96dd68073f1975bab90970ae85d1ae56d451a819ece6bff3d4e253ab4`。不得称为本次联网更新；缓存绑定及更新用下方脚本。
- 指定云机：用户于2026-10-21将离线地址63536更新为 `127.0.0.1:53444`；脚本默认使用新地址，测试前重新确认，不自动切换其它目标。尚无明确真机目标。
- 用户另行授权外部 `projects/fix/app/src/main/assets/fix.sh` 对53444执行配置变更，目标包为com.life.fingerprint；这不改变采集器普通App身份边界。执行前指纹d796074a、执行后指纹b19d58d7均在本工程根目录，现已按用户要求加工为包/安装来源合并版；采集原件在 `.codex/tmp/capture-53444-release-final`、`.codex/tmp/capture-53444-after-fix-20261021-01` 及 `.codex/backup-20261021-111159083-package-installer-merge`，历史采集摘要只约束这些原件。工作区 `.codex/tmp/rea-workbench/fingerprint-fix-53444-20261021-01/workflow.json` 绑定完整一次执行与后置采集，返回1不得称脚本成功，不自动重试。
- 工具、日志、临时文件位于本工程 `.codex/scripts`、`.codex/logs`、`.codex/tmp`；`.codex/backup-*` 无明确指令不得清理。开发服务必须使用可见 cmd，变更后原端口重启。
- ADB 自动采集：`backend/fingerprint_backend.py` 是仅 loopback 的 Python 网关；`backend/test_backend.py` 为9项合成安全/并发/传输测试；`.codex/scripts/adb-browser-qa.mjs` 支持真实单设备或 `--fixture` 双设备浏览器回归。后端任务结果保存在 `.codex/tmp/backend/jobs`，进度文件按 UUID 隔离，正文由 APK 采集。2026-10-21 已实测 `192.168.40.151:5555`，实测浏览器证据 `.codex/tmp/adb-browser-live-151-03/verification.json`，合成双侧证据 `.codex/tmp/adb-browser-fixture-01/verification.json`。
- UI 约定：桌面端 `Layout.Sider` 固定左侧、右侧 Layout 独立滚动；异常/通知使用 Ant Design 全局 `message`；多行 proc/storage 文本在 `display.ts` 按原始行号拆行对比；`.ant-typography.copyable-value` 双击复制，原始文件不变。最新 UI 回归脚本仍为 `.codex/scripts/ant-browser-qa.mjs`。

## 可复用脚本

- `.codex/scripts/Manage-Fingerprint.ps1`：PowerShell 7；`-Action Build/TestAndroid/Install/Run/Capture/Inspect/StartWeb/StopWeb/CheckEncoding`。支持显式 `-DeviceSerial`、`-ProjectRoot`、`-AdbPath`、`-JavaPath`、`-NodePath`、`-NpmCliPath`；端口1024..65535，等待10..240秒，采集输出拒绝覆盖。TestAndroid 构建并执行仪器测试，检查实际测试结论，finally 移除测试 APK 并恢复 release。Capture 通过真实界面控件开始，权限必须在 App 界面准备，禁止用 pm grant/appops 补权限。StopWeb 按完整 Vite 路径核身份并等待端口释放，只停止本项目服务。
- `.codex/scripts/browser-qa.mjs`：Node 24 + Playwright/Chrome；`--left <JSON> [--right <JSON>] --output <新目录> [--url <URL>] [--chrome <exe>]`。检查导入、原件字节、格式化、复制、键盘、语义、认证、三尺寸和零外网。未指定 right 时明确记为同文件自比，不能当作两台设备。
- `.codex/scripts/ant-browser-qa.mjs`：当前Ant Design界面回归，依赖ant-frontend安装的Playwright；必填`--left <JSON> --right <JSON> --output <新目录>`，可选`--url`/`--chrome`。验证差异数量、原件摘要、默认表格、屏幕5面板、滚动固定头/关闭按钮、getprop输出、过滤固定文案、键盘、离线认证、三尺寸和零外网。
- `.codex/scripts/audit-input.mjs`：`<JSON> [--gzip-output <新文件.json.gz>]`；只检查数字 token 的精度边界，可额外生成无损 gzip，不改输入，压缩输出拒绝覆盖。
- `.codex/scripts/Import-TrustSnapshot.ps1`：`-CacheDirectory <历史公开缓存> [-ProjectRoot <工程>]`；核固定官方URL/HTTP200/SHA256/PEM后写根快照，保留原查询时间，覆盖前备份。缺可信快照时保持未知，不把任意自签根当作可信根。
- `.codex/scripts/merge-package-installers.mjs`：Node24，`<文件.json...>`默认只检查；`--write --backup-directory <新目录> --verification <新JSON>` 执行原地合并并备份。与Web共用package-items.ts配对规则，保留采集值/原始数字token，幂等，拒绝重复或冲突来源、非法UTF-8、BOM、超限及并发变更。两份加工文件的前后摘要见 `.codex/tmp/package-installer-merge-verification.json`。
- browser-qa.mjs可选 `--package-key packages/<包名>`：核对单行合并、来源详情、复制/格式化与三宽度详情溢出；证据 `.codex/tmp/browser-package-merge-01`。旧/新结构同时导入也使用同一检查入口。

## 验证入口

- Web：在ant-frontend以Node24执行npm test、npm run build；Manage-Fingerprint.ps1的Build/StartWeb使用当前前端，浏览器回归使用ant-browser-qa.mjs。
- Android：`:app:assembleRelease :app:lintRelease`；Java 仪器测试 `CollectionChecks` 使用合成受控 Binder 覆盖超时、重复启动、取消和中断恢复，不冒充真实硬件证据。实际设备测试后必须恢复 release APK。
- 用户原始导入回归文件 `fingerprint-6fac6d33-961b-4aaf-9e4d-c1069ef07723.json` 不可改写；传感器 maximumRange=`3.4028235e38` 是合法浮点值。浏览器回归记录在 `.codex/tmp/browser-user-import-01/verification.json`。
- 目录 JNI 使用 O_PATH 打开祖先目录，O_NOFOLLOW 防止符号链接遍历，只有实际列举的目标目录使用 O_RDONLY。普通 App 可能只有祖先路径搜索权限，不能额外要求读取祖先目录。
