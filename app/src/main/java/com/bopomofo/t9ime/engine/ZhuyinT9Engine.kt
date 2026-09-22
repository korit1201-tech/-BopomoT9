package com.bopomofo.t9ime.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 注音 T9 解碼引擎（基於 libchewing-data 詞庫）
 *
 * 設計原則：
 * - 完全依賴 libchewing 的頻率權重排序，不做自訂預測邏輯
 * - Trie 前綴樹查詢 + 個人化使用頻率加權（UserDictionary）
 * - 零韻母容錯已在 KeyMapping.getTolerantSequences() 處理
 * - 字典非同步背景極速載入，主線程 0 阻塞秒開鍵盤
 */
class ZhuyinT9Engine(private val context: Context) {

    @Volatile
    private var trie = TrieDictionary()
    private var currentKeys = mutableListOf<Int>()
    private var currentToneIndex = 0

    private var cachedCandidates: List<DictEntry> = emptyList()
    private var cachedZhuyinCombos: List<String> = emptyList()
    private var lockedZhuyinCombo: String? = null

    private val userDict = UserDictionaryManager.getInstance(context)
    @Volatile
    private var nextWordMap = mutableMapOf<String, MutableList<DictEntry>>()

    @Volatile
    var isDictionaryLoaded = false
        private set

    var onDictionaryLoadedListener: (() -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        val TONE_SYMBOLS = listOf(' ', 'ˇ', 'ˋ', 'ˊ', '˙')
    }

    init {
        loadUserDictionaryEntries(trie)
        userDict.onDictionaryChangedListener = {
            loadUserDictionaryEntries(trie)
            if (currentKeys.isNotEmpty()) {
                recalculate()
            }
        }
        startAsyncDictionaryLoading()
    }

    private fun startAsyncDictionaryLoading() {
        Thread({
            val newTrie = TrieDictionary()
            val newNextWordMap = mutableMapOf<String, MutableList<DictEntry>>()
            loadDictionaryInternal(newTrie, newNextWordMap)
            loadUserDictionaryEntries(newTrie)

            mainHandler.post {
                trie = newTrie
                nextWordMap = newNextWordMap
                isDictionaryLoaded = true
                if (currentKeys.isNotEmpty()) {
                    recalculate()
                }
                onDictionaryLoadedListener?.invoke()
            }
        }, "ZhuyinT9DictLoader").start()
    }

    private fun loadUserDictionaryEntries(targetTrie: TrieDictionary) {
        val userEntries = userDict.getAllEntries()
        for (u in userEntries) {
            if (u.zhuyin.isNotEmpty()) {
                // 使用者選過的詞給予高優先權，確保出現在候選詞中
                val weight = 5_000_000 + minOf(u.count * 6_000_000, 100_000_000)
                targetTrie.insert(DictEntry(u.word, u.zhuyin, weight))
            }
        }
    }

