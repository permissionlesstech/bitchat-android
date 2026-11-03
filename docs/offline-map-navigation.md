# 離線地圖與避難所導航

「防災避難所」使用 Agus Maps／CoMaps 原生地圖引擎，在 App 內顯示台灣離線地圖、目前位置與附近避難所。點選避難所標記後可查看資訊並開始離線道路導航。

## 地圖與避難所資料

- `flutter_ui/assets/maps/`：CoMaps `World.mwm`、`WorldCoasts.mwm`、`Taiwan.mwm` 與 ICU 資料。
- `flutter_ui/assets/comaps_data/`：CoMaps 繪圖樣式、標籤、交通與分類等離線資源。
- `flutter_ui/assets/data/shelters_tw.json`：避難所座標快照。
- 地圖支援縮放、拖曳、重新定位，以及輸入數量控制地圖上顯示的附近避難所。

`Taiwan.mwm` 必須包含 CoMaps 可用於車輛路由的路網資料。地圖可以顯示，不代表該區域一定有可導航路線；若路網缺漏或起終點無法連通，App 會顯示導航失敗訊息，不會以直線假裝成道路路線。

### Taiwan-Offline-Map-Navigation-20261021 整合狀態

此資料包（2026-10-21）不含導航演算法、Dart／Kotlin 程式碼或原生路由函式庫。App 的導航仍由 Agus Maps 封裝的 CoMaps 原生路由引擎執行；資料包不能單獨替換導航引擎。

- 資料包的 `Taiwan.mwm`、`World.mwm`、`WorldCoasts.mwm` 已放在 `flutter_ui/assets/maps/`，SHA-256 與資料包一致。`Taiwan.mwm` 是路由道路資料的來源。
- 資料包的五個 `styles/*.bin` 已放在 `flutter_ui/assets/comaps_data/styles/`，且與資料包一致；`extractDataFiles()` 會將這些樣式解壓至原生引擎可讀取的資料目錄。
- 資料包的 `countries.txt` 標示資料版本 `201101`，而 App 的 CoMaps 資產及地圖註冊使用版本 `260106`。資料包的 `countries.txt` 與 `packed_polygons.bin` 也不等同於目前引擎資產，因此不應單獨覆蓋；索引、地圖和原生引擎必須使用相容版本，否則可能使地圖註冊或載入失敗。
- 資料包的日期不是道路資料完整性或新鮮度的證明。要進一步補足道路、橋隧、限制與連通性，需以同一 CoMaps 版本重新產生 MWM 及配套索引，再驗證路線計算；只增加繪圖樣式或更換區域索引無法補上路網。

已整合地圖與樣式的 SHA-256（可用來核對 APK 內資產）：

| 資產 | SHA-256 |
|---|---|
| `Taiwan.mwm` | `5bef07dabb4258d5b109023ee08aa43184ba4f494fc046130763761f1c77ee88` |
| `World.mwm` | `06bf64f2ba5288c74f52f092da611284eb2107612c220d523d029386ee608885` |
| `WorldCoasts.mwm` | `469493d50372b2ed2daeabbe6156e0fb4c6507830e179ddea0de84e919809a73` |
| `drules_proto.bin` | `defce1e24b8972d78f513704af2e882dc353ff2d4be113b1c7506ade4cc35d7e` |
| `drules_proto_clear.bin` | `7b7cc4e48c3e6d7c034e34bb55c0540d94cea2a1e25b016dab92800992418954` |
| `drules_proto_dark.bin` | `d7c552aac4218e6e6e3b356a794125d3d8aca4f4514755532238dbb4ff804935` |
| `drules_proto_vehicle_clear.bin` | `fa5a42c74535260201278ccbb009b8a6550075e7d9000168fbc61f17569d6c0d` |
| `drules_proto_vehicle_dark.bin` | `a3151cbe6775a0528a42ddbcd85700eac434a32d3bbc0141089334ea31fbaa45` |

## 離線導航流程

導航由固定版本的 Agus Maps 原生 CoMaps routing engine 計算與繪製，Flutter 不自行推算直線路徑：

1. 在避難所資訊中選擇「開始導航」。
2. Flutter 將目前 Geolocator 位置傳給原生 CoMaps，設定車輛路由的起點與避難所目的地。
3. 原生引擎使用本機 MWM 路網計算路線；建置完成後開始跟隨路線。
4. 導航面板顯示轉向、道路名稱、剩餘距離與預估時間；持續定位會更新原生引擎狀態。
5. 「結束導航」只關閉路線與定位更新，不重新建立地圖 Surface。

定位權限、裝置定位服務與區域路網資料都是必要條件。定位中斷、路線無效、無法連通或計算逾時時，畫面會明確提示錯誤。

## 原生導航建置

`flutter_ui/pubspec.yaml` 固定 Agus Maps 原始碼版本
`bf20d1e7460fb08e5702514881d76cfa047caa00`。該版本提供 CoMaps 路由 API；專案的
`tools/reproducible-builds/patches/agus-navigation-location.patch` 額外橋接 Flutter
定位至 CoMaps `Framework::OnLocationUpdate()`。

取得 Git 相依套件後，執行 `tools/reproducible-builds/apply-pub-cache-patches.sh`
會將定位 patch 及原生語系符號相容 patch 套用到符合固定 commit 的 Pub cache
checkout。當預編譯 Agus library 沒有 `nativeSetLocale` JNI 符號時，插件會記錄警告並
沿用預設語系，不應因此在登入前中止 Flutter plugin 註冊。這個相容處理只保障 App
啟動；checkout 仍必須為 Agus contributor build，且每個 APK ABI 都須匯出導航與定位
FFI 符號，否則離線路線與定位跟隨無法使用。只有 Dart 套件解析成功並不足以提供導航功能。

## 驗證

- Flutter widget 測試驗證避難所資訊與「開始導航」入口。
- 原生路線計算及轉向更新需使用包含路網資料的 MWM 和實機定位驗證；單元測試或地圖畫面可載入，不能單獨證明實機導航可用。
