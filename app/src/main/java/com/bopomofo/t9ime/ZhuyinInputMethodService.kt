package com.bopomofo.t9ime

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import com.bopomofo.t9ime.engine.ClipboardHistoryManager
import com.bopomofo.t9ime.engine.DictEntry
import com.bopomofo.t9ime.engine.EmojiKaomojiManager
import com.bopomofo.t9ime.engine.SnippetManager
import com.bopomofo.t9ime.engine.ZhuyinT9Engine
import com.bopomofo.t9ime.theme.AppTheme
import com.bopomofo.t9ime.theme.ThemeManager
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
        ZHUYIN,         // 12 鍵注音 (9 鍵)
        ZHUYIN_FULL,    // 41 鍵大千全注音
        NUMBER_SYM,     // 12 鍵數字/符號
        ENGLISH_QWERTY, // 26 鍵英文全鍵盤
        HANDWRITING     // 手寫輸入
    }

    enum class EnglishCaseState {
        LOWER,          // 全小寫 (abc)
        FIRST_UPPER,    // 首字母大寫 (Abc)
        ALL_UPPER       // 全大寫鎖定 (ABC)
    }

    enum class SymbolTab {
        FULLWIDTH, HALFWIDTH, DPAD, MATH, CLIPBOARD, EMOJI, KAOMOJI, SNIPPET
    }

    enum class HapticType {
        KEY_PRESS,      // 普通點按
        COMMIT,         // 確認上屏 (雙脈衝)
        DELETE,         // 刪除退格
        MODE_SWITCH,    // 鍵盤模式切換
        REPEAT_DELETE   // 連續長按刪除
    }

    enum class OneHandedMode(val id: String, val title: String) {
        FULL("full", "全寬"),
        LEFT("left", "單手·左"),
        RIGHT("right", "單手·右")
    }

    private var currentMode = KeyboardMode.ZHUYIN
    private var isSimplified = false
    private var englishCaseState = EnglishCaseState.LOWER
    private val isCapsLock: Boolean
        get() = englishCaseState != EnglishCaseState.LOWER
    private var currentSymbolTab = SymbolTab.DPAD
    private var lastCommittedWord: String? = null
    private val fullZhuyinBuffer = StringBuilder()

    private lateinit var engine: ZhuyinT9Engine
    private var candidateScroll: HorizontalScrollView? = null
    private var candidateMoreIndicator: TextView? = null
    private var btnCandidateExpand: Button? = null
    private var layoutCandidateGrid: LinearLayout? = null
    private var btnCandidateGridClose: Button? = null
    private var containerCandidateGrid: LinearLayout? = null
    private var tvCandidateGridTitle: TextView? = null
    private var isCandidateGridOpen = false
    private var currentCandidateList: List<DictEntry> = emptyList()
    private var currentOneHandedMode = OneHandedMode.FULL

    private lateinit var candidateContainer: LinearLayout
    private lateinit var layoutSymbols: LinearLayout
    private lateinit var scrollZhuyinCombos: ScrollView
    private lateinit var containerZhuyinCombos: LinearLayout
    private val candidateTextViewPool = ArrayList<TextView>()
    private val comboButtonPool = ArrayList<Button>()

    // 底線候選文字同音字/同拼法逐字替換狀態 (1.6.5)
    private var isHomophoneSelectionMode = false
    private var homophoneCharIndex: Int = -1
    private var customComposingWord: String? = null
    private val replacedCharsMap = mutableMapOf<Int, Pair<String, String>>() // index -> Pair(newChar, zhuyin)
    private var lastComposingStart: Int = -1
    private var lastComposingEnd: Int = -1

    private lateinit var layout12Key: LinearLayout
    private lateinit var layoutQwerty: LinearLayout
    private lateinit var layoutHandwriting: FrameLayout
    private lateinit var layoutZhuyinFull: LinearLayout
    private lateinit var layoutSymbolPanel: LinearLayout
    private lateinit var layoutMainFrame: FrameLayout
    private lateinit var layoutResizeHandle: FrameLayout
    private lateinit var handwritingCanvas: com.bopomofo.t9ime.ui.HandwritingCanvasView
    private var googleRecognizer: com.bopomofo.t9ime.engine.GoogleHandwritingRecognizer? = null

    private var rootView: View? = null
    private var vibrator: Vibrator? = null

    private lateinit var btnMode123: Button
    private lateinit var btnLangToggle: Button
    private lateinit var btnSpaceSwipe: SwipeKeyButton
    private lateinit var btnQwertyToggle: SwipeKeyButton
    private lateinit var btnSymbolDrawer: Button
    private lateinit var containerSymbolContent: FrameLayout

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

    private var lastUserTypingTime: Long = 0L
    private var activeHomophonePopup: android.widget.PopupMenu? = null

    private fun dismissHomophonePopup() {
        try {
            activeHomophonePopup?.dismiss()
        } catch (_: Exception) {}
        activeHomophonePopup = null
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

        // 初始化剪貼簿歷史與常用短語
        ClipboardHistoryManager.init(this)
        SnippetManager.init(this)

        try {
            val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            clipManager?.addPrimaryClipChangedListener {
                val clipData = clipManager.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val text = clipData.getItemAt(0)?.coerceToText(this)?.toString()
                    if (!text.isNullOrBlank()) {
                        // 密碼框防護
                        val inputType = currentInputEditorInfo?.inputType ?: 0
                        val isPassword = (inputType and android.text.InputType.TYPE_MASK_VARIATION) == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                                (inputType and android.text.InputType.TYPE_MASK_VARIATION) == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD ||
                                (inputType and android.text.InputType.TYPE_MASK_VARIATION) == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        if (!isPassword) {
                            ClipboardHistoryManager.addClip(this, text)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        googleRecognizer?.close()
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        rootView?.let { root ->
            ThemeManager.applyTheme(root, ThemeManager.getCurrentTheme(this))
            applyOneHandedMode()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        rootView?.let { root ->
            ThemeManager.applyTheme(root, ThemeManager.getCurrentTheme(this))
            applyOneHandedMode()
        }
    }

    /**
     * 觸發多級細緻按鍵震動反饋（依據輸入法設定的開關與強度，細分普通按鍵、確認上屏、退格刪除、模式切換等波形）
     */
    private fun triggerHapticFeedback(type: HapticType = HapticType.KEY_PRESS) {
        try {
            val prefs = getSharedPreferences("ime_prefs", Context.MODE_PRIVATE)
            val isEnabled = prefs.getBoolean("pref_vibration_enabled", true)
            if (!isEnabled) return

            val baseStrength = prefs.getInt("pref_vibration_strength", 30).coerceIn(5, 100)

            if (vibrator != null && vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = when (type) {
                        HapticType.KEY_PRESS -> {
                            val durationMs = (baseStrength * 0.7f).toLong().coerceAtLeast(6L)
                            val amplitude = ((baseStrength / 100f) * 180).toInt().coerceIn(1, 255)
                            VibrationEffect.createOneShot(durationMs, amplitude)
                        }
                        HapticType.COMMIT -> {
                            // 雙脈衝確認震動 (12ms 震, 35ms 停, 18ms 震)
                            val timings = longArrayOf(0, 12, 35, 18)
                            val amplitudes = intArrayOf(
                                0,
                                ((baseStrength / 100f) * 200).toInt().coerceIn(1, 255),
                                0,
                                ((baseStrength / 100f) * 255).toInt().coerceIn(1, 255)
                            )
                            VibrationEffect.createWaveform(timings, amplitudes, -1)
                        }
                        HapticType.DELETE -> {
                            val durationMs = (baseStrength * 0.9f).toLong().coerceAtLeast(10L)
                            val amplitude = ((baseStrength / 100f) * 230).toInt().coerceIn(1, 255)
                            VibrationEffect.createOneShot(durationMs, amplitude)
                        }
                        HapticType.MODE_SWITCH -> {
                            val durationMs = (baseStrength * 1.1f).toLong().coerceAtLeast(14L)
                            val amplitude = ((baseStrength / 100f) * 220).toInt().coerceIn(1, 255)
                            VibrationEffect.createOneShot(durationMs, amplitude)
                        }
                        HapticType.REPEAT_DELETE -> {
                            VibrationEffect.createOneShot(8L, ((baseStrength / 100f) * 120).toInt().coerceIn(1, 255))
                        }
                    }
                    vibrator?.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(baseStrength.toLong())
                }
            } else {
                val constant = when (type) {
                    HapticType.COMMIT -> HapticFeedbackConstants.CONFIRM
                    HapticType.DELETE, HapticType.REPEAT_DELETE -> HapticFeedbackConstants.KEYBOARD_RELEASE
                    else -> HapticFeedbackConstants.KEYBOARD_TAP
                }
                rootView?.performHapticFeedback(constant)
            }
        } catch (_: Exception) {
            // 忽略非致命震動異常
        }
    }

    override fun onCreateInputView(): View {
        candidateTextViewPool.clear()
        comboButtonPool.clear()

        val root = layoutInflater.inflate(R.layout.keyboard_view, null)
        rootView = root
        candidateScroll = root.findViewById(R.id.candidate_scroll)
        candidateContainer = root.findViewById(R.id.candidate_container)
        candidateMoreIndicator = root.findViewById(R.id.candidate_more_indicator)
        candidateMoreIndicator?.setOnClickListener {
            triggerHapticFeedback()
            candidateScroll?.smoothScrollBy(320, 0)
        }
        candidateScroll?.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            val maxScroll = (candidateContainer.width - (candidateScroll?.width ?: 0)).coerceAtLeast(0)
            if (scrollX >= maxScroll - 16) {
                candidateMoreIndicator?.visibility = View.GONE
            } else if (candidateContainer.width > (candidateScroll?.width ?: 0)) {
                candidateMoreIndicator?.visibility = View.VISIBLE
            }
        }
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
            clearCandidateBar()
        }

        btnMode123 = root.findViewById(R.id.btn_mode_123)
        btnLangToggle = root.findViewById(R.id.btn_lang_toggle)
        btnSpaceSwipe = root.findViewById(R.id.btn_space_swipe)
        btnQwertyToggle = root.findViewById(R.id.btn_qwerty_toggle)
        layoutZhuyinFull = root.findViewById(R.id.layout_zhuyin_full)
        layoutSymbolPanel = root.findViewById(R.id.layout_symbol_panel)
        btnSymbolDrawer = root.findViewById(R.id.btn_symbol_drawer)
        containerSymbolContent = root.findViewById(R.id.container_symbol_content)

        // 候選字展開格柵 (Grid Expansion)
        btnCandidateExpand = root.findViewById(R.id.btn_candidate_expand)
        layoutCandidateGrid = root.findViewById(R.id.layout_candidate_grid)
        btnCandidateGridClose = root.findViewById(R.id.btn_candidate_grid_close)
        containerCandidateGrid = root.findViewById(R.id.container_candidate_grid)
        tvCandidateGridTitle = root.findViewById(R.id.tv_candidate_grid_title)

        btnCandidateExpand?.setOnClickListener {
            if (isCandidateGridOpen) {
                closeCandidateGrid()
            } else {
                openCandidateGrid()
            }
        }
        btnCandidateGridClose?.setOnClickListener {
            closeCandidateGrid()
        }

        setup12KeyLayout(root)
        setupZhuyinFullLayout(root)
        setupQwertyLayout(root)
        setupSymbolPanel(root)
        setupSideActions(root)
        setupBottomActions(root)

        applyOneHandedMode()
        ThemeManager.applyTheme(root, ThemeManager.getCurrentTheme(this))

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
                lastUserTypingTime = SystemClock.uptimeMillis()
                dismissHomophonePopup()
                when (currentMode) {
                    KeyboardMode.ZHUYIN -> {
                        engine.currentContextWord = lastCommittedWord
                        if (customComposingWord != null || isHomophoneSelectionMode) {
                            customComposingWord = null
                            replacedCharsMap.clear()
                            isHomophoneSelectionMode = false
                            homophoneCharIndex = -1
                        }
                        if (keyNum == 11) {
                            val (_, candidates) = engine.cycleTone()
                            refreshUI(candidates)
                        } else {
                            // 新酷音詞邊界自動提交 (Word Boundary Commit)：
                            // 若當前已有完整多字詞候選（長度 >= 2，如「概念」、「目前」、「今天」），
                            // 且當前按鍵序列加上新按鍵 keyNum 在字典中已無法組成更長詞彙，
                            // 表示使用者按下此鍵是在輸入下一個字，立即自動確認提交前綴詞！
                            val topCandidate = engine.getCandidates().firstOrNull()
                            val curKeys = engine.getCurrentKeys()
                            if (topCandidate != null && topCandidate.word.length >= 2 && curKeys.isNotEmpty()) {
                                val testKeys = curKeys + keyNum
                                val canExtendLongerWord = engine.hasPrefixOrExact(testKeys)
                                if (!canExtendLongerWord) {
                                    commitProcessedWordWithUserDict(topCandidate.word, topCandidate.zhuyin)
                                }
                            }

                            engine.pressKey(keyNum)
                            refreshUI(engine.getCandidates())
                        }
                    }
                    KeyboardMode.NUMBER_SYM -> {
                        commitTextDirectly(getNumberChar(keyNum))
                    }
                    else -> {}
                }
            }

            btn.onSwipeListener = { direction ->
                triggerHapticFeedback()
                lastUserTypingTime = SystemClock.uptimeMillis()
                dismissHomophonePopup()
                when (currentMode) {
                    KeyboardMode.ZHUYIN -> {
                        val zhuyin = getSwipeZhuyin(keyNum, direction)
                        if (zhuyin != null) commitTextDirectly(zhuyin.toString())
                    }
                    KeyboardMode.NUMBER_SYM -> {
                        // 純數字模式不提供英文滑動輸入
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

    private fun resetT9MultiTap() {}

    /**
     * 數字/英文混合模式字符表
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
            text = when (englishCaseState) {
                EnglishCaseState.LOWER -> "⇧"
                EnglishCaseState.FIRST_UPPER -> "⇧•"
                EnglishCaseState.ALL_UPPER -> "⇪"
            }
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key_action)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.5f).apply {
                setMargins(2, 2, 2, 2)
            }
            layoutParams = params
            setOnClickListener {
                triggerHapticFeedback()
                cycleEnglishCase()
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
                if (englishCaseState == EnglishCaseState.FIRST_UPPER) {
                    englishCaseState = EnglishCaseState.LOWER
                    updateQwertyKeysText()
                    updateKeyboardModeUI()
                }
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

    private fun cycleEnglishCase() {
        englishCaseState = when (englishCaseState) {
            EnglishCaseState.LOWER -> EnglishCaseState.FIRST_UPPER
            EnglishCaseState.FIRST_UPPER -> EnglishCaseState.ALL_UPPER
            EnglishCaseState.ALL_UPPER -> EnglishCaseState.LOWER
        }
        updateQwertyKeysText()
        updateKeyboardModeUI()
    }

    /**
     * 41 鍵大千注音全鍵盤配置：
     * Row 1: ㄅ ㄉ ˇ ˋ ㄓ ˊ ˙ ㄚ ㄞ ㄢ (長按輸出數字 1~0)
     * Row 2: ㄆ ㄊ ㄍ ㄐ ㄔ ㄗ ㄧ ㄛ ㄟ ㄣ
     * Row 3: ㄇ ㄋ ㄎ ㄑ ㄕ ㄘ ㄨ ㄜ ㄠ ㄤ
     * Row 4: ㄈ ㄌ ㄏ ㄒ ㄖ ㄙ ㄩ ㄝ ㄡ ㄥ ㄦ
     */
    private fun setupZhuyinFullLayout(root: View) {
        val row1 = root.findViewById<LinearLayout>(R.id.zhuyin_full_row_1) ?: return
        val row2 = root.findViewById<LinearLayout>(R.id.zhuyin_full_row_2) ?: return
        val row3 = root.findViewById<LinearLayout>(R.id.zhuyin_full_row_3) ?: return
        val row4 = root.findViewById<LinearLayout>(R.id.zhuyin_full_row_4) ?: return

        row1.removeAllViews()
        row2.removeAllViews()
        row3.removeAllViews()
        row4.removeAllViews()

        val r1 = listOf('ㄅ' to "1", 'ㄉ' to "2", 'ˇ' to "3", 'ˋ' to "4", 'ㄓ' to "5", 'ˊ' to "6", '˙' to "7", 'ㄚ' to "8", 'ㄞ' to "9", 'ㄢ' to "0")
        val r2 = listOf('ㄆ', 'ㄊ', 'ㄍ', 'ㄐ', 'ㄔ', 'ㄗ', 'ㄧ', 'ㄛ', 'ㄟ', 'ㄣ')
        val r3 = listOf('ㄇ', 'ㄋ', 'ㄎ', 'ㄑ', 'ㄕ', 'ㄘ', 'ㄨ', 'ㄜ', 'ㄠ', 'ㄤ')
        val r4 = listOf('ㄈ', 'ㄌ', 'ㄏ', 'ㄒ', 'ㄖ', 'ㄙ', 'ㄩ', 'ㄝ', 'ㄡ', 'ㄥ', 'ㄦ')

        for ((ch, num) in r1) {
            row1.addView(createZhuyinFullKey(ch, 1f, num))
        }
        row1.addView(createZhuyinFullDelKey(1.1f))

        for (ch in r2) {
            row2.addView(createZhuyinFullKey(ch, 1f))
        }
        for (ch in r3) {
            row3.addView(createZhuyinFullKey(ch, 1f))
        }

        row4.addView(createZhuyinFullClearKey(1.1f))
        for (ch in r4) {
            row4.addView(createZhuyinFullKey(ch, 1f))
        }
        row4.addView(createZhuyinFullEnterKey(1.1f))
    }

    private fun createZhuyinFullKey(ch: Char, weight: Float, longClickChar: String? = null): Button {
        return Button(this).apply {
            text = ch.toString()
            textSize = 17f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(1, 2, 1, 2)
            }
            layoutParams = params
            setOnClickListener {
                triggerHapticFeedback()
                handleZhuyinFullKey(ch)
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

    private fun createZhuyinFullDelKey(weight: Float): Button {
        return Button(this).apply {
            text = "⌫"
            textSize = 17f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key_action)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(1, 2, 1, 2)
            }
            layoutParams = params
            setOnTouchListener { v, event ->
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
    }

    private fun createZhuyinFullEnterKey(weight: Float): Button {
        return Button(this).apply {
            text = "↵"
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key_action)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(1, 2, 1, 2)
            }
            layoutParams = params
            setOnClickListener {
                triggerHapticFeedback()
                performEnterAction()
            }
        }
    }

    private fun createZhuyinFullClearKey(weight: Float): Button {
        return Button(this).apply {
            text = "清空"
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key_action)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(1, 2, 1, 2)
            }
            layoutParams = params
            setOnClickListener {
                triggerHapticFeedback()
                lastUserTypingTime = SystemClock.uptimeMillis()
                dismissHomophonePopup()
                fullZhuyinBuffer.clear()
                currentInputConnection?.finishComposingText()
                clearCandidateBar()
            }
        }
    }

    private fun handleZhuyinFullKey(ch: Char) {
        lastUserTypingTime = SystemClock.uptimeMillis()
        dismissHomophonePopup()
        lastCommittedWord = null
        if (customComposingWord != null || isHomophoneSelectionMode) {
            customComposingWord = null
            replacedCharsMap.clear()
            isHomophoneSelectionMode = false
            homophoneCharIndex = -1
        }

        fullZhuyinBuffer.append(ch)
        updateComposingPreviewFull()

        // 呼叫注音全鍵盤專屬預測與候選檢索引擎（獨立簡拼、混合簡拼與全拼聯想）
        val candidates = engine.searchFullZhuyin(fullZhuyinBuffer.toString())
        refreshUI(candidates)
    }

    private fun updateComposingPreviewFull() {
        val preview = fullZhuyinBuffer.toString()
        val finalPreview = if (isSimplified) ChineseConverter.toSimplified(preview) else preview
        currentInputConnection?.setComposingText(finalPreview, 1)
    }

    private fun setupSymbolPanel(root: View) {
        val tabFull = root.findViewById<Button>(R.id.tab_sym_fullwidth)
        val tabHalf = root.findViewById<Button>(R.id.tab_sym_halfwidth)
        val tabDpad = root.findViewById<Button>(R.id.tab_sym_dpad)
        val tabMath = root.findViewById<Button>(R.id.tab_sym_math)
        val tabClip = root.findViewById<Button>(R.id.tab_sym_clipboard)
        val tabEmoji = root.findViewById<Button>(R.id.tab_sym_emoji)
        val tabKaomoji = root.findViewById<Button>(R.id.tab_sym_kaomoji)
        val tabSnippet = root.findViewById<Button>(R.id.tab_sym_snippet)

        containerSymbolContent = root.findViewById(R.id.container_symbol_content)

        tabFull?.setOnClickListener { triggerHapticFeedback(HapticType.MODE_SWITCH); switchSymbolTab(SymbolTab.FULLWIDTH) }
        tabHalf?.setOnClickListener { triggerHapticFeedback(HapticType.MODE_SWITCH); switchSymbolTab(SymbolTab.HALFWIDTH) }
        tabDpad?.setOnClickListener { triggerHapticFeedback(HapticType.MODE_SWITCH); switchSymbolTab(SymbolTab.DPAD) }
        tabMath?.setOnClickListener { triggerHapticFeedback(HapticType.MODE_SWITCH); switchSymbolTab(SymbolTab.MATH) }
        tabClip?.setOnClickListener { triggerHapticFeedback(HapticType.MODE_SWITCH); switchSymbolTab(SymbolTab.CLIPBOARD) }
        tabEmoji?.setOnClickListener { triggerHapticFeedback(HapticType.MODE_SWITCH); switchSymbolTab(SymbolTab.EMOJI) }
        tabKaomoji?.setOnClickListener { triggerHapticFeedback(HapticType.MODE_SWITCH); switchSymbolTab(SymbolTab.KAOMOJI) }
        tabSnippet?.setOnClickListener { triggerHapticFeedback(HapticType.MODE_SWITCH); switchSymbolTab(SymbolTab.SNIPPET) }

        btnSymbolDrawer.setOnClickListener {
            triggerHapticFeedback(HapticType.MODE_SWITCH)
            if (layoutSymbolPanel.visibility == View.VISIBLE) {
                hideSymbolPanel()
            } else {
                showSymbolPanel()
            }
        }
    }

    private fun showSymbolPanel() {
        layout12Key.visibility = View.GONE
        layoutQwerty.visibility = View.GONE
        if (::layoutHandwriting.isInitialized) layoutHandwriting.visibility = View.GONE
        if (::layoutZhuyinFull.isInitialized) layoutZhuyinFull.visibility = View.GONE
        layoutSymbolPanel.visibility = View.VISIBLE
        btnSymbolDrawer.text = "✕"
        switchSymbolTab(currentSymbolTab)
    }

    private fun hideSymbolPanel() {
        layoutSymbolPanel.visibility = View.GONE
        btnSymbolDrawer.text = "✛"
        updateKeyboardModeUI()
    }

    private fun switchSymbolTab(tab: SymbolTab) {
        currentSymbolTab = tab
        val tabFull = rootView?.findViewById<Button>(R.id.tab_sym_fullwidth)
        val tabHalf = rootView?.findViewById<Button>(R.id.tab_sym_halfwidth)
        val tabDpad = rootView?.findViewById<Button>(R.id.tab_sym_dpad)
        val tabMath = rootView?.findViewById<Button>(R.id.tab_sym_math)
        val tabClip = rootView?.findViewById<Button>(R.id.tab_sym_clipboard)
        val tabEmoji = rootView?.findViewById<Button>(R.id.tab_sym_emoji)
        val tabKaomoji = rootView?.findViewById<Button>(R.id.tab_sym_kaomoji)
        val tabSnippet = rootView?.findViewById<Button>(R.id.tab_sym_snippet)

        val activeColor = ContextCompat.getColor(this, R.color.kb_accent)
        val normalColor = ContextCompat.getColor(this, R.color.kb_text_primary)

        tabFull?.setTextColor(if (tab == SymbolTab.FULLWIDTH) activeColor else normalColor)
        tabHalf?.setTextColor(if (tab == SymbolTab.HALFWIDTH) activeColor else normalColor)
        tabDpad?.setTextColor(if (tab == SymbolTab.DPAD) activeColor else normalColor)
        tabMath?.setTextColor(if (tab == SymbolTab.MATH) activeColor else normalColor)
        tabClip?.setTextColor(if (tab == SymbolTab.CLIPBOARD) activeColor else normalColor)
        tabEmoji?.setTextColor(if (tab == SymbolTab.EMOJI) activeColor else normalColor)
        tabKaomoji?.setTextColor(if (tab == SymbolTab.KAOMOJI) activeColor else normalColor)
        tabSnippet?.setTextColor(if (tab == SymbolTab.SNIPPET) activeColor else normalColor)

        containerSymbolContent.removeAllViews()

        when (tab) {
            SymbolTab.DPAD -> containerSymbolContent.addView(createDpadView())
            SymbolTab.FULLWIDTH -> {
                val fullSymbols = listOf(
                    "，", "。", "！", "？", "、", "；", "：", "～",
                    "…", "「", "」", "『", "』", "《", "》", "（",
                    "）", "【", "】", "〔", "〕", "“", "”", "‘", "’", "·", "—", "￥"
                )
                containerSymbolContent.addView(createSymbolGrid(fullSymbols, 7))
            }
            SymbolTab.HALFWIDTH -> {
                val halfSymbols = listOf(
                    ",", ".", "!", "?", ":", ";", "/", "\\",
                    "~", "@", "#", "$", "%", "^", "&", "*",
                    "-", "_", "+", "=", "(", ")", "[", "]",
                    "{", "}", "<", ">", "\"", "'", "`", "|"
                )
                containerSymbolContent.addView(createSymbolGrid(halfSymbols, 8))
            }
            SymbolTab.MATH -> {
                val mathSymbols = listOf(
                    "↑", "↓", "←", "→", "±", "×", "÷", "≠",
                    "≈", "≤", "≥", "℃", "★", "✔", "❤", "☺",
                    "©", "®", "™", "¥", "€", "£", "§", "¶",
                    "∞", "π", "√", "°", "‰", "▲", "▼", "◆"
                )
                containerSymbolContent.addView(createSymbolGrid(mathSymbols, 8))
            }
            SymbolTab.CLIPBOARD -> containerSymbolContent.addView(createClipboardView())
            SymbolTab.EMOJI -> containerSymbolContent.addView(createSymbolGrid(EmojiKaomojiManager.POPULAR_EMOJIS, 7))
            SymbolTab.KAOMOJI -> containerSymbolContent.addView(createKaomojiView())
            SymbolTab.SNIPPET -> containerSymbolContent.addView(createSnippetView())
        }
    }

    private fun createClipboardView(): View {
        val scrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isVerticalScrollBarEnabled = true
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 4, 8, 4)
        }

        val clips = ClipboardHistoryManager.getHistory()
        if (clips.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "剪貼簿目前尚無紀錄\n複製任何文字將自動保存在此"
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.kb_text_secondary))
                gravity = Gravity.CENTER
                setPadding(16, 48, 16, 48)
            }
            layout.addView(emptyTv)
        } else {
            // 頂部列：清空按鈕
            val topBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            val btnClear = Button(this).apply {
                text = "清空剪貼簿"
                textSize = 12f
                setTextColor(Color.parseColor("#E53935"))
                setBackgroundResource(R.drawable.bg_key_action)
                setOnClickListener {
                    triggerHapticFeedback(HapticType.DELETE)
                    ClipboardHistoryManager.clearAll(context)
                    switchSymbolTab(SymbolTab.CLIPBOARD)
                }
            }
            topBar.addView(btnClear)
            layout.addView(topBar)

            for (clip in clips) {
                val btn = Button(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = 4
                        bottomMargin = 4
                    }
                    text = clip.take(60) + if (clip.length > 60) "..." else ""
                    textSize = 14f
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.bg_key)
                    setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
                    setOnClickListener {
                        triggerHapticFeedback(HapticType.COMMIT)
                        currentInputConnection?.commitText(clip, 1)
                        hideSymbolPanel()
                    }
                    setOnLongClickListener {
                        triggerHapticFeedback(HapticType.DELETE)
                        ClipboardHistoryManager.removeClip(context, clip)
                        switchSymbolTab(SymbolTab.CLIPBOARD)
                        true
                    }
                }
                layout.addView(btn)
            }
        }
        scrollView.addView(layout)
        return scrollView
    }

    private fun createKaomojiView(): View {
        val scrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isVerticalScrollBarEnabled = true
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6, 4, 6, 4)
        }

        val rows = EmojiKaomojiManager.KAOMOJI_LIST.chunked(3)
        for (rowItems in rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 4
                    bottomMargin = 4
                }
            }
            for (km in rowItems) {
                val btn = Button(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = 3
                        marginEnd = 3
                    }
                    text = km
                    textSize = 13f
                    setBackgroundResource(R.drawable.bg_key)
                    setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
                    setOnClickListener {
                        triggerHapticFeedback(HapticType.COMMIT)
                        commitProcessedText(km)
                        hideSymbolPanel()
                    }
                }
                rowLayout.addView(btn)
            }
            layout.addView(rowLayout)
        }
        scrollView.addView(layout)
        return scrollView
    }

    private fun createSnippetView(): View {
        val scrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isVerticalScrollBarEnabled = true
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 4, 8, 4)
        }

        val snippets = SnippetManager.getAllSnippets()
        for ((trigger, expansion) in snippets) {
            val btn = Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 4
                    bottomMargin = 4
                }
                text = "[$trigger] $expansion"
                textSize = 14f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_key)
                setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
                setOnClickListener {
                    triggerHapticFeedback(HapticType.COMMIT)
                    commitProcessedText(expansion)
                    hideSymbolPanel()
                }
            }
            layout.addView(btn)
        }
        scrollView.addView(layout)
        return scrollView
    }

    private fun createDpadView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            weightSum = 3f
        }

        // 左側快捷操作
        val leftActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.9f)
        }
        val btnHome = Button(this).apply {
            text = "Home"
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key_action)
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(2, 2, 2, 2) }
            layoutParams = p
            setOnClickListener { triggerHapticFeedback(); sendDpadKey(KeyEvent.KEYCODE_MOVE_HOME) }
        }
        val btnSelectAll = Button(this).apply {
            text = "全選"
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key_action)
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(2, 2, 2, 2) }
            layoutParams = p
            setOnClickListener { triggerHapticFeedback(); currentInputConnection?.performContextMenuAction(android.R.id.selectAll) }
        }
        val btnCopy = Button(this).apply {
            text = "複製"
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key_action)
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(2, 2, 2, 2) }
            layoutParams = p
            setOnClickListener { triggerHapticFeedback(); currentInputConnection?.performContextMenuAction(android.R.id.copy) }
        }
        leftActions.addView(btnHome)
        leftActions.addView(btnSelectAll)
        leftActions.addView(btnCopy)

        // 中間十字方向鍵盤
        val centerDpad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.2f)
        }

        val rowUp = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            gravity = Gravity.CENTER
        }
        val btnUp = Button(this).apply {
            text = "▲"
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key)
            val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(4, 2, 4, 2) }
            layoutParams = p
            setOnClickListener { triggerHapticFeedback(); sendDpadKey(KeyEvent.KEYCODE_DPAD_UP) }
        }
        rowUp.addView(btnUp)

        val rowMid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val btnLeft = Button(this).apply {
            text = "◀"
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key)
            val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(2, 2, 2, 2) }
            layoutParams = p
            setOnClickListener { triggerHapticFeedback(); sendDpadKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        }
        val btnEnter = Button(this).apply {
            text = "↵"
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key_action)
            val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(2, 2, 2, 2) }
            layoutParams = p
            setOnClickListener { triggerHapticFeedback(); performEnterAction() }
        }
        val btnRight = Button(this).apply {
            text = "▶"
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key)
            val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(2, 2, 2, 2) }
            layoutParams = p
            setOnClickListener { triggerHapticFeedback(); sendDpadKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        }
        rowMid.addView(btnLeft)
        rowMid.addView(btnEnter)
        rowMid.addView(btnRight)

        val rowDown = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            gravity = Gravity.CENTER
        }
        val btnDown = Button(this).apply {
            text = "▼"
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key)
            val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(4, 2, 4, 2) }
            layoutParams = p
            setOnClickListener { triggerHapticFeedback(); sendDpadKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        }
        rowDown.addView(btnDown)

        centerDpad.addView(rowUp)
        centerDpad.addView(rowMid)
        centerDpad.addView(rowDown)

        // 右側快捷操作
        val rightActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.9f)
        }
        val btnEnd = Button(this).apply {
            text = "End"
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key_action)
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(2, 2, 2, 2) }
            layoutParams = p
            setOnClickListener { triggerHapticFeedback(); sendDpadKey(KeyEvent.KEYCODE_MOVE_END) }
        }
        val btnCut = Button(this).apply {
            text = "剪下"
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key_action)
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(2, 2, 2, 2) }
            layoutParams = p
            setOnClickListener { triggerHapticFeedback(); currentInputConnection?.performContextMenuAction(android.R.id.cut) }
        }
        val btnPaste = Button(this).apply {
            text = "貼上"
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key_action)
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(2, 2, 2, 2) }
            layoutParams = p
            setOnClickListener { triggerHapticFeedback(); currentInputConnection?.performContextMenuAction(android.R.id.paste) }
        }
        val btnOneHanded = Button(this).apply {
            text = currentOneHandedMode.title
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.kb_accent))
            setBackgroundResource(R.drawable.bg_key_action)
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(2, 2, 2, 2) }
            layoutParams = p
            setOnClickListener {
                toggleOneHandedMode()
                text = currentOneHandedMode.title
            }
        }
        rightActions.addView(btnEnd)
        rightActions.addView(btnCut)
        rightActions.addView(btnPaste)
        rightActions.addView(btnOneHanded)

        root.addView(leftActions)
        root.addView(centerDpad)
        root.addView(rightActions)
        return root
    }

    private fun createSymbolGrid(symbols: List<String>, columns: Int): View {
        val scrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isVerticalScrollBarEnabled = false
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        var currentRow: LinearLayout? = null
        for ((idx, sym) in symbols.withIndex()) {
            if (idx % columns == 0) {
                currentRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 46.dpToPx())
                }
                container.addView(currentRow)
            }
            val btn = Button(this).apply {
                text = sym
                textSize = 17f
                setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
                setBackgroundResource(R.drawable.bg_key)
                val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(1, 1, 1, 1) }
                layoutParams = p
                setOnClickListener {
                    triggerHapticFeedback()
                    commitTextDirectly(sym)
                }
            }
            currentRow?.addView(btn)
        }
        scrollView.addView(container)
        return scrollView
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun sendDpadKey(keyCode: Int) {
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
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
        if (currentMode == KeyboardMode.ZHUYIN_FULL && fullZhuyinBuffer.isNotEmpty()) {
            val candidates = engine.searchFullZhuyin(fullZhuyinBuffer.toString())
            val word = candidates.firstOrNull()?.word ?: fullZhuyinBuffer.toString()
            commitProcessedWordWithUserDict(word)
            return
        }
        if (engine.hasComposing()) {
            val topWord = customComposingWord ?: engine.getCandidates().firstOrNull()?.word ?: engine.getTopComposingWord()
            commitProcessedWordWithUserDict(topWord)
        } else {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun isTraditionalMode(): Boolean {
        // 只要不是簡體模式，逗號/句號就輸出全形（適用注音、數字、手寫模式）
        // 英文模式下強制半形
        return when (currentMode) {
            KeyboardMode.ENGLISH_QWERTY -> false
            else -> !isSimplified
        }
    }


    private fun setupBottomActions(root: View) {
        btnComma = root.findViewById(R.id.btn_comma)
        btnPeriod = root.findViewById(R.id.btn_period)

        // 1. 123 數字/符號模式切換（長按打開設定）
        btnMode123.includeFontPadding = false
        btnMode123.setOnClickListener {
            triggerHapticFeedback()
            currentMode = if (currentMode == KeyboardMode.NUMBER_SYM) KeyboardMode.ZHUYIN else KeyboardMode.NUMBER_SYM
            engine.clear()
            fullZhuyinBuffer.clear()
            lastCommittedWord = null
            currentInputConnection?.setComposingText("", 1)
            refreshUI(emptyList())
            updateKeyboardModeUI()
        }
        btnMode123.setOnLongClickListener {
            triggerHapticFeedback(HapticType.MODE_SWITCH)
            openSettings()
            true
        }

        // 2. 左側鍵（數字模式下為括號雙向滑動鍵）
        btnQwertyToggle.onTapListener = {
            triggerHapticFeedback()
            commitTextDirectly("()")
            sendDpadKey(KeyEvent.KEYCODE_DPAD_LEFT)
        }
        btnQwertyToggle.onSwipeListener = { direction ->
            triggerHapticFeedback()
            when (direction) {
                SwipeKeyButton.Direction.LEFT -> {
                    val lBracket = if (isTraditionalMode()) "（" else "("
                    commitSymbol(lBracket)
                }
                SwipeKeyButton.Direction.RIGHT -> {
                    val rBracket = if (isTraditionalMode()) "）" else ")"
                    commitSymbol(rBracket)
                }
                else -> {}
            }
        }

        // 3. 右下角模式樞紐鍵 (中: 9鍵↔全鍵盤, 長按繁簡; 英: 大小寫三態; 數字: 回注音)
        btnLangToggle.setOnClickListener {
            triggerHapticFeedback()
            when (currentMode) {
                KeyboardMode.ZHUYIN -> {
                    currentMode = KeyboardMode.ZHUYIN_FULL
                    engine.clear()
                    fullZhuyinBuffer.clear()
                    currentInputConnection?.setComposingText("", 1)
                    refreshUI(emptyList())
                    updateKeyboardModeUI()
                }
                KeyboardMode.ZHUYIN_FULL -> {
                    currentMode = KeyboardMode.ZHUYIN
                    engine.clear()
                    fullZhuyinBuffer.clear()
                    currentInputConnection?.setComposingText("", 1)
                    refreshUI(emptyList())
                    updateKeyboardModeUI()
                }
                KeyboardMode.ENGLISH_QWERTY -> {
                    cycleEnglishCase()
                }
                KeyboardMode.NUMBER_SYM -> {
                    currentMode = KeyboardMode.ZHUYIN
                    engine.clear()
                    fullZhuyinBuffer.clear()
                    currentInputConnection?.setComposingText("", 1)
                    refreshUI(emptyList())
                    updateKeyboardModeUI()
                }
                KeyboardMode.HANDWRITING -> {
                    performBackspace()
                }
            }
        }

        btnLangToggle.setOnLongClickListener {
            triggerHapticFeedback(HapticType.MODE_SWITCH)
            isSimplified = !isSimplified
            val modeName = if (isSimplified) "簡體中文" else "繁體中文"
            android.widget.Toast.makeText(this, "已切換為：$modeName", android.widget.Toast.LENGTH_SHORT).show()
            updateKeyboardModeUI()
            if (engine.hasComposing() || fullZhuyinBuffer.isNotEmpty()) {
                val cands = if (currentMode == KeyboardMode.ZHUYIN_FULL) engine.searchFullZhuyin(fullZhuyinBuffer.toString()) else engine.getCandidates()
                refreshUI(cands)
            }
            true
        }

        btnSpaceSwipe.transformationMethod = null
        btnSpaceSwipe.includeFontPadding = false
        btnSpaceSwipe.setLineSpacing(0f, 0.9f)

        // 4. 空白鍵四向滑動指示盤（提示左滑/右滑切換語言模式）
        btnSpaceSwipe.swipeLabelsProvider = {
            when (currentMode) {
                KeyboardMode.ZHUYIN, KeyboardMode.ZHUYIN_FULL -> mapOf(
                    SwipeKeyButton.Direction.LEFT to "手寫",
                    SwipeKeyButton.Direction.RIGHT to "英文"
                )
                KeyboardMode.ENGLISH_QWERTY -> mapOf(
                    SwipeKeyButton.Direction.LEFT to "中文",
                    SwipeKeyButton.Direction.RIGHT to "手寫"
                )
                KeyboardMode.HANDWRITING -> mapOf(
                    SwipeKeyButton.Direction.LEFT to "英文",
                    SwipeKeyButton.Direction.RIGHT to "中文"
                )
                KeyboardMode.NUMBER_SYM -> mapOf(
                    SwipeKeyButton.Direction.LEFT to "手寫",
                    SwipeKeyButton.Direction.RIGHT to "中文"
                )
            }
        }

        btnSpaceSwipe.onTapListener = {
            triggerHapticFeedback()
            if (currentMode == KeyboardMode.ZHUYIN_FULL && fullZhuyinBuffer.isNotEmpty()) {
                val candidates = engine.searchFullZhuyin(fullZhuyinBuffer.toString())
                val topWord = candidates.firstOrNull()?.word ?: fullZhuyinBuffer.toString()
                commitProcessedWordWithUserDict(topWord)
            } else if (engine.hasComposing()) {
                val topWord = customComposingWord ?: engine.getCandidates().firstOrNull()?.word ?: engine.getTopComposingWord()
                commitProcessedWordWithUserDict(topWord)
            } else {
                commitTextDirectly(" ")
                lastCommittedWord = null
            }
        }

        btnSpaceSwipe.onSwipeListener = { direction ->
            triggerHapticFeedback()
            engine.clear()
            fullZhuyinBuffer.clear()
            currentInputConnection?.setComposingText("", 1)
            refreshUI(emptyList())

            if (direction == SwipeKeyButton.Direction.RIGHT) {
                currentMode = when (currentMode) {
                    KeyboardMode.ZHUYIN, KeyboardMode.ZHUYIN_FULL -> KeyboardMode.ENGLISH_QWERTY
                    KeyboardMode.ENGLISH_QWERTY -> KeyboardMode.HANDWRITING
                    KeyboardMode.HANDWRITING -> KeyboardMode.ZHUYIN
                    KeyboardMode.NUMBER_SYM -> KeyboardMode.ZHUYIN
                }
            } else if (direction == SwipeKeyButton.Direction.LEFT) {
                currentMode = when (currentMode) {
                    KeyboardMode.ZHUYIN, KeyboardMode.ZHUYIN_FULL -> KeyboardMode.HANDWRITING
                    KeyboardMode.HANDWRITING -> KeyboardMode.ENGLISH_QWERTY
                    KeyboardMode.ENGLISH_QWERTY -> KeyboardMode.ZHUYIN
                    KeyboardMode.NUMBER_SYM -> KeyboardMode.HANDWRITING
                }
            }

            if (currentMode == KeyboardMode.HANDWRITING && ::handwritingCanvas.isInitialized) {
                handwritingCanvas.clearCanvas()
            }

            updateKeyboardModeUI()
        }

        // 空白鍵長按快選常用標點（，。？！……：）
        btnSpaceSwipe.onLongClickListenerCustom = {
            triggerHapticFeedback()
            showQuickPunctuationPopup(btnSpaceSwipe)
        }

        // 逗點與句號 (數字模式半形「,」與「:」；注音繁體全形，簡體/英文半形)
        btnComma.setOnClickListener {
            triggerHapticFeedback()
            resetT9MultiTap()
            val sym = if (currentMode == KeyboardMode.NUMBER_SYM) "," else if (isTraditionalMode()) "，" else ","
            commitSymbol(sym)
        }
        btnPeriod.setOnClickListener {
            triggerHapticFeedback()
            resetT9MultiTap()
            val sym = if (currentMode == KeyboardMode.NUMBER_SYM) ":" else if (isTraditionalMode()) "。" else "."
            commitSymbol(sym)
        }
    }

    private fun openSettings() {
        triggerHapticFeedback()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    private fun formatMode123Label(mainText: String): CharSequence {
        val fullText = "$mainText\n⚙"
        val spannable = SpannableString(fullText)
        val split = mainText.length
        val primaryColor = ContextCompat.getColor(this, R.color.kb_text_primary)
        val secondaryColor = ContextCompat.getColor(this, R.color.kb_text_secondary)

        spannable.setSpan(RelativeSizeSpan(0.88f), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(StyleSpan(Typeface.BOLD), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(primaryColor), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        spannable.setSpan(RelativeSizeSpan(0.55f), split + 1, fullText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(secondaryColor), split + 1, fullText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return spannable
    }

    private fun formatSpaceChineseSubModeLabel(current: String, leftHint: String, rightHint: String): CharSequence {
        // 第一行：左側提示 + 中央主字 + 右側提示；第二行：空白鍵符號
        val line1 = "‹ $leftHint   $current   $rightHint ›"
        val line2 = "␣ 空白"
        val fullText = "$line1\n$line2"
        val spannable = SpannableString(fullText)

        val primaryColor = ContextCompat.getColor(this, R.color.kb_text_primary)
        val secondaryColor = ContextCompat.getColor(this, R.color.kb_text_secondary)

        val currentStart = line1.indexOf(current)
        val currentEnd = currentStart + current.length

        // 整體小字基礎
        spannable.setSpan(RelativeSizeSpan(0.55f), 0, fullText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(secondaryColor), 0, fullText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        // 中央主字（繁/簡/手）：大號、加粗、主色
        if (currentStart >= 0) {
            spannable.setSpan(RelativeSizeSpan(1.25f), currentStart, currentEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(StyleSpan(Typeface.BOLD), currentStart, currentEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(ForegroundColorSpan(primaryColor), currentStart, currentEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // 第二行 ␣ 空白：適中字號
        val line2Start = line1.length + 1
        spannable.setSpan(RelativeSizeSpan(0.65f), line2Start, fullText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(secondaryColor), line2Start, fullText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        return spannable
    }

    private fun updateKeyboardModeUI() {
        if (::layoutSymbolPanel.isInitialized) layoutSymbolPanel.visibility = View.GONE
        if (::btnSymbolDrawer.isInitialized) btnSymbolDrawer.text = "✛"

        btnMode123.transformationMethod = null
        btnMode123.includeFontPadding = false
        btnMode123.setLineSpacing(0f, 0.85f)

        btnLangToggle.transformationMethod = null
        btnLangToggle.includeFontPadding = false
        btnLangToggle.setLineSpacing(0f, 0.9f)
        btnLangToggle.setOnTouchListener(null)

        btnSpaceSwipe.transformationMethod = null
        btnSpaceSwipe.includeFontPadding = false
        btnSpaceSwipe.setLineSpacing(0f, 0.9f)

        when (currentMode) {
            KeyboardMode.ZHUYIN -> {
                layout12Key.visibility = View.VISIBLE
                if (::layoutZhuyinFull.isInitialized) layoutZhuyinFull.visibility = View.GONE
                layoutQwerty.visibility = View.GONE
                if (::layoutHandwriting.isInitialized) layoutHandwriting.visibility = View.GONE

                btnMode123.text = formatMode123Label("123")
                btnLangToggle.text = if (isSimplified) "9鍵·簡" else "9鍵·繁"
                btnSpaceSwipe.text = formatSpaceChineseSubModeLabel("中", "手寫", "英文")
                btnQwertyToggle.visibility = View.GONE

                update12KeyLabelsZhuyin()
                if (::btnSymAt.isInitialized) {
                    btnSymAt.text = "↵"
                    btnSymAt.visibility = View.VISIBLE
                }
                btnClear?.text = "清空"
            }
            KeyboardMode.ZHUYIN_FULL -> {
                layout12Key.visibility = View.GONE
                if (::layoutZhuyinFull.isInitialized) layoutZhuyinFull.visibility = View.VISIBLE
                layoutQwerty.visibility = View.GONE
                if (::layoutHandwriting.isInitialized) layoutHandwriting.visibility = View.GONE

                btnMode123.text = formatMode123Label("123")
                btnLangToggle.text = if (isSimplified) "全鍵·簡" else "全鍵·繁"
                btnSpaceSwipe.text = formatSpaceChineseSubModeLabel("中", "手寫", "英文")
                btnQwertyToggle.visibility = View.GONE
            }
            KeyboardMode.NUMBER_SYM -> {
                layout12Key.visibility = View.VISIBLE
                if (::layoutZhuyinFull.isInitialized) layoutZhuyinFull.visibility = View.GONE
                layoutQwerty.visibility = View.GONE
                if (::layoutHandwriting.isInitialized) layoutHandwriting.visibility = View.GONE

                btnMode123.text = formatMode123Label("注音")
                btnLangToggle.text = if (isSimplified) "簡體" else "繁體"
                btnSpaceSwipe.text = "空格"
                btnQwertyToggle.visibility = View.VISIBLE
                btnQwertyToggle.text = "( )"

                update12KeyLabelsNumbers()
                if (::btnSymAt.isInitialized) {
                    btnSymAt.text = "@"
                    btnSymAt.visibility = View.VISIBLE
                }
                btnClear?.text = "↵"
            }
            KeyboardMode.ENGLISH_QWERTY -> {
                layout12Key.visibility = View.GONE
                if (::layoutZhuyinFull.isInitialized) layoutZhuyinFull.visibility = View.GONE
                layoutQwerty.visibility = View.VISIBLE
                if (::layoutHandwriting.isInitialized) layoutHandwriting.visibility = View.GONE

                btnMode123.text = formatMode123Label("123")
                btnLangToggle.text = when (englishCaseState) {
                    EnglishCaseState.LOWER -> "abc"
                    EnglishCaseState.FIRST_UPPER -> "⇧Abc"
                    EnglishCaseState.ALL_UPPER -> "⇪ABC"
                }
                btnSpaceSwipe.text = formatSpaceChineseSubModeLabel("EN", "中文", "手寫")
                btnQwertyToggle.visibility = View.GONE

                updateQwertyKeysText()
                if (::btnSymAt.isInitialized) {
                    btnSymAt.text = "@"
                    btnSymAt.visibility = View.VISIBLE
                }
                btnClear?.text = "清空"
            }
            KeyboardMode.HANDWRITING -> {
                layout12Key.visibility = View.GONE
                if (::layoutZhuyinFull.isInitialized) layoutZhuyinFull.visibility = View.GONE
                layoutQwerty.visibility = View.GONE
                if (::layoutHandwriting.isInitialized) layoutHandwriting.visibility = View.VISIBLE

                btnMode123.text = formatMode123Label("123")
                btnLangToggle.text = "⌫"
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
                btnSpaceSwipe.text = formatSpaceChineseSubModeLabel("手", "英文", "中文")
                btnQwertyToggle.visibility = View.GONE
                if (::btnSymAt.isInitialized) {
                    btnSymAt.text = "↵"
                    btnSymAt.visibility = View.VISIBLE
                }
                btnClear?.text = "清空"
            }
        }
        updateSymbolsDisplay()
    }

    private fun updateSymbolsDisplay() {
        if (::btnComma.isInitialized) {
            btnComma.text = if (currentMode == KeyboardMode.NUMBER_SYM) "," else if (isTraditionalMode()) "，" else ","
        }
        if (::btnPeriod.isInitialized) {
            btnPeriod.text = if (currentMode == KeyboardMode.NUMBER_SYM) ":" else if (isTraditionalMode()) "。" else "."
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
            KeyboardMode.ZHUYIN, KeyboardMode.ZHUYIN_FULL -> {
                // 注音模式：常用全形標點（繁體中文高頻頓號置頂）
                setupSymbolButton(btnSym1, "？", listOf("?", "¿"))
                setupSymbolButton(btnSym2, "！", listOf("!", "¡"))
                setupSymbolButton(btnSym3, "……", listOf("…", "—"))
                setupSymbolButton(btnSym4, "：", listOf("；", "『", "』"))
                setupSymbolButton(btnSym5, "、", listOf("～", "·", "《", "》"))
            }
            KeyboardMode.ENGLISH_QWERTY -> {
                // QWERTY 模式下 layout_12key 隱藏，但仍重設按鈕避免殘留
                setupSymbolButton(btnSym1, "？", listOf("?", "¿"))
                setupSymbolButton(btnSym2, "！", listOf("!", "¡"))
                setupSymbolButton(btnSym3, "……", listOf("…", "—"))
                setupSymbolButton(btnSym4, "：", listOf("；", "『", "』"))
                setupSymbolButton(btnSym5, "、", listOf("～", "·", "《", "》"))
            }
            KeyboardMode.HANDWRITING -> {
                // 手寫模式：與注音相同標點
                setupSymbolButton(btnSym1, "？", listOf("?", "¿"))
                setupSymbolButton(btnSym2, "！", listOf("!", "¡"))
                setupSymbolButton(btnSym3, "……", listOf("…", "—"))
                setupSymbolButton(btnSym4, "：", listOf("；", "『", "』"))
                setupSymbolButton(btnSym5, "、", listOf("～", "·", "《", "》"))
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
            10 -> "#"; 11 -> "0"; 12 -> "."
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
        lastUserTypingTime = SystemClock.uptimeMillis()
        dismissHomophonePopup()
        resetT9MultiTap()
        if (isHomophoneSelectionMode) {
            exitHomophoneSelectionMode()
            return
        }
        if (customComposingWord != null) {
            customComposingWord = null
            replacedCharsMap.clear()
        }
        if (currentMode == KeyboardMode.ZHUYIN_FULL && fullZhuyinBuffer.isNotEmpty()) {
            fullZhuyinBuffer.deleteCharAt(fullZhuyinBuffer.length - 1)
            if (fullZhuyinBuffer.isEmpty()) {
                currentInputConnection?.finishComposingText()
                clearCandidateBar()
            } else {
                updateComposingPreviewFull()
                val candidates = engine.searchFullZhuyin(fullZhuyinBuffer.toString())
                refreshUI(candidates)
            }
            return
        }
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
            clearCandidateBar()
        }
    }

    private fun commitSymbol(text: String) {
        resetT9MultiTap()
        if (engine.hasComposing()) {
            engine.clear()
            currentInputConnection?.finishComposingText()
        }
        customComposingWord = null
        replacedCharsMap.clear()
        isHomophoneSelectionMode = false
        homophoneCharIndex = -1
        currentInputConnection?.commitText(text, 1)
        currentInputConnection?.finishComposingText()
        lastCommittedWord = null
        showNextWordPredictions("")
    }

    private fun commitTextDirectly(text: String) {
        customComposingWord = null
        replacedCharsMap.clear()
        isHomophoneSelectionMode = false
        homophoneCharIndex = -1
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
            customComposingWord = null
            replacedCharsMap.clear()
            isHomophoneSelectionMode = false
            homophoneCharIndex = -1
            lastComposingStart = -1
            lastComposingEnd = -1
            return
        }
        val previewWord = customComposingWord ?: engine.getTopComposingWord()
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
                val existing = comboButtonPool[i]
                if (existing.parent != containerZhuyinCombos) {
                    (existing.parent as? ViewGroup)?.removeView(existing)
                    containerZhuyinCombos.addView(existing)
                }
                existing
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

    private fun clearCandidateBar() {
        for (tv in candidateTextViewPool) {
            tv.visibility = View.GONE
        }
        candidateMoreIndicator?.visibility = View.GONE
        btnCandidateExpand?.visibility = View.GONE
        candidateScroll?.scrollTo(0, 0)
        currentCandidateList = emptyList()
        if (isCandidateGridOpen) {
            closeCandidateGrid()
        }
    }

    private fun openCandidateGrid() {
        if (currentCandidateList.isEmpty()) return
        triggerHapticFeedback(HapticType.MODE_SWITCH)
        isCandidateGridOpen = true

        layout12Key.visibility = View.GONE
        layoutQwerty.visibility = View.GONE
        if (::layoutHandwriting.isInitialized) layoutHandwriting.visibility = View.GONE
        if (::layoutZhuyinFull.isInitialized) layoutZhuyinFull.visibility = View.GONE
        if (::layoutSymbolPanel.isInitialized) layoutSymbolPanel.visibility = View.GONE

        layoutCandidateGrid?.visibility = View.VISIBLE
        btnCandidateExpand?.text = "▲"
        populateCandidateGrid()
    }

    private fun closeCandidateGrid() {
        if (!isCandidateGridOpen) return
        isCandidateGridOpen = false
        layoutCandidateGrid?.visibility = View.GONE
        btnCandidateExpand?.text = "▼"
        updateKeyboardModeUI()
    }

    private fun populateCandidateGrid() {
        val count = currentCandidateList.size
        tvCandidateGridTitle?.text = "全部候選字 (共 ${count} 個)"
        containerCandidateGrid?.removeAllViews()

        val currentTheme = ThemeManager.getCurrentTheme(this)
        val themeColors = ThemeManager.getThemeColors(this, currentTheme)
        layoutCandidateGrid?.setBackgroundColor(themeColors.bg)
        tvCandidateGridTitle?.setTextColor(themeColors.textSecondary)
        btnCandidateGridClose?.setTextColor(themeColors.accent)

        val itemsPerRow = 4
        val rows = currentCandidateList.chunked(itemsPerRow)

        for (rowItems in rows) {
            val rowLayout = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 3
                    bottomMargin = 3
                }
                orientation = LinearLayout.HORIZONTAL
            }

            for (entry in rowItems) {
                val btn = Button(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        marginStart = 3
                        marginEnd = 3
                    }
                    val displayWord = if (isSimplified) ChineseConverter.toSimplified(entry.word) else entry.word
                    text = displayWord
                    textSize = 17f
                    setTextColor(themeColors.textPrimary)
                    setOnClickListener {
                        triggerHapticFeedback(HapticType.COMMIT)
                        closeCandidateGrid()
                        if (isHomophoneSelectionMode) {
                            applyHomophoneReplacement(entry)
                        } else {
                            selectCandidate(entry)
                        }
                    }
                }
                rowLayout.addView(btn)
            }

            if (rowItems.size < itemsPerRow) {
                for (j in 0 until (itemsPerRow - rowItems.size)) {
                    val spacer = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    }
                    rowLayout.addView(spacer)
                }
            }
            containerCandidateGrid?.addView(rowLayout)
        }
    }

    fun applyOneHandedMode() {
        val root = rootView ?: return
        val prefs = getSharedPreferences("ime_prefs", Context.MODE_PRIVATE)
        val modeId = prefs.getString("pref_one_handed_mode", OneHandedMode.FULL.id)
        currentOneHandedMode = OneHandedMode.values().find { it.id == modeId } ?: OneHandedMode.FULL

        val density = resources.displayMetrics.density
        val sidePaddingPx = (75 * density).toInt()

        when (currentOneHandedMode) {
            OneHandedMode.FULL -> {
                root.setPadding(4, 4, 4, 4)
            }
            OneHandedMode.LEFT -> {
                root.setPadding(4, 4, sidePaddingPx, 4)
            }
            OneHandedMode.RIGHT -> {
                root.setPadding(sidePaddingPx, 4, 4, 4)
            }
        }
    }

    fun toggleOneHandedMode() {
        triggerHapticFeedback(HapticType.MODE_SWITCH)
        currentOneHandedMode = when (currentOneHandedMode) {
            OneHandedMode.FULL -> OneHandedMode.RIGHT
            OneHandedMode.RIGHT -> OneHandedMode.LEFT
            OneHandedMode.LEFT -> OneHandedMode.FULL
        }
        val prefs = getSharedPreferences("ime_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("pref_one_handed_mode", currentOneHandedMode.id).apply()
        applyOneHandedMode()
    }

    private fun updateCandidateBar(candidates: List<DictEntry>) {
        currentCandidateList = candidates
        btnCandidateExpand?.visibility = if (candidates.isNotEmpty()) View.VISIBLE else View.GONE
        if (isCandidateGridOpen) {
            populateCandidateGrid()
        }

        val displayCandidates = candidates.take(MAX_CANDIDATES_DISPLAY)
        val count = displayCandidates.size

        for (i in 0 until count) {
            val entry = displayCandidates[i]
            val displayWord = if (isSimplified) ChineseConverter.toSimplified(entry.word) else entry.word

            val tv = if (i < candidateTextViewPool.size) {
                val existing = candidateTextViewPool[i]
                if (existing.parent != candidateContainer) {
                    (existing.parent as? ViewGroup)?.removeView(existing)
                    candidateContainer.addView(existing)
                }
                existing
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

            val currentTheme = ThemeManager.getCurrentTheme(this)
            val themeColors = ThemeManager.getThemeColors(this, currentTheme)
            tv.text = displayWord
            tv.setTextColor(
                if (i == 0) themeColors.candidateText
                else themeColors.textPrimary
            )
            tv.setOnClickListener {
                triggerHapticFeedback(HapticType.COMMIT)
                if (isCandidateGridOpen) {
                    closeCandidateGrid()
                }
                if (isHomophoneSelectionMode) {
                    if (entry.word.startsWith("✔")) {
                        exitHomophoneSelectionMode()
                    } else {
                        applyHomophoneReplacement(entry)
                    }
                } else {
                    selectCandidate(entry)
                }
            }
            tv.setOnLongClickListener {
                val hasComp = engine.hasComposing() || fullZhuyinBuffer.isNotEmpty()
                if (hasComp && !isHomophoneSelectionMode) {
                    triggerHapticFeedback(HapticType.MODE_SWITCH)
                    enterHomophoneSelectionMode(0)
                    true
                } else false
            }

            tv.visibility = View.VISIBLE
        }

        for (i in count until candidateTextViewPool.size) {
            candidateTextViewPool[i].visibility = View.GONE
        }
        candidateScroll?.scrollTo(0, 0)
        candidateScroll?.post {
            val canScroll = candidateContainer.width > (candidateScroll?.width ?: 0)
            candidateMoreIndicator?.visibility = if (canScroll) View.VISIBLE else View.GONE
        }
    }

    private fun getCurrentComposingText(): String {
        return if (currentMode == KeyboardMode.ZHUYIN_FULL) {
            customComposingWord ?: (engine.searchFullZhuyin(fullZhuyinBuffer.toString()).firstOrNull()?.word ?: fullZhuyinBuffer.toString())
        } else {
            customComposingWord ?: engine.getTopComposingWord()
        }
    }

    /**
     * 底線文字選取同音替換模式：
     * 當使用者在輸入區長按或點選底線候選字中的某個字時，候選列切換為同音/同拼法候選字。
     */
    private fun enterHomophoneSelectionMode(charIndex: Int) {
        val currentWord = getCurrentComposingText()
        if (charIndex !in currentWord.indices) return

        isHomophoneSelectionMode = true
        homophoneCharIndex = charIndex
        val targetChar = currentWord[charIndex]

        val homophones = engine.getHomophonesForChar(targetChar)
        val candidateItems = mutableListOf<DictEntry>()

        // 第一項：原字確認項（可點擊保持原字或取消同音模式）
        val origZy = engine.getZhuyinForChar(targetChar)
        candidateItems.add(DictEntry("✔ $targetChar", origZy, 999_999_999))

        for (h in homophones) {
            if (h.word != targetChar.toString()) {
                candidateItems.add(h)
            }
        }

        updateCandidateBar(candidateItems)

        // 同步彈出懸浮同音字選單，確保在任何 App 輸入框長按時能直觀看到選單
        dismissHomophonePopup()
        try {
            val anchor = candidateContainer ?: rootView
            if (anchor != null) {
                val popup = android.widget.PopupMenu(this, anchor)
                activeHomophonePopup = popup
                popup.menu.add(0, 0, 0, "✔ 保持原字【$targetChar】")
                for ((index, homo) in homophones.withIndex()) {
                    if (homo.word != targetChar.toString()) {
                        val displayHomo = if (isSimplified) ChineseConverter.toSimplified(homo.word) else homo.word
                        popup.menu.add(0, index + 1, index + 1, displayHomo)
                    }
                }
                popup.setOnMenuItemClickListener { menuItem ->
                    triggerHapticFeedback(HapticType.COMMIT)
                    if (menuItem.itemId > 0) {
                        val chosen = homophones[menuItem.itemId - 1]
                        applyHomophoneReplacement(chosen)
                    } else {
                        exitHomophoneSelectionMode()
                    }
                    true
                }
                popup.setOnDismissListener {
                    if (activeHomophonePopup === popup) {
                        activeHomophonePopup = null
                    }
                }
                popup.show()
            }
        } catch (_: Exception) {}
    }

    private fun applyHomophoneReplacement(entry: DictEntry) {
        dismissHomophonePopup()
        val baseWord = getCurrentComposingText()
        if (homophoneCharIndex in baseWord.indices) {
            val sb = StringBuilder(baseWord)
            sb.setCharAt(homophoneCharIndex, entry.word[0])
            val newWord = sb.toString()
            customComposingWord = newWord
            val zhuyin = if (entry.zhuyin.isNotEmpty()) entry.zhuyin else engine.getZhuyinForChar(entry.word[0])
            replacedCharsMap[homophoneCharIndex] = Pair(entry.word, zhuyin)

            // 更新輸入框底線組字預覽（保持底線未確認狀態，讓使用者可繼續修改其他字）
            val finalPreview = if (isSimplified) ChineseConverter.toSimplified(newWord) else newWord
            currentInputConnection?.setComposingText(finalPreview, 1)
        }
        exitHomophoneSelectionMode()
    }

    private fun exitHomophoneSelectionMode() {
        dismissHomophonePopup()
        isHomophoneSelectionMode = false
        homophoneCharIndex = -1
        val baseCandidates = if (currentMode == KeyboardMode.ZHUYIN_FULL) {
            engine.searchFullZhuyin(fullZhuyinBuffer.toString())
        } else {
            engine.getCandidates()
        }
        if (customComposingWord != null) {
            val preview = customComposingWord!!
            val candidates = mutableListOf<DictEntry>()
            candidates.add(DictEntry(preview, "", 100_000_000))
            candidates.addAll(baseCandidates.filter { it.word != preview })
            updateCandidateBar(candidates)
        } else {
            updateCandidateBar(baseCandidates)
        }
    }

    private fun showNextWordPredictions(word: String) {
        if (word.isEmpty()) {
            clearCandidateBar()
            return
        }

        val predictions = engine.getNextWordPredictions(word)
        if (predictions.isNotEmpty()) {
            updateCandidateBar(predictions)
        } else {
            clearCandidateBar()
        }
    }

    private fun selectCandidate(entry: DictEntry) {
        if (entry.word.startsWith("【")) return
        val wordToCommit = if (customComposingWord != null && (entry.word == engine.getTopComposingWord() || entry.word == customComposingWord)) {
            customComposingWord!!
        } else {
            entry.word
        }
        commitProcessedWordWithUserDict(wordToCommit, entry.zhuyin)
    }

    /**
     * 詞彙確認上屏與個人詞庫記憶（等確定出去才紀錄成優選）
     */
    private fun commitProcessedWordWithUserDict(word: String, zhuyin: String = "") {
        if (word.startsWith("【")) return
        // 1. 記錄選定確認的整組詞彙，並即時注入 Trie 字典賦予絕對首選優選
        engine.learnWord(word, zhuyin)

        // 1.5 學習 Bigram 語境詞對
        val prev = lastCommittedWord
        if (prev != null && prev != word && prev.length in 1..8 && word.length in 1..8) {
            engine.learnBigram(prev, word)
        }
        lastCommittedWord = word
        engine.currentContextWord = word

        // 2. 記錄個別替換字及其注音，等確定出去才紀錄成優選
        for ((_, pair) in replacedCharsMap) {
            engine.learnWord(pair.first, pair.second)
        }

        // 3. 重設組字狀態
        customComposingWord = null
        replacedCharsMap.clear()
        isHomophoneSelectionMode = false
        homophoneCharIndex = -1
        lastComposingStart = -1
        lastComposingEnd = -1

        engine.clear()
        fullZhuyinBuffer.clear()
        val finalText = if (isSimplified) ChineseConverter.toSimplified(word) else word
        currentInputConnection?.commitText(finalText, 1)
        currentInputConnection?.finishComposingText()
        if (::handwritingCanvas.isInitialized) {
            handwritingCanvas.clearCanvas()
        }
        updateLeftZhuyinCombos()
        showNextWordPredictions(word)
    }

    /**
     * 監聽輸入框游標與選取區變更：
     * 當使用者在輸入文字區內「長按選取」或點選底線候選字其中之一時，觸發同音字替換模式
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)

        // 1. 打字中冷卻防護：鍵盤輸入後 800ms 內的所有 selection 變動皆為打字產生的游標移動，絕不觸發改字視窗
        if (SystemClock.uptimeMillis() - lastUserTypingTime < 800L) {
            return
        }

        // 2. 只有在使用者真的進行「長按選取」（newSelStart != newSelEnd）時才視為長按改字：
        // 一般光標移動或點擊時 newSelStart == newSelEnd，絕不觸發！
        if (newSelStart == newSelEnd) {
            return
        }

        if (candidatesStart >= 0) {
            lastComposingStart = candidatesStart
            lastComposingEnd = candidatesEnd
        }

        val hasComposing = engine.hasComposing() || fullZhuyinBuffer.isNotEmpty()
        if (!hasComposing) {
            lastComposingStart = -1
            lastComposingEnd = -1
            dismissHomophonePopup()
            return
        }

        val currentWord = getCurrentComposingText()
        if (currentWord.isEmpty()) return

        val cStart = if (candidatesStart >= 0) candidatesStart else lastComposingStart
        val cEnd = if (candidatesEnd >= 0) candidatesEnd else lastComposingEnd

        if (cStart >= 0 && cEnd > cStart) {
            val selMin = minOf(newSelStart, newSelEnd)
            val selMax = maxOf(newSelStart, newSelEnd)
            // 選取範圍與組字區間有重疊
            if (selMin in cStart until cEnd || selMax in (cStart + 1)..cEnd) {
                val offset = (selMin - cStart).coerceIn(0, currentWord.length - 1)
                if (isHomophoneSelectionMode && homophoneCharIndex == offset) {
                    return
                }
                triggerHapticFeedback()
                enterHomophoneSelectionMode(offset)
            }
        }
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
        if (currentMode == KeyboardMode.ENGLISH_QWERTY) {
            return super.onKeyDown(keyCode, event)
        }

        // 4. 注音模式下的實體鍵盤處理
        if (currentMode == KeyboardMode.ZHUYIN || currentMode == KeyboardMode.ZHUYIN_FULL) {
            // A. Backspace 刪除
            if (keyCode == KeyEvent.KEYCODE_DEL) {
                if (currentMode == KeyboardMode.ZHUYIN_FULL && fullZhuyinBuffer.isNotEmpty()) {
                    performBackspace()
                    return true
                }
                if (engine.hasComposing()) {
                    performBackspace()
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }

            // B. Space 空白鍵
            if (keyCode == KeyEvent.KEYCODE_SPACE) {
                if (currentMode == KeyboardMode.ZHUYIN_FULL && fullZhuyinBuffer.isNotEmpty()) {
                    val candidates = engine.searchFullZhuyin(fullZhuyinBuffer.toString())
                    val topWord = candidates.firstOrNull()?.word ?: fullZhuyinBuffer.toString()
                    commitProcessedWordWithUserDict(topWord)
                    return true
                }
                if (engine.hasComposing()) {
                    val topWord = customComposingWord ?: engine.getCandidates().firstOrNull()?.word ?: engine.getTopComposingWord()
                    commitProcessedWordWithUserDict(topWord)
                    return true
                } else {
                    commitTextDirectly(" ")
                    return true
                }
            }

            // C. Enter 鍵確認直接送出當前注音/預測候選
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                if ((currentMode == KeyboardMode.ZHUYIN_FULL && fullZhuyinBuffer.isNotEmpty()) || engine.hasComposing()) {
                    performEnterAction()
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }

            // D. 數字鍵選字 (1~9 選候選字，支援 9 鍵與 41 鍵全鍵盤)
            val hasActiveComposing = engine.hasComposing() || fullZhuyinBuffer.isNotEmpty()
            if (hasActiveComposing && keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
                val selectIndex = keyCode - KeyEvent.KEYCODE_1
                val candidates = if (currentMode == KeyboardMode.ZHUYIN_FULL) {
                    engine.searchFullZhuyin(fullZhuyinBuffer.toString())
                } else {
                    engine.getCandidates()
                }
                if (selectIndex < candidates.size) {
                    selectCandidate(candidates[selectIndex])
                    return true
                }
            }

            // E. 大千注音按鍵映射輸入
            val zhuyinChar = DAQIAN_KEY_MAP[keyCode]
            if (zhuyinChar != null) {
                if (currentMode == KeyboardMode.ZHUYIN_FULL) {
                    handleZhuyinFullKey(zhuyinChar)
                    return true
                } else {
                    if (zhuyinChar in listOf('ˇ', 'ˋ', 'ˊ', '˙')) {
                        val (_, cands) = engine.setTone(zhuyinChar)
                        refreshUI(cands)
                        return true
                    } else {
                        val keyId = com.bopomofo.t9ime.engine.KeyMapping.getKeyId(zhuyinChar)
                        if (keyId != null) {
                            val topCandidate = engine.getCandidates().firstOrNull()
                            val curKeys = engine.getCurrentKeys()
                            if (topCandidate != null && topCandidate.word.length >= 2 && curKeys.isNotEmpty()) {
                                val testKeys = curKeys + keyId
                                val canExtendLongerWord = engine.hasPrefixOrExact(testKeys)
                                if (!canExtendLongerWord) {
                                    commitProcessedWordWithUserDict(topCandidate.word, topCandidate.zhuyin)
                                }
                            }
                            engine.pressKey(keyId)
                            refreshUI(engine.getCandidates())
                            return true
                        }
                    }
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
        dismissHomophonePopup()
        if (isHomophoneSelectionMode) {
            isHomophoneSelectionMode = false
            homophoneCharIndex = -1
        }
        engine.clear()
        lastCommittedWord = null
        resetT9MultiTap()
        clearCandidateBar()
        currentInputConnection?.finishComposingText()
    }
}
