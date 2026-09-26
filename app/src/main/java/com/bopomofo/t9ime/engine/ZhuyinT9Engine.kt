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
 *
 * v1.6.5 新增：
 * - pollConfirmedPrefix 前綴確認門檻降低（≥6鍵、剩餘≥3鍵），讓連續輸入時「今天」等詞更快自動上屏
 * - getHomophonesFor() 同音字查詢，供候選詞長按替換功能使用
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
    private var charZhuyinMap = mutableMapOf<Char, MutableList<String>>()
    @Volatile
    private var initialMap = mutableMapOf<String, MutableList<DictEntry>>()
    /**
     * 反向音節索引：cleanZhuyin(去聲調) -> DictEntry 列表（單字）
     * 在載入字典時同步建立，讓 searchFullZhuyin 步驟 5 從 O(n) 線性掃描降至 O(1)
     */
    @Volatile
    private var soundToCharMap = mutableMapOf<String, MutableList<DictEntry>>()

    // Bigram 語境關聯索引：prevWord -> (nextWord -> weight)
    private val bigramMap = HashMap<String, MutableMap<String, Int>>()

    @Volatile
    var isDictionaryLoaded = false
        private set

    var onDictionaryLoadedListener: (() -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        val TONE_SYMBOLS = listOf(' ', 'ˇ', 'ˋ', 'ˊ', '˙')
    }

    init {
        initDefaultBigrams()
        loadUserDictionaryEntries(trie, charZhuyinMap)
        userDict.onDictionaryChangedListener = {
            loadUserDictionaryEntries(trie, charZhuyinMap)
            if (currentKeys.isNotEmpty()) {
                recalculate()
            }
        }
        startAsyncDictionaryLoading()
    }

    private fun initDefaultBigrams() {
        addBigramPair("去看", "醫生", 800)
        addBigramPair("看", "醫生", 600)
        addBigramPair("去", "醫院", 700)
        addBigramPair("醫院", "看病", 800)
        addBigramPair("醫院", "吃藥", 600)
        addBigramPair("吃", "飯", 800)
        addBigramPair("吃", "藥", 600)
        addBigramPair("洗", "澡", 800)
        addBigramPair("洗", "手", 700)
        addBigramPair("睡", "覺", 800)
        addBigramPair("喝", "水", 800)
        addBigramPair("喝", "茶", 600)
        addBigramPair("喝", "咖啡", 700)
        addBigramPair("今天", "天氣", 700)
        addBigramPair("大家", "好", 800)
        addBigramPair("非常", "感謝", 800)
        addBigramPair("祝你", "順心", 800)
        addBigramPair("台灣", "大學", 600)
        addBigramPair("台灣", "高鐵", 700)
        addBigramPair("捷運", "站", 700)
        loadBigramFromFile()
    }

    private fun getBigramFile(): java.io.File? {
        return try {
            java.io.File(context.filesDir, "user_bigram.json")
        } catch (_: Exception) {
            null
        }
    }
    private val bigramExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val bigramSaveRunnable = Runnable {
        bigramExecutor.execute {
            saveBigramToFile()
        }
    }

    private fun loadBigramFromFile() {
        try {
            val file = getBigramFile() ?: return
            if (!file.exists()) return
            val content = file.readText(Charsets.UTF_8)
            val json = org.json.JSONObject(content)
            val keys = json.keys()
            synchronized(bigramMap) {
                while (keys.hasNext()) {
                    val prev = keys.next()
                    val nextObj = json.getJSONObject(prev)
                    val nextKeys = nextObj.keys()
                    val map = bigramMap.getOrPut(prev) { HashMap() }
                    while (nextKeys.hasNext()) {
                        val next = nextKeys.next()
                        map[next] = nextObj.getInt(next)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BopomofoIME", "載入 Bigram 詞頻關聯失敗", e)
        }
    }

    private fun saveBigramToFile() {
        try {
            val file = getBigramFile() ?: return
            val json = org.json.JSONObject()
            synchronized(bigramMap) {
                for ((prev, nextMap) in bigramMap) {
                    val nextObj = org.json.JSONObject()
                    for ((next, weight) in nextMap) {
                        nextObj.put(next, weight)
                    }
                    json.put(prev, nextObj)
                }
            }
            file.writeText(json.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("BopomofoIME", "儲存 Bigram 詞頻關聯失敗", e)
        }
    }

    private fun addBigramPair(prev: String, next: String, weight: Int) {
        val nextMap = bigramMap.getOrPut(prev) { HashMap() }
        nextMap[next] = (nextMap[next] ?: 0) + weight
    }

    fun learnBigram(prevWord: String, nextWord: String) {
        if (prevWord.isBlank() || nextWord.isBlank()) return
        synchronized(bigramMap) {
            addBigramPair(prevWord, nextWord, 500)
        }
        mainHandler.removeCallbacks(bigramSaveRunnable)
        mainHandler.postDelayed(bigramSaveRunnable, 2500L)
    }

    fun getBigramBoost(prevWord: String?, candidateWord: String): Int {
        if (prevWord == null) return 0
        return synchronized(bigramMap) {
            bigramMap[prevWord]?.get(candidateWord) ?: 0
        }
    }

    private fun startAsyncDictionaryLoading() {
        Thread({
            val newTrie = TrieDictionary()
            val newNextWordMap = mutableMapOf<String, MutableList<DictEntry>>()
            val newCharZhuyinMap = mutableMapOf<Char, MutableList<String>>()
            val newInitialMap = mutableMapOf<String, MutableList<DictEntry>>()
            val newSoundToCharMap = mutableMapOf<String, MutableList<DictEntry>>()
            loadDictionaryInternal(newTrie, newNextWordMap, newCharZhuyinMap, newInitialMap, newSoundToCharMap)
            loadUserDictionaryEntries(newTrie, newCharZhuyinMap)

            mainHandler.post {
                trie = newTrie
                nextWordMap = newNextWordMap
                charZhuyinMap = newCharZhuyinMap
                initialMap = newInitialMap
                soundToCharMap = newSoundToCharMap
                isDictionaryLoaded = true
                if (currentKeys.isNotEmpty()) {
                    recalculate()
                }
                onDictionaryLoadedListener?.invoke()
            }
        }, "ZhuyinT9DictLoader").start()
    }

    private fun loadUserDictionaryEntries(
        targetTrie: TrieDictionary,
        targetCharZhuyinMap: MutableMap<Char, MutableList<String>>
    ) {
        val userEntries = userDict.getAllEntries()
        for (u in userEntries) {
            if (u.zhuyin.isNotEmpty()) {
                // 使用者選過的詞給予高優先權，確保出現在候選詞中
                val weight = 5_000_000 + minOf(u.count * 6_000_000, 100_000_000)
                targetTrie.insert(DictEntry(u.word, u.zhuyin, weight))
                if (u.word.length == 1) {
                    val list = targetCharZhuyinMap.getOrPut(u.word[0]) { ArrayList(2) }
                    if (!list.contains(u.zhuyin)) {
                        list.add(0, u.zhuyin)
                    }
                }
            }
        }
    }

    @Volatile
    private var totalDictWeight: Long = 60_000_000L
    @Volatile
    private var logTotalWeight: Double = 17.91

    private fun loadDictionaryInternal(
        targetTrie: TrieDictionary,
        targetNextWordMap: MutableMap<String, MutableList<DictEntry>>,
        targetCharZhuyinMap: MutableMap<Char, MutableList<String>>,
        targetInitialMap: MutableMap<String, MutableList<DictEntry>>,
        targetSoundToCharMap: MutableMap<String, MutableList<DictEntry>>
    ) {
        val nextWordTrack = mutableMapOf<String, HashSet<String>>()
        var accumulatedWeight = 0L
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
                        accumulatedWeight += weight

                        // 構建簡拼（聲母偷懶輸入）索引表 (2~6字詞)
                        val cleanZhuyin = zhuyin.filter { it !in "ˇˋˊ˙" }
                        if (word.length in 2..6) {
                            val syllables = SyllableManager.splitIntoSyllables(cleanZhuyin, word.length)
                            if (syllables != null && syllables.size == word.length) {
                                val initials = syllables.map { it[0] }.joinToString("")
                                if (initials.isNotEmpty()) {
                                    val list = targetInitialMap.getOrPut(initials) { ArrayList(4) }
                                    list.add(entry)
                                }
                            }
                        }

                        // 統計單字音節歷史頻率（教育部 429 個合法音節）與記錄單字注音查表 + 反向音節索引
                        if (word.length == 1) {
                            SyllableManager.addSyllableWeight(cleanZhuyin, weight)
                            val list = targetCharZhuyinMap.getOrPut(word[0]) { ArrayList(2) }
                            if (!list.contains(zhuyin)) {
                                list.add(zhuyin)
                            }
                            val soundList = targetSoundToCharMap.getOrPut(cleanZhuyin) { ArrayList(4) }
                            if (soundList.none { it.word == word }) {
                                soundList.add(entry)
                            }
                        }

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

            totalDictWeight = accumulatedWeight
            logTotalWeight = Math.log(maxOf(accumulatedWeight.toDouble(), 1.0))

            for ((_, list) in targetNextWordMap) {
                list.sortByDescending { it.weight }
            }
            for ((_, list) in targetInitialMap) {
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

    /**
     * 直接設置指定聲調（如大千實體鍵盤 3, 4, 6, 7 聲調鍵精確輸入）
     * 若再次輸入同一個聲調則取消聲調 (Toggle 回無聲調)
     */
    fun setTone(toneChar: Char): Pair<Char, List<DictEntry>> {
        if (currentKeys.isEmpty()) return Pair(' ', emptyList())

        val targetIndex = TONE_SYMBOLS.indexOf(toneChar)
        if (targetIndex <= 0) return Pair(' ', cachedCandidates)

        currentToneIndex = if (currentToneIndex == targetIndex) 0 else targetIndex

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
        previousTopWord = null
    }

    fun hasComposing(): Boolean = currentKeys.isNotEmpty()

    fun getCurrentKeys(): List<Int> = currentKeys.toList()

    fun selectZhuyinCombo(zhuyin: String): List<DictEntry> {
        // 點選同一個音節按鈕支援切換解除鎖定 (Toggle)
        lockedZhuyinCombo = if (lockedZhuyinCombo == zhuyin) null else zhuyin
        recalculateCandidatesOnly()
        return cachedCandidates
    }

    private var previousTopWord: String? = null

    /**
     * 工業級 Rime-Chewing 融合評分模型：
     * 1. 現代漢語詞長優先（2字詞 5.0x > 3字詞 3.5x > 4字詞 3.0x > 單字 0.15x）
     * 2. Rime 未完成距離平滑折扣模型 (0.85^distance)，讓高頻未完成大詞自然浮現，徹底擊潰低頻生僻完全匹配怪詞！
     * 3. 音節延續保護 (Syllable Continuity)：若候選詞繼承了使用者前一擊的首字音節（如「今」->「今天」），給予 1.6x 延續加成！
     */
    private fun getEffectiveWeight(entry: DictEntry, inputKeyCount: Int, isExact: Boolean, prevTopWord: String? = null): Double {
        val userBoost = userDict.getBoost(entry.word)
        if (userBoost > 0) {
            return 1_000_000_000.0 + userBoost
        }

        val rawWeight = entry.weight.toDouble()
        val lengthMultiplier = when (entry.word.length) {
            2 -> 5.0      // 雙字詞核心加成（中文日常最高頻詞型，佔70%+）
            3 -> 3.5      // 三字詞加成
            4 -> 3.0      // 四字成語/專有名詞加成
            1 -> {
                // 單字：若輸入按鍵數達到 3 鍵以上（已經具備拼出雙字詞的能力），
                // 降低單字優先權，避免散字擋在雙字詞前面
                if (inputKeyCount >= 3) 0.15 else 1.0
            }
            else -> 1.5
        }

        // Rime 距離折扣模型
        val targetKeyLen = KeyMapping.getSequence(entry.zhuyin, ignoreTones = true).size
        val distance = maxOf(0, targetKeyLen - inputKeyCount)
        val distanceMultiplier = if (isExact) 1.8 else Math.pow(0.85, distance.toDouble())

        // 音節延續加成
        val continuityMultiplier = if (prevTopWord != null && prevTopWord.isNotEmpty() && entry.word.startsWith(prevTopWord)) 1.6 else 1.0

        val tolerantMultiplier = if (entry.isTolerant) 0.35 else 1.0
        return rawWeight * lengthMultiplier * distanceMultiplier * continuityMultiplier * tolerantMultiplier
    }

    // ───────── 核心查詢邏輯 ─────────

    private fun recalculate() {
        if (currentKeys.isEmpty()) {
            cachedCandidates = emptyList()
            cachedZhuyinCombos = emptyList()
            previousTopWord = null
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

        val cleanKeys = currentKeys.filter { it != 11 }
        val phonemeKeyLen = cleanKeys.size
        val prevTop = previousTopWord

        val filteredExact = filterTone(exactResults)
        val filteredPrefix = filterTone(prefixResults)

        // 統一度量衡評分池：將完全匹配與前綴預測統一納入評分，徹底杜絕冷門生僻怪詞霸佔首選！
        val pool = mutableMapOf<String, Pair<DictEntry, Double>>()

        for (e in filteredExact) {
            val score = getEffectiveWeight(e, phonemeKeyLen, isExact = true, prevTopWord = prevTop)
            pool[e.word] = Pair(e, score)
        }

        for (p in filteredPrefix) {
            val score = getEffectiveWeight(p, phonemeKeyLen, isExact = false, prevTopWord = prevTop)
            val existing = pool[p.word]
            if (existing == null || score > existing.second) {
                pool[p.word] = Pair(p, score)
            }
        }

        val rankedList = pool.values
            .sortedWith(
                compareByDescending<Pair<DictEntry, Double>> { userDict.getBoost(it.first.word) > 0 }
                    .thenByDescending { !it.first.isTolerant }
                    .thenByDescending { it.second }
            )
            .map { it.first }

        val candidateList = ArrayList(rankedList)

        previousTopWord = candidateList.firstOrNull()?.word

        // 產生左側注音音節/詞彙組合列表（兼顧單字合法音節與多字詞組合，杜絕組合遺失與無字可選）
        val comboSet = LinkedHashSet<String>()

        // 1. 從候選字詞列表提取所有可能的前綴注音走向（涵蓋單字 ㄐㄧㄢ 與多字詞 ㄐㄧㄓ、ㄍㄣㄓ、ㄍㄣㄗ 等）
        for (entry in candidateList) {
            val clean = entry.zhuyin.filter { it !in "ˇˋˊ˙" }
            val prefix = if (clean.length >= phonemeKeyLen) clean.substring(0, phonemeKeyLen) else clean
            if (prefix.isNotEmpty()) {
                val label = if (toneChar != null) "$prefix$toneChar" else prefix
                comboSet.add(label)
            }
            if (comboSet.size >= 8) break
        }

        // 2. 對於短按鍵 (<= 3 鍵)，補充標準音節管理器中的合法單音節
        if (phonemeKeyLen <= 3) {
            val exactSyllables = SyllableManager.getExactSyllables(cleanKeys)
            for (syl in exactSyllables) {
                val label = if (toneChar != null) "$syl$toneChar" else syl
                comboSet.add(label)
                if (comboSet.size >= 8) break
            }
        }

        // 3. 保底：若依然為空，以各按鍵第一注音符號合成
        if (comboSet.isEmpty()) {
            val sb = StringBuilder()
            for (k in cleanKeys) {
                val chs = KeyMapping.getChars(k)
                if (chs.isNotEmpty()) sb.append(chs[0])
            }
            comboSet.add(if (toneChar != null) "${sb}${toneChar}" else sb.toString())
        }

        cachedZhuyinCombos = comboSet.toList()
        applyLockFilter(candidateList)
    }

    /**
     * 動態規劃 (DP / Viterbi) 全域最佳分詞演算法 - 取得分詞片段與按鍵長度
     */
    private fun findBestSentenceSegments(keys: List<Int>): List<Pair<String, Int>>? {
        val n = keys.size
        if (n < 4) return null

        val dp = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        dp[0] = 0.0
        val bestSplit = arrayOfNulls<Pair<Int, DictEntry>>(n + 1)

        val wordBonus = 12.0 // 參考 libchewing：強烈長詞偏好，優先切分 2、3、4 字詞，徹底杜絕單字碎散切分
        val logTotal = if (logTotalWeight > 0) logTotalWeight else 17.91

        for (i in 1..n) {
            val maxLen = minOf(12, i)
            for (len in 1..maxLen) {
                val j = i - len
                if (dp[j] != Double.NEGATIVE_INFINITY) {
                    val subKeys = keys.subList(j, i)
                    val node = trie.searchNode(subKeys)
                    if (node != null && node.exactEntries.isNotEmpty()) {
                        for (entry in node.exactEntries.values) {
                            val boost = userDict.getBoost(entry.word)
                            val effectiveWeight = entry.weight + boost
                            val logProb = Math.log(maxOf(effectiveWeight.toDouble(), 1.0)) - logTotal
                            val bonus = (entry.word.length - 1) * wordBonus
                            val penalty = if (entry.isTolerant) -8.0 else 0.0
                            val score = dp[j] + logProb + bonus + penalty
                            if (score > dp[i]) {
                                dp[i] = score
                                bestSplit[i] = Pair(j, entry)
                            }
                        }
                    }
                }
            }
        }

        if (dp[n] == Double.NEGATIVE_INFINITY) return null

        val segments = mutableListOf<Pair<String, Int>>()
        var curr = n
        while (curr > 0) {
            val split = bestSplit[curr] ?: return null
            val word = split.second.word
            val keyLen = curr - split.first
            segments.add(Pair(word, keyLen))
            curr = split.first
        }
        segments.reverse()
        return segments
    }

    private fun findBestSentence(keys: List<Int>): DictEntry? {
        val segments = findBestSentenceSegments(keys) ?: return null
        if (segments.size > 1) {
            val combinedWord = segments.joinToString("") { it.first }
            return DictEntry(combinedWord, "", 100_000_000)
        }
        return null
    }

    /**
     * 檢查給定按鍵序列在 Trie 中是否存在完全匹配或前綴延伸詞
     */
    fun hasPrefixOrExact(sequence: List<Int>): Boolean {
        return trie.searchNode(sequence) != null
    }

    /**
     * 長句輸入滑動窗口自動確認 (Sliding Window Prefix Commit)
     * 當連續輸入的字數/按鍵較多，且前面分詞已經十分肯定時，
     * 直接將穩定的第一段詞彙提前上屏確認，留存後續按鍵繼續拼音。
     */
    fun pollConfirmedPrefix(): Pair<String, Int>? {
        val cleanKeys = currentKeys.filter { it != 11 }
        if (cleanKeys.size < 4) return null

        val segments = findBestSentenceSegments(cleanKeys) ?: return null
        if (segments.size < 2) return null

        val firstSegment = segments[0] // Pair(word, keyLen)
        val remainingKeyCount = cleanKeys.size - firstSegment.second

        // 判定條件：
        // 1. 分詞至少有 3 段 (例如: 今天 + 天氣 + 很...)
        // 2. 或第一段詞長 >= 2 字 (例如: 目前、今天)，且後續已有新輸入 (remainingKeyCount >= 1)
        val shouldCommit = segments.size >= 3 || (firstSegment.first.length >= 2 && remainingKeyCount >= 1)
        if (!shouldCommit) return null

        val keysToRemove = firstSegment.second
        val newKeys = mutableListOf<Int>()
        var removed = 0
        for (k in currentKeys) {
            if (removed < keysToRemove && k != 11) {
                removed++
            } else {
                newKeys.add(k)
            }
        }
        currentKeys.clear()
        currentKeys.addAll(newKeys)
        currentToneIndex = 0
        lockedZhuyinCombo = null

        recalculate()
        return Pair(firstSegment.first, keysToRemove)
    }

    var currentContextWord: String? = null

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

    fun getZhuyinForChar(ch: Char): String {
        return charZhuyinMap[ch]?.firstOrNull() ?: ""
    }

    /**
     * 動態學習新詞彙或自訂同音字組合：
     * 1. 寫入使用者記憶體字典與磁碟檔案
     * 2. 即時以最高權重 (50,000,000) 注入 Trie 字典，確保該按鍵序列下一次輸入時 100% 穩居首位
     */
    fun learnWord(word: String, zhuyin: String = "") {
        if (word.isBlank() || word.startsWith("【")) return
        val zy = if (zhuyin.isNotEmpty()) zhuyin else {
            word.map { getZhuyinForChar(it) }.filter { it.isNotEmpty() }.joinToString(" ")
        }
        userDict.recordUsage(word, zy)
        if (zy.isNotEmpty()) {
            val usageCount = userDict.getUsageCount(word)
            val weight = 50_000_000 + minOf(usageCount * 1_000_000, 50_000_000)
            trie.insert(DictEntry(word, zy, weight))
            if (word.length == 1) {
                val list = charZhuyinMap.getOrPut(word[0]) { ArrayList(2) }
                if (!list.contains(zy)) {
                    list.add(0, zy)
                }
            }
            val cleanZy = zy.filter { it !in "ˇˋˊ˙" }
            val syllables = if (zy.contains(" ")) zy.split(" ") else SyllableManager.splitIntoSyllables(cleanZy, word.length)
            if (syllables != null && syllables.size == word.length) {
                val initials = syllables.mapNotNull { it.firstOrNull() }.joinToString("")
                if (initials.isNotEmpty()) {
                    val list = initialMap.getOrPut(initials) { ArrayList(4) }
                    list.removeAll { it.word == word }
                    list.add(0, DictEntry(word, zy, weight))
                }
            }
        }
    }

    /**
     * 41 鍵大千注音全鍵盤專屬預測與候選檢索引擎（支援聲母簡拼、混合簡打與全拼聯想）
     */
    fun searchFullZhuyin(inputZhuyin: String, contextWord: String? = null): List<DictEntry> {
        if (inputZhuyin.isEmpty()) return emptyList()

        val results = mutableListOf<DictEntry>()
        val seenWords = hashSetOf<String>()

        val cleanInput = inputZhuyin.filter { it !in "ˇˋˊ˙" }
        val toneChar = inputZhuyin.find { it in "ˇˋˊ˙" }
        val isSingleSyllable = SyllableManager.isValidSyllable(cleanInput)
        val soundMatches = soundToCharMap[cleanInput]

        // 策略分流：
        // A. 若輸入構成合法單音節且字典中有單字對應（例如 ㄒㄧㄢ、ㄓㄨ、ㄇㄚ、ㄕ、ㄍㄨㄛ 等）：
        //    使用者在拼寫單一字音，候選字必須以該音節之「單字同音字」為最優先呈現！
        if (isSingleSyllable && !soundMatches.isNullOrEmpty()) {
            val singleEntries = mutableListOf<DictEntry>()

            // 1. 個人詞庫中的該音節單字（最高優先）
            val userEntries = userDict.getAllEntries()
            val userCharSet = hashSetOf<String>()
            for (u in userEntries) {
                if (u.word.length == 1) {
                    val uClean = u.zhuyin.filter { it !in "ˇˋˊ˙" }
                    if (uClean == cleanInput) {
                        userCharSet.add(u.word)
                        val baseWeight = 50_000_000 + u.count * 1_000_000
                        val toneBonus = if (toneChar != null) {
                            if (u.zhuyin.contains(toneChar)) 20_000_000 else 0
                        } else {
                            if (!u.zhuyin.any { it in "ˇˋˊ˙" }) 5_000_000 else 0
                        }
                        singleEntries.add(DictEntry(u.word, u.zhuyin, baseWeight + toneBonus))
                    }
                }
            }

            // 2. 字典反向索引中的所有單字同音字
            for (e in soundMatches) {
                if (userCharSet.contains(e.word)) continue
                val boost = userDict.getBoost(e.word)
                val baseWeight = e.weight + boost
                val toneBonus = if (toneChar != null) {
                    if (e.zhuyin.contains(toneChar)) 20_000_000 else 0
                } else {
                    if (!e.zhuyin.any { it in "ˇˋˊ˙" }) 5_000_000 else 0
                }
                singleEntries.add(DictEntry(e.word, e.zhuyin, baseWeight + toneBonus))
            }

            // 排序單字：依據綜合加權降序
            singleEntries.sortByDescending { it.weight }
            for (e in singleEntries) {
                if (seenWords.add(e.word)) {
                    results.add(e)
                }
            }

            // 3. 單字之後，再補入該音節開頭的常用詞彙（個人詞庫多字詞與 Trie 詞庫）
            val userMultiEntries = userEntries.filter {
                it.word.length > 1 && it.zhuyin.filter { c -> c !in "ˇˋˊ˙" }.startsWith(cleanInput)
            }.sortedByDescending { it.count }
            for (u in userMultiEntries) {
                if (seenWords.add(u.word)) {
                    results.add(DictEntry(u.word, u.zhuyin, 30_000_000 + u.count * 1_000_000))
                    if (results.size >= 40) break
                }
            }

            val keys = cleanInput.mapNotNull { KeyMapping.getKeyId(it) }
            if (keys.isNotEmpty()) {
                val trieResults = trie.searchPrefix(keys)
                val sortedTrieResults = if (toneChar != null) {
                    trieResults.sortedByDescending {
                        if (it.zhuyin.startsWith(inputZhuyin) || it.zhuyin.takeWhile { c -> c != ' ' }.contains(toneChar)) {
                            it.weight + 10_000_000
                        } else {
                            it.weight
                        }
                    }
                } else {
                    trieResults
                }
                for (e in sortedTrieResults) {
                    val eClean = e.zhuyin.filter { it !in "ˇˋˊ˙" }
                    if (eClean.startsWith(cleanInput) && seenWords.add(e.word)) {
                        results.add(e)
                        if (results.size >= 60) break
                    }
                }
            }

            return results
        }

        // B. 非合法單音節或字典中無獨立單字音節（如簡拼 ㄐㄊ、混合簡打 ㄐㄧㄣㄊ、長詞全拼等）：
        // 1. 個人詞庫 (UserDict) 最高優先權
        val userEntries = userDict.getAllEntries()
        for (u in userEntries) {
            val uClean = u.zhuyin.filter { it !in "ˇˋˊ˙" }
            if (uClean.startsWith(cleanInput) || u.word.startsWith(cleanInput)) {
                if (seenWords.add(u.word)) {
                    results.add(DictEntry(u.word, u.zhuyin, 50_000_000 + u.count * 1_000_000))
                }
            }
        }

        // 2. 首碼簡拼精確匹配 (Pure Initials Match, 如 ㄐㄊ -> 今天, 家庭)
        if (cleanInput.length >= 2) {
            val matched = initialMap[cleanInput]
            if (matched != null) {
                for (e in matched) {
                    if (seenWords.add(e.word)) {
                        results.add(e)
                        if (results.size >= 30) break
                    }
                }
            }
        }

        // 3. 混合簡打匹配 (Hybrid Match, 如 ㄐㄧㄣㄊ -> 今天: 前綴音節 ㄐㄧㄣ + 後續首碼 ㄊ)
        if (cleanInput.length >= 3) {
            for (splitIdx in minOf(cleanInput.length - 1, 3) downTo 1) {
                val firstSyl = cleanInput.substring(0, splitIdx)
                val restInitials = cleanInput.substring(splitIdx)
                if (SyllableManager.isValidSyllable(firstSyl)) {
                    val targetInitials = firstSyl[0] + restInitials
                    val matched = initialMap[targetInitials]
                    if (matched != null) {
                        for (e in matched) {
                            val eClean = e.zhuyin.filter { it !in "ˇˋˊ˙" }
                            if (eClean.startsWith(firstSyl) && seenWords.add(e.word)) {
                                results.add(e)
                                if (results.size >= 40) break
                            }
                        }
                    }
                }
            }
        }

        // 4. 全拼前綴檢索 (Trie Prefix Match, 如 ㄐㄧㄣ -> 今, 金, 今天, 金融)
        val keys = cleanInput.mapNotNull { KeyMapping.getKeyId(it) }
        if (keys.isNotEmpty()) {
            val trieResults = trie.searchPrefix(keys)
            for (e in trieResults) {
                val eClean = e.zhuyin.filter { it !in "ˇˋˊ˙" }
                if (eClean.startsWith(cleanInput) && seenWords.add(e.word)) {
                    results.add(e)
                    if (results.size >= 50) break
                }
            }
        }

        // 5. 單字精確與聲母檢索 (若輸入為單字音節或聲母，使用反向音節索引 O(1) 替代全量線性掃描)
        val singleMatches = mutableListOf<DictEntry>()
        soundToCharMap[cleanInput]?.let { entries ->
            for (e in entries) {
                if (!seenWords.contains(e.word)) {
                    singleMatches.add(e)
                }
            }
        }
        if (cleanInput.length == 1) {
            for ((syllable, entries) in soundToCharMap) {
                if (syllable != cleanInput && syllable.startsWith(cleanInput)) {
                    for (e in entries) {
                        if (!seenWords.contains(e.word)) {
                            singleMatches.add(e)
                        }
                    }
                }
            }
        }
        singleMatches.sortByDescending { it.weight }
        for (e in singleMatches) {
            if (seenWords.add(e.word)) {
                results.add(e)
                if (results.size >= 60) break
            }
        }

        return results
    }

    /**
     * 單字同音/同按鍵組合候選字查詢（供底線組字長按逐字替換使用）：
     * 1. 查詢完全相同鍵序的所有單字（涵蓋該鍵位上所有可能的拼音字，如 Key 6 包含 此、次、意、吃、一、衣、依、醫等）
     * 2. 依該字之注音查詢同音字（不同聲調）
     * 3. 納入前綴延伸單字
     * 4. 排序：首位原字、使用者曾選字最優先 (getBoost > 0)、字典權重降序
     */
    fun getHomophonesForChar(ch: Char): List<DictEntry> {
        val zhuyins = charZhuyinMap[ch] ?: emptyList()
        val seen = LinkedHashSet<String>()
        val exactKeyEntries = mutableListOf<DictEntry>()
        val sameZhuyinEntries = mutableListOf<DictEntry>()
        val prefixKeyEntries = mutableListOf<DictEntry>()

        for (zy in zhuyins) {
            val cleanZy = zy.filter { it !in "ˇˋˊ˙" }
            val seqWithTone = KeyMapping.getSequence(zy, ignoreTones = false)
            val seqNoTone = KeyMapping.getSequence(zy, ignoreTones = true)

            // 1. 同按鍵構成之精確組合（完全相同鍵序的所有單字）
            for (seq in listOf(seqWithTone, seqNoTone).distinct()) {
                if (seq.isEmpty()) continue
                val node = trie.searchNode(seq) ?: continue
                for (e in node.exactEntries.values) {
                    if (e.word.length == 1 && seen.add(e.word)) {
                        val eCleanZy = e.zhuyin.filter { it !in "ˇˋˊ˙" }
                        if (eCleanZy == cleanZy) {
                            sameZhuyinEntries.add(e)
                        } else {
                            exactKeyEntries.add(e)
                        }
                    }
                }
            }

            // 2. 同按鍵構成之前綴延伸單字（例如單鍵長按時可展開多鍵字）
            if (seqNoTone.size <= 2) {
                val prefixNodes = trie.searchPrefix(seqNoTone, maxDepth = 2)
                for (e in prefixNodes) {
                    if (e.word.length == 1 && seen.add(e.word)) {
                        prefixKeyEntries.add(e)
                    }
                }
            }
        }

        // 保底：若查無注音，從當前候選字表中提取所有單字
        if (sameZhuyinEntries.isEmpty() && exactKeyEntries.isEmpty()) {
            for (c in cachedCandidates) {
                if (c.word.length == 1 && seen.add(c.word)) {
                    sameZhuyinEntries.add(c)
                }
            }
        }

        val originalEntry = sameZhuyinEntries.find { it.word == ch.toString() }
            ?: exactKeyEntries.find { it.word == ch.toString() }
            ?: DictEntry(ch.toString(), zhuyins.firstOrNull() ?: "", 1000)

        // 排序規則：使用者選過字最優先 (getBoost > 0)，其次同音字，再者同鍵其他字
        val sortComparator = compareByDescending<DictEntry> { userDict.getBoost(it.word) > 0 }
            .thenByDescending { it.weight + userDict.getBoost(it.word) }

        val sortedSameZy = sameZhuyinEntries.filter { it.word != ch.toString() }.sortedWith(sortComparator)
        val sortedExactKey = exactKeyEntries.filter { it.word != ch.toString() }.sortedWith(sortComparator)
        val sortedPrefix = prefixKeyEntries.filter { it.word != ch.toString() }.sortedWith(sortComparator)

        val merged = mutableListOf<DictEntry>()
        merged.add(originalEntry)
        merged.addAll(sortedSameZy)
        merged.addAll(sortedExactKey)
        merged.addAll(sortedPrefix)

        return merged.take(80)
    }

    /**
     * 同音字查詢（長按候選詞用）：根據詞條的注音，從詞典 Trie 中取出所有相同按鍵序列的同音字/詞，
     * 排除詞本身，依個人化使用權重排序，供使用者替換選字。
     */
    fun getHomophonesFor(entry: DictEntry): List<DictEntry> {
        if (entry.word.length == 1) {
            return getHomophonesForChar(entry.word[0])
        }
        val zhuyin = entry.zhuyin
        if (zhuyin.isEmpty()) return emptyList()

        val keys = KeyMapping.getSequence(zhuyin, ignoreTones = false)
        val keysNoTone = KeyMapping.getSequence(zhuyin, ignoreTones = true)

        val seen = LinkedHashSet<String>()
        val results = mutableListOf<DictEntry>()

        // 查詢帶聲調序列與無聲調序列的候選詞，合併去重
        for (seq in listOf(keys, keysNoTone).distinct()) {
            if (seq.isEmpty()) continue
            val node = trie.searchNode(seq) ?: continue
            for (e in node.exactEntries.values) {
                if (e.word != entry.word && e.zhuyin.isNotEmpty() && seen.add(e.word)) {
                    results.add(e)
                }
            }
        }

        return results
            .sortedByDescending { it.weight + userDict.getBoost(it.word) }
            .take(20)
    }

    fun getTopComposingWord(): String {
        val candidates = getCandidates()
        if (candidates.isNotEmpty()) return candidates.first().word
        return getPossibleZhuyinCombinations().firstOrNull() ?: ""
    }

    /**
     * 常用對話接續聯想詞保底（台灣生活語境高頻詞）
     */
    private val defaultAssociations = mapOf(
        "你" to listOf("好", "在幹嘛", "在哪裡", "覺得呢", "知道嗎", "可以嗎", "有空嗎", "要不要", "們"),
        "你好" to listOf("！", "，", "請問", "我是", "早安", "晚安", "歡迎", "大家", "嗎"),
        "我" to listOf("是", "在", "想", "要", "知道", "覺得", "喜歡", "今天", "現在", "可以", "們"),
        "他" to listOf("是", "說", "在", "想", "要", "知道", "今天", "去哪", "們"),
        "她" to listOf("是", "說", "在", "想", "要", "知道", "今天", "們"),
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

    /**
     * 接續詞預測（聯想詞）
     * 結合 libchewing 官方 16 萬詞庫語料索引與台灣地道高頻接續詞
     */
    fun getNextWordPredictions(word: String): List<DictEntry> {
        if (word.isEmpty()) return emptyList()

        val rankList = { list: List<DictEntry> ->
            list.sortedByDescending { it.weight + userDict.getBoost(it.word) }
        }

        val resultList = mutableListOf<DictEntry>()

        // 1. 完全匹配剛上屏詞彙
        val exactList = nextWordMap[word]
        if (!exactList.isNullOrEmpty()) {
            resultList.addAll(rankList(exactList))
        }

        // 2. 常用對話聯想規則匹配
        val defaultMatches = defaultAssociations[word]
        if (!defaultMatches.isNullOrEmpty()) {
            for (w in defaultMatches) {
                if (resultList.none { it.word == w }) {
                    resultList.add(DictEntry(w, "", 10_000))
                }
            }
        }

        // 3. 結尾 2 字匹配（如選了長詞，取末尾詞接續）
        if (word.length >= 2) {
            val suffix2 = word.takeLast(2)
            val list2 = nextWordMap[suffix2]
            if (!list2.isNullOrEmpty()) {
                for (e in rankList(list2)) {
                    if (resultList.none { it.word == e.word }) resultList.add(e)
                }
            }
            val def2 = defaultAssociations[suffix2]
            if (!def2.isNullOrEmpty()) {
                for (w in def2) {
                    if (resultList.none { it.word == w }) resultList.add(DictEntry(w, "", 8_000))
                }
            }
        }

        // 4. 結尾單字匹配
        val suffix1 = word.takeLast(1)
        val list1 = nextWordMap[suffix1]
        if (!list1.isNullOrEmpty()) {
            for (e in rankList(list1)) {
                if (resultList.none { it.word == e.word }) resultList.add(e)
            }
        }
        val def1 = defaultAssociations[suffix1]
        if (!def1.isNullOrEmpty()) {
            for (w in def1) {
                if (resultList.none { it.word == w }) resultList.add(DictEntry(w, "", 5_000))
            }
        }

        return resultList.take(15)
    }

    /**
     * 41 鍵全鍵盤「聲母簡拼 / 偷懶輸入」查詢：
     * 依各字的聲母（首字符號，如 "ㄐㄊ"）快速篩選詞庫，並以個人詞庫使用歷史優先排序
     */
    fun searchInitials(initials: String): List<DictEntry> {
        if (initials.isEmpty()) return emptyList()
        val list = initialMap[initials] ?: return emptyList()
        return list.sortedWith(
            compareByDescending<DictEntry> { userDict.getBoost(it.word) > 0 }
                .thenByDescending { it.weight + userDict.getBoost(it.word) }
        ).take(80)
    }
}
