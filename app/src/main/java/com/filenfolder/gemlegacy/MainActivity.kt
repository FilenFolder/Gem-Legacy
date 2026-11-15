package com.filenfolder.gemlegacy

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {

    private lateinit var chatContainer: LinearLayout
    private lateinit var promptInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var cameraButton: ImageButton
    private lateinit var galleryButton: ImageButton
    private lateinit var clearButton: ImageButton
    private lateinit var scrollView: ScrollView
    private lateinit var attachmentRow: View
    private lateinit var attachmentThumb: ImageView
    private lateinit var removePhotoButton: Button
    private lateinit var callButton: ImageButton
    private lateinit var callOverlay: View
    private lateinit var callStatus: TextView
    private lateinit var callCaption: TextView
    private lateinit var callSpeakerButton: ImageButton
    private lateinit var callEndButton: ImageButton
    private lateinit var callAvatar: ImageView
    private lateinit var settingsButton: ImageButton

    private var client = GeminiClient("", Prefs.DEFAULT_MODELS.split(","))

    private class Entry(val role: String, val text: String, val photo: Boolean, var jpeg: ByteArray?)

    private val log = ArrayList<Entry>()

    private var pendingJpeg: ByteArray? = null
    private var capturePath: String? = null
    private var busy = false

    private var call: VoiceCall? = null
    private var callSpeakerOn = false
    private var appliedDark = true

    private val handler = Handler(Looper.getMainLooper())
    private var thinkingView: TextView? = null
    private var dots = 0
    private val dotTicker = object : Runnable {
        override fun run() {
            dots = dots % 3 + 1
            thinkingView?.text = getString(R.string.thinking) + ".".repeat(dots)
            handler.postDelayed(this, 450)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedDark = Prefs.darkMode(this)
        setTheme(if (appliedDark) R.style.Theme_GemLegacy_Dark else R.style.Theme_GemLegacy_Light)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        chatContainer = findViewById(R.id.chatContainer)
        promptInput = findViewById(R.id.promptInput)
        sendButton = findViewById(R.id.sendButton)
        cameraButton = findViewById(R.id.cameraButton)
        galleryButton = findViewById(R.id.galleryButton)
        clearButton = findViewById(R.id.clearButton)
        scrollView = findViewById(R.id.scrollView)
        attachmentRow = findViewById(R.id.attachmentRow)
        attachmentThumb = findViewById(R.id.attachmentThumb)
        removePhotoButton = findViewById(R.id.removePhotoButton)
        callButton = findViewById(R.id.callButton)
        callOverlay = findViewById(R.id.callOverlay)
        callStatus = findViewById(R.id.callStatus)
        callCaption = findViewById(R.id.callCaption)
        callSpeakerButton = findViewById(R.id.callSpeakerButton)
        callEndButton = findViewById(R.id.callEndButton)
        callAvatar = findViewById(R.id.callAvatar)
        settingsButton = findViewById(R.id.settingsButton)

        capturePath = savedInstanceState?.getString(STATE_CAPTURE_PATH)

        sendButton.setOnClickListener { send() }
        cameraButton.setOnClickListener { launchCamera() }
        galleryButton.setOnClickListener { launchGallery() }
        clearButton.setOnClickListener { confirmClear() }
        removePhotoButton.setOnClickListener { clearAttachment() }
        callButton.setOnClickListener { startCall() }
        callEndButton.setOnClickListener { endCall() }
        callSpeakerButton.setOnClickListener { toggleSpeaker() }
        settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        loadChat()
        showSavedChat()
        refreshClient()

        val draft = Prefs.draft(this)
        if (draft.isNotEmpty()) {
            promptInput.setText(draft)
            promptInput.setSelection(draft.length)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        call?.end()
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.darkMode(this) != appliedDark) {
            recreate()
            return
        }
        refreshClient()
    }

    private fun refreshClient() {
        client = GeminiClient(Prefs.apiKey(this), Prefs.models(this))
    }

    private fun hasKey(): Boolean = Prefs.apiKey(this).isNotEmpty()

    private fun promptForKey() {
        addBubble(getString(R.string.need_key), SYSTEM)
        addChip(getString(R.string.open_settings)) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onPause() {
        super.onPause()
        Prefs.setDraft(this, promptInput.text.toString())
        if (call != null) endCall()
    }

    override fun onBackPressed() {
        if (call != null) endCall() else super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CAPTURE_PATH, capturePath)
    }

    private fun chatFile() = File(filesDir, "chat.json")

    private fun saveChat() {
        try {
            val arr = JSONArray()
            for (e in log.takeLast(MAX_SAVED)) {
                arr.put(JSONObject().put("role", e.role).put("text", e.text).put("photo", e.photo))
            }
            chatFile().writeText(arr.toString())
        } catch (e: Exception) {
        }
    }

    private fun loadChat() {
        try {
            val file = chatFile()
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                log.add(Entry(o.getString("role"), o.getString("text"), o.optBoolean("photo", false), null))
            }
        } catch (e: Exception) {
            log.clear()
        }
        while (log.isNotEmpty() && log[0].role != "user") log.removeAt(0)
        if (log.size % 2 != 0) log.removeAt(log.size - 1)
    }

    private fun showSavedChat() {
        if (log.isEmpty()) {
            showWelcome()
            return
        }
        for (e in log) {
            if (e.role == "user") addUserBubble(if (e.photo) "[photo] ${e.text}" else e.text, e)
            else addBubble(renderMarkdown(e.text), GEMINI)
        }
    }

    private fun confirmClear() {
        if (busy) return
        AlertDialog.Builder(this)
            .setMessage(R.string.clear_confirm)
            .setPositiveButton(R.string.delete) { _, _ -> clearChat() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearChat() {
        log.clear()
        chatFile().delete()
        chatContainer.removeAllViews()
        clearAttachment()
        showWelcome()
    }

    private fun showWelcome() {
        if (!hasKey()) {
            addBubble(getString(R.string.welcome), GEMINI)
            promptForKey()
            return
        }
        addBubble(getString(R.string.welcome), GEMINI)
        for (id in intArrayOf(R.string.chip_1, R.string.chip_2, R.string.chip_3)) {
            val text = getString(id)
            addChip(text) {
                promptInput.setText(text)
                send()
            }
        }
    }

    private fun launchCamera() {
        if (busy) return
        val dir = PhotoProvider.photoDir(this)
        if (dir == null) {
            Toast.makeText(this, R.string.no_storage, Toast.LENGTH_LONG).show()
            return
        }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        capturePath = file.absolutePath

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (Build.VERSION.SDK_INT >= 24) {
            val uri = PhotoProvider.uriFor(this, file)
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
            intent.clipData = ClipData.newRawUri("", uri)
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            intent.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(file))
        }

        try {
            startActivityForResult(intent, REQ_CAMERA)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_camera, Toast.LENGTH_LONG).show()
        }
    }

    private fun launchGallery() {
        if (busy) return
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "image/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        try {
            startActivityForResult(Intent.createChooser(intent, getString(R.string.gallery)), REQ_GALLERY)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_gallery, Toast.LENGTH_LONG).show()
        }
    }

    private fun copyPickedImage(uri: Uri): File? {
        val tmp = File(cacheDir, "pick_${System.currentTimeMillis()}.jpg")
        val input = contentResolver.openInputStream(uri) ?: return null
        input.use { src -> tmp.outputStream().use { dst -> src.copyTo(dst) } }
        return tmp
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_CAMERA -> {
                val path = capturePath
                capturePath = null
                if (path == null) return
                val file = File(path)
                if (resultCode != RESULT_OK || !file.exists() || file.length() == 0L) {
                    file.delete()
                    return
                }
                loadAttachment { file }
            }
            REQ_GALLERY -> {
                val uri = data?.data
                if (resultCode != RESULT_OK || uri == null) return
                loadAttachment { copyPickedImage(uri) }
            }
        }
    }

    private fun loadAttachment(source: () -> File?) {
        Thread {
            var file: File? = null
            var jpeg: ByteArray? = null
            try {
                file = source()
                if (file != null) jpeg = ImageUtils.toScaledJpeg(file.absolutePath)
            } catch (e: Exception) {
                jpeg = null
            }
            file?.delete()
            val finalJpeg = jpeg
            val thumb = if (finalJpeg != null) ImageUtils.thumbnail(finalJpeg) else null
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (finalJpeg == null) {
                    Toast.makeText(this, R.string.photo_failed, Toast.LENGTH_LONG).show()
                } else {
                    pendingJpeg = finalJpeg
                    attachmentThumb.setImageBitmap(thumb)
                    attachmentRow.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun clearAttachment() {
        pendingJpeg = null
        attachmentThumb.setImageBitmap(null)
        attachmentRow.visibility = View.GONE
    }

    private fun send() {
        if (busy) return
        if (!hasKey()) {
            promptForKey()
            return
        }
        val typed = promptInput.text.toString().trim()
        val jpeg = pendingJpeg
        if (typed.isEmpty() && jpeg == null) return

        val prompt = if (typed.isEmpty()) getString(R.string.default_photo_prompt) else typed
        val entry = Entry("user", prompt, jpeg != null, jpeg)

        addUserBubble(if (jpeg != null) "[photo] $prompt" else prompt, entry)
        promptInput.setText("")
        clearAttachment()
        setBusy(true)
        showThinking()

        askGemini(entry, false) { reply, error ->
            hideThinking()
            if (reply != null) {
                commit(entry, reply)
                addBubble(renderMarkdown(reply), GEMINI)
            } else {
                addBubble("Error: $error", ERROR)
            }
            setBusy(false)
        }
    }

    private fun askGemini(entry: Entry, voice: Boolean, onResult: (String?, String?) -> Unit) {
        val turns = ArrayList<Entry>(log)
        turns.add(entry)
        val recent = turns.takeLast(7)

        Thread {
            var reply: String? = null
            var error: String? = null
            try {
                reply = client.generate(recent.map { toContent(it) }, if (voice) VOICE_PROMPT else null)
            } catch (e: GeminiException) {
                error = e.message
            } catch (e: Exception) {
                error = "Unexpected error: ${e.message ?: e.javaClass.simpleName}"
            }
            val finalReply = reply
            val finalError = error
            runOnUiThread {
                if (!isFinishing) onResult(finalReply, finalError)
            }
        }.start()
    }

    private fun commit(entry: Entry, reply: String) {
        log.add(entry)
        log.add(Entry("model", reply, false, null))
        for (i in 0 until log.size - 4) log[i].jpeg = null
        saveChat()
    }

    private fun startCall() {
        if (busy || call != null) return
        if (!hasKey()) {
            promptForKey()
            return
        }
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        beginCall()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_AUDIO) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) beginCall()
        else Toast.makeText(this, R.string.no_mic, Toast.LENGTH_LONG).show()
    }

    private fun beginCall() {
        callSpeakerOn = Prefs.speakerOn(this)
        callSpeakerButton.isActivated = callSpeakerOn
        callCaption.text = ""
        callStatus.setText(R.string.call_connecting)
        callOverlay.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val c = VoiceCall(this, callListener)
        call = c
        c.setSpeaker(callSpeakerOn)
        c.start()
    }

    private fun endCall() {
        val c = call ?: return
        call = null
        c.end()
        setPulse(false)
        callOverlay.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toggleSpeaker() {
        callSpeakerOn = !callSpeakerOn
        Prefs.setSpeakerOn(this, callSpeakerOn)
        call?.setSpeaker(callSpeakerOn)
        callSpeakerButton.isActivated = callSpeakerOn
    }

    private val callListener = object : VoiceCall.Listener {
        override fun onState(state: VoiceCall.State) {
            callStatus.setTextColor(
                when (state) {
                    VoiceCall.State.LISTENING -> 0xFF66BB6A.toInt()
                    VoiceCall.State.THINKING -> 0xFFFFB74D.toInt()
                    VoiceCall.State.SPEAKING -> 0xFF64B5F6.toInt()
                    else -> 0xFFBBBBBB.toInt()
                }
            )
            setPulse(state == VoiceCall.State.LISTENING)
            callStatus.setText(
                when (state) {
                    VoiceCall.State.STARTING -> R.string.call_connecting
                    VoiceCall.State.LISTENING -> R.string.call_listening
                    VoiceCall.State.THINKING -> R.string.call_thinking
                    VoiceCall.State.SPEAKING -> R.string.call_speaking
                }
            )
        }

        override fun onHeard(text: String) {
            callCaption.text = "You: $text"
        }

        override fun onUserSaid(text: String) {
            callTurn(text)
        }

        override fun onFatal(message: String) {
            addBubble(message, ERROR)
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            endCall()
        }
    }

    private fun callTurn(text: String) {
        callCaption.text = "You: $text"
        val entry = Entry("user", text, false, null)
        addUserBubble(text, entry)
        askGemini(entry, true) { reply, error ->
            if (reply != null) {
                commit(entry, reply)
                addBubble(renderMarkdown(reply), GEMINI)
                val c = call
                if (c != null) {
                    callCaption.text = renderMarkdown(reply)
                    c.speak(reply)
                }
            } else {
                addBubble("Error: $error", ERROR)
                callCaption.text = "Error: $error"
                call?.resumeListening()
            }
        }
    }

    private fun toContent(e: Entry): JSONObject {
        val parts = JSONArray()
        val jpeg = e.jpeg
        if (jpeg != null) {
            val inline = JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
            parts.put(JSONObject().put("inline_data", inline))
        }
        val text = if (e.photo && jpeg == null) getString(R.string.photo_gone) + " " + e.text else e.text
        parts.put(JSONObject().put("text", text))
        return JSONObject().put("role", e.role).put("parts", parts)
    }

    private fun setBusy(value: Boolean) {
        busy = value
        sendButton.isEnabled = !value
        cameraButton.isEnabled = !value
        galleryButton.isEnabled = !value
        clearButton.isEnabled = !value
        callButton.isEnabled = !value
    }

    private fun showThinking() {
        dots = 1
        thinkingView = addBubble(getString(R.string.thinking) + ".", SYSTEM)
        handler.postDelayed(dotTicker, 450)
    }

    private fun hideThinking() {
        handler.removeCallbacks(dotTicker)
        thinkingView?.let { chatContainer.removeView(it) }
        thinkingView = null
    }

    private var pulsing = false
    private val pulseTicker = object : Runnable {
        private var big = false
        override fun run() {
            if (!pulsing) return
            big = !big
            val scale = if (big) 1.08f else 1.0f
            callAvatar.animate().scaleX(scale).scaleY(scale).setDuration(700).start()
            handler.postDelayed(this, 700)
        }
    }

    private fun setPulse(on: Boolean) {
        if (on == pulsing) return
        pulsing = on
        handler.removeCallbacks(pulseTicker)
        if (on) {
            handler.post(pulseTicker)
        } else {
            callAvatar.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        }
    }

    private fun addChip(label: String, onClick: () -> Unit) {
        val chip = TextView(this)
        chip.text = label
        chip.textSize = 14f
        chip.setTextColor(themeColor(R.attr.gemText))
        chip.setBackgroundResource(themeDrawable(R.attr.gemPill))
        chip.setPadding(dp(14), dp(8), dp(14), dp(8))
        chip.setOnClickListener {
            chatContainer.removeView(chip)
            onClick()
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.LEFT
        lp.topMargin = dp(4)
        lp.bottomMargin = dp(4)
        chatContainer.addView(chip, lp)
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addBubble(text: CharSequence, kind: Int): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 16f
        tv.setTextColor(if (kind == SYSTEM) themeColor(R.attr.gemText) else Color.WHITE)
        tv.setTextIsSelectable(true)
        tv.setBackgroundResource(
            when (kind) {
                USER -> R.drawable.bubble_user
                GEMINI -> R.drawable.bubble_gemini
                ERROR -> R.drawable.bubble_error
                else -> themeDrawable(R.attr.gemBubbleSystem)
            }
        )
        tv.setPadding(dp(12), dp(8), dp(12), dp(8))

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        when (kind) {
            USER -> { lp.gravity = Gravity.RIGHT; lp.leftMargin = dp(48) }
            else -> { lp.gravity = Gravity.LEFT; lp.rightMargin = dp(48) }
        }
        lp.topMargin = dp(4)
        lp.bottomMargin = dp(4)
        chatContainer.addView(tv, lp)
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        return tv
    }

    private fun addUserBubble(text: CharSequence, entry: Entry) {
        val bubble = addBubble(text, USER)
        bubble.tag = entry

        val edit = TextView(this)
        edit.text = getString(R.string.edit)
        edit.textSize = 12f
        edit.setTextColor(themeColor(R.attr.gemTextSecondary))
        edit.setPadding(dp(14), dp(4), dp(6), dp(6))
        edit.tag = entry
        edit.setOnClickListener { editMessage(entry) }

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.RIGHT
        chatContainer.addView(edit, lp)
    }

    private fun editMessage(entry: Entry) {
        if (busy || call != null) return
        var start = -1
        for (i in 0 until chatContainer.childCount) {
            if (chatContainer.getChildAt(i).tag === entry) {
                start = i
                break
            }
        }
        if (start < 0) return
        chatContainer.removeViews(start, chatContainer.childCount - start)

        val index = log.indexOf(entry)
        if (index >= 0) {
            while (log.size > index) log.removeAt(log.size - 1)
            saveChat()
        }

        promptInput.setText(entry.text)
        promptInput.setSelection(promptInput.text.length)
        val jpeg = entry.jpeg
        if (jpeg != null) {
            pendingJpeg = jpeg
            attachmentThumb.setImageBitmap(ImageUtils.thumbnail(jpeg))
            attachmentRow.visibility = View.VISIBLE
        } else if (entry.photo) {
            Toast.makeText(this, R.string.photo_not_saved, Toast.LENGTH_LONG).show()
        }
        promptInput.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(promptInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return value.data
    }

    private fun themeDrawable(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return value.resourceId
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun renderMarkdown(src: String): CharSequence {
        val out = SpannableStringBuilder()
        val lines = src.split("\n")
        for (i in lines.indices) {
            if (i > 0) out.append('\n')
            val trimmed = lines[i].trimStart()
            var heading = false
            val line = when {
                trimmed.startsWith("* ") || trimmed.startsWith("- ") -> "\u2022 " + trimmed.substring(2)
                trimmed.startsWith("#") -> { heading = true; trimmed.trimStart('#').trim() }
                else -> lines[i]
            }
            val start = out.length
            appendWithBold(out, line)
            if (heading) out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    private fun appendWithBold(out: SpannableStringBuilder, line: String) {
        var i = 0
        while (i < line.length) {
            val open = line.indexOf("**", i)
            val close = if (open < 0) -1 else line.indexOf("**", open + 2)
            if (open < 0 || close < 0) {
                out.append(line, i, line.length)
                return
            }
            out.append(line, i, open)
            val s = out.length
            out.append(line, open + 2, close)
            out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            i = close + 2
        }
    }

    companion object {
        private const val USER = 0
        private const val GEMINI = 1
        private const val SYSTEM = 2
        private const val ERROR = 3
        private const val REQ_CAMERA = 1001
        private const val REQ_GALLERY = 1002
        private const val REQ_AUDIO = 1003
        private const val VOICE_PROMPT =
            "You are on a live voice call with the user. Talk the way a friendly person does on the phone: " +
                "short and natural, one to three sentences unless they ask for more. Use plain spoken language " +
                "only - no markdown, no lists, no asterisks, no emojis. If what they said seems cut off or " +
                "unclear, ask briefly. Reply in the language the user is speaking."
        private const val MAX_SAVED = 100
        private const val STATE_CAPTURE_PATH = "capture_path"
    }
}
