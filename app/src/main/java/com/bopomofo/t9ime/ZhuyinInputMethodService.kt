package com.bopomofo.t9ime

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.core.content.ContextCompat
import com.bopomofo.t9ime.engine.ChineseConverter
import com.bopomofo.t9ime.engine.DictEntry
import com.bopomofo.t9ime.engine.ZhuyinT9Engine
import com.bopomofo.t9ime.ui.SwipeKeyButton

/**
 * 繁體注音 12 鍵 Android 輸入法服務（完整進階版）
 */
class ZhuyinInputMethodService : InputMethodService() {

    companion object {
        private const val MAX_CANDIDATES_DISPLAY = 30
        private const val CANDIDATE_BAR_PADDING_PX = 32
        private const val CANDIDATE_BAR_PADDING_VERTICAL_PX = 16
        private const val KEYBOARD_MIN_HEIGHT_DP = 180
        private const val KEYBOARD_MAX_HEIGHT_DP = 380
        private const val KEYBOARD_DEFAULT_HEIGHT_DP = 240
    }

    enum class KeyboardMode {
        ZHUYIN,         // 12 鍵注音
        NUMBER_SYM,     // 12 鍵數字/符號
        ENGLISH_T9,     // 12 鍵英文 (T9 9-Key Multi-tap)
        ENGLISH_QWERTY, // 26 鍵英文全鍵盤
        HANDWRITING     // 手寫輸入
    }

    enum class ChineseInputSubMode {
        TRADITIONAL,    // 繁體
        SIMPLIFIED,     // 簡體
        HANDWRITING     // 手寫
    }

    private var currentMode = KeyboardMode.ZHUYIN
    private var chineseSubMode = ChineseInputSubMode.TRADITIONAL
    private val isSimplified: Boolean
        get() = chineseSubMode == ChineseInputSubMode.SIMPLIFIED

    private var isCapsLock = false   // 大小寫切換狀態
    private var lastCommittedWord: String? = null

    // Multi-tap 狀態追蹤 (9 鍵英文連按輪替：A -> B -> C)
    private var lastT9Key = -1
    private var lastT9CharIndex = 0
    private var lastT9Time = 0L
    private val MULTI_TAP_TIMEOUT = 1000L // 1 秒內連按同一鍵切換字母

    private lateinit var engine: ZhuyinT9Engine
    private var candidateScroll: HorizontalScrollView? = null
    private lateinit var candidateContainer: LinearLayout
    private lateinit var layoutSymbols: LinearLayout
    private lateinit var scrollZhuyinCombos: ScrollView
    private lateinit var containerZhuyinCombos: LinearLayout
    private val candidateTextViewPool = ArrayList<TextView>()
    private val comboButtonPool = ArrayList<Button>()

    private lateinit var layout12Key: LinearLayout
    private lateinit var layoutQwerty: LinearLayout
    private lateinit var layoutHandwriting: FrameLayout
    private lateinit var layoutMainFrame: FrameLayout
    private lateinit var layoutResizeHandle: FrameLayout
    private lateinit var handwritingCanvas: com.bopomofo.t9ime.ui.HandwritingCanvasView
    private var googleRecognizer: com.bopomofo.t9ime.engine.GoogleHandwritingRecognizer? = null

    private var rootView: View? = null
    private var vibrator: Vibrator? = null

    private lateinit var btnMode123: Button
    private lateinit var btnLangToggle: Button
    private lateinit var btnSpaceSwipe: SwipeKeyButton
    private lateinit var btnQwertyToggle: Button

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var isRepeatingBackspace = false
    private val INITIAL_REPEAT_DELAY = 400L
    private val REPEAT_INTERVAL = 60L

    private val backspaceRunnable = object : Runnable {
        override fun run() {
            if (isRepeatingBackspace) {
                performBackspace()
                repeatHandler.postDelayed(this, REPEAT_INTERVAL)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        engine = ZhuyinT9Engine(this).apply {
            onDictionaryLoadedListener = {
                if (hasComposing()) {
                    refreshUI(getCandidates())
                }
            }
        }
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        // 初始化 Google ML Kit 官方高精度手寫辨識引擎（背景預先載入/下載）
        googleRecognizer = com.bopomofo.t9ime.engine.GoogleHandwritingRecognizer(this).apply {
            setup(
                languageTag = "zh-Hant",
                onModelReady = {
                    android.util.Log.d("BopomofoIME", "Google ML Kit Digital Ink model ready")
                },
                onDownloading = {
                    android.util.Log.d("BopomofoIME", "Google ML Kit Digital Ink model downloading...")
                }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        googleRecognizer?.close()
    }

    /**
     * 觸發按鍵震動反饋（依據輸入法設定的開關與強度，不依賴易失效的系統全局開關）
     */
    private fun triggerHapticFeedback() {
        try {
            val prefs = getSharedPreferences("ime_prefs", Context.MODE_PRIVATE)
            val isEnabled = prefs.getBoolean("pref_vibration_enabled", true)
            if (!isEnabled) return

            val strength = prefs.getInt("pref_vibration_strength", 30).coerceIn(5, 100)

            if (vibrator != null && vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val durationMs = strength.toLong()
                    val amplitude = ((strength / 100f) * 255).toInt().coerceIn(1, 255)
                    val effect = VibrationEffect.createOneShot(durationMs, amplitude)
                    vibrator?.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(strength.toLong())
                }
            } else {
                rootView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        } catch (_: Exception) {
            // 忽略非致命震動異常
        }
    }

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.keyboard_view, null)
        rootView = root
        candidateScroll = root.findViewById(R.id.candidate_scroll)
        candidateContainer = root.findViewById(R.id.candidate_container)
        layoutSymbols = root.findViewById(R.id.layout_symbols)
        scrollZhuyinCombos = root.findViewById(R.id.scroll_zhuyin_combos)
        containerZhuyinCombos = root.findViewById(R.id.container_zhuyin_combos)

        layout12Key = root.findViewById(R.id.layout_12key)
        layoutQwerty = root.findViewById(R.id.layout_qwerty)
        layoutHandwriting = root.findViewById(R.id.layout_handwriting)
        layoutMainFrame = root.findViewById(R.id.layout_main_frame)
        layoutResizeHandle = root.findViewById(R.id.layout_resize_handle)
        handwritingCanvas = root.findViewById(R.id.handwriting_canvas)

        // 鍵盤高度拉伸調整（支援上下拖動自由縮放大小，預設 240dp）
        val prefs = getSharedPreferences("ime_prefs", Context.MODE_PRIVATE)
        val savedHeightDp = prefs.getInt("pref_keyboard_height_dp", KEYBOARD_DEFAULT_HEIGHT_DP)
        val density = resources.displayMetrics.density
        layoutMainFrame.layoutParams.height = (savedHeightDp * density).toInt()

        var startY = 0f
        var startHeight = 0
        layoutResizeHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    startHeight = layoutMainFrame.height
                    triggerHapticFeedback()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = startY - event.rawY // 向上拉 deltaY > 0 -> 高度放大
                    val minHeightPx = (KEYBOARD_MIN_HEIGHT_DP * density).toInt()
                    val maxHeightPx = (KEYBOARD_MAX_HEIGHT_DP * density).toInt()
                    val newHeight = (startHeight + deltaY).toInt().coerceIn(minHeightPx, maxHeightPx)
                    if (layoutMainFrame.height != newHeight) {
                        layoutMainFrame.layoutParams.height = newHeight
                        layoutMainFrame.requestLayout()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val finalDp = (layoutMainFrame.height / density).toInt()
                    prefs.edit().putInt("pref_keyboard_height_dp", finalDp).apply()
                    triggerHapticFeedback()
                    true
                }
                else -> false
            }
        }

        handwritingCanvas.onRecognizeListener = { strokes ->
            if (googleRecognizer?.isReady() == true) {
                googleRecognizer?.recognize(
                    strokes,
                    onSuccess = { texts ->
                        val candidates = texts.map { DictEntry(it, "", 100000) }
                        if (candidates.isNotEmpty()) {
                            updateCandidateBar(candidates)
                        }
                    },
                    onError = {
                        android.util.Log.e("BopomofoIME", "Google handwriting recognition error", it)
                    }
                )
            } else {
                val tip = if (googleRecognizer?.isDownloadingModel() == true) {
                    listOf(DictEntry("【手寫模型下載中，請稍候...】", "", 999999))
                } else {
                    listOf(DictEntry("【正在載入 Google 手寫模型...】", "", 999999))
                }
                updateCandidateBar(tip)
            }
        }

        root.findViewById<Button>(R.id.btn_handwriting_clear)?.setOnClickListener {
            triggerHapticFeedback()
            handwritingCanvas.clearCanvas()
            candidateContainer.removeAllViews()
        }

        btnMode123 = root.findViewById(R.id.btn_mode_123)
        btnLangToggle = root.findViewById(R.id.btn_lang_toggle)
        btnSpaceSwipe = root.findViewById(R.id.btn_space_swipe)
        btnQwertyToggle = root.findViewById(R.id.btn_qwerty_toggle)

        setup12KeyLayout(root)
        setupQwertyLayout(root)
        setupSideActions(root)
        setupBottomActions(root)

        updateKeyboardModeUI()
        return root
    }

