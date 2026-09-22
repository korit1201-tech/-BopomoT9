package com.bopomofo.t9ime.engine

/**
 * 12 宮格鍵位定義與反向映射表
 */
object KeyMapping {

    // 12 宮格主要鍵位的字元集合 (K1 ~ K12)
    val KEY_DEFINITIONS = mapOf(
        1 to listOf('ㄅ', 'ㄉ', 'ㄚ'),
        2 to listOf('ㄍ', 'ㄐ', 'ㄞ'),
        3 to listOf('ㄓ', 'ㄗ', 'ㄢ', 'ㄦ'),
        4 to listOf('ㄆ', 'ㄊ', 'ㄛ'),
        5 to listOf('ㄎ', 'ㄑ', 'ㄟ'),
        6 to listOf('ㄔ', 'ㄘ', 'ㄣ', 'ㄧ'),
        7 to listOf('ㄇ', 'ㄋ', 'ㄜ'),
        8 to listOf('ㄏ', 'ㄒ', 'ㄠ', 'ㄡ'),
        9 to listOf('ㄕ', 'ㄙ', 'ㄤ', 'ㄨ'),
        10 to listOf('ㄈ', 'ㄌ', 'ㄝ'),
        11 to listOf('ˇ', 'ˋ', 'ˊ', '˙'), // 聲調鍵 (3, 4, 2, 輕聲)
        12 to listOf('ㄖ', 'ㄥ', 'ㄩ')
    )

    // 注音符號 -> Key ID 的反向查表
    private val CHAR_TO_KEY_MAP = mutableMapOf<Char, Int>()

    init {
        for ((keyId, chars) in KEY_DEFINITIONS) {
            for (ch in chars) {
                CHAR_TO_KEY_MAP[ch] = keyId
            }
        }
    }

    /**
     * 零韻母聲母集合（參考 libchewing）
     * ㄓ/ㄔ/ㄕ/ㄖ/ㄗ/ㄘ/ㄙ 這七個聲母可以不搭配韻母單獨成音節（空韻 ㄭ）
     * 台灣使用者有時習慣在後面多輸入一個 ㄜ，需要同時建立兩種鍵序索引
     */
    private val ZERO_RHYME_CONSONANTS = setOf('ㄓ', 'ㄔ', 'ㄕ', 'ㄖ', 'ㄗ', 'ㄘ', 'ㄙ')

    /** 所有聲母（用於判斷下一個字元是否為新音節的開頭） */
    private val ALL_INITIALS = setOf(
        'ㄅ', 'ㄆ', 'ㄇ', 'ㄈ', 'ㄉ', 'ㄊ', 'ㄋ', 'ㄌ',
        'ㄍ', 'ㄎ', 'ㄏ', 'ㄐ', 'ㄑ', 'ㄒ',
        'ㄓ', 'ㄔ', 'ㄕ', 'ㄖ', 'ㄗ', 'ㄘ', 'ㄙ'
    )

    /** 聲調符號集合 */
    private val TONE_MARKS = setOf('ˊ', 'ˇ', 'ˋ', '˙')

    /**
     * 取得某個注音字元所屬的鍵位編號 (1~12)
     */
    fun getKeyId(char: Char): Int? = CHAR_TO_KEY_MAP[char]

    /**
     * 取得該按鍵上的所有注音字元
     */
    fun getChars(keyId: Int): List<Char> = KEY_DEFINITIONS[keyId] ?: emptyList()

    /**
     * 將一個注音字串（包含或不包含聲調）轉換成【無聲調】與【帶聲調】的鍵位序列
     */
    fun getSequence(zhuyin: String, ignoreTones: Boolean = false): List<Int> {
        val list = mutableListOf<Int>()
        for (ch in zhuyin) {
            val k = CHAR_TO_KEY_MAP[ch]
            if (k != null) {
                if (ignoreTones && k == 11) {
                    continue
                }
                list.add(k)
            }
        }
        return list
    }

    /**
     * 零韻母展開：對注音字串中每個「零韻母聲母」後面插入 ㄜ，
     * 產生使用者「多打一個 ㄜ」時對應的展開注音字串。
     *
     * 例：ㄕˊㄇㄜ˙ → ㄕㄜˊㄇㄜ˙（讓 [9,7,7,7] 也能查到「什麼」）
     *
     * 判斷「零韻母」的條件：該聲母後面緊接著聲調符號、另一個聲母，或字串結尾
     */
    private fun buildZeroRhymeExpanded(zhuyin: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < zhuyin.length) {
            val ch = zhuyin[i]
            sb.append(ch)
            if (ch in ZERO_RHYME_CONSONANTS) {
                val next = zhuyin.getOrNull(i + 1)
                val isZeroRhyme = next == null || next in TONE_MARKS || next in ALL_INITIALS
                if (isZeroRhyme) {
                    sb.append('ㄜ') // 注入使用者可能多打的 ㄜ
                }
            }
            i++
        }
        return sb.toString()
    }

    /**
     * 語音容錯正規化：針對台灣人常見的發音混淆（ㄣ/ㄥ, ㄢ/ㄤ, ㄓ/ㄗ, ㄔ/ㄘ, ㄕ/ㄙ, ㄧㄣ/ㄧㄥ）
     * 以及零韻母聲母（ㄓ/ㄔ/ㄕ/ㄖ/ㄗ/ㄘ/ㄙ）使用者多打 ㄜ 的習慣，
     * 產生容錯鍵位序列，讓模糊拼寫依然能精準命中
     */
    fun getTolerantSequences(zhuyin: String, ignoreTones: Boolean = false): List<List<Int>> {
        val baseSeq = getSequence(zhuyin, ignoreTones)
        val results = mutableListOf(baseSeq)

        // 常見容錯替換表
        val replacements = listOf(
            "ㄣ" to "ㄥ", "ㄥ" to "ㄣ",
            "ㄢ" to "ㄤ", "ㄤ" to "ㄢ",
            "ㄓ" to "ㄗ", "ㄗ" to "ㄓ",
            "ㄔ" to "ㄘ", "ㄘ" to "ㄔ",
            "ㄕ" to "ㄙ", "ㄙ" to "ㄕ",
            "ㄧㄣ" to "ㄧㄥ", "ㄧㄥ" to "ㄧㄣ",
            "ㄨㄥ" to "ㄨㄣ", "ㄩㄥ" to "ㄩㄣ"
        )

        for ((from, to) in replacements) {
            if (zhuyin.contains(from)) {
                val altZhuyin = zhuyin.replace(from, to)
                val altSeq = getSequence(altZhuyin, ignoreTones)
                if (altSeq.isNotEmpty() && altSeq != baseSeq && !results.contains(altSeq)) {
                    results.add(altSeq)
                }
            }
        }

        // 零韻母容錯（libchewing 策略）：對可能被使用者多打 ㄜ 的音節產生展開鍵序
        val expandedZhuyin = buildZeroRhymeExpanded(zhuyin)
        if (expandedZhuyin != zhuyin) {
            val expandedSeq = getSequence(expandedZhuyin, ignoreTones)
            if (expandedSeq.isNotEmpty() && !results.contains(expandedSeq)) {
                results.add(expandedSeq)
            }
        }

        return results
    }
}
