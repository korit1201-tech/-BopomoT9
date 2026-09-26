package com.bopomofo.t9ime.engine

/**
 * 台灣注音標準音節管理器 (SyllableManager)
 * 依據教育部標準與 libchewing 詞庫，管理台灣繁體中文全量 429 個合法無聲調音節。
 * 徹底杜絕長度暴力截斷與非拼音雜訊，提供精確音節檢索與頻率排序。
 */
object SyllableManager {

    val VALID_SYLLABLES: List<String> = listOf(
        "ㄅ", "ㄅㄚ", "ㄅㄛ", "ㄅㄞ", "ㄅㄟ", "ㄅㄠ", "ㄅㄢ", "ㄅㄣ", "ㄅㄤ", "ㄅㄥ",
        "ㄅㄧ", "ㄅㄧㄝ", "ㄅㄧㄠ", "ㄅㄧㄢ", "ㄅㄧㄣ", "ㄅㄧㄥ", "ㄅㄨ", "ㄆ", "ㄆㄚ", "ㄆㄛ",
        "ㄆㄞ", "ㄆㄟ", "ㄆㄠ", "ㄆㄡ", "ㄆㄢ", "ㄆㄣ", "ㄆㄤ", "ㄆㄥ", "ㄆㄧ", "ㄆㄧㄝ",
        "ㄆㄧㄠ", "ㄆㄧㄢ", "ㄆㄧㄣ", "ㄆㄧㄥ", "ㄆㄨ", "ㄇ", "ㄇㄚ", "ㄇㄛ", "ㄇㄜ", "ㄇㄞ",
        "ㄇㄟ", "ㄇㄠ", "ㄇㄡ", "ㄇㄢ", "ㄇㄣ", "ㄇㄤ", "ㄇㄥ", "ㄇㄧ", "ㄇㄧㄝ", "ㄇㄧㄠ",
        "ㄇㄧㄡ", "ㄇㄧㄢ", "ㄇㄧㄣ", "ㄇㄧㄥ", "ㄇㄨ", "ㄈ", "ㄈㄚ", "ㄈㄛ", "ㄈㄟ", "ㄈㄡ",
        "ㄈㄢ", "ㄈㄣ", "ㄈㄤ", "ㄈㄥ", "ㄈㄧㄠ", "ㄈㄨ", "ㄈㄨㄥ", "ㄉ", "ㄉㄚ", "ㄉㄜ",
        "ㄉㄞ", "ㄉㄟ", "ㄉㄠ", "ㄉㄡ", "ㄉㄢ", "ㄉㄣ", "ㄉㄤ", "ㄉㄥ", "ㄉㄧ", "ㄉㄧㄝ",
        "ㄉㄧㄠ", "ㄉㄧㄡ", "ㄉㄧㄢ", "ㄉㄧㄥ", "ㄉㄨ", "ㄉㄨㄛ", "ㄉㄨㄟ", "ㄉㄨㄢ", "ㄉㄨㄣ", "ㄉㄨㄥ",
        "ㄊ", "ㄊㄚ", "ㄊㄜ", "ㄊㄞ", "ㄊㄠ", "ㄊㄡ", "ㄊㄢ", "ㄊㄤ", "ㄊㄥ", "ㄊㄧ",
        "ㄊㄧㄝ", "ㄊㄧㄠ", "ㄊㄧㄢ", "ㄊㄧㄥ", "ㄊㄨ", "ㄊㄨㄛ", "ㄊㄨㄟ", "ㄊㄨㄢ", "ㄊㄨㄣ", "ㄊㄨㄥ",
        "ㄋ", "ㄋㄚ", "ㄋㄜ", "ㄋㄞ", "ㄋㄟ", "ㄋㄠ", "ㄋㄡ", "ㄋㄢ", "ㄋㄣ", "ㄋㄤ",
        "ㄋㄥ", "ㄋㄧ", "ㄋㄧㄚ", "ㄋㄧㄝ", "ㄋㄧㄠ", "ㄋㄧㄡ", "ㄋㄧㄢ", "ㄋㄧㄣ", "ㄋㄧㄤ", "ㄋㄧㄥ",
        "ㄋㄨ", "ㄋㄨㄛ", "ㄋㄨㄢ", "ㄋㄨㄣ", "ㄋㄨㄥ", "ㄋㄩ", "ㄋㄩㄝ", "ㄌ", "ㄌㄚ", "ㄌㄛ",
        "ㄌㄜ", "ㄌㄞ", "ㄌㄟ", "ㄌㄠ", "ㄌㄡ", "ㄌㄢ", "ㄌㄤ", "ㄌㄥ", "ㄌㄧ", "ㄌㄧㄚ",
        "ㄌㄧㄝ", "ㄌㄧㄠ", "ㄌㄧㄡ", "ㄌㄧㄢ", "ㄌㄧㄣ", "ㄌㄧㄤ", "ㄌㄧㄥ", "ㄌㄨ", "ㄌㄨㄛ", "ㄌㄨㄢ",
        "ㄌㄨㄣ", "ㄌㄨㄥ", "ㄌㄩ", "ㄌㄩㄝ", "ㄌㄩㄢ", "ㄍ", "ㄍㄚ", "ㄍㄜ", "ㄍㄞ", "ㄍㄟ",
        "ㄍㄠ", "ㄍㄡ", "ㄍㄢ", "ㄍㄣ", "ㄍㄤ", "ㄍㄥ", "ㄍㄨ", "ㄍㄨㄚ", "ㄍㄨㄛ", "ㄍㄨㄞ",
        "ㄍㄨㄟ", "ㄍㄨㄢ", "ㄍㄨㄣ", "ㄍㄨㄤ", "ㄍㄨㄥ", "ㄎ", "ㄎㄚ", "ㄎㄜ", "ㄎㄞ", "ㄎㄠ",
        "ㄎㄡ", "ㄎㄢ", "ㄎㄣ", "ㄎㄤ", "ㄎㄥ", "ㄎㄨ", "ㄎㄨㄚ", "ㄎㄨㄛ", "ㄎㄨㄞ", "ㄎㄨㄟ",
        "ㄎㄨㄢ", "ㄎㄨㄣ", "ㄎㄨㄤ", "ㄎㄨㄥ", "ㄏ", "ㄏㄚ", "ㄏㄜ", "ㄏㄞ", "ㄏㄟ", "ㄏㄠ",
        "ㄏㄡ", "ㄏㄢ", "ㄏㄣ", "ㄏㄤ", "ㄏㄥ", "ㄏㄨ", "ㄏㄨㄚ", "ㄏㄨㄛ", "ㄏㄨㄞ", "ㄏㄨㄟ",
        "ㄏㄨㄢ", "ㄏㄨㄣ", "ㄏㄨㄤ", "ㄏㄨㄥ", "ㄐ", "ㄐㄧ", "ㄐㄧㄚ", "ㄐㄧㄝ", "ㄐㄧㄠ", "ㄐㄧㄡ",
        "ㄐㄧㄢ", "ㄐㄧㄣ", "ㄐㄧㄤ", "ㄐㄧㄥ", "ㄐㄩ", "ㄐㄩㄝ", "ㄐㄩㄢ", "ㄐㄩㄣ", "ㄐㄩㄥ", "ㄑ",
        "ㄑㄧ", "ㄑㄧㄚ", "ㄑㄧㄝ", "ㄑㄧㄠ", "ㄑㄧㄡ", "ㄑㄧㄢ", "ㄑㄧㄣ", "ㄑㄧㄤ", "ㄑㄧㄥ", "ㄑㄩ",
        "ㄑㄩㄝ", "ㄑㄩㄢ", "ㄑㄩㄣ", "ㄑㄩㄥ", "ㄒ", "ㄒㄧ", "ㄒㄧㄚ", "ㄒㄧㄝ", "ㄒㄧㄠ", "ㄒㄧㄡ",
        "ㄒㄧㄢ", "ㄒㄧㄣ", "ㄒㄧㄤ", "ㄒㄧㄥ", "ㄒㄩ", "ㄒㄩㄝ", "ㄒㄩㄢ", "ㄒㄩㄣ", "ㄒㄩㄥ", "ㄓ",
        "ㄓㄚ", "ㄓㄜ", "ㄓㄞ", "ㄓㄟ", "ㄓㄠ", "ㄓㄡ", "ㄓㄢ", "ㄓㄣ", "ㄓㄤ", "ㄓㄥ",
        "ㄓㄨ", "ㄓㄨㄚ", "ㄓㄨㄛ", "ㄓㄨㄞ", "ㄓㄨㄟ", "ㄓㄨㄢ", "ㄓㄨㄣ", "ㄓㄨㄤ", "ㄓㄨㄥ", "ㄔ",
        "ㄔㄚ", "ㄔㄜ", "ㄔㄞ", "ㄔㄠ", "ㄔㄡ", "ㄔㄢ", "ㄔㄣ", "ㄔㄤ", "ㄔㄥ", "ㄔㄨ",
        "ㄔㄨㄚ", "ㄔㄨㄛ", "ㄔㄨㄞ", "ㄔㄨㄟ", "ㄔㄨㄢ", "ㄔㄨㄣ", "ㄔㄨㄤ", "ㄔㄨㄥ", "ㄕ", "ㄕㄚ",
        "ㄕㄜ", "ㄕㄞ", "ㄕㄟ", "ㄕㄠ", "ㄕㄡ", "ㄕㄢ", "ㄕㄣ", "ㄕㄤ", "ㄕㄥ", "ㄕㄨ",
        "ㄕㄨㄚ", "ㄕㄨㄛ", "ㄕㄨㄞ", "ㄕㄨㄟ", "ㄕㄨㄢ", "ㄕㄨㄣ", "ㄕㄨㄤ", "ㄖ", "ㄖㄜ", "ㄖㄠ",
        "ㄖㄡ", "ㄖㄢ", "ㄖㄣ", "ㄖㄤ", "ㄖㄥ", "ㄖㄨ", "ㄖㄨㄛ", "ㄖㄨㄟ", "ㄖㄨㄢ", "ㄖㄨㄣ",
        "ㄖㄨㄥ", "ㄗ", "ㄗㄚ", "ㄗㄜ", "ㄗㄞ", "ㄗㄟ", "ㄗㄠ", "ㄗㄡ", "ㄗㄢ", "ㄗㄣ",
        "ㄗㄤ", "ㄗㄥ", "ㄗㄨ", "ㄗㄨㄛ", "ㄗㄨㄟ", "ㄗㄨㄢ", "ㄗㄨㄣ", "ㄗㄨㄥ", "ㄘ", "ㄘㄚ",
        "ㄘㄜ", "ㄘㄞ", "ㄘㄠ", "ㄘㄡ", "ㄘㄢ", "ㄘㄣ", "ㄘㄤ", "ㄘㄥ", "ㄘㄨ", "ㄘㄨㄛ",
        "ㄘㄨㄟ", "ㄘㄨㄢ", "ㄘㄨㄣ", "ㄘㄨㄥ", "ㄙ", "ㄙㄚ", "ㄙㄜ", "ㄙㄞ", "ㄙㄟ", "ㄙㄠ",
        "ㄙㄡ", "ㄙㄢ", "ㄙㄣ", "ㄙㄤ", "ㄙㄥ", "ㄙㄨ", "ㄙㄨㄛ", "ㄙㄨㄟ", "ㄙㄨㄢ", "ㄙㄨㄣ",
        "ㄙㄨㄥ", "ㄚ", "ㄛ", "ㄜ", "ㄝ", "ㄞ", "ㄟ", "ㄠ", "ㄡ", "ㄢ",
        "ㄣ", "ㄤ", "ㄥ", "ㄦ", "ㄧ", "ㄧㄚ", "ㄧㄛ", "ㄧㄝ", "ㄧㄞ", "ㄧㄠ",
        "ㄧㄡ", "ㄧㄢ", "ㄧㄣ", "ㄧㄤ", "ㄧㄥ", "ㄨ", "ㄨㄚ", "ㄨㄛ", "ㄨㄞ", "ㄨㄟ",
        "ㄨㄢ", "ㄨㄣ", "ㄨㄤ", "ㄨㄥ", "ㄩ", "ㄩㄝ", "ㄩㄢ", "ㄩㄣ", "ㄩㄥ"
    )

