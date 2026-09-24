# 安卓注音九宮格輸入法 (Android BOPOMOFO T9) 專案交接與進度備忘錄

> 本文件更新於 2026-09-24（版本 v1.6.0，versionCode: 10）。  
> 目的：記錄目前系統核心架構、關鍵演算法與維護手冊。

---

## 📌 一、專案基本資訊與環境

* **GitHub 倉庫**：`https://github.com/korit1201-tech/android-BOPOMOFO-t9.git`
* **主分支**：`main`
* **最新 Release**：`v1.6.0`（Git Tag: `v1.6.0`）
* **開發環境配置**：
  * **Java JBR**：`C:\Program Files\Android\Android Studio\jbr` 或 Linux OpenJDK 17
  * **Android SDK**：`C:\Users\Korit\AppData\Local\Android\Sdk` 或 Linux `/data/android-sdk`
  * **ADB 工具**：`platform-tools/adb`
  * **目標手機裝置代碼**：`3B1F4RE5MS13JZ5Z`
  * **目標 SDK**：CompileSdk 34 / MinSdk 24 / TargetSdk 34
  * **支援 ABI**：`arm64-v8a`, `armeabi-v7a`

---

## 🧱 二、核心架構與重要程式碼檔案

```text
app/src/main/
├── AndroidManifest.xml                  # 宣告 InputMethodService、權限 (VIBRATE, INTERNET)
├── assets/
│   └── dict_tw.txt                      # libchewing 官方 16 萬詞庫 (tsi.csv + word.csv，格式：詞\t注音\t權重)
├── java/com/bopomofo/t9ime/
│   ├── MainActivity.kt                  # 設定引導、個人詞庫 SAF 匯入匯出、按鍵震動開關與滑桿強度調整
│   ├── ZhuyinInputMethodService.kt      # 輸入法核心生命週期、按鍵事件分發、View Pool 候選字與音節重用、震動回饋
│   ├── engine/
│   │   ├── SyllableManager.kt           # 教育部 429 個合法注音音節圖管理、正向按鍵反查、音節歷史頻率排序
│   │   ├── KeyMapping.kt                # 12 鍵注音鍵位映射表、零韻母口語容錯（ㄕㄜㄇㄜ=什麼）規則
│   │   ├── TrieDictionary.kt            # 前綴樹 (Trie) 詞典引擎、階梯式 exact 與 prefix 分層搜尋、容錯分級
│   │   ├── ZhuyinT9Engine.kt            # T9 解碼調度器、階梯式候選詞排序、Unigram DP 全域分詞與前綴回退保底
│   │   ├── UserDictionaryManager.kt     # 本機學習詞庫管理器（記憶體計數、2.5 秒防抖延遲寫檔）
│   │   ├── ChineseConverter.kt          # 繁簡字元轉換器
│   │   └── GoogleHandwritingRecognizer.kt# Google ML Kit 端側手寫辨識引擎 (zh-Hant)
│   └── ui/
│       ├── SwipeKeyButton.kt            # 自訂按鍵（四向十字指示盤浮層、長拉拖動縮放高亮動畫、微觸反饋）
│       └── HandwritingCanvasView.kt     # 手寫畫布（手寫筆跡平滑繪製、自動定時辨識）
└── res/
    ├── layout/keyboard_view.xml         # 主鍵盤 XML（包含候選列、高度把手、12鍵、26鍵、手寫畫布）
    ├── layout/layout_swipe_preview.xml  # 四向十字羅盤拖選預覽浮層
    ├── layout/activity_main.xml         # 設定頁面佈局（震動開關、SeekBar、詞庫管理）
    └── values/colors.xml, strings.xml
```

---

## ✨ 三、v1.4.0 重大里程碑與真實功能清單

1. **四向十字羅盤拖選手勢與視覺動畫 (Compass Drag-to-Select)**：
   - 長按拖曳時按鍵上方即時彈出十字指示盤，手指移動時目標字元放大 1.35x 並亮起鮮明藍色圓圈，字體反白；放手瞬間文字即時上屏。
   - 數字模式支援全字母四向拖選（Key 7 支援 `s`，Key 9 支援 `z`）；注音模式支援所有注音符號四向拖選直出。
2. **libchewing 官方 16 萬繁體詞庫全量導入**：
   - 導入新酷音官方詞庫，包含 16 萬詞組與 2.6 萬單字，客觀權重。
   - 支援台灣常見口語縮讀容錯（如 `ㄕㄜㄇㄜ` = `什麼`、`ㄓㄜㄇㄜ` = `這麼`）。
3. **階梯式候選詞排序 (Tiered Candidate Ranking)**：
   - Tier 1（完全匹配）：個人常選詞穩居第 1 位藍色高亮。
   - Tier 2（一口氣長句預測）：DP 分詞合成中文句子，無單詞匹配時置頂排第 1 位藍色，預覽即時呈現中文。
   - Tier 3（前綴預測詞）：延伸長詞排在最後，絕不越級搶位。
4. **libchewing 接續聯想詞與零磁碟重複讀取**：
   - 單次遍歷字典構建記憶體索引 `nextWordMap`，查詞 $O(1)$。
   - `UserDictionaryManager` 採用 2.5 秒防抖寫檔，打字全程純記憶體極速運作。
5. **設定頁面專屬按鍵震動開關與即時強度試聽滑桿**：
   - 獨立開關與 SeekBar（5 ms ~ 100 ms），滑動即試聽震感，徹底解決系統偵測失效問題。
6. **版面細節優化**：
   - QWERTY 移除 9 鍵按鈕，空白鍵回歸純空格。
   - 中文模式下原本右側 `@` 位置改為換行鍵 `↵`。
   - 中英切換時 26 鍵 QWERTY 優先顯示；數字盤整合 9 鍵英文。
