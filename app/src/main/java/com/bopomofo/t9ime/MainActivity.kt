package com.bopomofo.t9ime

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bopomofo.t9ime.engine.UserDictionaryManager

/**
 * 設定頁面與個人化詞庫管理 (匯入/匯出)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvDictStats: TextView
    private val userDictManager by lazy { UserDictionaryManager.getInstance(this) }

    // SAF 檔案建立器 (匯出)
    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { os ->
                    userDictManager.exportToStream(os)
                }
                Toast.makeText(this, "✅ 詞庫已成功匯出！", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "❌ 匯出失敗: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // SAF 檔案挑選器 (匯入)
    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val count = contentResolver.openInputStream(uri)?.use { inputStream ->
                    userDictManager.importFromStream(inputStream)
                } ?: 0
                Toast.makeText(this, "✅ 成功匯入 $count 筆詞條！", Toast.LENGTH_SHORT).show()
                updateDictStats()
            } catch (e: Exception) {
                Toast.makeText(this, "❌ 匯入失敗: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvDictStats = findViewById(R.id.tv_dict_stats)

        findViewById<Button>(R.id.btn_enable_ime)?.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        findViewById<Button>(R.id.btn_select_ime)?.setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showInputMethodPicker()
        }

        // 匯出個人詞庫
        findViewById<Button>(R.id.btn_export_dict)?.setOnClickListener {
            val count = userDictManager.getEntryCount()
            if (count == 0) {
                Toast.makeText(this, "目前尚無記錄任何詞彙，請先使用輸入法打字或匯入詞庫", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            createDocumentLauncher.launch("bopomofo_user_dict.txt")
        }

        // 匯入自訂詞庫
        findViewById<Button>(R.id.btn_import_dict)?.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("text/plain", "*/*"))
        }

        // 清空學習紀錄
        findViewById<Button>(R.id.btn_clear_dict)?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("確認清空個人詞庫")
                .setMessage("這將會清除您目前在手機上學習的所有自訂詞彙與高頻加權記錄，確定要清空嗎？")
                .setPositiveButton("確定清空") { _, _ ->
                    userDictManager.clearDictionary()
                    updateDictStats()
                    Toast.makeText(this, "個人詞庫已清空", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        setupVibrationSettings()
    }

    private fun setupVibrationSettings() {
        val prefs = getSharedPreferences("ime_prefs", MODE_PRIVATE)
        val switchVib = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_vibration)
        val layoutStrength = findViewById<android.view.View>(R.id.layout_vibration_strength)
        val seekbarStrength = findViewById<android.widget.SeekBar>(R.id.seekbar_vibration_strength)
        val tvStrengthVal = findViewById<TextView>(R.id.tv_vibration_strength_val)

        val isEnabled = prefs.getBoolean("pref_vibration_enabled", true)
        val savedStrength = prefs.getInt("pref_vibration_strength", 30).coerceIn(5, 100)

        switchVib?.isChecked = isEnabled
        layoutStrength?.visibility = if (isEnabled) android.view.View.VISIBLE else android.view.View.GONE
        seekbarStrength?.progress = savedStrength
        tvStrengthVal?.text = "$savedStrength ms"

        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
        }

        val testVibrate = { ms: Int ->
            try {
                if (vibrator != null && vibrator.hasVibrator()) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        val amplitude = ((ms / 100f) * 255).toInt().coerceIn(1, 255)
                        val effect = android.os.VibrationEffect.createOneShot(ms.toLong(), amplitude)
                        vibrator.vibrate(effect)
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(ms.toLong())
                    }
                }
            } catch (_: Exception) {}
        }

        switchVib?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_vibration_enabled", isChecked).apply()
            layoutStrength?.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
            if (isChecked) {
                testVibrate(seekbarStrength?.progress ?: 30)
            }
        }

        seekbarStrength?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val strength = progress.coerceIn(5, 100)
                tvStrengthVal?.text = "$strength ms"
                if (fromUser) {
                    prefs.edit().putInt("pref_vibration_strength", strength).apply()
                    testVibrate(strength)
                }
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                val strength = (seekBar?.progress ?: 30).coerceIn(5, 100)
                prefs.edit().putInt("pref_vibration_strength", strength).apply()
                testVibrate(strength)
            }
        })

        setupUpdateCheck()
    }

    private fun setupUpdateCheck() {
        val tvCurrentVer = findViewById<TextView>(R.id.tv_current_version)
        val btnCheck = findViewById<Button>(R.id.btn_check_update)

        tvCurrentVer?.text = "目前安裝版本：v${BuildConfig.VERSION_NAME}"

        btnCheck?.setOnClickListener {
            btnCheck.isEnabled = false
            btnCheck.text = "正在連線檢查 GitHub Releases..."
            com.bopomofo.t9ime.update.AppUpdateManager.checkUpdate { hasUpdate, info, error ->
                btnCheck.isEnabled = true
                btnCheck.text = "🔍 檢查 GitHub 新版本"
                if (error != null) {
                    Toast.makeText(this, "檢查更新失敗: $error", Toast.LENGTH_LONG).show()
                } else if (hasUpdate && info != null) {
                    com.bopomofo.t9ime.update.AppUpdateManager.showUpdateDialog(this, info)
                } else {
                    Toast.makeText(this, "🎉 目前已是最新版本 (v${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateDictStats()
    }

    private fun updateDictStats() {
        val count = userDictManager.getEntryCount()
        tvDictStats.text = "目前已記錄：$count 個常用專屬詞彙"
    }
}
