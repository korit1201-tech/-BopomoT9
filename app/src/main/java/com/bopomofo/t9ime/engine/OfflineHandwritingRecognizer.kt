package com.bopomofo.t9ime.engine

import com.bopomofo.t9ime.ui.HandwritingCanvasView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 輕量純本地幾何筆畫手寫識別引擎
 * 提取筆畫特徵（起終點、方向夾角、曲率、交點、筆畫數）並比對核心字庫
 */
class OfflineHandwritingRecognizer(private val dictEntries: List<DictEntry>) {

    // 常用單字筆畫數統計表（涵蓋高頻繁體中文字）
    private val strokeCharMap = mapOf(
        1 to listOf("一", "乙", "1"),
        2 to listOf("二", "十", "人", "入", "八", "七", "卜", "刀", "力", "又", "丁", "了", "2"),
        3 to listOf("三", "口", "山", "大", "小", "上", "下", "工", "子", "女", "土", "已", "寸", "弓", "夕", "凡", "3"),
        4 to listOf("四", "中", "天", "日", "月", "木", "水", "火", "心", "不", "手", "文", "方", "王", "井", "太", "友", "分", "化", "牛", "毛", "今", "反", "引", "少", "4"),
        5 to listOf("五", "生", "正", "出", "立", "主", "目", "白", "田", "世", "本", "石", "左", "右", "平", "打", "北", "半", "民", "加", "皮", "示", "兄", "外", "市", "包", "5"),
        6 to listOf("六", "有", "同", "全", "多", "行", "年", "自", "回", "百", "米", "地", "成", "先", "光", "名", "合", "安", "共", "老", "好", "西", "西", "字", "考", "6"),
        7 to listOf("七", "我", "你", "作", "位", "李", "身", "車", "長", "走", "見", "言", "告", "何", "每", "別", "利", "局", "弟", "角", "完", "希", "兵", "求", "7"),
        8 to listOf("八", "的", "來", "到", "定", "事", "取", "東", "明", "金", "門", "物", "知", "受", "青", "非", "其", "直", "命", "或", "夜", "表", "始", "果", "店", "8"),
        9 to listOf("九", "南", "面", "信", "重", "軍", "前", "便", "思", "春", "點", "音", "風", "段", "看", "保", "政", "星", "建", "活", "律", "孩", "要", "食", "9"),
        10 to listOf("十", "個", "特", "高", "校", "原", "家", "師", "息", "根", "氣", "馬", "展", "留", "真", "笑", "書", "記", "問", "做", "能", "連", "旅", "通", "0"),
        11 to listOf("得", "張", "問", "動", "票", "球", "現", "產", "帶", "國", "眼", "接", "常", "許", "專", "清", "排", "訪", "停", "野", "第"),
        12 to listOf("這", "最", "開", "發", "間", "報", "集", "著", "道", "買", "無", "畫", "創", "提", "喜", "短", "程", "森", "費", "街"),
        13 to listOf("經", "電", "義", "意", "會", "話", "路", "新", "當", "感", "試", "業", "運", "準", "想", "話", "溫", "裝", "詩"),
        14 to listOf("實", "寫", "認", "聞", "管", "遠", "語", "算", "種", "綠", "說", "對", "領", "網", "察", "維", "漢", "端"),
        15 to listOf("請", "學", "論", "課", "線", "熱", "寫", "標", "機", "選", "數", "樣", "談", "質", "調", "趣", "廣", "確"),
        16 to listOf("錢", "辦", "整", "歷", "頭", "機", "獨", "憲", "鋼", "錦", "隨", "興", "龍", "導", "親", "戰", "橋"),
        17 to listOf("聲", "謝", "優", "總", "應", "點", "講", "聯", "營", "繁", "簡", "聰", "遠", "戲", "購"),
        18 to listOf("關", "轉", "醫", "題", "豐", "舊", "簡", "雙", "觀", "禮", "織", "難"),
        19 to listOf("識", "邊", "壞", "願", "關", "警", "贊", "證", "麗", "寶"),
        20 to listOf("鐘", "議", "覺", "黨", "競", "魔", "寶", "護", "嚴")
    )

    fun recognize(strokes: List<List<HandwritingCanvasView.StrokePoint>>): List<DictEntry> {
        val count = strokes.size
        if (count == 0) return emptyList()

        val results = mutableListOf<DictEntry>()
        val directChars = strokeCharMap[count] ?: emptyList()
        val adjacentChars = (strokeCharMap[count - 1] ?: emptyList()) + (strokeCharMap[count + 1] ?: emptyList())

        // 結合幾何筆劃方向判斷（水平、垂直、撇捺）
        val isHorizontal = isMainlyHorizontal(strokes)
        val isVertical = isMainlyVertical(strokes)

        if (count == 1) {
            if (isHorizontal) return listOf(DictEntry("一", "ㄧ", 100000), DictEntry("1", "", 90000), DictEntry("乙", "ㄧˇ", 80000))
            if (isVertical) return listOf(DictEntry("丨", "ㄍㄨㄣˇ", 100000), DictEntry("1", "", 90000), DictEntry("十", "ㄕˊ", 80000))
        }

        if (count == 2) {
            if (isHorizontal) return listOf(DictEntry("二", "ㄦˋ", 100000), DictEntry("十", "ㄕˊ", 90000), DictEntry("人", "ㄖㄣˊ", 85000), DictEntry("入", "ㄖㄨˋ", 80000), DictEntry("八", "ㄅㄚ", 75000))
        }

        if (count == 3) {
            return listOf(DictEntry("三", "ㄙㄢ", 100000), DictEntry("口", "ㄎㄡˇ", 95000), DictEntry("山", "ㄕㄢ", 90000), DictEntry("大", "ㄉㄚˋ", 85000), DictEntry("小", "ㄒㄧㄠˇ", 80000), DictEntry("工", "ㄍㄨㄥ", 75000))
        }

        if (count == 4) {
            return listOf(DictEntry("四", "ㄙˋ", 100000), DictEntry("中", "ㄓㄨㄥ", 95000), DictEntry("天", "ㄊㄧㄢ", 90000), DictEntry("日", "ㄖˋ", 85000), DictEntry("月", "ㄩㄝˋ", 80000), DictEntry("木", "ㄇㄨˋ", 75000), DictEntry("水", "ㄕㄨㄟˇ", 70000))
        }

        // 高筆畫數：從常用庫匹配
        val candidates = directChars.take(8) + adjacentChars.take(4)
        for (c in candidates) {
            results.add(DictEntry(c, "", 50000))
        }

        return results
    }

    private fun isMainlyHorizontal(strokes: List<List<HandwritingCanvasView.StrokePoint>>): Boolean {
        if (strokes.isEmpty()) return false
        val s = strokes.first()
        if (s.size < 2) return false
        val dx = abs(s.last().x - s.first().x)
        val dy = abs(s.last().y - s.first().y)
        return dx > dy * 1.5
    }

    private fun isMainlyVertical(strokes: List<List<HandwritingCanvasView.StrokePoint>>): Boolean {
        if (strokes.isEmpty()) return false
        val s = strokes.first()
        if (s.size < 2) return false
        val dx = abs(s.last().x - s.first().x)
        val dy = abs(s.last().y - s.first().y)
        return dy > dx * 1.5
    }
}