    private fun setup12KeyLayout(root: View) {
        val keyIds = listOf(
            R.id.key_k1 to 1, R.id.key_k2 to 2, R.id.key_k3 to 3,
            R.id.key_k4 to 4, R.id.key_k5 to 5, R.id.key_k6 to 6,
            R.id.key_k7 to 7, R.id.key_k8 to 8, R.id.key_k9 to 9,
            R.id.key_k10 to 10, R.id.key_k11 to 11, R.id.key_k12 to 12
        )

        for ((viewId, keyNum) in keyIds) {
            val btn = root.findViewById<SwipeKeyButton>(viewId) ?: continue

            btn.onTapListener = {
                triggerHapticFeedback()
                when (currentMode) {
                    KeyboardMode.ZHUYIN -> {
                        lastCommittedWord = null
                        if (keyNum == 11) {
                            val (_, candidates) = engine.cycleTone()
                            refreshUI(candidates)
                        } else {
                            val candidates = engine.pressKey(keyNum)
                            refreshUI(candidates)
                        }
                    }
                    KeyboardMode.NUMBER_SYM -> {
                        commitTextDirectly(getNumberChar(keyNum))
                    }
                    KeyboardMode.ENGLISH_T9 -> {
                        handleT9EnglishTap(keyNum)
                    }
                    else -> {}
                }
            }

            btn.onSwipeListener = { direction ->
                triggerHapticFeedback()
                when (currentMode) {
                    KeyboardMode.ZHUYIN -> {
                        val zhuyin = getSwipeZhuyin(keyNum, direction)
                        if (zhuyin != null) commitTextDirectly(zhuyin.toString())
                    }
                    KeyboardMode.NUMBER_SYM -> {
                        // 純數字模式不提供英文滑動輸入
                    }
                    KeyboardMode.ENGLISH_T9 -> {
                        val letter = getT9EnglishSwipe(keyNum, direction)
                        if (letter != null) {
                            val finalChar = if (isCapsLock) letter.uppercaseChar() else letter
                            commitTextDirectly(finalChar.toString())
                            resetT9MultiTap()
                        }
                    }
                    else -> {}
                }
            }

            // 提供四方向拖選預覽字符（動態十字指示盤與縮放動畫）
            btn.swipeLabelsProvider = {
                val map = mutableMapOf<SwipeKeyButton.Direction, String>()
                when (currentMode) {
                    KeyboardMode.ZHUYIN -> {
                        for (dir in SwipeKeyButton.Direction.values()) {
                            val zh = getSwipeZhuyin(keyNum, dir)
                            if (zh != null) map[dir] = zh.toString()
                        }
                    }
                    KeyboardMode.NUMBER_SYM -> {
                        // 純數字模式不顯示十字滑動字母指示盤
                    }
                    KeyboardMode.ENGLISH_T9 -> {
                        for (dir in SwipeKeyButton.Direction.values()) {
                            val ch = getT9EnglishSwipe(keyNum, dir)
                            if (ch != null) {
                                val finalCh = if (isCapsLock) ch.uppercaseChar() else ch
                                map[dir] = finalCh.toString()
                            }
                        }
                    }
                    else -> {}
                }
                map
            }

            // 長按改為由滑動拖選（Drag-to-select）統一處理，不再彈出干擾彈窗
            btn.onLongClickListenerCustom = null
        }
    }

    /**
     * 數字/英文混合模式長按彈窗：顯示該按鍵所屬的所有字符（數字、字母或符號），點擊直出
     */
    private fun showCombinedNumberEnglishPopup(anchor: View, keyNum: Int) {
        val chars = getCombinedNumberEnglishChars(keyNum)
        if (chars.isEmpty()) return

        val popup = android.widget.PopupMenu(this, anchor)
        for ((index, item) in chars.withIndex()) {
            val display = if (isCapsLock && item.length == 1 && item[0].isLetter()) item.uppercase() else item
            popup.menu.add(0, index, index, display)
        }
        popup.setOnMenuItemClickListener { menuItem ->
            triggerHapticFeedback()
            val selected = chars[menuItem.itemId]
            val finalStr = if (isCapsLock && selected.length == 1 && selected[0].isLetter()) selected.uppercase() else selected
            commitTextDirectly(finalStr)
            resetT9MultiTap()
            true
        }
        popup.show()
    }

    /**
     * 9 鍵英文長按彈出該鍵所屬字母/數字選單（所選即所得）
     */
    private fun showEnglishKeyPopup(anchor: View, keyNum: Int) {
        val chars = getT9CharsForKey(keyNum)
        if (chars.isEmpty()) return

        val popup = android.widget.PopupMenu(this, anchor)
        for ((index, ch) in chars.withIndex()) {
            val displayChar = if (isCapsLock && ch.isLetter()) ch.uppercaseChar() else ch
            popup.menu.add(0, index, index, displayChar.toString())
        }
        popup.setOnMenuItemClickListener { item ->
            triggerHapticFeedback()
            val selectedChar = chars[item.itemId]
            val finalChar = if (isCapsLock && selectedChar.isLetter()) selectedChar.uppercaseChar() else selectedChar
            commitTextDirectly(finalChar.toString())
            resetT9MultiTap()
            true
        }
        popup.show()
    }

    /**
     * 長按 12 鍵彈出該鍵所屬注音符號選單（所選即所得）
     */
    private fun showZhuyinKeyPopup(anchor: View, keyNum: Int) {
        val chars = com.bopomofo.t9ime.engine.KeyMapping.getChars(keyNum)
        if (chars.isEmpty()) return

        val popup = android.widget.PopupMenu(this, anchor)
        for ((index, ch) in chars.withIndex()) {
            popup.menu.add(0, index, index, ch.toString())
        }
        popup.setOnMenuItemClickListener { item ->
            triggerHapticFeedback()
            val selectedChar = chars[item.itemId]
            commitTextDirectly(selectedChar.toString())
            true
        }
        popup.show()
    }

    /**
     * 空白鍵長按快選常用標點（，。？！）
     */
    private fun showQuickPunctuationPopup(anchor: View) {
        val puncts = if (isTraditionalMode()) listOf("，", "。", "！", "？", "……", "：") else listOf(",", ".", "!", "?", "...", ":")
        val popup = android.widget.PopupMenu(this, anchor)
        for ((index, p) in puncts.withIndex()) {
            popup.menu.add(0, index, index, p)
        }
        popup.setOnMenuItemClickListener { item ->
            triggerHapticFeedback()
            val selectedPunct = puncts[item.itemId]
            commitSymbol(selectedPunct)
            true
        }
        popup.show()
    }

    /**
     * 9 鍵英文 Multi-tap 處理（連按同一鍵循環切換：A -> B -> C -> 2）
     */
    private fun handleT9EnglishTap(keyNum: Int) {
        val charList = getT9CharsForKey(keyNum)
        if (charList.isEmpty()) return

        val now = System.currentTimeMillis()
        if (keyNum == lastT9Key && (now - lastT9Time) < MULTI_TAP_TIMEOUT) {
            // 同一按鍵在 1 秒內連按：先刪除前一個字元，再輸出下一個字母！
            currentInputConnection?.deleteSurroundingText(1, 0)
            lastT9CharIndex = (lastT9CharIndex + 1) % charList.size
        } else {
            // 新按鍵或超時：輸出第一個字母
            lastT9Key = keyNum
            lastT9CharIndex = 0
        }
        lastT9Time = now

        val targetChar = charList[lastT9CharIndex]
        val finalChar = if (isCapsLock) targetChar.uppercaseChar() else targetChar
        currentInputConnection?.commitText(finalChar.toString(), 1)
    }

    private fun resetT9MultiTap() {
        lastT9Key = -1
        lastT9CharIndex = 0
        lastT9Time = 0L
    }

    /**
     * 數字/英文混合模式字符表（數字優先，連按同鍵切英文字母）
     * 參考 libchewing 的 9 鍵英文設計，第一個位置永遠是數字
     */
    private fun getCombinedNumberEnglishChars(keyNum: Int): List<String> {
        return when (keyNum) {
            1  -> listOf("1", "@", ".", "_")
            2  -> listOf("2", "a", "b", "c")
            3  -> listOf("3", "d", "e", "f")
            4  -> listOf("4", "g", "h", "i")
            5  -> listOf("5", "j", "k", "l")
            6  -> listOf("6", "m", "n", "o")
            7  -> listOf("7", "p", "q", "r", "s")
            8  -> listOf("8", "t", "u", "v")
            9  -> listOf("9", "w", "x", "y", "z")
            10 -> listOf(".", "-", "+", "*")
            11 -> listOf("0", "/", "=", ")")
            12 -> listOf("#", "%", "&", "!")
            else -> emptyList()
        }
    }

