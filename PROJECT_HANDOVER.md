# 安卓注音九宮格輸入法 (Android BOPOMOFO T9) 專案交接與進度備忘錄

> 本文件建立於 2026-09-22（版本 v1.1.0，Commit: `ac42dbc`）。  
> 目的：便於下次直接在 `C:\Users\Korit\ai\ime` 目錄無縫繼續開發與除錯。

---

## 📌 一、專案基本資訊與環境

* **GitHub 倉庫**：`https://github.com/korit1201-tech/android-BOPOMOFO-t9.git`
* **主分支**：`main`
* **最新 Release**：`v1.1.0`（Git Tag: `v1.1.0`）
* **開發環境配置**：
  * **Java JBR**：`C:\Program Files\Android\Android Studio\jbr`
  * **Android SDK**：`C:\Users\Korit\AppData\Local\Android\Sdk`
  * **ADB 工具**：`C:\Users\Korit\AppData\Local\Android\Sdk\platform-tools\adb.exe`
  * **目標手機裝置代碼**：`3B1F4RE5MS13JZ5Z`
  * **目標 SDK**：CompileSdk 34 / MinSdk 24 / TargetSdk 34
  * **支援 ABI**：`arm64-v8a`, `armeabi-v7a`

---

## 🧱 二、核心架構與重要程式碼檔案

```text
app/src/main/
├── AndroidManifest.xml                  # 宣告 InputMethodService、權限 (VIBRATE, INTERNET)
├── assets/
│   └── dict_tw.txt                      # 台灣在地詞庫 (55,000+ 詞，格式：詞\t注音\t權重)
├── java/com/bopomofo/t9ime/
│   ├── MainActivity.kt                  # 啟動引導頁面（啟用輸入法、設為預設）
│   ├── ZhuyinInputMethodService.kt      # 輸入法核心生命週期、按鍵事件分發、實體鍵盤監聽
│   ├── engine/
│   │   ├── KeyMapping.kt                # 12 鍵注音鍵位映射表、發音容錯（ㄣ/ㄥ等）規則
│   │   ├── TrieDictionary.kt            # 前綴樹 (Trie) 詞典引擎、容錯與前綴模糊查詢
│   │   ├── ZhuyinT9Engine.kt            # T9 解碼調度器、聲調切換、分詞、雙字接續預測 (N-gram)
│   │   ├── ChineseConverter.kt          # 繁簡字元轉換器
│   │   └── GoogleHandwritingRecognizer.kt# Google ML Kit 端側手寫辨識引擎 (zh-Hant)
│   └── ui/
│       ├── SwipeKeyButton.kt            # 自訂按鍵（支援單擊 Tap、4 向滑動 Swipe、長按快選）
│       └── HandwritingCanvasView.kt     # 手寫畫布（手寫筆跡平滑繪製、自動定時辨識）
└── res/
    ├── layout/keyboard_view.xml         # 主鍵盤 XML（包含候選列、高度把手、12鍵、26鍵、手寫畫布）
    └── values/colors.xml, strings.xml
```

---

## ✨ 三、目前已完成的重要功能里程碑 (v1.1.0)

1. **經典 12 鍵（4×3）九宮格注音**：
   - K1 ~ K12 佈局，支援單擊按鍵、4 向滑動直出注音、長按注音精確選單。
   - K11 聲調鍵循環切換（ˇ → ˋ → ˊ → ˙ → 空白）。
   - 左側注音組合候選列即時回顯（如 `ㄅㄚˊ`），候選詞嚴格依聲調精準過濾。
2. **多模式整合**：
   - **數字模式**：左側 5 格直出 `+` `-` `*` `/` `=`（長按展開進階符號），中央鍵位為 `1~9`、`.`、`0`、`#`。
   - **英文 26 鍵 (QWERTY)**：頂部專屬符號列（`+ - * / = ( ) @ _ &`），字母鍵長按直出數字與標點。
   - **英文 9 鍵**：Multi-tap 字母輪替 + 長按彈出直出選單 + 自動隱藏右側重複 `@` 鍵。
   - **Google ML Kit 高精度手寫**：空白鍵左右滑動切換「繁」「簡」「手」，離線端側推論。
   - **實體鍵盤支援**：標準大千注音佈局、單按 Shift 切換中英、數字鍵選字、空白鍵確認。
3. **鍵盤高度自由拉伸（Drag-to-Resize）**：
   - 候選列下方把手可隨意上下拖動（180dp ~ 380dp），自動持久化儲存設定。
4. **Release 混淆極限瘦身**：
   - 啟用 R8 ProGuard 混淆與資源壓縮，安裝包僅 **22.6 MB**。

---

## 🔍 四、深入診斷：「輸入容易有錯誤」的根本原因剖析

使用者反饋：**「目前覺得他輸入還是容易有錯誤」**。  
經分析，T9 12 鍵注音打字錯誤通常源於以下五大核心瓶頸：

