package com.filenfolder.gemlegacy

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

class GeminiException(message: String, val httpCode: Int = 0) : Exception(message)

class GeminiClient(private val apiKey: String, private val models: List<String>) {

    @Volatile
    private var startIndex = 0

    fun generate(contents: List<JSONObject>, systemInstruction: String? = null): String {
        if (models.isEmpty()) throw GeminiException("No model configured.")
        var last: GeminiException? = null
        val start = startIndex
        for (step in models.indices) {
            val index = (start + step) % models.size
            try {
                val reply = call(models[index], contents, systemInstruction)
                startIndex = index
                return reply
            } catch (e: GeminiException) {
                last = e
                if (e.httpCode !in RETRYABLE) throw e
            }
        }
        throw GeminiException("All ${models.size} models failed. Last error: ${last?.message}", last?.httpCode ?: 0)
    }

    private fun call(model: String, contents: List<JSONObject>, systemInstruction: String?): String {
        val array = JSONArray()
        for (c in contents) array.put(c)
        val request = JSONObject().put("contents", array)
        if (systemInstruction != null) {
            val parts = JSONArray().put(JSONObject().put("text", systemInstruction))
            request.put("system_instruction", JSONObject().put("parts", parts))
        }
        val body = request.toString().toByteArray(Charsets.UTF_8)

        val conn = URL("$BASE_URL/$model:generateContent").openConnection() as HttpURLConnection
        try {
            if (conn is HttpsURLConnection) conn.sslSocketFactory = LegacyTls.socketFactory()
            conn.connectTimeout = 20_000
            conn.readTimeout = 90_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.setFixedLengthStreamingMode(body.size)

            conn.outputStream.use { it.write(body) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = if (stream == null) "" else
                BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }

            if (code !in 200..299) throw GeminiException(describeError(code, text), code)
            return parseReply(text)
        } catch (e: UnknownHostException) {
            throw GeminiException("No internet connection. Check your Wi-Fi or mobile data.")
        } catch (e: SocketTimeoutException) {
            throw GeminiException("The connection timed out. Try again in a moment.")
        } catch (e: SSLException) {
            throw GeminiException("Secure connection failed. Check that your phone's date and time are correct.")
        } catch (e: IOException) {
            throw GeminiException("Network error: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }

    private fun parseReply(json: String): String {
        val root = JSONObject(json)
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val block = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            throw GeminiException(
                if (block.isNotEmpty()) "Request blocked ($block)." else "Empty response from Gemini."
            )
        }
        val candidate = candidates.getJSONObject(0)
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.optBoolean("thought", false)) continue
                sb.append(part.optString("text", ""))
            }
        }
        if (sb.isEmpty()) {
            val reason = candidate.optString("finishReason", "")
            throw GeminiException("No text returned" + if (reason.isNotEmpty()) " (finishReason: $reason)." else ".")
        }
        return sb.toString().trim()
    }

    private fun describeError(code: Int, body: String): String {
        val apiMessage = try {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        } catch (e: Exception) {
            ""
        }
        val keyProblem = body.contains("API_KEY_INVALID") || body.contains("API key not valid")
        return when {
            keyProblem -> "That API key isn't valid. Open Settings and check it."
            code == 400 || code == 401 || code == 403 ->
                "Gemini refused the request (HTTP $code). ${apiMessage.ifEmpty { "Check your API key in Settings." }}"
            code == 404 -> "That model isn't available (HTTP 404). You can change the model list in Settings."
            code == 429 -> "You've reached Gemini's free usage limit for now. Wait a little and try again."
            code in 500..599 -> "Gemini is busy right now (HTTP $code). Try again in a moment."
            else -> "HTTP $code: ${apiMessage.ifEmpty { body.take(200) }}"
        }
    }

    companion object {
        private val RETRYABLE = setOf(404, 429, 500, 502, 503, 504)
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    }
}
