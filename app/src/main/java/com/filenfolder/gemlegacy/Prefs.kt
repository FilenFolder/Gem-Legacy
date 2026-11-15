package com.filenfolder.gemlegacy

import android.content.Context

object Prefs {

    const val DEFAULT_MODELS =
        "gemini-3.1-flash-lite,gemini-2.5-flash-lite,gemini-3.5-flash-lite,gemini-2.5-flash," +
            "gemini-3.6-flash,gemini-3.8-flash,gemini-flash-latest"

    const val GET_KEY_URL = "https://aistudio.google.com/apikey"

    private fun prefs(context: Context) =
        context.getSharedPreferences("gem_legacy", Context.MODE_PRIVATE)

    fun apiKey(context: Context): String =
        (prefs(context).getString("api_key", "") ?: "").trim()

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString("api_key", key.trim()).apply()
    }

    fun modelsText(context: Context): String =
        (prefs(context).getString("models", DEFAULT_MODELS) ?: DEFAULT_MODELS).trim()

    fun setModelsText(context: Context, text: String) {
        prefs(context).edit().putString("models", text.trim()).apply()
    }

    fun darkMode(context: Context): Boolean = prefs(context).getBoolean("dark_mode", true)

    fun setDarkMode(context: Context, dark: Boolean) {
        prefs(context).edit().putBoolean("dark_mode", dark).apply()
    }

    fun draft(context: Context): String = prefs(context).getString("draft", "") ?: ""

    fun setDraft(context: Context, text: String) {
        prefs(context).edit().putString("draft", text).apply()
    }

    fun speakerOn(context: Context): Boolean = prefs(context).getBoolean("speaker_on", false)

    fun setSpeakerOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("speaker_on", on).apply()
    }

    fun models(context: Context): List<String> {
        val list = modelsText(context).split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return if (list.isEmpty()) DEFAULT_MODELS.split(",") else list
    }
}
