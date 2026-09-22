package com.bopomofo.t9ime.engine

import android.content.Context
import com.bopomofo.t9ime.ui.HandwritingCanvasView
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * 高精度離線手寫辨識引擎
 * 基於標準筆畫特徵提取（橫1、豎2、撇3、捺點4、折5）、幾何轉折角分析、字元封閉環形檢測，
 * 並結合 8000+ 常用繁體中文字筆順庫進行動態 Levenshtein 編輯距離比對與頻率加權。
 */
class OfflineHandwritingRecognizer(context: Context) {

    data class StrokeEntry(val char: String, val code: String, val freq: Long)

    // 依筆畫數分組索引：strokeCount -> List<StrokeEntry>
    private val strokeIndex = mutableMapOf<Int, MutableList<StrokeEntry>>()
    private val allEntries = mutableListOf<StrokeEntry>()

    init {
        loadStrokeDatabase(context)
    }

    private fun loadStrokeDatabase(context: Context) {
        try {
            context.assets.open("handwriting_strokes.txt").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) continue
                        val parts = trimmed.split("\t")
                        if (parts.size >= 2) {
                            val ch = parts[0]
                            val code = parts[1]
                            val freq = if (parts.size >= 3) parts[2].toLongOrNull() ?: 100L else 100L
                            val entry = StrokeEntry(ch, code, freq)
                            allEntries.add(entry)

                            val count = code.length
                            val list = strokeIndex.getOrPut(count) { mutableListOf() }
                            list.add(entry)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 辨識筆畫軌跡，回傳候選字列表
     */
    fun recognize(strokes: List<List<HandwritingCanvasView.StrokePoint>>): List<DictEntry> {
        if (strokes.isEmpty()) return emptyList()

        val results = mutableListOf<DictEntry>()
        val count = strokes.size

        // 1. 特殊單筆或雙筆形狀檢測（數字、符號、極簡字）
        if (count == 1) {
            val s = strokes.first()
            if (isClosedLoop(s)) {
                return listOf(
                    DictEntry("0", "", 200000),
                    DictEntry("O", "", 190000),
                    DictEntry("o", "", 180000),
                    DictEntry("口", "ㄎㄡˇ", 170000)
                )
            }
            val code = classifySingleStroke(s)
            if (code == '1') {
                return listOf(
                    DictEntry("一", "ㄧ", 200000),
                    DictEntry("1", "", 180000),
                    DictEntry("乙", "ㄧˇ", 160000),
                    DictEntry("-", "", 150000)
                )
            } else if (code == '2') {
                return listOf(
                    DictEntry("1", "", 200000),
                    DictEntry("丨", "ㄍㄨㄣˇ", 180000),
                    DictEntry("十", "ㄕˊ", 150000),
                    DictEntry("l", "", 140000)
                )
            } else if (code == '5') {
                return listOf(
                    DictEntry("乙", "ㄧˇ", 200000),
                    DictEntry("7", "", 190000),
                    DictEntry("2", "", 180000),
                    DictEntry("C", "", 170000),
                    DictEntry("L", "", 160000)
                )
            }
        }

        // 2. 提取整體筆畫代碼字串（1~5）
        val userStrokeCode = StringBuilder()
        for (stroke in strokes) {
            userStrokeCode.append(classifySingleStroke(stroke))
        }
        val userCode = userStrokeCode.toString()

        // 3. 搜尋筆畫數鄰近的候選字庫 (count - 1, count, count + 1, count + 2)
        val searchCounts = mutableListOf(count)
        if (count > 1) searchCounts.add(count - 1)
        searchCounts.add(count + 1)
        if (count > 2) searchCounts.add(count - 2)
        searchCounts.add(count + 2)

        data class ScoredCandidate(val char: String, val score: Double)
        val candidates = mutableListOf<ScoredCandidate>()
        val seenChars = mutableSetOf<String>()

        for (cnt in searchCounts) {
            val entries = strokeIndex[cnt] ?: continue
            for (entry in entries) {
                if (entry.char in seenChars) continue

                val dist = levenshtein(userCode, entry.code)
                val maxLen = max(userCode.length, entry.code.length)
                val similarity = 1.0 - (dist.toDouble() / maxLen.toDouble())

                // 相似度大於 0.45 視為合格候選
                if (similarity >= 0.45) {
                    val exactBonus = if (dist == 0) 250000.0 else 0.0
                    val countBonus = if (cnt == count) 30000.0 else 0.0
                    val freqWeight = log10(max(10.0, entry.freq.toDouble())) * 4000.0
                    val finalScore = (similarity * 150000.0) + exactBonus + countBonus + freqWeight

                    candidates.add(ScoredCandidate(entry.char, finalScore))
                    seenChars.add(entry.char)
                }
            }
        }

        // 4. 排序並生成最終結果
        candidates.sortByDescending { it.score }
        for (cand in candidates.take(25)) {
            results.add(DictEntry(cand.char, "", cand.score.toInt()))
        }

        // 5. 若比對結果過少，兜底補入常見筆畫數單字
        if (results.size < 5) {
            val fallbackEntries = strokeIndex[count] ?: emptyList()
            for (fb in fallbackEntries.take(10)) {
                if (fb.char !in seenChars) {
                    results.add(DictEntry(fb.char, "", 10000))
                    seenChars.add(fb.char)
                }
            }
        }

        return results
    }

    /**
     * 單筆畫幾何分類：
     * 1: 橫 (含提)
     * 2: 豎 (含豎鉤)
     * 3: 撇
     * 4: 捺、點
     * 5: 折 (轉折筆畫)
     */
    private fun classifySingleStroke(pts: List<HandwritingCanvasView.StrokePoint>): Char {
        if (pts.size < 2) return '4' // 點

        var arcLength = 0.0
        for (i in 0 until pts.size - 1) {
            arcLength += hypot((pts[i + 1].x - pts[i].x).toDouble(), (pts[i + 1].y - pts[i].y).toDouble())
        }
        if (arcLength < 22.0) return '4' // 極短筆畫為點

        val p0 = pts.first()
        val pk = pts.last()
        val disp = hypot((pk.x - p0.x).toDouble(), (pk.y - p0.y).toDouble())

        // 1. 檢測最大直線偏移距離 (Max perpendicular deviation)
        val lineA = (pk.y - p0.y).toDouble()
        val lineB = -(pk.x - p0.x).toDouble()
        val lineC = (pk.x * p0.y - pk.y * p0.x).toDouble()
        val denom = hypot(lineA, lineB)
        var maxDev = 0.0
        if (denom > 1e-4) {
            for (p in pts) {
                val d = abs(lineA * p.x + lineB * p.y + lineC) / denom
                if (d > maxDev) maxDev = d
            }
        }

        // 2. 檢測前段與後段角度變化 (Corner / Turn detection)
        val n = pts.size
        val pThird1 = pts[max(1, n / 3)]
        val pThird2 = pts[min(n - 2, (2 * n) / 3)]
        val ang1 = Math.toDegrees(atan2((pThird1.y - p0.y).toDouble(), (pThird1.x - p0.x).toDouble()))
        val ang2 = Math.toDegrees(atan2((pk.y - pThird2.y).toDouble(), (pk.x - pThird2.x).toDouble()))
        val angleDiff = abs((ang1 - ang2 + 180.0) % 360.0 - 180.0)

        val devRatio = maxDev / arcLength
        val dispRatio = disp / arcLength

        // 顯著折角判定
        if (angleDiff > 45.0 || (dispRatio < 0.78 && devRatio > 0.18)) {
            return '5' // 折
        }

        // 3. 直線筆畫方向判定
        val overallAngle = Math.toDegrees(atan2((pk.y - p0.y).toDouble(), (pk.x - p0.x).toDouble()))

        return when {
            overallAngle in -35.0..35.0 -> '1'  // 橫
            overallAngle in -75.0..-35.0 -> '1' // 提
            overallAngle in 60.0..120.0 -> '2'  // 豎
            overallAngle in 120.0..175.0 -> '3' // 撇 (向左下)
            overallAngle in 35.0..60.0 -> '4'   // 捺 (向右下)
            overallAngle < -120.0 || overallAngle > 175.0 -> '3' // 向左/左下撇
            else -> '1'
        }
    }

    /**
     * 檢測筆畫是否為封閉環（如 0, O, o, 口等）
     */
    private fun isClosedLoop(pts: List<HandwritingCanvasView.StrokePoint>): Boolean {
        if (pts.size < 6) return false
        var arcLen = 0.0
        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE

        for (i in pts.indices) {
            val p = pts[i]
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
            if (i < pts.size - 1) {
                arcLen += hypot((pts[i + 1].x - p.x).toDouble(), (pts[i + 1].y - p.y).toDouble())
            }
        }

        val width = maxX - minX
        val height = maxY - minY
        val p0 = pts.first()
        val pk = pts.last()
        val endDist = hypot((pk.x - p0.x).toDouble(), (pk.y - p0.y).toDouble())

        // 起點與終點距離接近，且包圍盒有一定寬高，且軌跡長度大於包圍盒周長的一半
        return (endDist < max(width, height) * 0.35f) && (width > 25f && height > 25f) && (arcLen > (width + height))
    }

    /**
     * Levenshtein 編輯距離計算
     */
    private fun levenshtein(s1: String, s2: String): Int {
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length

        var prev = IntArray(s2.length + 1) { it }
        var curr = IntArray(s2.length + 1)

        for (i in s1.indices) {
            curr[0] = i + 1
            val c1 = s1[i]
            for (j in s2.indices) {
                val cost = if (c1 == s2[j]) 0 else 1
                curr[j + 1] = min(
                    min(curr[j] + 1, prev[j + 1] + 1),
                    prev[j] + cost
                )
            }
            val temp = prev
            prev = curr
            curr = temp
        }
        return prev[s2.length]
    }
}
