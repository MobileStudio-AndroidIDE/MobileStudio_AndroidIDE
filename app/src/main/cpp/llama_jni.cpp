#include <jni.h>
#include <string>
#include <vector>
#include <algorithm>
#include <android/log.h>
#include <time.h>
#include <cstring>
#include <malloc.h>
#include <atomic>
#include "llama.cpp/include/llama.h"

#ifndef M_BIONIC_SET_HEAP_TAGGING_LEVEL
#define M_BIONIC_SET_HEAP_TAGGING_LEVEL 10000
#endif

#define TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct LlamaSession {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    llama_sampler * smpl = nullptr;

    ~LlamaSession() {
        if (smpl) llama_sampler_free(smpl);
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
    }
};

static jclass boolean_class;
static jmethodID boolean_value_method;

static void llama_log_callback(enum ggml_log_level level, const char * text, void * user_data) {
    if (level <= GGML_LOG_LEVEL_WARN) {
        LOGE("LLAMA: %s", text);
    } else {
        LOGI("LLAMA: %s", text);
    }
}

static std::atomic<int64_t> g_abort_deadline_ms(0);

static int64_t now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static bool llama_abort_callback(void * user_data) {
    int64_t deadline = g_abort_deadline_ms.load(std::memory_order_relaxed);
    if (deadline == 0) return false;
    return now_ms() > deadline;
}

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, 0);

    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass local_boolean_class = env->FindClass("java/lang/Boolean");
    boolean_class = reinterpret_cast<jclass>(env->NewGlobalRef(local_boolean_class));
    boolean_value_method = env->GetMethodID(boolean_class, "booleanValue", "()Z");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL Java_com_aide_mobilestudio_ai_api_LlamaAndroid_loadModel(
        JNIEnv *env, jobject thiz, jstring jpath, jint n_ctx, jint n_gpu, jint n_threads, jstring jbackend) {

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    const char *backend_str = env->GetStringUTFChars(jbackend, nullptr);
    LOGI("Loading model: %s (gpu=%d, threads=%d, backend=%s)", path, n_gpu, n_threads, backend_str);

    static bool backend_init = false;
    if (!backend_init) {
        llama_backend_init();
        llama_log_set(llama_log_callback, nullptr);
        backend_init = true;
    }

    llama_model_params model_params = llama_model_default_params();
    // Force CPU: if Vulkan is not compiled, don't request GPU layers
    model_params.n_gpu_layers = (n_gpu > 0) ? n_gpu : 0;
    model_params.load_mode = LLAMA_LOAD_MODE_AUTO;
    // Disable weight repacking (KleidiAI etc.) - suspected hang source on low-end devices
    model_params.use_extra_bufts = false;

    llama_model * model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(jpath, path);
    env->ReleaseStringUTFChars(jbackend, backend_str);

    if (!model) {
        LOGE("Failed to load model");
        return 0;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_ctx;
    // Some Android CPU backends stall while evaluating a multi-token prefill
    // graph. Decode the prompt through the stable single-token path instead.
    // It is slower on paper, but reliably reaches the first generated token on
    // devices where a 128-token graph never completes.
    ctx_params.n_batch = 1;
    ctx_params.n_ubatch = 1;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    // Do not attach a per-graph-node logging callback in release builds. It
    // turns a normal prompt evaluation into thousands of logcat calls.
    ctx_params.cb_eval = nullptr;
    ctx_params.abort_callback = llama_abort_callback;

    llama_context * ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGE("Failed to create context");
        llama_model_free(model);
        return 0;
    }

    LlamaSession * session = new LlamaSession();
    session->model = model;
    session->ctx = ctx;

    LOGI("Model loaded successfully. Session: %p", session);
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT void JNICALL Java_com_aide_mobilestudio_ai_api_LlamaAndroid_unloadModel(
        JNIEnv *env, jobject thiz, jlong handle) {
    LlamaSession * session = reinterpret_cast<LlamaSession *>(handle);
    if (session) {
        LOGI("Unloading model session: %p", session);
        delete session;
    }
}

// A byte-level BPE token can end in the middle of a multi-byte UTF-8
// character - the remaining bytes arrive with the next token - and
// NewStringUTF() aborts the whole process under CheckJNI when it sees such a
// truncated sequence ("input is not valid Modified UTF-8"). This copies the
// leading bytes of `in` that form complete, valid UTF-8 sequences into `out`,
// reports how many bytes of `in` that accounts for, and turns any invalid byte
// into U+FFFD rather than passing it on.
static void utf8_whole_prefix(const std::string& in, std::string& out, size_t& consumed) {
    consumed = 0;
    size_t i = 0;
    while (i < in.size()) {
        unsigned char c = static_cast<unsigned char>(in[i]);
        size_t need;
        if (c < 0x80) need = 1;
        else if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        else { out += "\xEF\xBF\xBD"; ++i; consumed = i; continue; }
        if (i + need > in.size()) break;  // character not complete yet
        bool ok = true;
        for (size_t k = 1; k < need; ++k) {
            if ((static_cast<unsigned char>(in[i + k]) & 0xC0) != 0x80) { ok = false; break; }
        }
        if (!ok) { out += "\xEF\xBF\xBD"; ++i; consumed = i; continue; }
        out.append(in, i, need);
        i += need;
        consumed = i;
    }
}

extern "C" JNIEXPORT jstring JNICALL Java_com_aide_mobilestudio_ai_api_LlamaAndroid_generate(
        JNIEnv *env, jobject thiz, jlong handle, jstring jprompt,
        jfloat temp, jfloat top_p, jint top_k, jfloat repeat_penalty, jint seed,
        jobjectArray stop_seq, jobject callback) {

    LlamaSession * session = reinterpret_cast<LlamaSession *>(handle);
    if (!session) return env->NewStringUTF("Error: Invalid session handle");

    const char *prompt_cstr = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(prompt_cstr);
    env->ReleaseStringUTFChars(jprompt, prompt_cstr);

    const llama_vocab * vocab = llama_model_get_vocab(session->model);

    // Tokenize prompt
    std::vector<llama_token> tokens(prompt.size() + 32);
    int32_t n_tokens = llama_tokenize(
        vocab,
        prompt.c_str(), (int32_t)prompt.size(),
        tokens.data(), (int32_t)tokens.size(),
        true, true
    );

    if (n_tokens < 0) {
        LOGE("Tokenization failed: %d", n_tokens);
        return env->NewStringUTF("Error: Tokenization failed");
    }

    tokens.resize(n_tokens);

    jclass cb_class = env->GetObjectClass(callback);
    jmethodID cb_method = env->GetMethodID(cb_class, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");

    // Rebuild sampler with provided params
    if (session->smpl) llama_sampler_free(session->smpl);
    session->smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(session->smpl, llama_sampler_init_top_k(top_k > 0 ? top_k : 40));
    llama_sampler_chain_add(session->smpl, llama_sampler_init_top_p(top_p > 0 ? top_p : 0.95f, 1));
    llama_sampler_chain_add(session->smpl, llama_sampler_init_temp(temp > 0 ? temp : 0.7f));
    if (seed > 0) {
        llama_sampler_chain_add(session->smpl, llama_sampler_init_dist(static_cast<uint32_t>(seed)));
    } else {
        llama_sampler_chain_add(session->smpl, llama_sampler_init_dist(static_cast<uint32_t>(time(NULL))));
    }

    // Ensure prompt doesn't exceed context size
    uint32_t n_ctx = llama_n_ctx(session->ctx);
    if ((uint32_t)n_tokens > n_ctx) {
        LOGE("Prompt too long: %d tokens, context size: %d", n_tokens, n_ctx);
        return env->NewStringUTF("Error: Prompt exceeds context size");
    }

    // Clear KV cache from previous generation (fixes retry "Decode failed")
    llama_memory_clear(llama_get_memory(session->ctx), true);

    // Batch encode: split into chunks of n_batch to avoid GGML_ASSERT
    uint32_t n_batch = llama_n_batch(session->ctx);
    LOGI("Decoding prompt with %d tokens (n_batch=%d)", n_tokens, n_batch);

    struct timespec ts_start, ts_end;
    clock_gettime(CLOCK_MONOTONIC, &ts_start);

    for (int32_t i = 0; i < n_tokens; i += n_batch) {
        int32_t chunk_size = std::min((int32_t)n_batch, n_tokens - i);
        if (i == 0 || i + chunk_size == n_tokens || (i % 64) == 0) {
            LOGI("Decoding prompt %d/%d tokens", i + chunk_size, n_tokens);
        }

        // Use llama_batch_get_one - positions are auto-tracked by llama_decode
        llama_batch batch = llama_batch_get_one(tokens.data() + i, chunk_size);

        g_abort_deadline_ms.store(now_ms() + 180000); // Slow mobile CPU prefill

        int decode_res = llama_decode(session->ctx, batch);
        g_abort_deadline_ms.store(0);
        clock_gettime(CLOCK_MONOTONIC, &ts_end);
        long elapsed_ms = (ts_end.tv_sec - ts_start.tv_sec) * 1000 + (ts_end.tv_nsec - ts_start.tv_nsec) / 1000000;

        if (decode_res != 0) {
            LOGE("llama_decode failed at offset %d/%d, code: %d (elapsed: %ldms)", i, n_tokens, decode_res, elapsed_ms);
            if (decode_res == 2) return env->NewStringUTF("Error: Decode aborted (timeout)");
            if (decode_res == 1) return env->NewStringUTF("Error: KV slot full (Decode failed)");
            return env->NewStringUTF("Error: Decode failed (prompt)");
        }
        LOGI("Chunk decoded OK (elapsed: %ldms)", elapsed_ms);
        clock_gettime(CLOCK_MONOTONIC, &ts_start);
    }
    llama_synchronize(session->ctx); // Ensure computation is done
    LOGI("Prompt decoded successfully. Starting generation loop.");

    std::string full_response;
    std::string pending;  // bytes of a character whose remaining bytes have not arrived yet

    clock_gettime(CLOCK_MONOTONIC, &ts_start);
    for (int i = 0; i < 2048; ++i) {
        llama_token id = llama_sampler_sample(session->smpl, session->ctx, 0);

        if (llama_vocab_is_eog(vocab, id)) {
            LOGI("EOG token hit at step %d", i);
            break;
        }

        char buf[256];
        int32_t n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
        if (n <= 0) break;

        std::string piece(buf, n);
        if (i < 5) LOGI("Token %d: [%s]", i, piece.c_str());

        pending += piece;
        std::string ready;
        size_t consumed = 0;
        utf8_whole_prefix(pending, ready, consumed);
        if (consumed > 0) pending.erase(0, consumed);
        full_response += ready;
        if (!ready.empty()) {
            jstring jtoken = env->NewStringUTF(ready.c_str());
            jobject res = env->CallObjectMethod(callback, cb_method, jtoken);
            env->DeleteLocalRef(jtoken);

            if (res) {
                jboolean cont = env->CallBooleanMethod(res, boolean_value_method);
                env->DeleteLocalRef(res);
                if (!cont) break;
            }
        }

        // Decode next token using batch_get_one
        llama_token next_token = id;
        llama_batch one_batch = llama_batch_get_one(&next_token, 1);
        g_abort_deadline_ms.store(now_ms() + 30000); // 30s timeout per token
        int gen_res = llama_decode(session->ctx, one_batch);
        g_abort_deadline_ms.store(0);
        if (gen_res) {
            LOGE("llama_decode failed in generation loop at step %d, code: %d", i, gen_res);
            break;
        }
    }

    if (!pending.empty()) {
        // Generation ended on a half character: forward what is whole and let
        // U+FFFD stand in for the rest instead of handing Modified UTF-8 an
        // invalid sequence.
        std::string tail;
        size_t consumed = 0;
        utf8_whole_prefix(pending, tail, consumed);
        if (consumed < pending.size()) tail += "\xEF\xBF\xBD";
        full_response += tail;
        if (!tail.empty()) {
            jstring jtoken = env->NewStringUTF(tail.c_str());
            jobject res = env->CallObjectMethod(callback, cb_method, jtoken);
            env->DeleteLocalRef(jtoken);
            if (res) env->DeleteLocalRef(res);
        }
    }

    return env->NewStringUTF(full_response.c_str());
}

extern "C" JNIEXPORT jobject JNICALL Java_com_aide_mobilestudio_ai_api_LlamaAndroid_getStats(
        JNIEnv *env, jobject thiz, jlong handle) {
    jclass stats_class = env->FindClass("com/aide/mobilestudio/ai/api/LlamaAndroid$ModelStats");
    jmethodID stats_ctor = env->GetMethodID(stats_class, "<init>", "(JJJJI)V");
    return env->NewObject(stats_class, stats_ctor, 0L, 0L, 0L, 0L, 0);
}