    private fun loadDictionaryInternal(
        targetTrie: TrieDictionary,
        targetNextWordMap: MutableMap<String, MutableList<DictEntry>>
    ) {
        val nextWordTrack = mutableMapOf<String, HashSet<String>>()
        try {
            context.assets.open("dict_tw.txt").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream), 65536).useLines { lines ->
                    for (line in lines) {
                        val tab1 = line.indexOf('\t')
                        if (tab1 == -1) continue
                        val tab2 = line.indexOf('\t', tab1 + 1)
                        if (tab2 == -1) continue

                        val word = line.substring(0, tab1)
                        val zhuyin = line.substring(tab1 + 1, tab2)
                        val weight = line.substring(tab2 + 1).toIntOrNull() ?: 100

                        val entry = DictEntry(word, zhuyin, weight)
                        targetTrie.insert(entry)

                        // 單次讀取時構建高頻接續聯想詞庫（weight >= 25 且使用 HashSet 快速去重）
                        if (word.length in 2..4 && weight >= 25) {
                            val addNext = { p: String, n: String ->
                                val set = nextWordTrack.getOrPut(p) { HashSet(8) }
                                if (set.size < 10 && set.add(n)) {
                                    val list = targetNextWordMap.getOrPut(p) { ArrayList(8) }
                                    list.add(DictEntry(n, "", weight))
                                }
                            }
                            when (word.length) {
                                2 -> addNext(word.substring(0, 1), word.substring(1))
                                3 -> {
                                    addNext(word.substring(0, 1), word.substring(1))
                                    addNext(word.substring(0, 2), word.substring(2))
                                }
                                4 -> {
                                    addNext(word.substring(0, 2), word.substring(2))
                                    addNext(word.substring(0, 1), word.substring(1))
                                }
                            }
                        }
                    }
                }
            }

            for ((_, list) in targetNextWordMap) {
                list.sortByDescending { it.weight }
            }
        } catch (e: Exception) {
            android.util.Log.e("BopomofoIME", "字典載入失敗", e)
        }
    }

    // ───────── 按鍵操作 ─────────

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
        return Pair(TONE_SYMBOLS[currentToneIndex], cachedCandidates)
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

    // ───────── 核心查詢邏輯 ─────────

    private fun recalculate() {
        if (currentKeys.isEmpty()) {
            cachedCandidates = emptyList()
            cachedZhuyinCombos = emptyList()
            return
        }

        val exactResults = trie.searchExact(currentKeys)
        val prefixResults = trie.searchPrefix(currentKeys, maxDepth = 3)

        // 聲調過濾
        val toneChar = if (currentToneIndex > 0) TONE_SYMBOLS[currentToneIndex] else null
        val filterTone = { list: List<DictEntry> ->
            if (toneChar != null) {
                val filtered = list.filter { it.zhuyin.contains(toneChar) }
                if (filtered.isNotEmpty()) filtered else list
            } else list
        }

        // Tier 1: 完全匹配詞排序（使用者常選詞穩居第一位）
        val rankedExact = filterTone(exactResults).sortedByDescending {
            it.weight + userDict.getBoost(it.word)
        }

        // Tier 2: DP 最佳長句分詞（一口氣輸入較長句子 >= 4 鍵時）
        val segmentedSentence = if (currentKeys.size >= 4) {
            findBestSentence(currentKeys)
        } else null

        // Tier 3: 前綴預測詞排序
        val rankedPrefix = filterTone(prefixResults).sortedByDescending {
            it.weight + userDict.getBoost(it.word)
        }

        // 組合候選詞（階梯式嚴格優先級）：
        // 1. 若無字典完全匹配詞（純連續打長句子），DP 分詞預測的中文句子直接置頂排在第一位（藍色高亮）！
        // 2. 若有完全匹配詞，常選詞/完全匹配排第一，預測長句緊跟其後，子節點長詞永遠排在最後！
        val candidateList = mutableListOf<DictEntry>()
        if (segmentedSentence != null && rankedExact.none { it.word == segmentedSentence.word }) {
            if (rankedExact.isEmpty()) {
                candidateList.add(segmentedSentence)
                candidateList.addAll(rankedPrefix.filter { it.word != segmentedSentence.word })
            } else {
                candidateList.addAll(rankedExact)
                candidateList.add(segmentedSentence)
                candidateList.addAll(rankedPrefix.filter { p -> candidateList.none { it.word == p.word } })
            }
        } else {
            candidateList.addAll(rankedExact)
            candidateList.addAll(rankedPrefix.filter { p -> candidateList.none { it.word == p.word } })
        }

        // 計算純注音按鍵長度（排除聲調鍵 K11）
        val phonemeKeyLen = currentKeys.count { it != 11 }

        // 從 top 候選詞抽取注音前綴組合（最多 4 個，避免雜訊）
        val comboSet = LinkedHashSet<String>()
        for (entry in candidateList) {
            val clean  = entry.zhuyin.filter { it !in "ˇˋˊ˙" }
            val prefix = if (clean.length >= phonemeKeyLen) clean.substring(0, phonemeKeyLen) else clean
            val label  = if (toneChar != null) "$prefix$toneChar" else prefix
            if (label.isNotEmpty()) comboSet.add(label)
            if (comboSet.size >= 4) break
        }

        if (comboSet.isEmpty()) {
            val sb = StringBuilder()
            for (k in currentKeys) {
                if (k == 11) continue
                val chs = KeyMapping.getChars(k)
                if (chs.isNotEmpty()) sb.append(chs[0])
            }
            comboSet.add(if (toneChar != null) "${sb}${toneChar}" else sb.toString())
        }

        cachedZhuyinCombos = comboSet.toList()
        applyLockFilter(candidateList)
    }

    /**
     * 動態規劃 (DP / Viterbi) 全域最佳分詞演算法
     * 當一口氣輸入較長句子時，從詞庫中尋找最合理的多詞組合並合成中文
     */
    private fun findBestSentence(keys: List<Int>): DictEntry? {
        val n = keys.size
        if (n < 4) return null

        val dp = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        dp[0] = 0.0
        val bestSplit = arrayOfNulls<Pair<Int, DictEntry>>(n + 1)

        for (i in 1..n) {
            val maxLen = minOf(8, i)
            for (len in 1..maxLen) {
                val j = i - len
                if (dp[j] != Double.NEGATIVE_INFINITY) {
                    val subKeys = keys.subList(j, i)
                    val node = trie.searchNode(subKeys)
                    if (node != null && node.exactEntries.isNotEmpty()) {
                        val best = node.exactEntries.maxByOrNull { it.weight + userDict.getBoost(it.word) }!!
                        val lengthMultiplier = Math.pow(len.toDouble(), 1.25)
                        val score = dp[j] + Math.log(maxOf(best.weight.toDouble(), 10.0)) * lengthMultiplier
                        if (score > dp[i]) {
                            dp[i] = score
                            bestSplit[i] = Pair(j, best)
                        }
                    }
                }
            }
        }

        if (dp[n] == Double.NEGATIVE_INFINITY) {
            return null
        }

        val words = mutableListOf<String>()
        val zhuyins = mutableListOf<String>()
        var curr = n
        while (curr > 0) {
            val split = bestSplit[curr] ?: return null
            words.add(split.second.word)
            zhuyins.add(split.second.zhuyin)
            curr = split.first
        }
        words.reverse()
        zhuyins.reverse()

        if (words.size > 1) {
            return DictEntry(words.joinToString(""), zhuyins.joinToString(""), 100_000_000)
        }
        return null
    }

    private fun applyLockFilter(baseList: List<DictEntry>) {
        val locked = lockedZhuyinCombo
        if (locked != null) {
            val cleanLock = locked.filter { it !in "ˇˋˊ˙" }
            val filtered = baseList.filter { entry ->
                entry.zhuyin.filter { it !in "ˇˋˊ˙" }.startsWith(cleanLock)
            }
            cachedCandidates = if (filtered.isNotEmpty()) filtered else baseList
            return
        }
        cachedCandidates = baseList
    }

    private fun recalculateCandidatesOnly() {
        recalculate()
    }

    // ───────── 對外查詢 API ─────────

    fun getPossibleZhuyinCombinations(): List<String> = cachedZhuyinCombos

    fun getCandidates(): List<DictEntry> = cachedCandidates

    val currentCandidates: List<DictEntry>
        get() = cachedCandidates

    fun getTopComposingWord(): String {
        val candidates = getCandidates()
        if (candidates.isNotEmpty()) return candidates.first().word
        return getPossibleZhuyinCombinations().firstOrNull() ?: ""
    }

    /**
     * 接續詞預測（聯想詞）
     * 100% 基於 libchewing 官方 16 萬詞庫在開機載入時建置的真實繁體詞頻索引，零硬編碼，零磁碟重複讀取！
     */
    fun getNextWordPredictions(word: String): List<DictEntry> {
        if (word.isEmpty()) return emptyList()

        val rankList = { list: List<DictEntry> ->
            list.sortedByDescending { it.weight + userDict.getBoost(it.word) }.take(10)
        }

        // 1. 完全匹配剛上屏詞彙
        val exactList = nextWordMap[word]
        if (!exactList.isNullOrEmpty()) {
            return rankList(exactList)
        }

        // 2. 結尾 2 字匹配（如選了長詞，取末尾詞接續）
        if (word.length >= 2) {
            val suffix2 = word.takeLast(2)
            val list2 = nextWordMap[suffix2]
            if (!list2.isNullOrEmpty()) {
                return rankList(list2)
            }
        }

        // 3. 結尾單字匹配
        val suffix1 = word.takeLast(1)
        val list1 = nextWordMap[suffix1]
        if (!list1.isNullOrEmpty()) {
            return rankList(list1)
        }

        return emptyList()
    }
}
