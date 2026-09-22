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
     * 常用接續聯想詞表
     */
    private fun initAssociations() {
        val rules = mapOf(
            "你" to listOf("好", "在幹嘛", "在哪裡", "覺得呢", "知道嗎", "可以嗎", "有空嗎", "要不要"),
            "你好" to listOf("！", "，", "請問", "我是", "早安", "晚安", "歡迎", "大家"),
            "我" to listOf("是", "在", "想", "要", "知道", "覺得", "喜歡", "今天", "現在", "可以"),
            "他" to listOf("是", "說", "在", "想", "要", "知道", "今天", "去哪"),
            "她" to listOf("是", "說", "在", "想", "要", "知道", "今天"),
            "謝謝" to listOf("你", "您", "大家", "配合", "分享", "幫忙", "！"),
            "貢丸" to listOf("湯", "好吃", "新竹", "麵", "米粉"),
            "台灣" to listOf("高鐵", "大學", "美食", "天氣", "啤酒", "銀行"),
            "今天" to listOf("天氣", "晚上", "中午", "早上", "星期幾", "要吃什麼", "好累"),
            "明天" to listOf("見", "早上", "晚上", "下午", "放假", "要開會"),
            "什麼" to listOf("時候", "意思", "名字", "東西", "事情", "原因"),
            "怎麼" to listOf("了", "樣", "辦", "去", "做", "會這樣", "聯絡"),
            "可以" to listOf("嗎", "幫我", "直接", "一起", "考慮", "使用"),
            "沒問題" to listOf("！", "，我來處理", "，交給我", "馬上辦"),
            "大家" to listOf("好", "早安", "晚安", "辛苦了", "注意安全"),
            "早安" to listOf("！", "，祝你有美好的一天", "，今天好冷"),
            "晚安" to listOf("！", "，祝好夢", "，明天見"),
            "工作" to listOf("順利", "認真", "人員", "愉快", "機會")
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
        val keyLen = currentKeys.size

        for (entry in candidateList) {
            val clean = entry.zhuyin.filter { it !in "ˇˋˊ˙" }
            if (clean.length >= keyLen) {
                comboSet.add(clean.substring(0, keyLen))
            } else {
                comboSet.add(clean)
            }
            if (comboSet.size >= 8) break
        }

        if (comboSet.isEmpty()) {
            val sb = StringBuilder()
            for (k in currentKeys) {
                val chs = KeyMapping.getChars(k)
                if (chs.isNotEmpty()) sb.append(chs[0])
            }
            comboSet.add(sb.toString())
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

        if (locked != null) {
            val filtered = allResults.filter { entry ->
                val clean = entry.zhuyin.filter { it !in "ˇˋˊ˙" }
                clean.startsWith(locked)
            }
            cachedCandidates = if (filtered.isNotEmpty()) filtered else allResults
            return
        }

        cachedCandidates = allResults
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
