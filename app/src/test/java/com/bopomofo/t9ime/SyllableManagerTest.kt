package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.SyllableManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyllableManagerTest {
    @Test
    fun testValidSyllable() {
        assertTrue(SyllableManager.isValidSyllable("ㄒㄧㄢ"))
        assertTrue(SyllableManager.isValidSyllable("ㄓㄨ"))
        assertTrue(SyllableManager.isValidSyllable("ㄇㄚ"))
        assertTrue(SyllableManager.isValidSyllable("ㄕ"))
        assertTrue(SyllableManager.isValidSyllable("ㄍㄨㄛ"))

        assertFalse(SyllableManager.isValidSyllable("ㄐㄊ"))
        assertFalse(SyllableManager.isValidSyllable("ㄅㄓㄉ"))
        assertFalse(SyllableManager.isValidSyllable("ㄐㄧㄣㄊ"))
    }
}
