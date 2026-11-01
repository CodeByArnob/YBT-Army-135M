package com.ybtarmy.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
            status = "Native AI runtime could not be loaded in this APK."
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

    MaterialTheme(colorScheme = lightColorScheme(
        primary = Color(0xFF171717),
        onPrimary = Color.White,
        secondary = Color(0xFF555D6C),
        background = Color(0xFFF7F7F8),
        surface = Color.White,
        onSurface = Color(0xFF15171A)
    )) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("YBT Army", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("LOCAL AI  •  PRIVATE  •  OFFLINE", fontSize = 10.sp, color = Color.Gray, letterSpacing = 1.sp)
                    }
                    TextButton(onClick = { persist(emptyList()) }, enabled = !isGenerating) {
                        Text("New chat")
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                modifier = Modifier.size(9.dp),
                                shape = CircleShape,
                                color = if (isModelReady) Color(0xFF15965A) else Color(0xFFD18A27)
                            ) {}
                            Text(
                                modifier = Modifier.padding(start = 8.dp).weight(1f),
                                text = status,
                                fontSize = 13.sp,
                                color = Color(0xFF343A40)
                            )
                            if (isDownloading) {
                                CircularProgressIndicator(modifier = Modifier.size(19.dp), strokeWidth = 2.dp)
                            }
                        }
                        if (isDownloading) {
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(downloadLabel, fontSize = 11.sp, color = Color.Gray)
                        } else if (!isModelReady) {
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
                        Text("⚡", fontSize = 42.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("What can I help with?", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        Text("Your conversations stay on this device.", color = Color.Gray, fontSize = 13.sp)
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
                                Text("YBT Army is thinking…", color = Color.Gray, fontSize = 12.sp)
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
                            if (input.isNotBlank() && isModelReady && !isGenerating) {
                                val question = input.trim()
                                input = ""
                                scope.launch {
                                    val withQuestion = messages + ChatMessage("user", question)
                                    persist(withQuestion)
                                    isGenerating = true
                                    try {
                                        val answer = generate(buildPrompt(withQuestion)).trim()
                                        persist(withQuestion + ChatMessage("assistant", answer.ifBlank { "No response was generated." }))
                                    } catch (error: Exception) {
                                        persist(messages + ChatMessage("assistant", "Error: ${error.message ?: "generation failed"}"))
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
                            if (question.isNotEmpty() && isModelReady && !isGenerating) {
                                input = ""
                                scope.launch {
                                    val before = messages
                                    val withQuestion = before + ChatMessage("user", question)
                                    persist(withQuestion)
                                    isGenerating = true
                                    try {
                                        val answer = generate(buildPrompt(withQuestion)).trim()
                                        persist(withQuestion + ChatMessage("assistant", answer.ifBlank { "No response was generated." }))
                                    } catch (error: Exception) {
                                        persist(withQuestion + ChatMessage("assistant", "Error: ${error.message ?: "generation failed"}"))
                                    } finally {
                                        isGenerating = false
                                    }
                                }
                            }
                        },
                        enabled = isModelReady && !isGenerating && input.isNotBlank(),
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
                containerColor = if (user) Color(0xFF171717) else Color.White
            )
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                color = if (user) Color.White else Color(0xFF202329),
                fontSize = 14.sp,
                lineHeight = 21.sp
            )
        }
    }
}

private fun buildPrompt(history: List<ChatMessage>): String {
    val systemPrompt = "You are YBT Army, a helpful AI model. Answer accurately and briefly. If you do not know something, say so instead of guessing. Reply in the same language the user writes in."
    val prompt = StringBuilder()
    prompt.append("<|im_start|>system\n").append(systemPrompt).append("<|im_end|>\n")
    history.takeLast(8).forEach { message ->
        val role = if (message.role == "assistant") "assistant" else "user"
        val content = message.content.takeLast(1800)
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
