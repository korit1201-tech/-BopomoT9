# BopomoT9 (ㄅ半 12 鍵注音輸入法)

一款專為 Android 設計的**輕量、流暢、純本地離線** 12 鍵（4×3 宮格）繁體注音輸入法。

> **致敬與靈感來源**：  
> 本專案的 12 鍵注音鍵盤佈局靈感與設計概念參考了 [Rizumu85/fcitx5-android-t9-phone](https://github.com/Rizumu85/fcitx5-android-t9-phone)。本專案採用純原生 Android Kotlin 獨立開發實現，具備獨立的前綴樹（Trie）注音斷詞引擎，不依賴 Fcitx5 框架。

---

## ✨ 核心特色

- 🎯 **經典 12 鍵（4×3）注音佈局**：
  - 符合單手操作與實體按鍵配置（K1 ~ K12）。
  - **聲調一鍵輪替**（K11：`ˇ` → `ˋ` → `ˊ` → `˙` → 一聲）。
  - **4 向滑動（Swipe）直出注音**：滑動按鍵即可直接輸入特定注音符號。
- ⚡ **超輕量 & 高效能**：
  - APK 大小僅約 5 MB，記憶體佔用約 30 MB，極致輕巧省電。
  - 前綴樹（Trie）毫秒級索引，支援連續長句打字與動態貪婪斷詞。
- 📚 **在地化台灣詞庫**：
  - 內建 55,000+ 筆台灣常用詞彙、在地地名、生活用語與注音讀音。
  - 支援打字後的**接續預測推薦詞（Next-word Prediction）**。
- 🔄 **繁簡自由切換**：
  - 空白鍵左右滑動即可一秒切換「繁體」與「簡體」。
  - 簡體輸出仍保留台灣在地注音邏輯，僅轉換為簡體字輸出。
- 🔠 **多元英文輸入模式**：
  - **英文 9 鍵（T9 Multi-tap）**：支援連按輪替字母（A → B → C）與滑動直出。
  - **英文 26 鍵（QWERTY）**：支援 Shift 大小寫鎖定與專屬退位鍵。
- 📳 **系統級觸覺震動回饋**：
  - 自動偵測 Android 系統震動開關，開關跟隨手機系統設定。
- 🔒 **100% 本地離線 & 隱私安全**：
  - **零網路權限（No Internet Permission）**，完全無連網能力。
  - 零第三方統計 SDK、零按鍵記錄，保護輸入隱私。

---

## 🛠️ 開發與建置

### 環境需求
- Android Studio Iguana / Jellyfish 或更新版本
- Android SDK 34 (minSdk 24)
- JDK 17 / Java 1.8+

### 本地編譯
```bash
# Windows PowerShell
$env:JAVA_HOME="<你的 Java 路徑>"
./gradlew assembleDebug
```
編譯產物位於 `app/build/outputs/apk/debug/app-debug.apk`。

---

## 📄 開源授權

本專案基於 [MIT License](LICENSE) 開源。
