package com.bopomofo.t9ime.engine

/**
 * 詞庫條目
 */
data class DictEntry(
    val word: String,
    val zhuyin: String,
    val weight: Int
)

/**
 * 前綴樹節點
 */
class TrieNode {
    val children = mutableMapOf<Int, TrieNode>()
    val exactEntries = mutableListOf<DictEntry>()
    val prefixEntries = mutableListOf<DictEntry>()
}

class TrieDictionary {
    val root = TrieNode()

    fun insert(entry: DictEntry) {
        val noToneSeqs = KeyMapping.getTolerantSequences(entry.zhuyin, ignoreTones = true)
        for ((index, seq) in noToneSeqs.withIndex()) {
            if (seq.isNotEmpty()) {
                val weightFactor = if (index == 0) 1.0 else 0.85
                val adjustedEntry = if (index == 0) entry else DictEntry(entry.word, entry.zhuyin, (entry.weight * weightFactor).toInt())
                insertSequence(seq, adjustedEntry)
            }
        }

        val fullSeqs = KeyMapping.getTolerantSequences(entry.zhuyin, ignoreTones = false)
        for ((index, seq) in fullSeqs.withIndex()) {
            if (seq.isNotEmpty() && !noToneSeqs.contains(seq)) {
                val weightFactor = if (index == 0) 1.0 else 0.85
                val adjustedEntry = if (index == 0) entry else DictEntry(entry.word, entry.zhuyin, (entry.weight * weightFactor).toInt())
                insertSequence(seq, adjustedEntry)
            }
        }
    }

    private fun insertSequence(seq: List<Int>, entry: DictEntry) {
        var curr = root
        for (k in seq) {
            curr = curr.children.getOrPut(k) { TrieNode() }
        }
        curr.exactEntries.add(entry)
    }

    /**
     * 高效檢索：直接獲取當前按鍵節點，並搜集候選詞（毫秒級完成）
     */
    fun searchNode(sequence: List<Int>): TrieNode? {
        var curr = root
        for (k in sequence) {
            val next = curr.children[k] ?: return null
            curr = next
        }
        return curr
    }

    fun search(sequence: List<Int>): List<DictEntry> {
        if (sequence.isEmpty()) return emptyList()

        val targetNode = searchNode(sequence) ?: return emptyList()

        val exactList = targetNode.exactEntries.distinctBy { it.word }.sortedByDescending { it.weight }
        val prefixList = mutableListOf<DictEntry>()
        collectPrefix(targetNode, prefixList, 0, maxDepth = 3)

        val sortedPrefix = prefixList.distinctBy { it.word }.sortedByDescending { it.weight }

        val combined = mutableListOf<DictEntry>()
        combined.addAll(exactList)
        combined.addAll(sortedPrefix.filter { p -> exactList.none { it.word == p.word } })

        return combined
    }

    private fun collectPrefix(node: TrieNode, results: MutableList<DictEntry>, depth: Int, maxDepth: Int) {
        if (depth > maxDepth || results.size > 50) return
        for ((_, child) in node.children) {
            results.addAll(child.exactEntries)
            collectPrefix(child, results, depth + 1, maxDepth)
        }
    }
}
