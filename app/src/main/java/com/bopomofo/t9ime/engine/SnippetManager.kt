package com.bopomofo.t9ime.engine

import android.content.Context
import org.json.JSONObject

object SnippetManager {
    private const val PREF_NAME = "ime_snippet_prefs"
    private const val KEY_SNIPPETS = "custom_snippets"

    private val snippetMap = mutableMapOf<String, String>()
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_SNIPPETS, null)

        if (!jsonStr.isNullOrEmpty()) {
            try {
                val obj = JSONObject(jsonStr)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    snippetMap[k] = obj.getString(k)
                }
            } catch (_: Exception) {}
        }

        // 預設常用範例短語（若尚無資料）
        if (snippetMap.isEmpty()) {
            snippetMap["addr"] = "台北市信義區信義路五段7號"
            snippetMap["phone"] = "0912-345-678"
            snippetMap["thx"] = "感謝您的協助，祝您順心！"
            snippetMap["sig"] = "Best regards"
            snippetMap["ok"] = "好的，沒問題！"
            save(context)
        }
        isInitialized = true
    }

    fun getAllSnippets(): Map<String, String> = snippetMap.toMap()

    fun getExpansion(trigger: String): String? {
        return snippetMap[trigger.lowercase()]
    }

    fun setSnippet(context: Context, trigger: String, expansion: String) {
        if (trigger.isBlank() || expansion.isBlank()) return
        snippetMap[trigger.lowercase()] = expansion
        save(context)
    }

    fun removeSnippet(context: Context, trigger: String) {
        snippetMap.remove(trigger.lowercase())
        save(context)
    }

    private fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val obj = JSONObject()
        for ((k, v) in snippetMap) {
            obj.put(k, v)
        }
        prefs.edit().putString(KEY_SNIPPETS, obj.toString()).apply()
    }
}
