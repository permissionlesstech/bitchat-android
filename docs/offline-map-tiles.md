# 台灣離線瓦片原型

目前「防災避難所」入口整合台灣離線瓦片底圖、定位、避難所標記、公共設施標記及原有搜尋／分類 UI。道路導航未接入此底圖。

## 瓦片資料

- 來源：Esri exploded cache `CH_3857_Contour_OpenData`，投影 EPSG:3857，256×256 JPEG。
- 官方資料來源／授權資訊參考：內政部國土測繪中心[電子地圖資料供應服務](https://maps.nlsc.gov.tw/MbIndex_qryPage.action?fun=8)。
- `tools/offline_maps/build_taiwan_mbtiles.py` 篩選台灣本島及離島範圍，並將 XYZ 列轉為 MBTiles TMS 列。
- 產生的 `Taiwan_Offline_Tiles.mbtiles` 含 77,741 張瓦片，涵蓋 L00–L15；MBTiles 約 826 MiB。L16–L19 沒有來源瓦片，原型縮放上限因此為 L15。
- 地理範圍以多個矩形選取瓦片，並非依行政界線裁切。MBTiles 的 `bounds` 只描述整體範圍，不代表每個位置、每個層級都有瓦片。
- 來源設定檔宣告 PNG32，但實際影像為 JPEG；匯入器依影像資料驗證格式與尺寸，MBTiles metadata 記錄為 `jpg`。
- 資料包不隨 APK 打包。請將檔案放在 App 專用外部檔案目錄：

  `Android/data/com.bitchat.droid/files/offline_maps/Taiwan_Offline_Tiles.mbtiles`

  先在手機開啟離線瓦片頁，App 會建立專用資料夾；再用 Windows ADB 推送資料包：

  ```powershell
  adb push <Taiwan_Offline_Tiles.mbtiles 路徑> /sdcard/Android/data/com.bitchat.droid/files/offline_maps/Taiwan_Offline_Tiles.mbtiles
  ```

官方資料來源頁面已記錄；資料的再散布條件及正式產品署名要求仍須依該頁資訊確認。確認前只應用於受控測試，不應將資料包發布或納入 APK。

## 地圖設施資料來源、授權與更新日期

地圖使用的三份 JSON 由下列來源整理。資料更新日期、下載日期與目錄頁面的 metadata 更新日期是不同概念，請勿互相替代：

| 資料 | 來源與資料日期 | 授權／署名 |
|---|---|---|
| 防空避難所 | 警政署防空疏散避難設施資料，由 [kiang/npa.gov.tw](https://github.com/kiang/npa.gov.tw) 整理；該專案 `docs/json` 最近可查的資料更新 commit 為 2025-06-19。 | kiang 專案的 MIT 授權只適用於專案程式碼，不代表警政署原始資料授權。原始資料授權尚未確認；保留來源署名，確認前勿據此宣稱可再散布。 |
| 警察局 | [警政署資料集 #5958](https://data.gov.tw/dataset/5958)；來源頁 metadata 更新日期 2026-11-06，提供的 CSV 檔名 `PoliceAddress1_1151106.csv`。 | 政府資料開放授權條款第 1 版。 |
| 消防局 | [消防署資料集 #5969](https://data.gov.tw/dataset/5969)；來源頁 metadata 更新日期 2025-12-16；提供的 CSV 於 2026-11-17 下載。下載日不代表資料更新日。 | 政府資料開放授權條款第 1 版。 |
| 公車站 | [交通部 TDX](https://tdx.transportdata.tw/) GIS-T；提供的快照日期為 2026-10-01，但 CSV `Updatetime` 值分布於 2026-10-28 至 2026-10-30，兩者不一致，實際資料日期仍待確認。本機提供檔案於 2026-11-17 下載。 | TDX GIS-T 使用條款要求標示原資料提供機關；發布前應再次確認其現行條款。 |
| 醫療機構 | [衛福部資料集 #15393](https://data.gov.tw/dataset/15393)；提供的 ODS 檔名版本日期為 2025-12-31，來源頁 metadata 更新日期為 2025-08-29。 | 政府資料開放授權條款第 1 版。該 ODS 沒有經緯度，無法可靠產生地圖標記；目前不輸出醫院標記，亦不以地址自行推算座標。 |

### 重建設施 JSON

產生器位於 `tools/offline_maps/build_offline_map_data.py`，輸出到 `flutter_ui/assets/data/`。需先取得 kiang 專案 `docs/json` 內的縣市 GeoJSON，以及前述警察、消防、TDX 公車站與臺鐵車站來源檔。Python 3.10 以上可安裝唯一的第三方依賴：

```powershell
py -m pip install -r .\tools\offline_maps\requirements.txt
```

在 repository root 執行；先用 `--check` 驗證，不會寫檔。確認來源日期與差異後，才加 `--overwrite` 更新既有 JSON：

```powershell
py .\tools\offline_maps\build_offline_map_data.py --shelter-json-dir "D:\map-source\npa.gov.tw\docs\json" --emergency-dir "D:\map-source\enmergecy" --bus-dir "D:\map-source\bus-stop" --check
py .\tools\offline_maps\build_offline_map_data.py --shelter-json-dir "D:\map-source\npa.gov.tw\docs\json" --emergency-dir "D:\map-source\enmergecy" --bus-dir "D:\map-source\bus-stop" --overwrite
```

產生器支援各縣市 GeoJSON 欄位差異、警察 TWD97 座標轉換、重複地圖記錄去重，並清除避難所名稱尾端的 `(255.0)`。`(0, 0)` 或超出經緯度範圍的避難所座標、重複避難所記錄，以及無效消防隊座標會略過並印出筆數；其他缺少必要欄位或來源格式異常會明確失敗。執行輸出應保存於變更紀錄，並在更新前檢查資料日期及筆數差異。

以目前提供的來源執行 `--check`，產生器回報避難所 79,183 筆、公共設施 2,719 筆、公車站 131,140 筆；現有 JSON 分別為 78,947、2,718、131,140 筆。公車站 ID 集合相同，但候選輸出的緯度有 26,684 筆、經度有 32,148 筆與現有值不同；避難所候選輸出增加 236 筆。由於 TDX 日期仍不一致且來源差異尚待確認，本次只修正現有避難所 JSON 的一筆 `(255.0)` 名稱，未整批覆寫三份資產。確認並審核來源差異後，再使用 `--overwrite` 更新。

```powershell
py -m unittest tools.offline_maps.test_build_offline_map_data -v
```

## Windows 下載環境

目前 App 不會自行下載地圖瓦片；需先在電腦取得來源資料、產生 MBTiles，再透過 USB/ADB 複製到手機。準備以下工具：

- Python 3.10 或更新版本。匯入器只使用 Python 標準函式庫，不需安裝額外套件。
- Android SDK Platform-Tools（提供 `adb`）。可透過 Android Studio 的 SDK Manager 安裝，或從 [Android 官方 Platform-Tools 頁面](https://developer.android.com/tools/releases/platform-tools)下載；將 `adb.exe` 所在目錄加入 `PATH`。
- 從[內政部國土測繪中心電子地圖資料供應服務](https://maps.nlsc.gov.tw/MbIndex_qryPage.action?fun=8)依其網站提供的方式下載 `CH_3857_Contour_OpenData`，並解壓縮。下載權限、使用條款及署名要求以該網站公告為準。匯入器的 `--tile-root` 要指向包含 `L00`、`L01` 等層級目錄的資料夾（若有 `conf.xml`，也應在同一層）。

在 PowerShell 確認工具可用：

```powershell
python --version
adb version
```

若 `python` 指令不存在，可改用 Windows Python Launcher 的 `py -3` 執行下方匯入器命令。打包後的資料約 826 MiB；另外還需要保留已下載／解壓縮的來源資料空間。

## 原型行為

- `flutter_ui/lib/services/taiwan_mbtiles_source.dart` 以唯讀 SQLite 開啟 App 專用目錄中的 MBTiles。
- `flutter_ui/lib/screens/offline_tile_preview_screen.dart` 顯示台北初始視角，支援拖曳、雙指縮放、縮放按鈕、重新定位及 L10–L19 放大倍率；L16–L19 使用 L15 底圖放大，不代表有原始高解析瓦片。
- 地圖整合離線避難所與公共設施資料，顯示最近 15 處避難所及附近消防局、警察局、車站、公車站標記；分類列提供消防局、警察局、車站及公車站篩選。醫療機構 ODS 沒有座標，因此不顯示醫院標記。
- 地圖控制面板提供資料來源與授權資訊入口，列出資料集來源、已知日期差異、授權狀態及無座標的醫療資料限制。
- 拖曳時以合成平移已載入的瓦片與標記，放開後才更新地圖中心並載入新瓦片，避免每個移動事件都重建瓦片元件。
- `flutter_ui/lib/screens/shelter_screen.dart` 進入整合離線地圖並保留 RouteObserver 設定，不顯示地圖專用小助理選項。
- 舊地圖 UI 與標記測試保留於 `flutter_ui/deferred_integration/`，不屬於目前 Flutter 套件，不會編譯或顯示。
- 目前建置移除 Agus Maps／CoMaps 原生插件與地圖資產宣告，避免載入舊路線引擎；舊導航定位橋接及語系相容補丁也已從 PUB_CACHE 補丁流程移除。
- 此階段不提供路線規劃或導航。瓦片底圖已整合原有避難所與公共設施資料；正式授權與資料新鮮度確認後再發布。

## 產生與驗證資料包

在 repository root 開啟 PowerShell，將範例中的來源與輸出路徑換成實際位置，先執行 dry run 驗證來源，再產生資料包：

```powershell
python .\tools\offline_maps\build_taiwan_mbtiles.py --tile-root "D:\map-source\CH_3857_Contour_OpenData" --output "D:\map-output\Taiwan_Offline_Tiles.mbtiles" --dry-run
python .\tools\offline_maps\build_taiwan_mbtiles.py --tile-root "D:\map-source\CH_3857_Contour_OpenData" --output "D:\map-output\Taiwan_Offline_Tiles.mbtiles"
adb devices
adb push "D:\map-output\Taiwan_Offline_Tiles.mbtiles" "/sdcard/Android/data/com.bitchat.droid/files/offline_maps/Taiwan_Offline_Tiles.mbtiles"
```

產生器不會覆寫既有輸出檔。`--dry-run` 會檢查選取瓦片皆為 256×256 JPEG 並回報數量；實際產生時會另外執行 SQLite integrity check。

執行 `adb push` 前，請先在手機開啟離線瓦片頁以建立 App 專用資料夾，並確認 `adb devices` 顯示已授權的裝置。

## 後續實機檢查

使用 Debug APK 並將資料包放入上述 App 專用目錄後，確認：

1. 離線冷啟動可載入台北 L15 瓦片，無網路服務依賴。
2. 拖曳與縮放時瓦片位置穩定，縮放不超過 L15。
3. 移至來源未涵蓋的位置時不崩潰，並能辨識缺瓦片區域。
4. 檔案缺失、損壞或資料庫無法開啟時有明確錯誤與重試方式。
5. 確認資料來源授權與署名要求後，才決定是否將資料包發布給測試者。
