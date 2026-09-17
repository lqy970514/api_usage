# 套餐用量（PlanUsage）

一个**架构极简**的 Android 用量看板：填入 OpenCode Go 与 Command Code GOAT 的 API Key，
点「刷新」即可查看 **5 小时 / 周 / 月度** 三个窗口的已用额度与重置倒计时。
显示逻辑对齐 cc-switch 的 Token Plan 面板（三档窗口 + 百分比进度条 + 重置倒计时），
但界面按手机端重做：单列卡片、大点击区域、Key 折叠显示、拇指可达的刷新按钮。

数据来源与解析口径完全依据 `opencode-go-command-code-goat-usage.md`。

## 架构（单模块，无第三方网络库）

```
app/src/main/java/com/dsh/planusage/
├── MainActivity.kt   界面：Scaffold + 两张服务商卡片 + 自绘进度条（Compose）
├── UsageApi.kt       网络层：HttpURLConnection + 中文错误提示
├── UsageParser.kt    纯解析层：org.json 逐窗口防御性解析（不碰 Android API，可单测）
├── Models.kt         UsageWindow / ProviderSnapshot 两个数据类
├── Prefs.kt          SharedPreferences 存 Key（应用私有目录）
└── Format.kt         金额 / 百分比 / 倒计时 / 时间格式化
```

- **依赖只有**：Compose BOM、Material3、activity-compose、core-ktx、coroutines。
  没有 Retrofit / OkHttp / Hilt / Room / 导航库——两个接口、两个页面状态，用不上。
- **状态**：`AppScreen()` 里的 `remember { mutableStateOf }`，没有 ViewModel、没有仓库层。
- **线程**：`UsageApi` 内部 `withContext(Dispatchers.IO)`，UI 侧只 `launch`。
- **网络**：`HttpURLConnection` + `org.json`（Android 内置），零反射零注解处理。
- **最小 SDK 26**（Android 8.0），可直接用 `java.time`。

## 功能

| 功能 | 说明 |
|---|---|
| 两个 Key 输入框 | 分别对应 OpenCode Go 与 Command Code GOAT，输入即保存，带明文/密文切换 |
| 刷新 | 每张卡片独立刷新；顶栏按钮一次刷新两个 |
| 冷启动自动拉取 | 已保存过 Key 时自动查询一次 |
| 倒计时自走 | 每 30 秒重算一次「多久后重置」 |
| 错误提示 | 401 / 403 / 404 分别给中文解释（例如 403 = Key 有效但无 Go 订阅） |
| 深色模式 | 跟随系统 |

## 解析规则（按文档）

**OpenCode Go** — `GET https://opencode.ai/zen/go/v1/usage`，`Authorization: Bearer <key>`

- `usage.rolling` = 5 小时、`usage.weekly` = 周、`usage.monthly` = 月度，取 `percent`。
- 只有百分比，没有金额；按文档口径 `$12/5h`、`$30/周`、`$60/月` 换算成 `≈ $x / $y` 展示。
- `percent == 0` 时上游的 `resetsAt` 是占位值，**不展示倒计时**，改为提示「未开始」。
- `status == "rate-limited"` 显示「已限流」徽标。

**Command Code GOAT** — host 必须是 `api.commandcode.ai`

- `GET /alpha/billing/credits`：`credits.monthlyCredits` 是**剩余**额度；
  `windowLimits.fiveHour|weekly` 给 `used/cap/exceeded/resetAt`（`resetAt` 是**毫秒**时间戳）。
  `windowLimits` 正常在 `credits` 同级，也兼容历史响应里嵌在 `credits` 内的变体。
- `GET /alpha/billing/subscriptions`：取 `planId` 与 `currentPeriodEnd`（月度重置）。
- 月度 cap 不在响应里，用官方 CLI 的套餐硬编码表：
  `individual-go=10`、`individual-goat=70`、`individual-pro=30`、`individual-pro-v1=80`、
  `individual-provider=15`、`individual-max=150`、`individual-ultra=300`、`teams-pro=40`。
- 月度计算：`pool = max(planCap, monthlyCredits) + purchasedCredits + freeCredits`，
  `used = pool - (monthlyCredits + purchasedCredits + freeCredits)`。
- 两个接口都做**逐窗口防御性解析**：单个窗口解析失败只丢那个窗口，不整体报错。

## 构建

```powershell
# 本机已装 JDK 17 + Gradle 8.9 + Android SDK 35
cd C:\Users\1\Documents\.dshs\test\plan-usage-android

# 纯解析层单测（18 个用例，样本取自笔记里的实测响应）
gradle testReleaseUnitTest

# 出包
gradle assembleRelease --offline
```

产物：`app\build\outputs\apk\release\app-release.apk`（用 debug keystore 签名，可直接侧载安装）。

首次装到手机（USB 调试）：

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
```

### 已验证

- `app-release.apk`：`com.dsh.planusage` v1.0.0，minSdk 26 / targetSdk 35，仅 `INTERNET` 权限，
  APK Signature Scheme v2 校验通过（Android Debug 证书），`usesCleartextTraffic=false`。
- `UsageParserTest`：18 用例全绿，覆盖文档里的两份实测响应、`percent==0` 占位重置时间、
  `rate-limited`、`exceeded`、`windowLimits` 嵌套变体、旧版扁平结构、单窗口结构异常等边界。
- **未验证**：本机没有模拟器/真机，界面在真实设备上的显示与安装未做端到端验证。

## 注意

- 两个端点都是**未文档化的私有/第一方路由**，厂商随时可能改结构；本应用不保证长期可用。
- OpenCode Go 的用量端点只认 `Authorization: Bearer`，用 `x-api-key` 会 403。
- Key 存在应用私有 `SharedPreferences`（`/data/data/com.dsh.planusage/`），未做加密存储；
  仅发往 `opencode.ai` 与 `api.commandcode.ai` 两个官方域名，`usesCleartextTraffic=false` 且无任何统计/上报。