    /**
     * 數字/英文混合按鍵處理：
     * - 第一次按下 → 輸出數字（直接上屏）
     * - 1 秒內再按同一鍵 → 刪除前一字元，輸出下一個英文字母（依序循環）
     */
    private fun handleCombinedNumberEnglishTap(keyNum: Int) {
        val charList = getCombinedNumberEnglishChars(keyNum)
        if (charList.isEmpty()) return

        val now = System.currentTimeMillis()
        if (keyNum == lastT9Key && (now - lastT9Time) < MULTI_TAP_TIMEOUT) {
            currentInputConnection?.deleteSurroundingText(1, 0)
            lastT9CharIndex = (lastT9CharIndex + 1) % charList.size
        } else {
            lastT9Key = keyNum
            lastT9CharIndex = 0
        }
        lastT9Time = now

        val selected = charList[lastT9CharIndex]
        val finalStr = if (isCapsLock && selected.length == 1 && selected[0].isLetter())
            selected.uppercase() else selected
        currentInputConnection?.commitText(finalStr, 1)
    }

    private fun getT9CharsForKey(keyNum: Int): List<Char> {
        return when (keyNum) {
            1 -> listOf('@', '.', '_', '1')
            2 -> listOf('a', 'b', 'c', '2')
            3 -> listOf('d', 'e', 'f', '3')
            4 -> listOf('g', 'h', 'i', '4')
            5 -> listOf('j', 'k', 'l', '5')
            6 -> listOf('m', 'n', 'o', '6')
            7 -> listOf('p', 'q', 'r', 's', '7')
            8 -> listOf('t', 'u', 'v', '8')
            9 -> listOf('w', 'x', 'y', 'z', '9')
            10 -> listOf('-', '+', '*', '(')
            11 -> listOf('/', '=', '0', ')')
            12 -> listOf('%', '&', '#', '!')
            else -> emptyList()
        }
    }

