package com.bopomofo.t9ime.engine

/**
 * 詞庫條目
 */
data class DictEntry(
    val word: String,
    val zhuyin: String,
    val weight: Int,
    val isTolerant: Boolean = false
)

/**
 * 前綴樹節點
 */
class TrieNode {
    val children = mutableMapOf<Int, TrieNode>()
    val exactEntries = mutableListOf<DictEntry>()
}

class TrieDictionary {
    val root = TrieNode()

    fun insert(entry: DictEntry) {
        val seqNoTone = KeyMapping.getSequence(entry.zhuyin, ignoreTones = true)
        if (seqNoTone.isNotEmpty()) {
            insertSequence(seqNoTone, entry)
        }

        val seqWithTone = KeyMapping.getSequence(entry.zhuyin, ignoreTones = false)
        if (seqWithTone.isNotEmpty() && seqWithTone != seqNoTone) {
            insertSequence(seqWithTone, entry)
        }

        // 僅對常用詞彙（詞長 <= 3 且 weight >= 60）進行跨鍵位容錯索引（避免 18 萬詞全面展開造成記憶體爆炸與 OOM）
        if (entry.word.length <= 3 && entry.weight >= 60 && !entry.isTolerant) {
            val altSeqs = KeyMapping.getCrossKeyTolerantSequences(entry.zhuyin)
            for (altSeq in altSeqs) {
                if (altSeq != seqNoTone && altSeq != seqWithTone) {
                    val adjustedEntry = DictEntry(
                        entry.word,
                        entry.zhuyin,
                        (entry.weight * 0.35).toInt(),
                        isTolerant = true
                    )
                    insertSequence(altSeq, adjustedEntry)
                }
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

        val exactList = searchExact(sequence)
        val prefixList = searchPrefix(sequence)

        val combined = mutableListOf<DictEntry>()
        combined.addAll(exactList)
        combined.addAll(prefixList)
        return combined
    }

    fun searchExact(sequence: List<Int>): List<DictEntry> {
        if (sequence.isEmpty()) return emptyList()
        val targetNode = searchNode(sequence) ?: return emptyList()
        return targetNode.exactEntries
            .distinctBy { it.word }
            .sortedWith(
                compareByDescending<DictEntry> { !it.isTolerant }
                    .thenByDescending { it.weight }
            )
    }

    fun searchPrefix(sequence: List<Int>, maxDepth: Int = 3): List<DictEntry> {
        if (sequence.isEmpty()) return emptyList()
        val targetNode = searchNode(sequence) ?: return emptyList()
        val exactWords = targetNode.exactEntries.map { it.word }.toSet()
        val prefixList = mutableListOf<DictEntry>()
        collectPrefix(targetNode, prefixList, 0, maxDepth)
        return prefixList
            .distinctBy { it.word }
            .filter { it.word !in exactWords }
            .sortedWith(
                compareByDescending<DictEntry> { !it.isTolerant }
                    .thenByDescending { it.weight }
            )
    }

    private fun collectPrefix(node: TrieNode, results: MutableList<DictEntry>, depth: Int, maxDepth: Int) {
        if (depth > maxDepth || results.size >= 50) return
        for ((_, child) in node.children) {
            val remaining = 50 - results.size
            if (remaining <= 0) return
            results.addAll(child.exactEntries.take(remaining))
            collectPrefix(child, results, depth + 1, maxDepth)
        }
    }
}
