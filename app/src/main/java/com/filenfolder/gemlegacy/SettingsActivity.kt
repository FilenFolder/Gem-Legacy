package com.filenfolder.gemlegacy

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

class SettingsActivity : Activity() {

    private lateinit var keyInput: EditText
    private lateinit var modelsInput: EditText
    private lateinit var testResult: TextView
    private lateinit var testButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (Prefs.darkMode(this)) R.style.Theme_GemLegacy_Dark else R.style.Theme_GemLegacy_Light)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val darkCheck = findViewById<CheckBox>(R.id.darkModeCheck)
        darkCheck.isChecked = Prefs.darkMode(this)
        darkCheck.setOnCheckedChangeListener { _, checked ->
            Prefs.setDarkMode(this, checked)
            recreate()
        }

        keyInput = findViewById(R.id.keyInput)
        modelsInput = findViewById(R.id.modelsInput)
        testResult = findViewById(R.id.testResult)
        testButton = findViewById(R.id.testButton)

        keyInput.setText(Prefs.apiKey(this))
        keyInput.transformationMethod = PasswordTransformationMethod.getInstance()
        modelsInput.setText(Prefs.modelsText(this))

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            ""
        }
        findViewById<TextView>(R.id.aboutText).text = getString(R.string.about_text, version)

        findViewById<CheckBox>(R.id.showKey).setOnCheckedChangeListener { _, checked ->
            keyInput.transformationMethod = if (checked) null else PasswordTransformationMethod.getInstance()
            keyInput.setSelection(keyInput.text.length)
        }
        findViewById<Button>(R.id.getKeyButton).setOnClickListener { openKeyPage() }
        testButton.setOnClickListener { testKey() }
        findViewById<Button>(R.id.resetModelsButton).setOnClickListener {
            modelsInput.setText(Prefs.DEFAULT_MODELS)
        }
        findViewById<Button>(R.id.saveButton).setOnClickListener { save() }
    }

    private fun openKeyPage() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Prefs.GET_KEY_URL)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, Prefs.GET_KEY_URL, Toast.LENGTH_LONG).show()
        }
    }

    private fun testKey() {
        val key = keyInput.text.toString().trim()
        if (key.isEmpty()) {
            showTest(getString(R.string.key_empty), false)
            return
        }
        val models = modelsInput.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { Prefs.DEFAULT_MODELS.split(",") }
        testButton.isEnabled = false
        testResult.setTextColor(0xFFBBBBBB.toInt())
        testResult.setText(R.string.testing)

        Thread {
            var error: String? = null
            try {
                val parts = JSONArray().put(JSONObject().put("text", "Reply with the single word OK."))
                val message = JSONObject().put("role", "user").put("parts", parts)
                GeminiClient(key, models).generate(listOf(message))
            } catch (e: GeminiException) {
                error = e.message
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            val finalError = error
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                testButton.isEnabled = true
                if (finalError == null) showTest(getString(R.string.key_ok), true) else showTest(finalError, false)
            }
        }.start()
    }

    private fun showTest(text: String, ok: Boolean) {
        testResult.setTextColor(if (ok) 0xFF66BB6A.toInt() else 0xFFEF5350.toInt())
        testResult.text = text
    }

    private fun save() {
        Prefs.setApiKey(this, keyInput.text.toString())
        Prefs.setModelsText(this, modelsInput.text.toString())
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}
