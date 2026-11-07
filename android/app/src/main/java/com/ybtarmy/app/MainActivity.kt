package com.ybtarmy.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class ChatMessage(val role: String, val content: String)

class MainActivity : ComponentActivity() {
    private var nativeLibraryReady = false

    private external fun nativeLoadModel(path: String, contextSize: Int, threads: Int): Boolean
    private external fun nativeGenerate(prompt: String, maxNewTokens: Int): String
    private external fun nativeReleaseModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nativeLibraryReady = runCatching {
            System.loadLibrary("ybt_native")
            true
        }.getOrDefault(false)

        setContent {
            YbtArmyScreen(
                nativeLibraryReady = nativeLibraryReady,
                loadModel = { path ->
                    withContext(Dispatchers.IO) {
                        nativeLoadModel(
                            path,
                            2048,
                            Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                        )
                    }
                },
                generate = { prompt ->
                    withContext(Dispatchers.Default) { nativeGenerate(prompt, 256) }
                }
            )
        }
    }

    override fun onDestroy() {
        if (nativeLibraryReady) runCatching { nativeReleaseModel() }
        super.onDestroy()
    }
}

@Composable
private fun YbtArmyScreen(
    nativeLibraryReady: Boolean,
    loadModel: suspend (String) -> Boolean,
    generate: suspend (String) -> String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val preferences = remember {
        context.getSharedPreferences("ybt_chat_history", 0)
    }
    var messages by remember { mutableStateOf(readMessages(preferences.getString("messages", null))) }
    var input by remember { mutableStateOf("") }
    var isDownloading by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }
    var isModelReady by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Checking for an installed model…") }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadLabel by remember { mutableStateOf("") }
    var selectedProvider by remember { mutableStateOf(AiProvider.OPENROUTER) }
    val apiKeyStore = remember { SecureApiKeyStore(context) }
    var apiKeyDraft by remember { mutableStateOf("") }
    var hasRemoteKey by remember { mutableStateOf(runCatching { apiKeyStore.read(AiProvider.OPENROUTER.id) != null }.getOrDefault(false)) }
    var remoteModel by remember { mutableStateOf(AiProvider.OPENROUTER.defaultModel) }
    var availableModels by remember { mutableStateOf(emptyList<String>()) }
    var isLoadingModels by remember { mutableStateOf(false) }

    suspend fun generateAnswer(history: List<ChatMessage>): String {
        val savedKey = runCatching { apiKeyStore.read(selectedProvider.id) }.getOrNull()
        return if (!savedKey.isNullOrBlank()) {
            AiProviderClient.chat(selectedProvider, savedKey, remoteModel, buildPrompt(history))
        } else {
            check(isModelReady) { "Add a provider API key or download the offline model before chatting." }
            generate(buildPrompt(history))
        }
    }

    fun persist(updated: List<ChatMessage>) {
        messages = updated
        val array = JSONArray()
        updated.takeLast(40).forEach { message ->
            array.put(JSONObject().put("role", message.role).put("content", message.content))
        }
        preferences.edit().putString("messages", array.toString()).apply()
    }

    LaunchedEffect(nativeLibraryReady) {
        if (!nativeLibraryReady) {
            status = "Offline runtime unavailable. Configure a cloud provider API key to continue."
            return@LaunchedEffect
        }
        val localModel = ModelRepository.installedFile(context)
        if (localModel.isFile) {
            status = "Loading the downloaded model…"
            val loaded = runCatching { loadModel(localModel.absolutePath) }.getOrDefault(false)
            isModelReady = loaded
            status = if (loaded) "YBT Army is ready • Offline" else "Model could not be loaded. Download it again."
        } else {
            status = "AI model not installed. Download it once to use offline."
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, isGenerating) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFF58D6C8),
        onPrimary = Color(0xFF062A2A),
        secondary = Color(0xFFFFC66D),
        background = Color(0xFF080E18),
        surface = Color(0xFF111B29),
        onSurface = Color(0xFFEAF2FA),
        surfaceVariant = Color(0xFF1B2938),
        onSurfaceVariant = Color(0xFFA9B8C8)
    )) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF080E18)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Surface(shape = RoundedCornerShape(15.dp), color = Color(0xFF173C49), modifier = Modifier.size(46.dp)) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Y", color = Color(0xFFFFC857), fontSize = 24.sp, fontWeight = FontWeight.Black)
                                }
                            }
                            Column {
                                Text("YBT Army", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF1F6FC))
                                Text("LOCAL AI  •  PRIVATE  •  OFFLINE", fontSize = 10.sp, color = Color(0xFF8FA4B8), letterSpacing = 1.1.sp)
                            }
                        }
                    }
                    TextButton(onClick = { persist(emptyList()) }, enabled = !isGenerating) {
                        Text("＋ New chat", color = Color(0xFF72D9CD), fontWeight = FontWeight.SemiBold)
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF111B29))
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                modifier = Modifier.size(9.dp),
                                shape = CircleShape,
                                color = if (isModelReady) Color(0xFF48D597) else Color(0xFFFFC66D)
                            ) {}
                            Text(
                                modifier = Modifier.padding(start = 8.dp).weight(1f),
                                text = status,
                                fontSize = 13.sp,
                                color = Color(0xFFE4EDF6)
                            )
                            if (isDownloading) {
                                CircularProgressIndicator(modifier = Modifier.size(19.dp), strokeWidth = 2.dp)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("AI PROVIDER", color = Color(0xFF8FA4B8), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            TextButton(onClick = {
                                val providers = AiProvider.values()
                                selectedProvider = providers[(providers.indexOf(selectedProvider) + 1) % providers.size]
                                remoteModel = selectedProvider.defaultModel
                                availableModels = emptyList()
                                hasRemoteKey = runCatching { apiKeyStore.read(selectedProvider.id) != null }.getOrDefault(false)
                                apiKeyDraft = ""
                            }) {
                                Text(selectedProvider.title + "  ▾", color = Color(0xFF72D9CD), fontWeight = FontWeight.SemiBold)
                            }
                        }
                        OutlinedTextField(
                            value = apiKeyDraft,
                            onValueChange = { apiKeyDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Your ${selectedProvider.title} API key") },
                            placeholder = { Text("Paste your private API key") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                        OutlinedTextField(
                            value = remoteModel,
                            onValueChange = { remoteModel = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Model ID") },
                            singleLine = true
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        isLoadingModels = true
                                        try {
                                            val key = apiKeyStore.read(selectedProvider.id)
                                                ?: error("Save your API key before loading models.")
                                            availableModels = AiProviderClient.listModels(selectedProvider, key).take(60)
                                            status = "Loaded ${availableModels.size} available models."
                                        } catch (error: Exception) {
                                            status = error.message ?: "Could not load models."
                                        } finally {
                                            isLoadingModels = false
                                        }
                                    }
                                },
                                enabled = hasRemoteKey && !isLoadingModels
                            ) { Text(if (isLoadingModels) "Loading models…" else "↻ Load available models", color = Color(0xFF72D9CD)) }
                        }
                        if (availableModels.isNotEmpty()) {
                            Text("CHOOSE A MODEL", color = Color(0xFF8FA4B8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Column {
                                availableModels.take(5).forEach { modelId ->
                                    TextButton(onClick = { remoteModel = modelId }) {
                                        Text(
                                            text = if (remoteModel == modelId) "✓  $modelId" else modelId,
                                            color = if (remoteModel == modelId) Color(0xFF48D597) else Color(0xFFCAD6E2),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = {
                                    runCatching { apiKeyStore.save(selectedProvider.id, apiKeyDraft) }
                                        .onSuccess {
                                            hasRemoteKey = true
                                            apiKeyDraft = ""
                                            status = "${selectedProvider.title} key saved securely on this device."
                                        }
                                        .onFailure { status = it.message ?: "Could not save API key." }
                                },
                                enabled = apiKeyDraft.isNotBlank()
                            ) { Text("Save key") }
                            TextButton(onClick = {
                                apiKeyStore.remove(selectedProvider.id)
                                hasRemoteKey = false
                                status = "Provider key removed from this device."
                            }, enabled = hasRemoteKey) { Text("Remove key", color = Color(0xFFFFA4A4)) }
                        }
                        if (hasRemoteKey) {
                            Text("Cloud AI ready • API key encrypted with Android Keystore", fontSize = 11.sp, color = Color(0xFF48D597))
                        }
                        if (isDownloading) {
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(downloadLabel, fontSize = 11.sp, color = Color.Gray)
                        } else if (!isModelReady && !hasRemoteKey) {
                            Button(
                                onClick = {
                                    if (!nativeLibraryReady) {
                                        status = "This APK does not contain the native AI runtime."
                                    } else {
                                        scope.launch {
                                            isDownloading = true
                                            downloadProgress = 0f
                                            status = "Finding the latest Android model release…"
                                            try {
                                                val file = ModelRepository.downloadLatest(context) { done, total ->
                                                    mainHandler.post {
                                                        downloadProgress = if (total > 0) {
                                                            (done.toFloat() / total).coerceIn(0f, 1f)
                                                        } else 0f
                                                        status = if (total > 0 && done >= total) "Verifying model integrity…" else "Downloading AI model…"
                                                        downloadLabel = if (total > 0) {
                                                            "${done / 1_048_576} / ${total / 1_048_576} MB"
                                                        } else "${done / 1_048_576} MB downloaded"
                                                    }
                                                }
                                                status = "Verifying and loading the model…"
                                                val loaded = loadModel(file.absolutePath)
                                                isModelReady = loaded
                                                status = if (loaded) {
                                                    "YBT Army is ready • Offline"
                                                } else {
                                                    "Downloaded model could not be loaded. Please report this build."
                                                }
                                            } catch (error: Exception) {
                                                status = error.message ?: "Model download failed."
                                            } finally {
                                                isDownloading = false
                                            }
                                        }
                                    }
                                },
                                enabled = !isDownloading && !isGenerating
                            ) {
                                Text("Download AI model")
                            }
                            Text(
                                "The model is downloaded separately from GitHub Releases. After installation, chat works without internet.",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        } else if (hasRemoteKey) {
                            Text("Cloud model selected. Internet is required for provider requests; offline model remains optional.", fontSize = 11.sp, color = Color(0xFF9AABBD))
                        } else {
                            Text("The model is saved on this device. Internet is not needed for chat.", fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                }

                if (messages.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(shape = CircleShape, color = Color(0xFFE1F3F1), modifier = Modifier.size(76.dp)) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("✦", fontSize = 42.sp, color = Color(0xFF087F8C))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Your private AI space", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF1F6FC))
                        Text("A capable little assistant, running right on your device.", color = Color(0xFF9AABBD), fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        Text("YOUR DATA STAYS LOCAL", color = Color(0xFF72D9CD), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("TRY A QUICK PROMPT", color = Color(0xFF8FA4B8), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { input = "Explain a difficult idea in simple words." }) {
                                Text("Explain simply", color = Color(0xFF72D9CD), fontSize = 12.sp)
                            }
                            TextButton(onClick = { input = "Help me make a practical step-by-step plan." }) {
                                Text("Make a plan", color = Color(0xFF72D9CD), fontSize = 12.sp)
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(messages) { message -> MessageBubble(message) }
                        if (isGenerating) {
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text("YBT Army is thinking…", color = Color(0xFF71818A), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message YBT Army…") },
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (input.isNotBlank() && (isModelReady || hasRemoteKey) && !isGenerating) {
                                val question = input.trim()
                                input = ""
                                scope.launch {
                                    val withQuestion = messages + ChatMessage("user", question)
                                    persist(withQuestion)
                                    isGenerating = true
                                    try {
                                        val answer = cleanModelResponse(generateAnswer(withQuestion))
                                        persist(withQuestion + ChatMessage("assistant", answer.ifBlank { "No response was generated." }))
                                    } catch (error: Exception) {
                                        persist(withQuestion + ChatMessage("assistant", "Error: ${error.message ?: "generation failed"}"))
                                    } finally {
                                        isGenerating = false
                                    }
                                }
                            }
                        }),
                        shape = RoundedCornerShape(20.dp)
                    )
                    Button(
                        onClick = {
                            val question = input.trim()
                            if (question.isNotEmpty() && (isModelReady || hasRemoteKey) && !isGenerating) {
                                input = ""
                                scope.launch {
                                    val before = messages
                                    val withQuestion = before + ChatMessage("user", question)
                                    persist(withQuestion)
                                    isGenerating = true
                                    try {
                                        val answer = cleanModelResponse(generateAnswer(withQuestion))
                                        persist(withQuestion + ChatMessage("assistant", answer.ifBlank { "No response was generated." }))
                                    } catch (error: Exception) {
                                        persist(withQuestion + ChatMessage("assistant", "Error: ${error.message ?: "generation failed"}"))
                                    } finally {
                                        isGenerating = false
                                    }
                                }
                            }
                        },
                        enabled = (isModelReady || hasRemoteKey) && !isGenerating && input.isNotBlank(),
                        modifier = Modifier.height(56.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text("Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val user = message.role == "user"
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.88f),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (user) 18.dp else 5.dp,
                bottomEnd = if (user) 5.dp else 18.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (user) Color(0xFF155B60) else Color(0xFF172536)
            )
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    text = message.content,
                    color = Color(0xFFEAF2FA),
                    fontSize = 15.sp,
                    lineHeight = 23.sp
                )
                if (!user) {
                    TextButton(
                        onClick = { clipboard.setText(AnnotatedString(message.content)) },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Copy response", color = Color(0xFF72D9CD), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}


private fun cleanModelResponse(raw: String): String {
    val answer = raw
        .replace(Regex("<\\|im_(start|end)\\|>"), "")
        .replace(Regex("<\\|[^>]+\\|>"), "")
        .trim()
    val leakedTemplate = Regex(
        """(?i)\\[(user'?s name|military|your name|organization|company|phone number|email address)]"""
    )
    if (leakedTemplate.containsMatchIn(answer)) {
        return "দুঃখিত, এই মডেলটি আপনার প্রশ্নের উপযুক্ত উত্তর তৈরি করতে পারেনি। আরেকভাবে প্রশ্নটি লিখে চেষ্টা করুন।"
    }
    return answer.ifBlank {
        "দুঃখিত, কোনো উত্তর তৈরি হয়নি। প্রশ্নটি আবার লিখে চেষ্টা করুন।"
    }
}

private fun buildPrompt(history: List<ChatMessage>): String {
    val systemPrompt = "You are YBT Army, a concise and respectful assistant. Answer the user's actual question directly. Match the language of the latest user message, including Bengali when the user writes Bengali. Never invent a name, job, military membership, contact details, events, or personal facts. Never use template placeholders such as [User's Name] or [military]. Do not write generic email or letter templates unless explicitly requested. If information is missing or you are unsure, say so clearly. Use short, natural answers and simple formatting."
    val prompt = StringBuilder()
    prompt.append("<|im_start|>system\n").append(systemPrompt).append("<|im_end|>\n")
    history.takeLast(6).forEach { message ->
        val role = if (message.role == "assistant") "assistant" else "user"
        val content = message.content.takeLast(900)
        prompt.append("<|im_start|>").append(role).append("\n")
            .append(content).append("<|im_end|>\n")
    }
    prompt.append("<|im_start|>assistant\n")
    return prompt.toString()
}

private fun readMessages(serialized: String?): List<ChatMessage> = runCatching {
    if (serialized.isNullOrBlank()) return emptyList()
    val array = JSONArray(serialized)
    (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        ChatMessage(item.optString("role", "user"), item.optString("content"))
    }.filter { it.content.isNotBlank() }
}.getOrDefault(emptyList())