### 1. 九宮格歧義性與音節邊界缺乏語法驗證（Phonotactic Constraints）
* **問題現況**：九宮格每個鍵有 3~4 個注音符號。如果使用者連按 3 個鍵，可能有高達 $3 \times 3 \times 4 = 36$ 種拼音排列。目前引擎只要 Trie 裡面能湊出任何詞就會顯示，但中文注音音節有極為嚴格的「聲母 + 介音 + 韻母」結構。
* **改進點**：引入「中文注音合法音節驗證表（約 411 個合法音節）」。在九宮格組合生成階段，直接剪枝（Prune）掉所有不可能拼成的注音組合（例如 `ㄅ+ㄉ`、`ㄍ+ㄐ` 根本不可能拼在一起），歧義度會瞬間下降 70%！

### 2. 單字與高頻日常詞的權重排序失衡（Ranking Weight）
* **問題現況**：目前詞庫雖然有 55,000+ 筆，但部分冷門詞或長詞的權重（Weight）過高，壓過了超高頻核心常用字（例如：`的`、`是`、`在`、`有`、`我`、`你`、`他`、`了`、`就`、`也`、`這`、`要`、`說`）。
* **改進點**：建立一組 **「台灣前 500 大核心極高頻字詞加權清單」**，賦予其最高優先級加權，確保單擊一個音節時，最常用的字永遠排在第 1 候選位。

### 3. 滑動手勢（Swipe）與點擊（Tap）的誤觸閥值
* **問題現況**：`SwipeKeyButton.kt` 中：
  ```kotlin
  private val SWIPE_DISTANCE_THRESHOLD = 40f // 40px
  private val LONG_PRESS_TIMEOUT = 350L      // 350ms
  ```
  在快速打字時，手指點擊鍵盤往往會有輕微位移。如果位移超過 40px，系統會誤判成「滑動」，導致輸出非預期的單個注音符號，進而打斷了整串 T9 連續拼音！
* **改進點**：
  - 加大滑動距離門檻（例如由 `40f` 提高至 `55f ~ 60f`），避免快打誤觸。
  - 增加滑動速度（Velocity）判斷，唯有「快劃」才視為滑動，普通的「點按後手指抬起微滑」仍視為點擊。

### 4. 貪婪斷詞（Greedy Segment）在長句時容易選錯分詞切點
* **問題現況**：`findBestSentence` 採用貪心演算法（從長到短比對）。若句中某個詞切錯，會導致後面的按鍵全盤皆錯。
* **改進點**：改用簡易的動態規劃（DP）或 Viterbi 演算法，以整句詞頻乘積（或 Log 加總）求最佳全域斷詞路徑。

### 5. 發音容錯過度寬鬆（Over-tolerance）
* **問題現況**：目前 `KeyMapping.getTolerantSequences` 對 `ㄣ/ㄥ`、`ㄢ/ㄤ`、`ㄓ/ㄗ`、`ㄔ/ㄘ`、`ㄕ/ㄙ` 自動雙向全展開，如果打字本來就很標準，容錯詞會混在候選列前面干擾。
* **改進點**：嚴格限制容錯詞的權重衰減（降權至 0.6 或排在標準拼音候選之後），唯有標準拼音找不到候選時才提升容錯詞順位。

---

## 📋 五、下次可直接執行的改造待辦清單 (TODO)

- [ ] **Task 1：調優觸控靈敏度防誤觸**
  - 調整 `SwipeKeyButton.kt` 的 `SWIPE_DISTANCE_THRESHOLD` 為 `55f`。
  - 確保快速連點打字時，絕不因手指微偏而誤判為 Swipe。
- [ ] **Task 2：引入注音合法音節表過濾器 (Phonotactic Validator)**
  - 建立台灣 411 個標準合法注音音節白名單（如 `ㄅㄚ`、`ㄉㄚ` 合法；`ㄅㄉ`、`ㄍㄐ` 非法）。
  - 在 T9 拼音展開時直接剔除非法音節。
- [ ] **Task 3：核心極高頻字詞權重調優 (Frequency Boosting)**
  - 精準加權「常用單字 500 個」與「日常生活高頻詞彙」。
  - 確保輸入單鍵或雙鍵時，第一候選字符合直覺。
- [ ] **Task 4：動態規劃（DP）最佳斷詞取代貪婪匹配**
  - 長句子輸入時依照全域最高機率組合分詞，減少斷錯句情況。
- [ ] **Task 5：容錯候選詞降權排序**
  - 將容錯產生的詞彙權重打 6 折，確保精確符合者優先。

---

## 🛠️ 六、常用開發與除錯指令速查

```powershell
# 1. 切換目錄
cd C:\Users\Korit\ai\ime

# 2. 設定 Java 17 環境
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

# 3. 編譯 Debug APK
./gradlew assembleDebug

# 4. 編譯 Release APK (已開啟混淆壓縮)
./gradlew assembleRelease

# 5. 安裝到已連線手機
& "C:\Users\Korit\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk

# 6. 查看即時 Logcat 日誌 (過濾輸入法標籤)
& "C:\Users\Korit\AppData\Local\Android\Sdk\platform-tools\adb.exe" logcat -s BopomofoIME

# 7. Git 快速狀態與提交
git status
git add .
git commit -m "feat/fix: <說明>"
git push origin main
```