    private val EXACT_KEY_MAP = mutableMapOf<List<Int>, MutableList<String>>()
    private val SYLLABLE_WEIGHTS = mutableMapOf<String, Long>()

    init {
        for (syl in VALID_SYLLABLES) {
            val seq = KeyMapping.getSequence(syl, ignoreTones = true)
            if (seq.isNotEmpty()) {
                EXACT_KEY_MAP.getOrPut(seq) { mutableListOf() }.add(syl)
            }
        }
    }

    /**
     * 在載入字典時，累計單字的詞頻到對應音節，用於音節排序
     */
    fun addSyllableWeight(cleanZhuyin: String, weight: Int) {
        if (cleanZhuyin.isEmpty()) return
        val current = SYLLABLE_WEIGHTS[cleanZhuyin] ?: 0L
        SYLLABLE_WEIGHTS[cleanZhuyin] = current + weight
    }

    fun getSyllableWeight(syl: String): Long {
        return SYLLABLE_WEIGHTS[syl] ?: 100L
    }

    /**
     * 精確匹配：找出鍵序完全吻合的合法音節，並按音節熱度降序排列
     * 例：[1, 9] -> [ㄅㄨ, ㄉㄤ, ㄉㄨ, ㄅㄤ]
     */
    fun getExactSyllables(keys: List<Int>): List<String> {
        val matches = EXACT_KEY_MAP[keys] ?: return emptyList()
        return matches.sortedByDescending { getSyllableWeight(it) }
    }

