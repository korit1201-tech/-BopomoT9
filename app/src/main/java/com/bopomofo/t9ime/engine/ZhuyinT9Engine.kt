package com.bopomofo.t9ime.engine

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 注音 T9 解碼、動態分詞、接續詞預測與自動上屏引擎
 */
class ZhuyinT9Engine(private val context: Context) {

    private val trie = TrieDictionary()
    private val currentKeys = mutableListOf<Int>()
    private var currentToneIndex = 0

    private var cachedCandidates: List<DictEntry> = emptyList()
    private var cachedZhuyinCombos: List<String> = emptyList()
    private var lockedZhuyinCombo: String? = null

    // 聯想詞庫 (Next-word Prediction)：記錄「上一個詞」接續「下一個推薦詞」
    private val nextWordAssociations = mutableMapOf<String, MutableList<String>>()

    companion object {
        val TONE_SYMBOLS = listOf(' ', 'ˇ', 'ˋ', 'ˊ', '˙')
    }

    init {
        loadDictionary()
        initAssociations()
    }

    private fun loadDictionary() {
        try {
            context.assets.open("dict_tw.txt").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).useLines { lines ->
                    for (line in lines) {
                        val parts = line.split("\t")
                        if (parts.size >= 3) {
                            val word = parts[0]
                            val zhuyin = parts[1]
                            val weight = parts[2].toIntOrNull() ?: 100
                            trie.insert(DictEntry(word, zhuyin, weight))
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 常用接續聯想詞表（2-gram / N-gram 語言模型）
     * 參考 rime-bopomofo-t9 / 万象語意關聯強化台灣在地高頻對話接續詞
     */
    private fun initAssociations() {
        val rules = mapOf(
            "你" to listOf("好", "在幹嘛", "在哪裡", "覺得呢", "知道嗎", "可以嗎", "有空嗎", "要不要", "要去哪", "吃飯沒", "先忙"),
            "你好" to listOf("！", "，", "請問", "我是", "早安", "晚安", "歡迎", "大家", "很高興認識你"),
            "我" to listOf("是", "在", "想", "要", "知道", "覺得", "喜歡", "今天", "現在", "可以", "到了", "出發了", "等等到", "不知道"),
            "他" to listOf("是", "說", "在", "想", "要", "知道", "今天", "去哪", "回來了沒"),
            "她" to listOf("是", "說", "在", "想", "要", "知道", "今天", "也在"),
            "我們" to listOf("一起", "要不要", "等等", "明天", "今天", "去看", "去吃", "開會", "約幾點"),
            "謝謝" to listOf("你", "您", "大家", "配合", "分享", "幫忙", "關心", "收到了", "！"),
            "貢丸" to listOf("湯", "好吃", "新竹", "麵", "米粉", "冬粉"),
            "台灣" to listOf("高鐵", "大學", "美食", "天氣", "啤酒", "銀行", "之光", "夜市", "文化"),
            "今天" to listOf("天氣", "晚上", "中午", "早上", "星期幾", "要吃什麼", "好累", "放假", "辛苦了", "好熱", "下雨"),
            "明天" to listOf("見", "早上", "晚上", "下午", "放假", "要開會", "幾點", "天氣如何"),
            "昨天" to listOf("晚上", "太累了", "忘記了", "有去", "謝謝你"),
            "現在" to listOf("在哪", "可以嗎", "出發", "幾點了", "要走嗎", "在路上"),
            "什麼" to listOf("時候", "意思", "名字", "東西", "事情", "原因", "好吃的", "地方"),
            "怎麼" to listOf("了", "樣", "辦", "去", "做", "會這樣", "聯絡", "走"),
            "可以" to listOf("嗎", "幫我", "直接", "一起", "考慮", "使用", "傳給我", "等我一下"),
            "沒問題" to listOf("！", "，我來處理", "，交給我", "馬上辦", "，晚點給您"),
            "好的" to listOf("！", "，收到", "，謝謝", "，馬上處理", "，沒問題"),
            "收到" to listOf("！", "，謝謝", "，了解", "，辛苦了"),
            "大家" to listOf("好", "早安", "晚安", "辛苦了", "注意安全", "加油"),
            "早安" to listOf("！", "，祝你有美好的一天", "，今天好冷", "，大家早"),
            "晚安" to listOf("！", "，祝好夢", "，明天見", "，早點休息"),
            "工作" to listOf("順利", "認真", "人員", "愉快", "機會", "忙碌", "進度"),
            "請" to listOf("稍等", "幫我", "過目", "參考", "查收", "進一步", "多多指教"),
            "麻煩" to listOf("你", "您", "幫我", "了", "盡快", "協助"),
            "真的" to listOf("假的", "很棒", "太好笑了", "辛苦了", "不好意思", "好吃"),
            "不好意思" to listOf("打擾了", "請問", "讓您久等了", "謝謝"),
            "辛苦" to listOf("了", "大家了", "您了", "這陣子"),
            "恭喜" to listOf("！", "發財", "老爺", "升遷", "賀喜"),
            "祝" to listOf("你", "您", "順心", "早日康復", "生日快樂", "平安順遂"),
            "生日" to listOf("快樂", "大餐", "禮物", "願望"),
            "一起" to listOf("去吃", "出發", "加油", "努力", "討論"),
            "要不要" to listOf("一起", "喝飲料", "吃宵夜", "去看電影", "先休息"),
            "吃" to listOf("飽沒", "飯了沒", "什麼", "宵夜", "早餐", "午餐", "晚餐"),
            "喝" to listOf("飲料", "咖啡", "水", "一杯", "茶"),
            "路上" to listOf("小心", "注意安全", "塞車嗎", "順風"),
            "到了" to listOf("跟我說", "再聯絡", "門口了", "捷運站"),
            "幾點" to listOf("見面", "出發", "開會", "到", "下班"),
            "哪裡" to listOf("見面", "集合", "買的", "好吃")
        )
        for ((k, v) in rules) {
            nextWordAssociations[k] = v.toMutableList()
        }
    }

    /**
     * 取得特定已上屏詞彙的接續推薦詞列表
     */
    fun getNextWordPredictions(lastWord: String): List<DictEntry> {
        val list = nextWordAssociations[lastWord] ?: emptyList()
        if (list.isNotEmpty()) {
            return list.map { DictEntry(it, "", 1000) }
        }
        // 若無完全匹配，嘗試前綴字
        for ((k, v) in nextWordAssociations) {
            if (lastWord.endsWith(k)) {
                return v.map { DictEntry(it, "", 1000) }
            }
        }
        return emptyList()
    }

    fun pressKey(keyId: Int): List<DictEntry> {
        currentKeys.add(keyId)
        currentToneIndex = 0
        lockedZhuyinCombo = null
        recalculate()
        return cachedCandidates
    }

    fun cycleTone(): Pair<Char, List<DictEntry>> {
        if (currentKeys.isEmpty()) return Pair(' ', emptyList())

        currentToneIndex = (currentToneIndex + 1) % TONE_SYMBOLS.size

        if (currentKeys.isNotEmpty() && currentKeys.last() == 11) {
            currentKeys.removeAt(currentKeys.size - 1)
        }

        if (currentToneIndex > 0) {
            currentKeys.add(11)
        }

        lockedZhuyinCombo = null
        recalculate()
        val toneChar = TONE_SYMBOLS[currentToneIndex]
        return Pair(toneChar, cachedCandidates)
    }

    fun backspace(): List<DictEntry> {
        if (currentKeys.isNotEmpty()) {
            currentKeys.removeAt(currentKeys.size - 1)
            currentToneIndex = 0
            lockedZhuyinCombo = null
        }
        recalculate()
        return cachedCandidates
    }

    fun clear() {
        currentKeys.clear()
        currentToneIndex = 0
        lockedZhuyinCombo = null
        cachedCandidates = emptyList()
        cachedZhuyinCombos = emptyList()
    }

    fun hasComposing(): Boolean = currentKeys.isNotEmpty()

    fun selectZhuyinCombo(zhuyin: String): List<DictEntry> {
        lockedZhuyinCombo = zhuyin
        recalculateCandidatesOnly()
        return cachedCandidates
    }

    private fun recalculate() {
        if (currentKeys.isEmpty()) {
            cachedCandidates = emptyList()
            cachedZhuyinCombos = emptyList()
            return
        }

        val directResults = trie.search(currentKeys)
        val candidateList = mutableListOf<DictEntry>()
        candidateList.addAll(directResults)

        if (currentKeys.size >= 4) {
            val segmentedSentence = findBestSentence(currentKeys)
            if (segmentedSentence != null && candidateList.none { it.word == segmentedSentence.word }) {
                candidateList.add(0, segmentedSentence)
            }
        }

        val comboSet = LinkedHashSet<String>()
        // 純注音按鍵數（排除聲調鍵 K11），用於截取注音前綴
        val toneChar = if (currentToneIndex > 0) TONE_SYMBOLS[currentToneIndex] else null
        val phonemeKeyLen = currentKeys.count { it != 11 }

        for (entry in candidateList) {
            val clean = entry.zhuyin.filter { it !in "ˇˋˊ˙" }
            val prefix = if (clean.length >= phonemeKeyLen) clean.substring(0, phonemeKeyLen) else clean
            // 若使用者已選聲調，在顯示標籤上附加聲調符號
            val displayLabel = if (toneChar != null) "$prefix$toneChar" else prefix
            comboSet.add(displayLabel)
            if (comboSet.size >= 8) break
        }

        if (comboSet.isEmpty()) {
            val sb = StringBuilder()
            for (k in currentKeys) {
                if (k == 11) continue  // 聲調鍵由 toneChar 處理
                val chs = KeyMapping.getChars(k)
                if (chs.isNotEmpty()) sb.append(chs[0])
            }
            val fallback = if (toneChar != null) "${sb}${toneChar}" else sb.toString()
            comboSet.add(fallback)
        }

        cachedZhuyinCombos = comboSet.toList()
        recalculateCandidatesOnly(candidateList)
    }

    private fun findBestSentence(keys: List<Int>): DictEntry? {
        val words = mutableListOf<String>()
        val zhuyins = mutableListOf<String>()
        var idx = 0

        while (idx < keys.size) {
            var matched = false
            val maxLen = minOf(8, keys.size - idx)
            for (len in maxLen downTo 1) {
                val subKeys = keys.subList(idx, idx + len)
                val node = trie.searchNode(subKeys)
                if (node != null && node.exactEntries.isNotEmpty()) {
                    val best = node.exactEntries.maxByOrNull { it.weight }!!
                    words.add(best.word)
                    zhuyins.add(best.zhuyin)
                    idx += len
                    matched = true
                    break
                }
            }
            if (!matched) {
                return null
            }
        }

        if (words.size > 1) {
            return DictEntry(words.joinToString(""), zhuyins.joinToString(""), 100000000)
        }
        return null
    }

    private fun recalculateCandidatesOnly(rawResults: List<DictEntry>? = null) {
        val allResults = rawResults ?: trie.search(currentKeys)
        val locked = lockedZhuyinCombo

        // 按聲調過濾：若使用者已選定聲調，只顯示含該聲調的候選詞
        val toneFiltered = if (currentToneIndex > 0) {
            val expectedTone = TONE_SYMBOLS[currentToneIndex]
            val filtered = allResults.filter { entry -> entry.zhuyin.contains(expectedTone) }
            if (filtered.isNotEmpty()) filtered else allResults
        } else {
            allResults
        }

        if (locked != null) {
            val filtered = toneFiltered.filter { entry ->
                val clean = entry.zhuyin.filter { it !in "ˇˋˊ˙" }
                clean.startsWith(locked.filter { it !in "ˇˋˊ˙" })
            }
            cachedCandidates = if (filtered.isNotEmpty()) filtered else toneFiltered
            return
        }

        cachedCandidates = toneFiltered
    }

    fun getPossibleZhuyinCombinations(): List<String> = cachedZhuyinCombos

    fun getCandidates(): List<DictEntry> = cachedCandidates

    fun getTopComposingWord(): String {
        val candidates = getCandidates()
        if (candidates.isNotEmpty()) {
            return candidates.first().word
        }
        return getPossibleZhuyinCombinations().firstOrNull() ?: ""
    }
}
