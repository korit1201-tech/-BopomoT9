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
    var isDictionaryLoaded = false
        private set

    var onDictionaryLoadedListener: (() -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        val TONE_SYMBOLS = listOf(' ', 'ˇ', 'ˋ', 'ˊ', '˙')
    }

    init {
        loadUserDictionaryEntries(trie, charZhuyinMap)
        userDict.onDictionaryChangedListener = {
            loadUserDictionaryEntries(trie, charZhuyinMap)
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
            val newCharZhuyinMap = mutableMapOf<Char, MutableList<String>>()
            loadDictionaryInternal(newTrie, newNextWordMap, newCharZhuyinMap)
            loadUserDictionaryEntries(newTrie, newCharZhuyinMap)

            mainHandler.post {
                trie = newTrie
                nextWordMap = newNextWordMap
                charZhuyinMap = newCharZhuyinMap
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
        targetCharZhuyinMap: MutableMap<Char, MutableList<String>>
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

                        // 統計單字音節歷史頻率（教育部 429 個合法音節）與記錄單字注音查表
                        if (word.length == 1) {
                            val cleanZhuyin = zhuyin.filter { it !in "ˇˋˊ˙" }
                            SyllableManager.addSyllableWeight(cleanZhuyin, weight)
                            val list = targetCharZhuyinMap.getOrPut(word[0]) { ArrayList(2) }
                            if (!list.contains(zhuyin)) {
                                list.add(zhuyin)
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
    }

    fun hasComposing(): Boolean = currentKeys.isNotEmpty()

    fun selectZhuyinCombo(zhuyin: String): List<DictEntry> {
        // 點選同一個音節按鈕支援切換解除鎖定 (Toggle)
        lockedZhuyinCombo = if (lockedZhuyinCombo == zhuyin) null else zhuyin
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

        // Tier 1: 完全匹配詞排序（使用者常選詞穩居第一位，原生匹配字優先於容錯字）
        val rankedExact = filterTone(exactResults).sortedWith(
            compareByDescending<DictEntry> { !it.isTolerant }
                .thenByDescending { it.weight + userDict.getBoost(it.word) }
        )

        // Tier 2: DP 最佳長句分詞（一口氣輸入較長句子 >= 4 鍵時）
        val segmentedSentence = if (currentKeys.size >= 4) {
            findBestSentence(currentKeys)
        } else null

        // Tier 3: 前綴預測詞排序
        val rankedPrefix = filterTone(prefixResults).sortedWith(
            compareByDescending<DictEntry> { !it.isTolerant }
                .thenByDescending { it.weight + userDict.getBoost(it.word) }
        )

        // 組合候選詞（階梯式嚴格優先級）：
        // 1. 若有字典完全匹配詞 (rankedExact)，真實高頻詞穩居第一位，絕不被拼裝詞干擾！
        // 2. 若無完全匹配詞（純長句輸入），DP 分詞預測的完整句子置頂排在第一位（藍色高亮）！
        // 3. 隨後依序排列前綴延伸詞 (rankedPrefix)
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

        // 產生左側注音音節/詞彙組合列表（兼顧單字合法音節與多字詞組合，杜絕組合遺失與無字可選）
        val cleanKeys = currentKeys.filter { it != 11 }
        val phonemeKeyLen = cleanKeys.size
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

        val wordBonus = 3.0
        val logTotal = if (logTotalWeight > 0) logTotalWeight else 17.91

        for (i in 1..n) {
            val maxLen = minOf(12, i)
            for (len in 1..maxLen) {
                val j = i - len
                if (dp[j] != Double.NEGATIVE_INFINITY) {
                    val subKeys = keys.subList(j, i)
                    val node = trie.searchNode(subKeys)
                    if (node != null && node.exactEntries.isNotEmpty()) {
                        for (entry in node.exactEntries) {
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
     * 長句輸入滑動窗口自動確認 (Sliding Window Prefix Commit)
     * 當連續輸入的字數/按鍵較多（>= 8 鍵，約 3~4 字以上），且前面分詞已經十分肯定時，
     * 直接將穩定的第一段詞彙提前上屏確認，留存後續按鍵繼續拼音。
     */
    fun pollConfirmedPrefix(): Pair<String, Int>? {
        val cleanKeys = currentKeys.filter { it != 11 }
        if (cleanKeys.size < 6) return null

        val segments = findBestSentenceSegments(cleanKeys) ?: return null
        if (segments.size < 2) return null

        val firstSegment = segments[0] // Pair(word, keyLen)
        val remainingKeyCount = cleanKeys.size - firstSegment.second

        // 判定條件（1.6.5 更積極提早確認前綴，防止後續按鍵干擾已確認字詞）：
        // 1. 分詞至少有 3 段 (例如: 今天 + 天氣 + 很...)
        // 2. 或第一段詞長 >= 2 字 (例如: 今天)，且後續剩餘按鍵 >= 3 鍵 (後面至少還有 1 個字)
        val shouldCommit = segments.size >= 3 || (firstSegment.first.length >= 2 && remainingKeyCount >= 3)
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
     * 單字同音/同拼法候選字查詢（供底線組字長按逐字替換使用）：
     * 1. 依該字的注音找出相同音節（同聲調與不同聲調）
     * 2. 依 9 鍵序列找出同按鍵拼法的字
     * 3. 去重並依個人詞庫使用頻率與字典權重排序
     */
    fun getHomophonesForChar(ch: Char): List<DictEntry> {
        val zhuyins = charZhuyinMap[ch] ?: emptyList()
        val seen = LinkedHashSet<String>()
        val results = mutableListOf<DictEntry>()

        for (zy in zhuyins) {
            val seqWithTone = KeyMapping.getSequence(zy, ignoreTones = false)
            val seqNoTone = KeyMapping.getSequence(zy, ignoreTones = true)

            for (seq in listOf(seqWithTone, seqNoTone).distinct()) {
                if (seq.isEmpty()) continue
                val node = trie.searchNode(seq) ?: continue
                for (e in node.exactEntries) {
                    if (e.word.length == 1 && seen.add(e.word)) {
                        results.add(e)
                    }
                }
            }
        }

        // 保底：若查無注音，從當前候選字表中提取所有單字
        if (results.isEmpty()) {
            for (c in cachedCandidates) {
                if (c.word.length == 1 && seen.add(c.word)) {
                    results.add(c)
                }
            }
        }

        val originalEntry = results.find { it.word == ch.toString() }
            ?: DictEntry(ch.toString(), zhuyins.firstOrNull() ?: "", 1000)

        val others = results.filter { it.word != ch.toString() }
            .sortedByDescending { it.weight + userDict.getBoost(it.word) }

        return listOf(originalEntry) + others.take(25)
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
            for (e in node.exactEntries) {
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
}
