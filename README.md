# CARES Mesh（Android）

> [!WARNING]
> 本軟體未經外部安全審查，可能存在漏洞，也未必達成其宣稱的安全目標。請勿用於敏感情境，在完成審查前請勿依賴其安全性。本專案為在校專題，開發中。

一套離線的災害應變 mesh 應用。受災者在無任何基礎設施的情況下，透過 Bluetooth LE mesh 發布自身狀況與位置；救援者讀取這些回報，據以決定優先前往的對象。

本專案是 [bitchat-android](https://github.com/permissionlesstech/bitchat-android) 的**修改版 fork**，保留其 BLE mesh 傳輸層與二進位協定，在其上加入災害應變的應用層。**詳細的來源說明與 GPLv3 授權義務見 [NOTICE.md](NOTICE.md)。**

領域語彙以 [CONTEXT.md](CONTEXT.md) 為準（Health Report、Status、Severity 等詞在本專案有精確定義，不可混用）。

---

## 本組的修改

上游 bitchat 是一套通用的匿名通訊軟體。本專案將其改造為災害應變工具，主要改動如下：

### 應用層（新增）

| 項目 | 位置 |
|---|---|
| **Health Report 封包** — 受災者自述狀況（Status）、位置與聯絡資訊的線路格式與序列化 | `app/.../protocol/DisasterReportPacket.kt` |
| **Flutter ↔ Android 橋接** — MethodChannel／EventChannel，讓 Flutter UI 驅動原生 mesh | `app/.../flutter/BitchatFlutterChannels.kt`、`FlutterChatActivity.kt` |
| **Flutter UI 全套畫面** — 登入／註冊／信箱驗證、首頁、SOS、避難所地圖、物資、防災知識、健康回報 | `flutter_ui/lib/screens/` |
| **Firebase 整合** — 使用者驗證與健康資料儲存 | `flutter_ui/lib/services/auth_service.dart` |

### 上游元件的修改

- `mesh/MessageHandler.kt` — 加入 tagged broadcast 處理路徑（`BroadcastContentTag`）
- `mesh/BluetoothMeshService.kt`、`mesh/PacketProcessor.kt` — 接上災害回報流程
- `protocol/BinaryProtocol.kt` — 新增 `HEALTH_REPORT`（`0x30`）等訊息型別
- `service/MeshForegroundService.kt`、`service/MeshServiceHolder.kt` — 前景服務生命週期調整
- `ui/`、`MainActivity.kt` — 移除或停用不適用於本情境的上游 UI
- Gradle — 嵌入 `:flutter` 子專案、加入 google-services plugin

完整的檔案層級差異：`git diff 632ee88 main`（`632ee88` 為 fork 自上游的最後一個未修改狀態）。

---

## 建置

有兩種建置方式，產出相同：

- **主機建置**：日常開發與 Android Studio 用。需要自己裝齊工具鏈，版本必須與下方一致。
- **容器建置**：只需要 Docker，工具鏈與 CI、正式 release 完全相同。在 Windows 上跑單元測試時建議用這個（見下方）。

### 主機建置

#### 前置需求

- Android SDK (compileSdk 37、minSdk 26，後續會自動下載NDK 28.2.13676358和CMake 3.22.1)
- JDK 21
- Flutter 3.41.4（stable，Dart 3.11.1）
- Git Bash（Windows）
- 磁碟容量約15GB

#### 步驟

```bash
git clone https://github.com/dioispen/cares-mesh-app-android.git
cd cares-mesh-app-android

# 1. 取得 Flutter 相依套件
cd flutter_ui && flutter pub get --enforce-lockfile && cd ..

# 2. 修補 PUB_CACHE 中的第三方套件（每台機器做一次；清除 pub cache 後要重做）
tools/reproducible-builds/apply-pub-cache-patches.sh                                 # macOS / Linux
PUB_CACHE="$LOCALAPPDATA/Pub/Cache" tools/reproducible-builds/apply-pub-cache-patches.sh   # Windows（Git Bash）

# 3. 建置
./gradlew assembleDebug        # per-ABI + universal APK
./gradlew installDebug         # 安裝到已連接的裝置

# 測試
./gradlew test                 # Kotlin 單元測試
cd flutter_ui && flutter test  # Dart 測試
```

**為什麼要做第 2 步**：`flutter_inappwebview_android` 1.1.3 呼叫了 AGP 9 已移除的 `getDefaultProguardFile('proguard-android.txt')`。沒修補的話，連 debug 建置都會在 Gradle 設定階段失敗：

```
A problem occurred evaluating project ':flutter_inappwebview_android'.
> `getDefaultProguardFile('proguard-android.txt')` is no longer supported ...
```

pub 沒有 patch 機制，只能改 PUB_CACHE 裡的副本。腳本會比對修補前後的 SHA-256，重複執行不會重複修補，套件改版時也會直接報錯，不會默默套錯。容器建置會自動執行這一步。細節見 [docs/reproducible-builds.md](docs/reproducible-builds.md#patching-third-party-dart-packages)。

`assembleDebug` / `assembleRelease` 會產生分 ABI 的 APK（arm64、x86_64、armeabi-v7a、x86）加上一個 universal APK；`bundleRelease` 會關閉 split（由 Play Store 處理 ABI 分發）。

### 容器建置

只需要 Docker（Windows 用 Docker Desktop + WSL2）。第一次執行會建置約 9 GB 的映像檔，之後會沿用。

```bash
tools/reproducible-builds/run-in-container.sh ./gradlew testDebugUnitTest
tools/reproducible-builds/run-in-container.sh ./gradlew lint
tools/reproducible-builds/run-in-container.sh bash -c 'cd flutter_ui && flutter --no-version-check test'
```

- **Windows 上的單元測試請用容器跑。** Windows 主機上的單元測試會因為檔案鎖定出現 22–23 個與程式邏輯無關的失敗（#29），同一套 623 個測試在容器裡是 0 失敗。
- 容器會把 `flutter_ui/.android` 與 `flutter_ui/.dart_tool` 改寫成容器內的路徑。跑完後回到主機開發前，在 `flutter_ui/` 再執行一次 `flutter pub get`，Android Studio 才會恢復正常。
- 正式 release 固定走容器，流程與硬體需求見 [docs/reproducible-builds.md](docs/reproducible-builds.md)。

### Firebase

`app/google-services.json` **已納入版本控制**，clone 下來即可建置。它會被編進 APK 的字串資源，影響 release 的位元組，所以必須進版控才能重現 release；裡面的值本來就包含在每個公開的 APK 中，提交它不會多洩漏任何東西。詳見 [docs/reproducible-builds.md](docs/reproducible-builds.md#firebase-configuration)。

#### Firestore 安全規則

`firestore.rules` **已納入版本控制**，是本專案的存取控制程式碼——它決定誰能讀寫災民的健康資料、SOS、物資與認領紀錄。
它不含任何機密，內容本來就會被 Firebase 用於每一次請求。

- 規則檔：[`firestore.rules`](firestore.rules)
- Firebase CLI 設定：[`firebase.json`](firebase.json)（宣告規則檔位置，`deploy` 與 emulator 都靠它）

**任何規則變更都必須走 PR**，與其他程式碼相同（見 [CONTRIBUTING.md](CONTRIBUTING.md)）。不要直接在 Firebase 主控台編輯規則——主控台的改動不會回到 repo，下一次部署就會被覆蓋掉。

部署（需要該 Firebase 專案的權限；由專案擁有者執行）：

```bash
# 前置：安裝 Firebase CLI 並登入
npm install -g firebase-tools
firebase login

# 先看變更內容（部署是覆蓋式的，會整份取代線上規則）
git diff origin/main -- firestore.rules

# 部署（<project-id> 換成實際的 Firebase 專案 ID）
firebase deploy --only firestore:rules --project <project-id>
```

**部署前請先跑規則測試**（跑在本機 emulator 上，不會碰到線上專案；需要 Java 與 Node）：

```bash
cd firestore-tests
npm install
npm test        # 啟動 Firestore emulator 並執行 rules.test.mjs
```

測試涵蓋 `supply_items` 的欄位層級限制，以及 `supply_requests`、`supply_pledges` 的擁有者比對；其中最關鍵的一條是「使用者必須能認領**別人建立的**物資」——那是加錯授權條件最容易弄壞的地方。

規則語法錯誤會讓 `deploy` 直接失敗，不會部署出半套規則；但**語法正確不等於行為正確**，改動授權條件時請務必補上對應測試。

---

## 架構

Android（Kotlin）層擁有全部的網路、密碼學與背景服務；Flutter 層擁有絕大多數使用者可見的畫面。

```
flutter_ui/lib/screens/          使用者畫面
        │
        ├── bridge/bitchat_bridge.dart      Dart 端唯一的原生呼叫入口
        │        ▲  MethodChannel（動作）／EventChannel（事件）
        │        ▼
app/.../flutter/BitchatFlutterChannels.kt   Kotlin 端橋接
        │
        ├── mesh/BluetoothMeshService        BLE mesh 核心
        ├── protocol/BinaryProtocol          線路格式
        ├── noise/、crypto/                  Noise 握手與加密
        └── service/MeshForegroundService    背景常駐
```

### 主要套件

| 套件 | 職責 |
|---|---|
| `mesh/` | BLE mesh 核心 — `BluetoothMeshService` 協調 `PeerManager`、`FragmentManager`、`SecurityManager`、`StoreForwardManager`、`MessageHandler`、`BluetoothConnectionManager`、`PacketProcessor` |
| `protocol/` | 線路格式 — `BinaryProtocol.kt` 編解碼；`MessageType`、`BroadcastContentTag` 定義封包型別；`HealthReportPayload` 為健康回報結構 |
| `service/` | 前景服務 — `MeshForegroundService` 維持 mesh 存活；`MeshServiceHolder` 為單例存取點 |
| `crypto/`、`noise/` | X25519／Ed25519／AES-256-GCM（BouncyCastle）與 Noise Protocol 會話 |
| `net/` | 網際網路上行與 Tor／Arti |
| `flutter/` | Flutter 橋接 |
| `ui/` | Jetpack Compose 舊版 UI（多數畫面已由 Flutter 取代） |

更完整的說明見 [AGENTS.md](AGENTS.md)。

---

## 協定要點

- 封包標頭：version(1) + type(1) + TTL(1) + timestamp(8) + flags(1) + payloadLength(2 或 4)
- 最大 TTL 為 7 跳
- 訊息 >100 bytes 自動以 LZ4 壓縮
- 與上游 iOS bitchat 的二進位協定相容性由上游維持；本組新增的 `0x30` 等型別為本專案專有

**ContentTag 規則**：`MessageHandler.handleTaggedBroadcast()` 在轉交 Flutter 前已剝除 `payload[0]` 的 `BroadcastContentTag`。橋接層收到的是**未帶 tag 的原始 payload** — 不要在橋接層再次偵測或剝除 tag byte。

---

## 文件

| 文件 | 內容 |
|---|---|
| [CONTEXT.md](CONTEXT.md) | 領域語彙 — 專案用詞的權威定義 |
| [docs/PLAN.md](docs/PLAN.md) | 專題改善計畫、實驗矩陣、時程與分工 |
| [NOTICE.md](NOTICE.md) | fork 來源、GPLv3 義務、第三方元件 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 分支策略與 commit 規範 |
| [AGENTS.md](AGENTS.md) | 架構細節與開發標準 |
| [docs/adr/](docs/adr/) | 架構決策紀錄 |
| [PRIVACY_POLICY.md](PRIVACY_POLICY.md) | 隱私政策 |

---

## 分支現況

`main` 為專題的單一真相來源，所有工作分支都應由 `main` 開出。分支策略見 [CONTRIBUTING.md](CONTRIBUTING.md)。

**待處理**：`origin/penny` 有 2026-06-30 之後的 UI 修正（`login_screen`、`supply_screen`、`onboarding_screen`、`pubspec.lock`、iOS 平台設定檔等）尚未併入。這些改動與 `main` 上的同名檔案重疊，需要人工解衝突，不在 R1 的範圍內。

## 授權

本專案依 **GNU General Public License v3.0** 授權，與上游 bitchat-android 相同。完整條款見 [LICENSE.md](LICENSE.md)，來源說明與修改紀錄見 [NOTICE.md](NOTICE.md)。

> 上游 README 曾寫有「released into the public domain」，該敘述在上游於 2026-02-28 將授權由 MIT 改為 GPLv3（commit `fb2bb64`）後即已失效。本 repo 的授權以 `LICENSE.md` 為準。