    /**
     * 前綴匹配：找出鍵序以前綴開頭的合法音節
     * 例：[1] -> [ㄅㄚ, ㄅㄤ, ㄅㄨ, ㄉㄤ, ...]
     */
    fun getPrefixSyllables(keys: List<Int>, maxCount: Int = 12): List<String> {
        if (keys.isEmpty()) return emptyList()
        val results = mutableListOf<String>()
        for ((seq, sylList) in EXACT_KEY_MAP) {
            if (seq.size > keys.size && seq.subList(0, keys.size) == keys) {
                results.addAll(sylList)
            }
        }
        return results.distinct().sortedByDescending { getSyllableWeight(it) }.take(maxCount)
    }

    val VALID_SYLLABLES_SET: Set<String> by lazy { VALID_SYLLABLES.toHashSet() }

    /**
     * 依據教育部標準 429 個合法音節，將詞彙的無聲調注音字串切分為指定字數的合法音節
     * 例："ㄊㄞㄨㄢ", 2 -> ["ㄊㄞ", "ㄨㄢ"]
     */
    fun splitIntoSyllables(cleanZhuyin: String, wordLength: Int): List<String>? {
        val len = cleanZhuyin.length
        fun backtrack(idx: Int, count: Int): MutableList<String>? {
            if (idx == len && count == wordLength) return ArrayList(wordLength)
            if (idx >= len || count >= wordLength) return null
            for (subLen in 3 downTo 1) {
                if (idx + subLen <= len) {
                    val sub = cleanZhuyin.substring(idx, idx + subLen)
                    if (VALID_SYLLABLES_SET.contains(sub)) {
                        val rem = backtrack(idx + subLen, count + 1)
                        if (rem != null) {
                            rem.add(0, sub)
                            return rem
                        }
                    }
                }
            }
            return null
        }
        return backtrack(0, 0)
    }

    /**
     * 檢查注音字串是否為合法音節（無聲調）
     */
    fun isValidSyllable(cleanZhuyin: String): Boolean {
        return cleanZhuyin in VALID_SYLLABLES_SET
    }
}
