package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.DictEntry
import com.bopomofo.t9ime.engine.TrieDictionary
import com.bopomofo.t9ime.engine.KeyMapping
import org.junit.Assert.assertEquals
import org.junit.Test

class TrieDictionaryTest {
    @Test
    fun testInsertAndSearch() {
        val trie = TrieDictionary()
        val entry1 = DictEntry("測試", "ㄘㄜˋ ㄕˋ", 100)
        val entry2 = DictEntry("測試", "ㄘㄜˋ ㄕˋ", 200)
        trie.insert(entry1)
        trie.insert(entry2)
        val keys = KeyMapping.getSequence(entry1.zhuyin)
        val results = trie.searchExact(keys)
        assertEquals(1, results.size)
        assertEquals(200, results[0].weight)
    }
}
