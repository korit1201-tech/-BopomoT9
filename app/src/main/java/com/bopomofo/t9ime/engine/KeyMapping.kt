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
}
