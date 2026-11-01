#include <jni.h>
#include <android/log.h>
#include <llama.h>

#include <algorithm>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#define LOG_TAG "YBTArmyNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
std::mutex g_mutex;
llama_model * g_model = nullptr;
llama_context * g_context = nullptr;

std::string fromJavaString(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const jchar * chars = env->GetStringChars(value, nullptr);
    const jsize length = env->GetStringLength(value);
    std::string out;
    out.reserve(static_cast<size_t>(length) * 3);
    for (jsize i = 0; i < length; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < length) {
            const uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                ++i;
            }
        }
        if (cp <= 0x7F) {
            out.push_back(static_cast<char>(cp));
        } else if (cp <= 0x7FF) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp <= 0xFFFF) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }
    env->ReleaseStringChars(value, chars);
    return out;
}

jstring toJavaString(JNIEnv * env, const std::string & value) {
    jclass string_class = env->FindClass("java/lang/String");
    jmethodID constructor = env->GetMethodID(
        string_class, "<init>", "([BLjava/lang/String;)V"
    );
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(value.size()));
    if (bytes != nullptr && !value.empty()) {
        env->SetByteArrayRegion(
            bytes, 0, static_cast<jsize>(value.size()),
            reinterpret_cast<const jbyte *>(value.data())
        );
    }
    jstring charset = env->NewStringUTF("UTF-8");
    jstring result = static_cast<jstring>(
        env->NewObject(string_class, constructor, bytes, charset)
    );
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(string_class);
    return result;
}

void releaseLocked() {
    if (g_context != nullptr) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ybtarmy_app_MainActivity_nativeLoadModel(
    JNIEnv * env, jobject, jstring model_path, jint context_size, jint threads
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    releaseLocked();

    const std::string path = fromJavaString(env, model_path);
    if (path.empty()) return JNI_FALSE;

    llama_backend_init();
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(path.c_str(), model_params);
    if (g_model == nullptr) {
        LOGE("Unable to load model file: %s", path.c_str());
        return JNI_FALSE;
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(
        std::clamp(context_size, 512, 4096)
    );
    context_params.n_batch = context_params.n_ctx;
    context_params.n_ubatch = std::min<uint32_t>(512, context_params.n_ctx);
    context_params.n_threads = std::clamp(threads, 2, 4);
    context_params.n_threads_batch = context_params.n_threads;

    g_context = llama_init_from_model(g_model, context_params);
    if (g_context == nullptr) {
        LOGE("Unable to initialize model context");
        releaseLocked();
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ybtarmy_app_MainActivity_nativeGenerate(
    JNIEnv * env, jobject, jstring java_prompt, jint max_new_tokens
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model == nullptr || g_context == nullptr) {
        return toJavaString(env, "Model is not loaded.");
    }

    const std::string prompt = fromJavaString(env, java_prompt);
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    int32_t token_count = llama_tokenize(
        vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
        nullptr, 0, false, false
    );
    if (token_count < 0) token_count = -token_count;
    if (token_count <= 0) return toJavaString(env, "Could not tokenize this prompt.");

    const uint32_t context_length = llama_n_ctx(g_context);
    if (static_cast<uint32_t>(token_count) + 16 >= context_length) {
        return toJavaString(env, "Conversation is too long. Start a new chat and try again.");
    }

    std::vector<llama_token> tokens(static_cast<size_t>(token_count));
    const int32_t actual_count = llama_tokenize(
        vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
        tokens.data(), token_count, false, false
    );
    if (actual_count < 0) return toJavaString(env, "Tokenization failed.");

    llama_memory_clear(llama_get_memory(g_context), true);
    llama_batch batch = llama_batch_init(actual_count, 0, 1);
    for (int32_t i = 0; i < actual_count; ++i) {
        batch.token[i] = tokens[static_cast<size_t>(i)];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (i == actual_count - 1) ? 1 : 0;
    }
    batch.n_tokens = actual_count;

    if (llama_decode(g_context, batch) < 0) {
        llama_batch_free(batch);
        return toJavaString(env, "The model could not process this prompt.");
    }
    llama_batch_free(batch);

    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    llama_sampler * sampler = llama_sampler_chain_init(sampler_params);
    if (sampler == nullptr) return toJavaString(env, "Could not initialize text sampler.");
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(24));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.82f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.45f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string output;
    const int32_t limit = std::clamp(max_new_tokens, 1, 512);
    char piece[256];
    for (int32_t i = 0; i < limit; ++i) {
        const llama_token token = llama_sampler_sample(sampler, g_context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;

        const int32_t piece_size = llama_token_to_piece(
            vocab, token, piece, sizeof(piece), 0, true
        );
        if (piece_size > 0) {
            output.append(piece, static_cast<size_t>(piece_size));
        }

        llama_batch next = llama_batch_get_one(
            const_cast<llama_token *>(&token), 1
        );
        if (llama_decode(g_context, next) < 0) break;
    }

    llama_sampler_free(sampler);
    return toJavaString(env, output);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ybtarmy_app_MainActivity_nativeReleaseModel(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    releaseLocked();
}
