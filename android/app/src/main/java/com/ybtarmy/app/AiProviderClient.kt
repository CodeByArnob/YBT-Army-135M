package com.ybtarmy.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class AiProvider(val id: String, val title: String, val endpoint: String, val defaultModel: String) {
    OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openrouter/free"),
    GROQ("groq", "Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
    GEMINI("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-3.8-flash")
}

/** BYOK clients. Endpoints are built in; keys are supplied by the user at runtime. */
object AiProviderClient {
    private const val SYSTEM_PROMPT = "You are YBT Army, a concise and respectful assistant. Answer the user's actual question directly. Match the user's language, including Bengali. Never invent personal facts. If unsure, say so clearly."
    suspend fun chat(provider: AiProvider, apiKey: String, model: String, messages: List<ChatMessage>): String =
        withContext(Dispatchers.IO) {
            require(apiKey.isNotBlank()) { "Add your ${provider.title} API key in Settings first." }
            when (provider) {
                AiProvider.GEMINI -> gemini(apiKey, model.ifBlank { provider.defaultModel }, messages)
                else -> openAiCompatible(provider, apiKey, model.ifBlank { provider.defaultModel }, messages)
            }
        }

    suspend fun listModels(provider: AiProvider, apiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            require(apiKey.isNotBlank()) { "Enter an API key first." }
            if (provider == AiProvider.GEMINI) {
                val json = request(
                    URL("${provider.endpoint}/models"),
                    "GET", apiKey, null, "x-goog-api-key"
                )
                val models = json.optJSONArray("models") ?: JSONArray()
                (0 until models.length()).mapNotNull { i ->
                    val item = models.optJSONObject(i) ?: return@mapNotNull null
                    val name = item.optString("name").removePrefix("models/")
                    val methods = item.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                    if (name.isNotBlank() && (0 until methods.length()).any { methods.optString(it) == "generateContent" }) name else null
                }.sorted()
            } else {
                val json = request(URL("${provider.endpoint}/models"), "GET", apiKey, null)
                val data = json.optJSONArray("data") ?: JSONArray()
                (0 until data.length()).mapNotNull { i ->
                    data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
                }.filter { provider != AiProvider.OPENROUTER || it.endsWith(":free") || it == "openrouter/free" }.sorted()
            }
        }

    private fun openAiCompatible(provider: AiProvider, apiKey: String, model: String, messages: List<ChatMessage>): String {
        val apiMessages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        messages.takeLast(12).forEach { message ->
            apiMessages.put(JSONObject().put("role", if (message.role == "assistant") "assistant" else "user").put("content", message.content))
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", apiMessages)
            .put("temperature", 0.7)
            .put("max_tokens", 1024)
        val response = request(URL("${provider.endpoint}/chat/completions"), "POST", apiKey, body)
        return response.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content")
            ?.takeIf { it.isNotBlank() } ?: error("The provider returned an empty response.")
    }

    private fun gemini(apiKey: String, model: String, messages: List<ChatMessage>): String {
        val contents = JSONArray()
        messages.takeLast(12).forEach { message ->
            contents.put(
                JSONObject()
                    .put("role", if (message.role == "assistant") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", message.content)))
            )
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
            .put("contents", contents)
        val response = request(
            URL("${AiProvider.GEMINI.endpoint}/models/${model}:generateContent"),
            "POST", apiKey, body, "x-goog-api-key"
        )
        val parts = response.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        return (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("text") }
            .joinToString("").ifBlank { error("Gemini returned no text. Check model access and API quota.") }
    }

    private fun request(url: URL, method: String, apiKey: String?, body: JSONObject?, authHeader: String = "Authorization"): JSONObject {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 90_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "YBT-Army-Android")
            if (apiKey != null) setRequestProperty(authHeader, if (authHeader == "Authorization") "Bearer $apiKey" else apiKey)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(responseText).optJSONObject("error")?.optString("message") }.getOrNull()
                error(when (code) {
                    401, 403 -> "API key rejected by provider (HTTP $code). Check the key and permissions."
                    429 -> "Provider rate limit or free quota reached. Try later or choose another model."
                    else -> message?.takeIf { it.isNotBlank() } ?: "Provider request failed (HTTP $code)."
                })
            }
            return JSONObject(responseText)
        } finally {
            connection.disconnect()
        }
    }
}
