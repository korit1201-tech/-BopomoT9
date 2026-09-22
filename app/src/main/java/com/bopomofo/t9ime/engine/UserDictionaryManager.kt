package com.bopomofo.t9ime.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.concurrent.Executors

data class UserWordEntry(
    val word: String,
    val zhuyin: String,
    var count: Int,
    var lastUsed: Long = System.currentTimeMillis()
)

/**
 * 本機個人化詞庫管理器
 * 1. 本地記錄：每次打字選擇詞彙時自動記憶使用次數與時間
 * 2. 動態加權：依使用頻率自動為候選詞增幅權重（越常選越前排）
 * 3. 匯入匯出：支援標準 TSV 格式純文字檔案匯出備份與跨裝置匯入
 * 4. 100% 離線隱私：所有打字記憶完全保存在手機端，不聯網上傳
 */
class UserDictionaryManager private constructor(private val context: Context) {

    private val memoryDict = mutableMapOf<String, UserWordEntry>()
    private val executor = Executors.newSingleThreadExecutor()
    private val dictFile: File
        get() = File(context.filesDir, "bopomofo_user_dict.json")

    var onDictionaryChangedListener: (() -> Unit)? = null

    companion object {
        @Volatile
        private var instance: UserDictionaryManager? = null

        fun getInstance(context: Context): UserDictionaryManager {
            return instance ?: synchronized(this) {
                instance ?: UserDictionaryManager(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        loadFromFile()
    }

    private fun loadFromFile() {
        try {
            if (!dictFile.exists()) return
            val jsonStr = dictFile.readText(Charsets.UTF_8)
            val jsonArray = JSONArray(jsonStr)
            synchronized(memoryDict) {
                memoryDict.clear()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val word = obj.getString("word")
                    val zhuyin = obj.optString("zhuyin", "")
                    val count = obj.optInt("count", 1)
                    val lastUsed = obj.optLong("lastUsed", System.currentTimeMillis())
                    memoryDict[word] = UserWordEntry(word, zhuyin, count, lastUsed)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BopomofoIME", "載入個人詞庫失敗", e)
        }
    }

    private fun saveToFile() {
        try {
            val jsonArray = JSONArray()
            synchronized(memoryDict) {
                for (entry in memoryDict.values) {
                    val obj = JSONObject().apply {
                        put("word", entry.word)
                        put("zhuyin", entry.zhuyin)
                        put("count", entry.count)
                        put("lastUsed", entry.lastUsed)
                    }
                    jsonArray.put(obj)
                }
            }
            dictFile.writeText(jsonArray.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("BopomofoIME", "儲存個人詞庫失敗", e)
        }
    }

    private var savePending = false
    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val saveRunnable = Runnable {
        executor.execute {
            saveToFile()
            savePending = false
        }
    }

    /**
     * 記錄使用者選擇詞彙（每次點擊選字時調用）
     * 記憶體內即時累加 count，存檔自動防抖合併寫入，杜絕頻繁 IO
     */
    fun recordUsage(word: String, zhuyin: String = "") {
        if (word.isBlank() || word.startsWith("【")) return
        synchronized(memoryDict) {
            val entry = memoryDict.getOrPut(word) {
                UserWordEntry(word, zhuyin, 0)
            }
            entry.count += 1
            entry.lastUsed = System.currentTimeMillis()
        }

        // 防抖延遲 2.5 秒儲存，打字期間合併寫檔
        saveHandler.removeCallbacks(saveRunnable)
        saveHandler.postDelayed(saveRunnable, 2500L)
    }

    /**
     * 計算候選詞個人化增幅權重
     * 每次點選給予 6,000,000 分加權（上限 1.2 億），選過 1 次立即超越一般同音詞
     */
    fun getBoost(word: String): Int {
        val entry = synchronized(memoryDict) { memoryDict[word] } ?: return 0
        return minOf(entry.count * 6_000_000, 120_000_000)
    }

    /**
     * 取得目前記錄的所有個人詞彙列表（按使用次數倒序）
     */
    fun getAllEntries(): List<UserWordEntry> {
        return synchronized(memoryDict) {
            memoryDict.values.sortedByDescending { it.count }
        }
    }

    /**
     * 詞庫總詞數
     */
    fun getEntryCount(): Int {
        return synchronized(memoryDict) { memoryDict.size }
    }

    /**
     * 匯出個人詞庫至串流（標準 TSV 格式）
     */
    fun exportToStream(outputStream: OutputStream) {
        val writer = BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8))
        writer.write("# 安卓注音九宮格輸入法 - 個人詞庫備份檔\n")
        writer.write("# 格式：詞彙\t注音\t使用次數\n")

        val entries = getAllEntries()
        for (entry in entries) {
            writer.write("${entry.word}\t${entry.zhuyin}\t${entry.count}\n")
        }
        writer.flush()
    }

    /**
     * 從串流匯入個人詞庫（支援 TSV 格式與純詞彙文字檔）
     * @return 成功匯入/合併的詞條數量
     */
    fun importFromStream(inputStream: InputStream): Int {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        var importedCount = 0

        synchronized(memoryDict) {
            reader.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine

                val parts = trimmed.split("\t")
                val word = parts[0].trim()
                if (word.isNotEmpty()) {
                    val zhuyin = if (parts.size >= 2) parts[1].trim() else ""
                    val count = if (parts.size >= 3) parts[2].trim().toIntOrNull() ?: 5 else 5

                    val existing = memoryDict[word]
                    if (existing != null) {
                        existing.count += count
                        existing.lastUsed = System.currentTimeMillis()
                    } else {
                        memoryDict[word] = UserWordEntry(word, zhuyin, count)
                    }
                    importedCount++
                }
            }
        }

        saveToFile()
        onDictionaryChangedListener?.invoke()
        return importedCount
    }

    /**
     * 清空本機個人詞庫記錄
     */
    fun clearDictionary() {
        synchronized(memoryDict) {
            memoryDict.clear()
        }
        executor.execute {
            saveToFile()
        }
        onDictionaryChangedListener?.invoke()
    }
}