    /**
     * 26 鍵英文全鍵盤 (QWERTY Layout - 包含 Shift大小寫切換、退格鍵、逗點、句號)
     */
    private fun setupQwertyLayout(root: View) {
        val rowSymbols = root.findViewById<LinearLayout>(R.id.qwerty_row_symbols)
        val row1 = root.findViewById<LinearLayout>(R.id.qwerty_row_1)
        val row2 = root.findViewById<LinearLayout>(R.id.qwerty_row_2)
        val row3 = root.findViewById<LinearLayout>(R.id.qwerty_row_3)

        // 0. 常用符號列 (Direct Symbol Row): + - * / = ( ) @ _ &
        val symbols = listOf("+", "-", "*", "/", "=", "(", ")", "@", "_", "&")
        rowSymbols?.removeAllViews()
        for (sym in symbols) {
            rowSymbols?.addView(createQwertySymbolKey(sym, 1f))
        }

        val letters1 = listOf("q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5", "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0")
        val letters2 = listOf("a" to "!", "s" to "?", "d" to "(", "f" to ")", "g" to "[", "h" to "]", "j" to "{", "k" to "}", "l" to "\"")
        val letters3 = listOf("z" to "~", "x" to "\\", "c" to "'", "v" to "<", "b" to ">", "n" to ";", "m" to ":")

        row1?.removeAllViews()
        for ((ch, num) in letters1) {
            row1?.addView(createQwertyKey(ch, 1f, num))
        }

        row2?.removeAllViews()
        for ((ch, sym) in letters2) {
            row2?.addView(createQwertyKey(ch, 1f, sym))
        }

        row3?.removeAllViews()

        // 1. Shift 大小寫切換鍵 (左側)
        val btnShift = Button(this).apply {
            text = if (isCapsLock) "⇪" else "⇧"
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key_action)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.5f).apply {
                setMargins(2, 2, 2, 2)
            }
            layoutParams = params
            setOnClickListener {
                triggerHapticFeedback()
                isCapsLock = !isCapsLock
                updateQwertyKeysText()
            }
        }
        row3?.addView(btnShift)

        // 2. 字母鍵 Z X C V B N M
        for ((ch, sym) in letters3) {
            row3?.addView(createQwertyKey(ch, 1f, sym))
        }

        // 3. ENTER 換行鍵（夾在字母與退格鍵之間，使用者需求）
        val btnQwertyEnter = Button(this).apply {
            text = "↵"
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key_action)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.5f).apply {
                setMargins(2, 2, 2, 2)
            }
            layoutParams = params
            setOnClickListener {
                triggerHapticFeedback()
                performEnterAction()
            }
        }
        row3?.addView(btnQwertyEnter)

        // 4. 26 鍵專屬退格鍵 (右側，支援點按與長按連續退位)
        val btnQwertyDel = Button(this).apply {
            text = "⌫"
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key_action)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.5f).apply {
                setMargins(2, 2, 2, 2)
            }
            layoutParams = params
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        performBackspace()
                        isRepeatingBackspace = true
                        repeatHandler.postDelayed(backspaceRunnable, INITIAL_REPEAT_DELAY)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        isRepeatingBackspace = false
                        repeatHandler.removeCallbacks(backspaceRunnable)
                        true
                    }
                    else -> false
                }
            }
        }
        row3?.addView(btnQwertyDel)
    }

    private fun createQwertySymbolKey(sym: String, weight: Float): Button {
        return Button(this).apply {
            text = sym
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key_action)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(2, 2, 2, 2)
            }
            layoutParams = params
            setOnClickListener {
                triggerHapticFeedback()
                commitTextDirectly(sym)
            }

            // 長按彈出關聯拓展符號選單
            val related = when (sym) {
                "+" -> listOf("=", "±", "++")
                "-" -> listOf("_", "~", "–", "—")
                "*" -> listOf("×", "•", "°", "^")
                "/" -> listOf("÷", "\\", "|")
                "=" -> listOf("≠", "≈", "≤", "≥")
                "(" -> listOf("[", "{", "<", "（", "【")
                ")" -> listOf("]", "}", ">", "）", "】")
                "@" -> listOf("#", "$", "©")
                "_" -> listOf("-", "—")
                "&" -> listOf("%", "$", "§")
                else -> emptyList()
            }
            if (related.isNotEmpty()) {
                setOnLongClickListener {
                    triggerHapticFeedback()
                    val popup = android.widget.PopupMenu(this@ZhuyinInputMethodService, this)
                    for ((index, item) in related.withIndex()) {
                        popup.menu.add(0, index, index, item)
                    }
                    popup.setOnMenuItemClickListener { menuItem ->
                        triggerHapticFeedback()
                        commitTextDirectly(related[menuItem.itemId])
                        true
                    }
                    popup.show()
                    true
                }
            }
        }
    }

    private fun createQwertyKey(text: String, weight: Float, longClickChar: String? = null): Button {
        return Button(this).apply {
            this.text = if (isCapsLock) text.uppercase() else text.lowercase()
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(2, 2, 2, 2)
            }
            layoutParams = params
            setOnClickListener {
                triggerHapticFeedback()
                val letterToCommit = if (isCapsLock) text.uppercase() else text.lowercase()
                commitTextDirectly(letterToCommit)
            }
            if (longClickChar != null) {
                setOnLongClickListener {
                    triggerHapticFeedback()
                    commitTextDirectly(longClickChar)
                    true
                }
            }
        }
    }

    private fun updateQwertyKeysText() {
        val root = layoutQwerty
        setupQwertyLayout(root)
    }

    private lateinit var btnSym1: Button
    private lateinit var btnSym2: Button
    private lateinit var btnSym3: Button
    private lateinit var btnSym4: Button
    private lateinit var btnSym5: Button
    private lateinit var btnComma: Button
    private lateinit var btnPeriod: Button
    private lateinit var btnSymAt: Button
    private var btnClear: Button? = null

    private fun setupSideActions(root: View) {
        btnSym1 = root.findViewById(R.id.btn_sym_1)
        btnSym2 = root.findViewById(R.id.btn_sym_2)
        btnSym3 = root.findViewById(R.id.btn_sym_3)
        btnSym4 = root.findViewById(R.id.btn_sym_4)
        btnSym5 = root.findViewById(R.id.btn_sym_5)
        btnSymAt = root.findViewById(R.id.btn_sym_at)

        updateSymbolsDisplay()

        // @ 位置：中文模式下改為確認/換行(ENTER)鍵，英文/數字模式保留 @
        btnSymAt.setOnClickListener {
            triggerHapticFeedback()
            when (currentMode) {
                KeyboardMode.ZHUYIN, KeyboardMode.HANDWRITING -> {
                    performEnterAction()
                }
                else -> commitSymbol("@")
            }
        }

        val btnBackspace = root.findViewById<Button>(R.id.btn_backspace)
        btnBackspace?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    performBackspace()
                    isRepeatingBackspace = true
                    repeatHandler.postDelayed(backspaceRunnable, INITIAL_REPEAT_DELAY)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    isRepeatingBackspace = false
                    repeatHandler.removeCallbacks(backspaceRunnable)
                    true
                }
                else -> false
            }
        }

        // 清空按鈕：NUMBER_SYM 模式下改為換行鍵
        btnClear = root.findViewById(R.id.btn_clear)
        btnClear?.setOnClickListener {
            triggerHapticFeedback()
            when (currentMode) {
                KeyboardMode.NUMBER_SYM -> {
                    performEnterAction()
                }
                else -> {
                    engine.clear()
                    lastCommittedWord = null
                    resetT9MultiTap()
                    currentInputConnection?.setComposingText("", 1)
                    refreshUI(emptyList())
                }
            }
        }
    }

    /**
     * Enter 鍵動作處理：
     * - 若處於組字未決定狀態（hasComposing），按下 Enter 視為確認（上屏首選字/預測句子），不換行。
     * - 若無組字狀態，則送出正常的換行 (KEYCODE_ENTER) 事件。
     */
    private fun performEnterAction() {
        if (engine.hasComposing()) {
            val candidates = engine.getCandidates()
            if (candidates.isNotEmpty()) {
                selectCandidate(candidates.first())
            } else {
                val topWord = engine.getTopComposingWord()
                if (topWord.isNotEmpty()) {
                    commitProcessedText(topWord)
                }
                engine.clear()
                lastCommittedWord = topWord
                currentInputConnection?.finishComposingText()
                refreshUI(emptyList())
            }
        } else {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun isTraditionalMode(): Boolean {
        // 只要不是簡體模式，逗號/句號就輸出全形（適用注音、數字、手寫模式）
        // 英文模式下強制半形
        return when (currentMode) {
            KeyboardMode.ENGLISH_T9, KeyboardMode.ENGLISH_QWERTY -> false
            else -> !isSimplified
        }
    }


    private fun setupBottomActions(root: View) {
        btnComma = root.findViewById(R.id.btn_comma)
        btnPeriod = root.findViewById(R.id.btn_period)

        btnMode123.setOnClickListener {
            triggerHapticFeedback()
            currentMode = if (currentMode == KeyboardMode.NUMBER_SYM) KeyboardMode.ZHUYIN else KeyboardMode.NUMBER_SYM
            engine.clear()
            lastCommittedWord = null
            resetT9MultiTap()
            currentInputConnection?.setComposingText("", 1)
            refreshUI(emptyList())
            updateKeyboardModeUI()
        }

        // 123 鍵回歸單純點擊切換
        btnMode123.setOnLongClickListener(null)

        // 長按 中/EN 鍵開啟設定畫面，介面帶有 ⚙ 齒輪提示
        btnLangToggle.setOnLongClickListener {
            openSettings()
            true
        }

        btnSpaceSwipe.transformationMethod = null
        btnSpaceSwipe.includeFontPadding = false
        btnSpaceSwipe.setLineSpacing(0f, 0.9f)

        // 提供空白鍵四向滑動指示盤（向左向右提示切換簡體/手寫）
        btnSpaceSwipe.swipeLabelsProvider = {
            if (currentMode == KeyboardMode.ZHUYIN || currentMode == KeyboardMode.HANDWRITING) {
                when (chineseSubMode) {
                    ChineseInputSubMode.TRADITIONAL -> mapOf(
                        SwipeKeyButton.Direction.LEFT to "簡體",
                        SwipeKeyButton.Direction.RIGHT to "手寫"
                    )
                    ChineseInputSubMode.SIMPLIFIED -> mapOf(
                        SwipeKeyButton.Direction.LEFT to "手寫",
                        SwipeKeyButton.Direction.RIGHT to "繁體"
                    )
                    ChineseInputSubMode.HANDWRITING -> mapOf(
                        SwipeKeyButton.Direction.LEFT to "繁體",
                        SwipeKeyButton.Direction.RIGHT to "簡體"
                    )
                }
            } else {
                emptyMap()
            }
        }

        btnQwertyToggle.setOnClickListener {
            triggerHapticFeedback()
            // 26鍵 ↔ 數字/英文9鍵（NUMBER_SYM 承擔原本 ENGLISH_T9 角色）
            currentMode = if (currentMode == KeyboardMode.ENGLISH_QWERTY)
                KeyboardMode.NUMBER_SYM else KeyboardMode.ENGLISH_QWERTY
            engine.clear()
            lastCommittedWord = null
            resetT9MultiTap()
            currentInputConnection?.setComposingText("", 1)
            refreshUI(emptyList())
            updateKeyboardModeUI()
        }

        btnLangToggle.setOnClickListener {
            triggerHapticFeedback()
            currentMode = when (currentMode) {
                KeyboardMode.ZHUYIN, KeyboardMode.NUMBER_SYM, KeyboardMode.HANDWRITING -> KeyboardMode.ENGLISH_QWERTY
                KeyboardMode.ENGLISH_T9, KeyboardMode.ENGLISH_QWERTY -> KeyboardMode.ZHUYIN
            }
            engine.clear()
            lastCommittedWord = null
            resetT9MultiTap()
            currentInputConnection?.setComposingText("", 1)
            refreshUI(emptyList())
            updateKeyboardModeUI()
        }

        btnSpaceSwipe.onTapListener = {
            triggerHapticFeedback()
            resetT9MultiTap()
            if (engine.hasComposing()) {
                val candidates = engine.getCandidates()
                if (candidates.isNotEmpty()) {
                    selectCandidate(candidates.first())
                } else {
                    val topWord = engine.getTopComposingWord()
                    commitProcessedText(topWord)
                    engine.clear()
                    lastCommittedWord = topWord
                    currentInputConnection?.finishComposingText()
                    showNextWordPredictions(topWord)
                }
            } else {
                commitTextDirectly(" ")
                lastCommittedWord = null
            }
        }

        btnSpaceSwipe.onSwipeListener = { direction ->
            triggerHapticFeedback()
            if (direction == SwipeKeyButton.Direction.LEFT || direction == SwipeKeyButton.Direction.RIGHT) {
                if (currentMode == KeyboardMode.ENGLISH_QWERTY) {
                    // QWERTY 26 鍵模式：已有 SHIFT 鍵，滑動空白鍵保持輸入空格
                    commitTextDirectly(" ")
                } else if (currentMode == KeyboardMode.ENGLISH_T9 || currentMode == KeyboardMode.NUMBER_SYM) {
                    // 數字混合模式下：滑動切換「大小寫」
                    isCapsLock = !isCapsLock
                    btnSpaceSwipe.text = if (isCapsLock) "大寫" else "空格"
                    updateKeyboardModeUI()
                } else {
                    // 中文模式下：滑動依序輪替「繁」->「簡」->「手」！
                    chineseSubMode = when (chineseSubMode) {
                        ChineseInputSubMode.TRADITIONAL -> ChineseInputSubMode.SIMPLIFIED
                        ChineseInputSubMode.SIMPLIFIED -> ChineseInputSubMode.HANDWRITING
                        ChineseInputSubMode.HANDWRITING -> ChineseInputSubMode.TRADITIONAL
                    }

                    if (chineseSubMode == ChineseInputSubMode.HANDWRITING) {
                        currentMode = KeyboardMode.HANDWRITING
                        engine.clear()
                        currentInputConnection?.setComposingText("", 1)
                        if (::handwritingCanvas.isInitialized) {
                            handwritingCanvas.clearCanvas()
                        }
                    } else {
                        currentMode = KeyboardMode.ZHUYIN
                    }

                    updateKeyboardModeUI()
                    if (engine.hasComposing()) {
                        refreshUI(engine.getCandidates())
                    } else if (lastCommittedWord != null) {
                        showNextWordPredictions(lastCommittedWord!!)
                    }
                }
            }
        }

        // 空白鍵長按快選常用標點（，。？！……：）
        btnSpaceSwipe.onLongClickListenerCustom = {
            triggerHapticFeedback()
            showQuickPunctuationPopup(btnSpaceSwipe)
        }

        // 逗點與句號 (繁體全形，簡體/英文半形)
        btnComma.setOnClickListener {
            triggerHapticFeedback()
            resetT9MultiTap()
            commitSymbol(if (isTraditionalMode()) "，" else ",")
        }
        btnPeriod.setOnClickListener {
            triggerHapticFeedback()
            resetT9MultiTap()
            commitSymbol(if (isTraditionalMode()) "。" else ".")
        }
    }

    private fun openSettings() {
        triggerHapticFeedback()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    private fun formatLangKeyLabel(lang: String): CharSequence {
        val fullText = "$lang\n⚙"
        val spannable = SpannableString(fullText)
        val split = lang.length
        val primaryColor = ContextCompat.getColor(this, R.color.kb_text_primary)
        val secondaryColor = ContextCompat.getColor(this, R.color.kb_text_secondary)

        spannable.setSpan(RelativeSizeSpan(0.95f), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(StyleSpan(Typeface.BOLD), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(primaryColor), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        spannable.setSpan(RelativeSizeSpan(0.60f), split + 1, fullText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(secondaryColor), split + 1, fullText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return spannable
    }

    private fun formatSpaceChineseSubModeLabel(current: String, leftHint: String, rightHint: String): CharSequence {
        val fullText = "$current\n‹ $leftHint · $rightHint ›"
        val spannable = SpannableString(fullText)
        val split = current.length
        val primaryColor = ContextCompat.getColor(this, R.color.kb_text_primary)
        val secondaryColor = ContextCompat.getColor(this, R.color.kb_text_secondary)

        // 第一行主狀態大字加粗
        spannable.setSpan(RelativeSizeSpan(1.15f), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(StyleSpan(Typeface.BOLD), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(primaryColor), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        // 第二行滑動切換提示
        spannable.setSpan(RelativeSizeSpan(0.60f), split + 1, fullText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(secondaryColor), split + 1, fullText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return spannable
    }

    private fun updateKeyboardModeUI() {
        when (currentMode) {
            KeyboardMode.ZHUYIN -> {
                layout12Key.visibility = View.VISIBLE
                layoutQwerty.visibility = View.GONE
                if (::layoutHandwriting.isInitialized) layoutHandwriting.visibility = View.GONE
                btnMode123.text = "123"
                btnLangToggle.transformationMethod = null
                btnLangToggle.includeFontPadding = false
                btnLangToggle.setLineSpacing(0f, 0.9f)
                btnLangToggle.text = formatLangKeyLabel("中/EN")
                btnLangToggle.setOnTouchListener(null) // 恢復語言切換 click 行為
                btnLangToggle.setOnLongClickListener {
                    openSettings()
                    true
                }

                btnSpaceSwipe.transformationMethod = null
                btnSpaceSwipe.includeFontPadding = false
                btnSpaceSwipe.setLineSpacing(0f, 0.9f)
                btnSpaceSwipe.text = when (chineseSubMode) {
                    ChineseInputSubMode.TRADITIONAL -> formatSpaceChineseSubModeLabel("繁", "簡體", "手寫")
                    ChineseInputSubMode.SIMPLIFIED -> formatSpaceChineseSubModeLabel("簡", "手寫", "繁體")
                    ChineseInputSubMode.HANDWRITING -> formatSpaceChineseSubModeLabel("手", "繁體", "簡體")
                }
                btnQwertyToggle.visibility = View.GONE
                update12KeyLabelsZhuyin()
                if (::btnSymAt.isInitialized) {
                    btnSymAt.text = "↵"  // 中文模式：@ 位置改為換行鍵
                    btnSymAt.visibility = View.VISIBLE
                }
                btnClear?.text = "清空"
            }
            KeyboardMode.HANDWRITING -> {
                layout12Key.visibility = View.GONE
                layoutQwerty.visibility = View.GONE
                if (::layoutHandwriting.isInitialized) layoutHandwriting.visibility = View.VISIBLE
                btnMode123.text = "123"
                btnSpaceSwipe.transformationMethod = null
                btnSpaceSwipe.includeFontPadding = false
                btnSpaceSwipe.setLineSpacing(0f, 0.9f)
                btnSpaceSwipe.text = formatSpaceChineseSubModeLabel("手", "繁體", "簡體")
                btnQwertyToggle.visibility = View.GONE
                if (::btnSymAt.isInitialized) {
                    btnSymAt.text = "↵"  // 手寫模式：@ 位置改為換行鍵
                    btnSymAt.visibility = View.VISIBLE
                }
                btnClear?.text = "清空"
                // 手寫模式下「中/英」位置改為退格鍵
                btnLangToggle.text = "⌫"
                btnLangToggle.setOnLongClickListener(null)
                btnLangToggle.setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            v.isPressed = true
                            triggerHapticFeedback()
                            performBackspace()
                            isRepeatingBackspace = true
                            repeatHandler.postDelayed(backspaceRunnable, INITIAL_REPEAT_DELAY)
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.isPressed = false
                            isRepeatingBackspace = false
                            repeatHandler.removeCallbacks(backspaceRunnable)
                            true
                        }
                        else -> false
                    }
                }
            }
            KeyboardMode.NUMBER_SYM -> {
                layout12Key.visibility = View.VISIBLE
                layoutQwerty.visibility = View.GONE
                if (::layoutHandwriting.isInitialized) layoutHandwriting.visibility = View.GONE
                btnMode123.text = "注音"
                btnLangToggle.transformationMethod = null
                btnLangToggle.includeFontPadding = false
                btnLangToggle.setLineSpacing(0f, 0.9f)
                btnLangToggle.text = formatLangKeyLabel("中/EN")
                btnLangToggle.setOnTouchListener(null)
                btnLangToggle.setOnLongClickListener {
                    openSettings()
                    true
                }
                btnSpaceSwipe.text = if (isCapsLock) "大寫" else "空格"
                btnQwertyToggle.visibility = View.VISIBLE  // 可切換到 26 鍵英文
                btnQwertyToggle.text = "26鍵"
                update12KeyLabelsNumbers()
                if (::btnSymAt.isInitialized) {
                    btnSymAt.text = "@"  // 數字模式：@ 位置保持 @
                    btnSymAt.visibility = View.VISIBLE
                }
                btnClear?.text = "↵"  // 數字/英文模式：清空位置改為換行鍵
            }
            KeyboardMode.ENGLISH_T9 -> {
                // 保留向下相容（正常流程不會到這裡）
                layout12Key.visibility = View.VISIBLE
                layoutQwerty.visibility = View.GONE
                if (::layoutHandwriting.isInitialized) layoutHandwriting.visibility = View.GONE
                btnMode123.text = "123"
                btnLangToggle.transformationMethod = null
                btnLangToggle.includeFontPadding = false
                btnLangToggle.setLineSpacing(0f, 0.9f)
                btnLangToggle.text = formatLangKeyLabel("EN/中")
                btnLangToggle.setOnTouchListener(null)
                btnLangToggle.setOnLongClickListener {
                    openSettings()
                    true
                }
                btnSpaceSwipe.text = if (isCapsLock) "大寫" else "小寫"
                btnQwertyToggle.visibility = View.VISIBLE
                btnQwertyToggle.text = "26鍵"
                update12KeyLabelsT9English()
                if (::btnSymAt.isInitialized) btnSymAt.visibility = View.GONE
                btnClear?.text = "↵"
            }
            KeyboardMode.ENGLISH_QWERTY -> {
                layout12Key.visibility = View.GONE
                layoutQwerty.visibility = View.VISIBLE
                if (::layoutHandwriting.isInitialized) layoutHandwriting.visibility = View.GONE
                btnMode123.text = "123"
                btnLangToggle.transformationMethod = null
                btnLangToggle.includeFontPadding = false
                btnLangToggle.setLineSpacing(0f, 0.9f)
                btnLangToggle.text = formatLangKeyLabel("EN/中")
                btnLangToggle.setOnTouchListener(null) // 恢復語言切換 click 行為
                btnLangToggle.setOnLongClickListener {
                    openSettings()
                    true
                }
                btnSpaceSwipe.text = "空格"
                btnQwertyToggle.visibility = View.GONE // 拿掉 26 鍵時的 9 鍵切換按鈕
                updateQwertyKeysText()
                if (::btnSymAt.isInitialized) {
                    btnSymAt.text = "@"
                    btnSymAt.visibility = View.VISIBLE
                }
                btnClear?.text = "清空"
            }
        }
        updateSymbolsDisplay()
    }

    private fun updateSymbolsDisplay() {
        if (::btnComma.isInitialized) {
            btnComma.text = if (isTraditionalMode()) "，" else ","
        }
        if (::btnPeriod.isInitialized) {
            btnPeriod.text = if (isTraditionalMode()) "。" else "."
        }

        if (!::btnSym1.isInitialized) return

        when (currentMode) {
            KeyboardMode.NUMBER_SYM -> {
                // 數字模式：左側直出「+ - * / =」
                setupSymbolButton(btnSym1, "+", listOf("±", "++"))
                setupSymbolButton(btnSym2, "-", listOf("_", "–", "—"))
                setupSymbolButton(btnSym3, "*", listOf("×", "•", "°"))
                setupSymbolButton(btnSym4, "/", listOf("÷", "\\", "|"))
                setupSymbolButton(btnSym5, "=", listOf("≠", "≈", "≤", "≥"))
            }
            KeyboardMode.ZHUYIN -> {
                // 注音模式：常用全形標點
                setupSymbolButton(btnSym1, "？", listOf("?", "¿"))
                setupSymbolButton(btnSym2, "！", listOf("!", "¡"))
                setupSymbolButton(btnSym3, "……", listOf("…", "—"))
                setupSymbolButton(btnSym4, "：", listOf("；", "『", "』"))
                setupSymbolButton(btnSym5, "～", listOf("·", "《", "》"))
            }
            KeyboardMode.ENGLISH_T9 -> {
                // 9鍵英文模式：半形標點
                setupSymbolButton(btnSym1, "?", listOf("¿"))
                setupSymbolButton(btnSym2, "!", listOf("¡"))
                setupSymbolButton(btnSym3, "...", listOf("…", "—"))
                setupSymbolButton(btnSym4, ":", listOf(";", "\""))
                setupSymbolButton(btnSym5, "@", listOf("#", "$"))
            }
            KeyboardMode.ENGLISH_QWERTY -> {
                // QWERTY 模式下 layout_12key 隱藏，但仍重設按鈕避免殘留
                setupSymbolButton(btnSym1, "？", listOf("?", "¿"))
                setupSymbolButton(btnSym2, "！", listOf("!", "¡"))
                setupSymbolButton(btnSym3, "……", listOf("…", "—"))
                setupSymbolButton(btnSym4, "：", listOf("；", "『", "』"))
                setupSymbolButton(btnSym5, "～", listOf("·", "《", "》"))
            }
            KeyboardMode.HANDWRITING -> {
                // 手寫模式：與注音相同標點
                setupSymbolButton(btnSym1, "？", listOf("?", "¿"))
                setupSymbolButton(btnSym2, "！", listOf("!", "¡"))
                setupSymbolButton(btnSym3, "……", listOf("…", "—"))
                setupSymbolButton(btnSym4, "：", listOf("；", "『", "』"))
                setupSymbolButton(btnSym5, "～", listOf("·", "《", "》"))
            }
        }
    }

    private fun setupSymbolButton(btn: Button, primary: String, related: List<String>) {
        btn.text = primary
        btn.setOnClickListener {
            triggerHapticFeedback()
            commitSymbol(primary)
        }
        if (related.isNotEmpty()) {
            btn.setOnLongClickListener {
                triggerHapticFeedback()
                val popup = android.widget.PopupMenu(this, btn)
                for ((index, item) in related.withIndex()) {
                    popup.menu.add(0, index, index, item)
                }
                popup.setOnMenuItemClickListener { menuItem ->
                    triggerHapticFeedback()
                    commitSymbol(related[menuItem.itemId])
                    true
                }
                popup.show()
                true
            }
        } else {
            btn.setOnLongClickListener(null)
        }
    }

    private fun update12KeyLabelsZhuyin() {
        set12KeyText(1, "ㄅ ㄉ ㄚ")
        set12KeyText(2, "ㄍ ㄐ ㄞ")
        set12KeyText(3, "ㄓ ㄗ ㄢ ㄦ")
        set12KeyText(4, "ㄆ ㄊ ㄛ")
        set12KeyText(5, "ㄎ ㄑ ㄟ")
        set12KeyText(6, "ㄔ ㄘ ㄣ ㄧ")
        set12KeyText(7, "ㄇ ㄋ ㄜ")
        set12KeyText(8, "ㄏ ㄒ ㄠ ㄡ")
        set12KeyText(9, "ㄕ ㄙ ㄤ ㄨ")
        set12KeyText(10, "ㄈ ㄌ ㄝ")
        set12KeyText(11, "ˇ ˋ ˊ ˙")
        set12KeyText(12, "ㄖ ㄥ ㄩ")
    }

    private fun formatNumberKeyLabel(primary: String, secondary: String): CharSequence {
        val fullText = "$primary\n$secondary"
        val spannable = SpannableString(fullText)
        val splitIndex = primary.length
        val primaryColor = ContextCompat.getColor(this, R.color.kb_text_primary)
        val secondaryColor = ContextCompat.getColor(this, R.color.kb_text_secondary)

        // 主數字 / 主符號：字體加大、粗體、深色主文字
        spannable.setSpan(RelativeSizeSpan(1.45f), 0, splitIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(StyleSpan(Typeface.BOLD), 0, splitIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(primaryColor), 0, splitIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        // 次要字母 / 符號：縮小、柔和次要文字顏色
        spannable.setSpan(RelativeSizeSpan(0.68f), splitIndex + 1, fullText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(secondaryColor), splitIndex + 1, fullText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        return spannable
    }

    private fun update12KeyLabelsNumbers() {
        // 純數字模式：僅顯示大號粗體數字與主符號，移除英文字母
        for (i in 1..12) {
            val numStr = getNumberChar(i)
            val spannable = SpannableString(numStr).apply {
                setSpan(RelativeSizeSpan(1.4f), 0, numStr.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(Typeface.BOLD), 0, numStr.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(ContextCompat.getColor(this@ZhuyinInputMethodService, R.color.kb_text_primary)), 0, numStr.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            set12KeyText(i, spannable)
        }
    }

    private fun update12KeyLabelsT9English() {
        val caseTransform = { s: String -> if (isCapsLock) s.uppercase() else s.lowercase() }
        set12KeyText(1,  formatNumberKeyLabel("1", "@ . _"))
        set12KeyText(2,  formatNumberKeyLabel("2", caseTransform("a b c")))
        set12KeyText(3,  formatNumberKeyLabel("3", caseTransform("d e f")))
        set12KeyText(4,  formatNumberKeyLabel("4", caseTransform("g h i")))
        set12KeyText(5,  formatNumberKeyLabel("5", caseTransform("j k l")))
        set12KeyText(6,  formatNumberKeyLabel("6", caseTransform("m n o")))
        set12KeyText(7,  formatNumberKeyLabel("7", caseTransform("p q r s")))
        set12KeyText(8,  formatNumberKeyLabel("8", caseTransform("t u v")))
        set12KeyText(9,  formatNumberKeyLabel("9", caseTransform("w x y z")))
        set12KeyText(10, formatNumberKeyLabel(".", "- + *"))
        set12KeyText(11, formatNumberKeyLabel("0", "/ = )"))
        set12KeyText(12, formatNumberKeyLabel("#", "% & !"))
    }

    private fun set12KeyText(keyNum: Int, text: CharSequence) {
        val root = layout12Key
        val viewId = when (keyNum) {
            1 -> R.id.key_k1; 2 -> R.id.key_k2; 3 -> R.id.key_k3
            4 -> R.id.key_k4; 5 -> R.id.key_k5; 6 -> R.id.key_k6
            7 -> R.id.key_k7; 8 -> R.id.key_k8; 9 -> R.id.key_k9
            10 -> R.id.key_k10; 11 -> R.id.key_k11; 12 -> R.id.key_k12
            else -> return
        }
        val btn = root.findViewById<SwipeKeyButton>(viewId) ?: return
        btn.transformationMethod = null
        btn.includeFontPadding = false
        btn.setLineSpacing(0f, 0.9f)
        btn.text = text
    }

    private fun getNumberChar(keyNum: Int): String {
        return when (keyNum) {
            1 -> "1"; 2 -> "2"; 3 -> "3"
            4 -> "4"; 5 -> "5"; 6 -> "6"
            7 -> "7"; 8 -> "8"; 9 -> "9"
            10 -> "."; 11 -> "0"; 12 -> "#"
            else -> ""
        }
    }

    private fun getNumberSwipe(keyNum: Int, dir: SwipeKeyButton.Direction): String? {
        val chars = getCombinedNumberEnglishChars(keyNum)
        if (chars.isEmpty()) return null
        val idx = when (dir) {
            SwipeKeyButton.Direction.UP -> if (keyNum == 7 || keyNum == 9) 4 else 0
            SwipeKeyButton.Direction.LEFT -> 1
            SwipeKeyButton.Direction.DOWN -> 2
            SwipeKeyButton.Direction.RIGHT -> 3
        }
        if (idx < chars.size) {
            val s = chars[idx]
            return if (isCapsLock && s.length == 1 && s[0].isLetter()) s.uppercase() else s
        }
        return null
    }

    private fun getT9EnglishSwipe(keyNum: Int, dir: SwipeKeyButton.Direction): Char? {
        return when (keyNum) {
            2 -> when (dir) { SwipeKeyButton.Direction.LEFT -> 'a'; SwipeKeyButton.Direction.DOWN -> 'b'; SwipeKeyButton.Direction.RIGHT -> 'c'; else -> null }
            3 -> when (dir) { SwipeKeyButton.Direction.LEFT -> 'd'; SwipeKeyButton.Direction.DOWN -> 'e'; SwipeKeyButton.Direction.RIGHT -> 'f'; else -> null }
            4 -> when (dir) { SwipeKeyButton.Direction.LEFT -> 'g'; SwipeKeyButton.Direction.DOWN -> 'h'; SwipeKeyButton.Direction.RIGHT -> 'i'; else -> null }
            5 -> when (dir) { SwipeKeyButton.Direction.LEFT -> 'j'; SwipeKeyButton.Direction.DOWN -> 'k'; SwipeKeyButton.Direction.RIGHT -> 'l'; else -> null }
            6 -> when (dir) { SwipeKeyButton.Direction.LEFT -> 'm'; SwipeKeyButton.Direction.DOWN -> 'n'; SwipeKeyButton.Direction.RIGHT -> 'o'; else -> null }
            7 -> when (dir) { SwipeKeyButton.Direction.LEFT -> 'p'; SwipeKeyButton.Direction.DOWN -> 'q'; SwipeKeyButton.Direction.RIGHT -> 'r'; SwipeKeyButton.Direction.UP -> 's' }
            8 -> when (dir) { SwipeKeyButton.Direction.LEFT -> 't'; SwipeKeyButton.Direction.DOWN -> 'u'; SwipeKeyButton.Direction.RIGHT -> 'v'; else -> null }
            9 -> when (dir) { SwipeKeyButton.Direction.LEFT -> 'w'; SwipeKeyButton.Direction.DOWN -> 'x'; SwipeKeyButton.Direction.RIGHT -> 'y'; SwipeKeyButton.Direction.UP -> 'z' }
            else -> null
        }
    }

    private fun getSwipeZhuyin(keyId: Int, direction: SwipeKeyButton.Direction): Char? {
        return when (keyId) {
            1 -> when (direction) { SwipeKeyButton.Direction.LEFT -> 'ㄅ'; SwipeKeyButton.Direction.DOWN -> 'ㄉ'; SwipeKeyButton.Direction.RIGHT -> 'ㄚ'; else -> null }
            2 -> when (direction) { SwipeKeyButton.Direction.LEFT -> 'ㄍ'; SwipeKeyButton.Direction.DOWN -> 'ㄐ'; SwipeKeyButton.Direction.RIGHT -> 'ㄞ'; else -> null }
            3 -> when (direction) { SwipeKeyButton.Direction.LEFT -> 'ㄓ'; SwipeKeyButton.Direction.DOWN -> 'ㄗ'; SwipeKeyButton.Direction.RIGHT -> 'ㄢ'; SwipeKeyButton.Direction.UP -> 'ㄦ' }
            4 -> when (direction) { SwipeKeyButton.Direction.LEFT -> 'ㄆ'; SwipeKeyButton.Direction.DOWN -> 'ㄊ'; SwipeKeyButton.Direction.RIGHT -> 'ㄛ'; else -> null }
            5 -> when (direction) { SwipeKeyButton.Direction.LEFT -> 'ㄎ'; SwipeKeyButton.Direction.DOWN -> 'ㄑ'; SwipeKeyButton.Direction.RIGHT -> 'ㄟ'; else -> null }
            6 -> when (direction) { SwipeKeyButton.Direction.LEFT -> 'ㄔ'; SwipeKeyButton.Direction.DOWN -> 'ㄘ'; SwipeKeyButton.Direction.RIGHT -> 'ㄣ'; SwipeKeyButton.Direction.UP -> 'ㄧ' }
            7 -> when (direction) { SwipeKeyButton.Direction.LEFT -> 'ㄇ'; SwipeKeyButton.Direction.DOWN -> 'ㄋ'; SwipeKeyButton.Direction.RIGHT -> 'ㄜ'; else -> null }
            8 -> when (direction) { SwipeKeyButton.Direction.LEFT -> 'ㄏ'; SwipeKeyButton.Direction.DOWN -> 'ㄒ'; SwipeKeyButton.Direction.RIGHT -> 'ㄠ'; SwipeKeyButton.Direction.UP -> 'ㄡ' }
            9 -> when (direction) { SwipeKeyButton.Direction.LEFT -> 'ㄕ'; SwipeKeyButton.Direction.DOWN -> 'ㄙ'; SwipeKeyButton.Direction.RIGHT -> 'ㄤ'; SwipeKeyButton.Direction.UP -> 'ㄨ' }
            10 -> when (direction) { SwipeKeyButton.Direction.LEFT -> 'ㄈ'; SwipeKeyButton.Direction.DOWN -> 'ㄌ'; SwipeKeyButton.Direction.RIGHT -> 'ㄝ'; else -> null }
            11 -> when (direction) { SwipeKeyButton.Direction.LEFT -> 'ˇ'; SwipeKeyButton.Direction.DOWN -> 'ˋ'; SwipeKeyButton.Direction.RIGHT -> 'ˊ'; SwipeKeyButton.Direction.UP -> '˙' }
            12 -> when (direction) { SwipeKeyButton.Direction.LEFT -> 'ㄖ'; SwipeKeyButton.Direction.DOWN -> 'ㄥ'; SwipeKeyButton.Direction.RIGHT -> 'ㄩ'; else -> null }
            else -> null
        }
    }

    private fun performBackspace() {
        triggerHapticFeedback()
        resetT9MultiTap()
        if (engine.hasComposing()) {
            val candidates = engine.backspace()
            refreshUI(candidates)
        } else {
            val ic = currentInputConnection
            if (ic != null) {
                try {
                    val selectedText = ic.getSelectedText(0)
                    if (!selectedText.isNullOrEmpty()) {
                        // 若有反白選取文字，直接以空字串替換以刪除選取範圍
                        ic.commitText("", 1)
                    } else {
                        // 針對已確認上屏文字退格刪除：
                        // 1. Android N (7.0+) 優先使用 deleteSurroundingTextInCodePoints 刪除完整字元 (含 Emoji / 延伸字符)
                        // 2. 其次使用 deleteSurroundingText 刪除一個字元
                        // 3. 備用容錯：若上述 API 失敗則調用系統軟鍵盤退格鍵事件
                        var deleted = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            deleted = ic.deleteSurroundingTextInCodePoints(1, 0)
                        }
                        if (!deleted) {
                            deleted = ic.deleteSurroundingText(1, 0)
                        }
                        if (!deleted) {
                            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                        }
                    }
                } catch (e: Exception) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                }
            }
            // 退格後清除接續預測（因為前一個詞可能已被修改）
            lastCommittedWord = null
            candidateContainer.removeAllViews()
        }
    }

    private fun commitSymbol(text: String) {
        resetT9MultiTap()
        if (engine.hasComposing()) {
            engine.clear()
            currentInputConnection?.finishComposingText()
        }
        currentInputConnection?.commitText(text, 1)
        currentInputConnection?.finishComposingText()
        lastCommittedWord = null
        showNextWordPredictions("")
    }

    private fun commitTextDirectly(text: String) {
        currentInputConnection?.commitText(text, 1)
        currentInputConnection?.finishComposingText()
        lastCommittedWord = null
        showNextWordPredictions("")
    }

    private fun commitProcessedText(text: String) {
        val finalText = if (isSimplified) ChineseConverter.toSimplified(text) else text
        currentInputConnection?.commitText(finalText, 1)
    }

    private fun refreshUI(candidates: List<DictEntry>) {
        updateComposingPreview()
        updateCandidateBar(candidates)
        updateLeftZhuyinCombos()
    }

    private fun updateComposingPreview() {
        if (!engine.hasComposing()) {
            currentInputConnection?.setComposingText("", 1)
            currentInputConnection?.finishComposingText()
            return
        }
        val previewWord = engine.getTopComposingWord()
        val finalPreview = if (isSimplified) ChineseConverter.toSimplified(previewWord) else previewWord
        currentInputConnection?.setComposingText(finalPreview, 1)
    }

    private fun updateLeftZhuyinCombos() {
        if (!engine.hasComposing() || currentMode != KeyboardMode.ZHUYIN) {
            layoutSymbols.visibility = View.VISIBLE
            scrollZhuyinCombos.visibility = View.GONE
            for (btn in comboButtonPool) {
                btn.visibility = View.GONE
            }
            return
        }

        layoutSymbols.visibility = View.GONE
        scrollZhuyinCombos.visibility = View.VISIBLE

        val combos = engine.getPossibleZhuyinCombinations()
        val density = resources.displayMetrics.density
        val btnHeightPx = (50 * density).toInt()
        val count = combos.size

        for (i in 0 until count) {
            val combo = combos[i]
            val btn = if (i < comboButtonPool.size) {
                comboButtonPool[i]
            } else {
                Button(this).apply {
                    textSize = 15f
                    setTextColor(Color.parseColor("#E65100"))
                    setBackgroundResource(R.drawable.bg_zhuyin_combo)
                    gravity = Gravity.CENTER
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        btnHeightPx
                    ).apply {
                        setMargins(2, 2, 2, 2)
                    }
                    layoutParams = params
                    containerZhuyinCombos.addView(this)
                    comboButtonPool.add(this)
                }
            }

            btn.text = combo
            btn.setOnClickListener {
                triggerHapticFeedback()
                val filtered = engine.selectZhuyinCombo(combo)
                updateCandidateBar(filtered)
                updateComposingPreview()
            }
            btn.visibility = View.VISIBLE
        }

        for (i in count until comboButtonPool.size) {
            comboButtonPool[i].visibility = View.GONE
        }
    }

    private fun updateCandidateBar(candidates: List<DictEntry>) {
        val displayCandidates = candidates.take(MAX_CANDIDATES_DISPLAY)
        val count = displayCandidates.size

        for (i in 0 until count) {
            val entry = displayCandidates[i]
            val displayWord = if (isSimplified) ChineseConverter.toSimplified(entry.word) else entry.word

            val tv = if (i < candidateTextViewPool.size) {
                candidateTextViewPool[i]
            } else {
                TextView(this).apply {
                    textSize = 20f
                    setPadding(
                        CANDIDATE_BAR_PADDING_PX,
                        CANDIDATE_BAR_PADDING_VERTICAL_PX,
                        CANDIDATE_BAR_PADDING_PX,
                        CANDIDATE_BAR_PADDING_VERTICAL_PX
                    )
                    candidateContainer.addView(this)
                    candidateTextViewPool.add(this)
                }
            }

            tv.text = displayWord
            tv.setTextColor(
                if (i == 0) ContextCompat.getColor(this, R.color.kb_candidate_text)
                else ContextCompat.getColor(this, R.color.kb_text_primary)
            )
            tv.setOnClickListener {
                triggerHapticFeedback()
                selectCandidate(entry)
            }
            tv.visibility = View.VISIBLE
        }

        for (i in count until candidateTextViewPool.size) {
            candidateTextViewPool[i].visibility = View.GONE
        }
        candidateScroll?.scrollTo(0, 0)
    }

    private fun showNextWordPredictions(word: String) {
        if (word.isEmpty()) {
            for (tv in candidateTextViewPool) {
                tv.visibility = View.GONE
            }
            return
        }

        val predictions = engine.getNextWordPredictions(word)
        if (predictions.isNotEmpty()) {
            updateCandidateBar(predictions)
        } else {
            for (tv in candidateTextViewPool) {
                tv.visibility = View.GONE
            }
        }
        candidateScroll?.scrollTo(0, 0)
    }

    private fun selectCandidate(entry: DictEntry) {
        if (entry.word.startsWith("【")) return
        commitProcessedText(entry.word)
        lastCommittedWord = entry.word
        com.bopomofo.t9ime.engine.UserDictionaryManager.getInstance(this).recordUsage(entry.word, entry.zhuyin)
        engine.clear()
        currentInputConnection?.finishComposingText()
        if (::handwritingCanvas.isInitialized) {
            handwritingCanvas.clearCanvas()
        }
        updateLeftZhuyinCombos()
        showNextWordPredictions(entry.word)
    }

    // 實體鍵盤大千注音鍵位映射表 (標準 PC 鍵盤注音符號對應)
    private val DAQIAN_KEY_MAP = mapOf(
        KeyEvent.KEYCODE_1 to 'ㄅ', KeyEvent.KEYCODE_Q to 'ㄆ', KeyEvent.KEYCODE_A to 'ㄇ', KeyEvent.KEYCODE_Z to 'ㄈ',
        KeyEvent.KEYCODE_2 to 'ㄉ', KeyEvent.KEYCODE_W to 'ㄊ', KeyEvent.KEYCODE_S to 'ㄋ', KeyEvent.KEYCODE_X to 'ㄌ',
        KeyEvent.KEYCODE_E to 'ㄍ', KeyEvent.KEYCODE_D to 'ㄎ', KeyEvent.KEYCODE_C to 'ㄏ',
        KeyEvent.KEYCODE_R to 'ㄐ', KeyEvent.KEYCODE_F to 'ㄑ', KeyEvent.KEYCODE_V to 'ㄒ',
        KeyEvent.KEYCODE_5 to 'ㄓ', KeyEvent.KEYCODE_T to 'ㄔ', KeyEvent.KEYCODE_G to 'ㄕ', KeyEvent.KEYCODE_B to 'ㄖ',
        KeyEvent.KEYCODE_Y to 'ㄗ', KeyEvent.KEYCODE_H to 'ㄘ', KeyEvent.KEYCODE_N to 'ㄙ',
        KeyEvent.KEYCODE_U to 'ㄧ', KeyEvent.KEYCODE_J to 'ㄨ', KeyEvent.KEYCODE_M to 'ㄩ',
        KeyEvent.KEYCODE_8 to 'ㄚ', KeyEvent.KEYCODE_I to 'ㄛ', KeyEvent.KEYCODE_K to 'ㄜ', KeyEvent.KEYCODE_COMMA to 'ㄝ',
        KeyEvent.KEYCODE_9 to 'ㄞ', KeyEvent.KEYCODE_O to 'ㄟ', KeyEvent.KEYCODE_L to 'ㄠ', KeyEvent.KEYCODE_PERIOD to 'ㄡ',
        KeyEvent.KEYCODE_0 to 'ㄢ', KeyEvent.KEYCODE_P to 'ㄣ', KeyEvent.KEYCODE_SEMICOLON to 'ㄤ', KeyEvent.KEYCODE_SLASH to 'ㄦ',
        // 聲調鍵 (3 4 6 7)
        KeyEvent.KEYCODE_3 to 'ˇ', // 三聲
        KeyEvent.KEYCODE_4 to 'ˋ', // 四聲
        KeyEvent.KEYCODE_6 to 'ˊ', // 二聲
        KeyEvent.KEYCODE_EQUALS to 'ˊ', // = 鍵對應二聲（大千標準，與 6 鍵重複但符合習慣）
        KeyEvent.KEYCODE_7 to '˙'  // 輕聲
    )

    private var isPhysicalShiftPressed = false

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 1. Shift 鍵按下標記
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            isPhysicalShiftPressed = true
            return true
        }

        // 2. 組合鍵（如 Ctrl+C, Ctrl+V, Alt 等）直接放行交由系統處理
        if (event.isCtrlPressed || event.isAltPressed) {
            return super.onKeyDown(keyCode, event)
        }

        // 3. 英文模式下：實體鍵盤直接輸出字元
        if (currentMode == KeyboardMode.ENGLISH_T9 || currentMode == KeyboardMode.ENGLISH_QWERTY) {
            return super.onKeyDown(keyCode, event)
        }

        // 4. 注音模式下的實體鍵盤處理
        if (currentMode == KeyboardMode.ZHUYIN) {
            // A. Backspace 刪除
            if (keyCode == KeyEvent.KEYCODE_DEL) {
                if (engine.hasComposing()) {
                    performBackspace()
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }

            // B. Space 空白鍵
            if (keyCode == KeyEvent.KEYCODE_SPACE) {
                if (engine.hasComposing()) {
                    val candidates = engine.getCandidates()
                    if (candidates.isNotEmpty()) {
                        selectCandidate(candidates.first())
                    } else {
                        val top = engine.getTopComposingWord()
                        commitProcessedText(top)
                        engine.clear()
                        currentInputConnection?.finishComposingText()
                    }
                    return true
                } else {
                    commitTextDirectly(" ")
                    return true
                }
            }

            // C. Enter 鍵確認直接送出當前注音/預測候選
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                if (engine.hasComposing()) {
                    performEnterAction()
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }

            // D. 數字鍵選字 (1~9 選候選字)
            if (engine.hasComposing() && keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
                val selectIndex = keyCode - KeyEvent.KEYCODE_1
                val candidates = engine.getCandidates()
                if (selectIndex < candidates.size) {
                    selectCandidate(candidates[selectIndex])
                    return true
                }
            }

            // E. 大千注音按鍵映射輸入
            val zhuyinChar = DAQIAN_KEY_MAP[keyCode]
            if (zhuyinChar != null) {
                val keyId = com.bopomofo.t9ime.engine.KeyMapping.getKeyId(zhuyinChar)
                if (keyId != null) {
                    val candidates = if (keyId == 11) {
                        val (_, cands) = engine.cycleTone()
                        cands
                    } else {
                        engine.pressKey(keyId)
                    }
                    refreshUI(candidates)
                    return true
                }
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Shift 單按一秒切換中英文（PC 經典體驗）
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            if (isPhysicalShiftPressed && !event.isCanceled) {
                currentMode = if (currentMode == KeyboardMode.ZHUYIN) KeyboardMode.ENGLISH_QWERTY else KeyboardMode.ZHUYIN
                engine.clear()
                currentInputConnection?.finishComposingText()
                refreshUI(emptyList())
                updateKeyboardModeUI()
            }
            isPhysicalShiftPressed = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        engine.clear()
        lastCommittedWord = null
        resetT9MultiTap()
        candidateContainer.removeAllViews()
        currentInputConnection?.finishComposingText()
    }
}
