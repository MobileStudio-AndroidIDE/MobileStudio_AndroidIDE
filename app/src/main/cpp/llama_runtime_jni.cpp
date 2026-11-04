/*
 * llama_runtime_jni.cpp — dlopen-based JNI bridge to the downloaded llama.cpp
 * Android runtime (arm64-v8a, CPU + Vulkan backends).
 *
 * The runtime ships as prebuilt Android .so files:
 *   libggml-base.so, libggml-cpu.so, libggml-vulkan.so, libggml.so, libllama.so
 *
 * This bridge is compiled at app build time WITHOUT linking llama.cpp. It
 * dlopen()s the runtime libraries from the on-device install directory and
 * resolves the llama.cpp C API via dlsym(). This means:
 *   - llama.cpp is NEVER recompiled when the app is built
 *   - the runtime can be upgraded on-device (version management)
 *   - the Vulkan GPU backend is really used (libggml-vulkan.so loaded)
 *
 * Load order matters: dependencies are dlopen()ed first (libggml-base →
 * libggml-cpu → libggml-vulkan → libggml → libllama) so that DT_NEEDED
 * soname lookups resolve to the runtime's own libraries. All errors are
 * reported via nativeLastError()/logcat — never hidden.
 */

#include <jni.h>
#include <dlfcn.h>
#include <string>
#include <vector>
#include <algorithm>
#include <android/log.h>
#include <time.h>
#include <cstring>
#include <atomic>
#include <vulkan/vulkan.h>
#include "llama.cpp/include/llama.h"

#define TAG "LlamaRuntimeJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#ifndef M_BIONIC_SET_HEAP_TAGGING_LEVEL
#define M_BIONIC_SET_HEAP_TAGGING_LEVEL 10000
#endif

/* ------------------------------------------------------------------ */
/* Runtime function pointers (resolved via dlsym)                     */
/* ------------------------------------------------------------------ */

static void  (*p_llama_backend_init)(void) = nullptr;
static void  (*p_llama_log_set)(ggml_log_callback, void*) = nullptr;
static struct llama_model_params (*p_llama_model_default_params)(void) = nullptr;
static struct llama_model* (*p_llama_model_load_from_file)(const char*, struct llama_model_params) = nullptr;
static void  (*p_llama_model_free)(struct llama_model*) = nullptr;
static struct llama_context_params (*p_llama_context_default_params)(void) = nullptr;
static struct llama_context* (*p_llama_init_from_model)(struct llama_model*, struct llama_context_params) = nullptr;
static void  (*p_llama_free)(struct llama_context*) = nullptr;
static uint32_t (*p_llama_n_ctx)(const struct llama_context*) = nullptr;
static uint32_t (*p_llama_n_batch)(const struct llama_context*) = nullptr;
static llama_memory_t (*p_llama_get_memory)(const struct llama_context*) = nullptr;
static void  (*p_llama_memory_clear)(llama_memory_t, bool) = nullptr;
static const struct llama_vocab* (*p_llama_model_get_vocab)(const struct llama_model*) = nullptr;
static int32_t (*p_llama_tokenize)(const struct llama_vocab*, const char*, int32_t, llama_token*, int32_t, bool, bool) = nullptr;
static int32_t (*p_llama_token_to_piece)(const struct llama_vocab*, llama_token, char*, int32_t, int32_t, bool) = nullptr;
static bool  (*p_llama_vocab_is_eog)(const struct llama_vocab*, llama_token) = nullptr;
static struct llama_batch (*p_llama_batch_get_one)(llama_token*, int32_t) = nullptr;
static int32_t (*p_llama_decode)(struct llama_context*, struct llama_batch) = nullptr;
static void  (*p_llama_synchronize)(struct llama_context*) = nullptr;
static struct llama_sampler* (*p_llama_sampler_chain_init)(struct llama_sampler_chain_params) = nullptr;
static struct llama_sampler_chain_params (*p_llama_sampler_chain_default_params)(void) = nullptr;
static void  (*p_llama_sampler_chain_add)(struct llama_sampler*, struct llama_sampler*) = nullptr;
static struct llama_sampler* (*p_llama_sampler_init_top_k)(int32_t) = nullptr;
static struct llama_sampler* (*p_llama_sampler_init_top_p)(float, size_t) = nullptr;
static struct llama_sampler* (*p_llama_sampler_init_temp)(float) = nullptr;
static struct llama_sampler* (*p_llama_sampler_init_dist)(uint32_t) = nullptr;
static llama_token (*p_llama_sampler_sample)(struct llama_sampler*, struct llama_context*, int32_t) = nullptr;
static void  (*p_llama_sampler_free)(struct llama_sampler*) = nullptr;

/* ------------------------------------------------------------------ */
/* State                                                              */
/* ------------------------------------------------------------------ */

static std::atomic<bool> g_runtime_loaded(false);
static std::string g_last_error = "runtime not loaded";
static std::string g_runtime_dir;
static std::atomic<bool> g_backend_init(false);
static bool g_vulkan_available = false;
static std::string g_gpu_name = "unknown";

struct RuntimeSession {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    llama_sampler* smpl = nullptr;
    int nGpuLayers = 0;
    ~RuntimeSession() {
        if (smpl) p_llama_sampler_free(smpl);
        if (ctx) p_llama_free(ctx);
        if (model) p_llama_model_free(model);
    }
};

static jclass g_boolean_class = nullptr;
static jmethodID g_boolean_value_method = nullptr;

static std::atomic<int64_t> g_abort_deadline_ms(0);

static int64_t now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void set_error(const std::string& msg) {
    g_last_error = msg;
    LOGE("%s", msg.c_str());
}

static void runtime_log_callback(enum ggml_log_level level, const char* text, void* user_data) {
    (void)user_data;
    if (level <= GGML_LOG_LEVEL_WARN) {
        LOGE("LLAMA: %s", text);
    } else {
        LOGI("LLAMA: %s", text);
    }
}

/* ------------------------------------------------------------------ */
/* Vulkan GPU detection (via the system Vulkan loader)                */
/* ------------------------------------------------------------------ */

static void query_vulkan_gpu() {
    g_vulkan_available = false;
    g_gpu_name = "unknown";
    void* vk = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!vk) {
        LOGI("Vulkan loader not available (libvulkan.so) — CPU-only fallback");
        return;
    }
    auto vkGetInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(vk, "vkGetInstanceProcAddr"));
    if (!vkGetInstanceProcAddr) {
        dlclose(vk);
        LOGI("vkGetInstanceProcAddr not found — CPU-only fallback");
        return;
    }
    // Only global-level commands may be queried with a NULL instance
    // (vkCreateInstance, vkEnumerateInstanceVersion, ...). Instance-level
    // commands (vkEnumeratePhysicalDevices, vkGetPhysicalDeviceProperties,
    // vkDestroyInstance) MUST be queried after the instance is created —
    // querying them with VK_NULL_HANDLE returns NULL (loader logs
    // "invalid vkGetInstanceProcAddr" and detection wrongly gives up).
    auto vkCreateInstance = reinterpret_cast<PFN_vkCreateInstance>(
        vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkCreateInstance"));
    if (!vkCreateInstance) {
        dlclose(vk);
        LOGI("vkCreateInstance not found — CPU-only fallback");
        return;
    }

    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo = &appInfo;

    VkInstance instance = VK_NULL_HANDLE;
    if (vkCreateInstance(&ci, nullptr, &instance) != VK_SUCCESS || instance == VK_NULL_HANDLE) {
        dlclose(vk);
        LOGI("Vulkan instance creation failed — CPU-only fallback");
        return;
    }

    auto vkEnumeratePhysicalDevices = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(
        vkGetInstanceProcAddr(instance, "vkEnumeratePhysicalDevices"));
    auto vkGetPhysicalDeviceProperties = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(
        vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties"));
    auto vkDestroyInstance = reinterpret_cast<PFN_vkDestroyInstance>(
        vkGetInstanceProcAddr(instance, "vkDestroyInstance"));
    if (!vkEnumeratePhysicalDevices || !vkGetPhysicalDeviceProperties) {
        if (vkDestroyInstance) vkDestroyInstance(instance, nullptr);
        dlclose(vk);
        LOGI("Vulkan instance-level entry points incomplete — CPU-only fallback");
        return;
    }

    uint32_t count = 0;
    if (vkEnumeratePhysicalDevices(instance, &count, nullptr) == VK_SUCCESS && count > 0) {
        std::vector<VkPhysicalDevice> devices(count);
        if (vkEnumeratePhysicalDevices(instance, &count, devices.data()) == VK_SUCCESS) {
            VkPhysicalDeviceProperties props{};
            vkGetPhysicalDeviceProperties(devices[0], &props);
            g_gpu_name = props.deviceName;
            g_vulkan_available = true;
            LOGI("Vulkan GPU: %s (api=%u.%u)", props.deviceName,
                 VK_API_VERSION_MAJOR(props.apiVersion), VK_API_VERSION_MINOR(props.apiVersion));
        }
    }
    if (vkDestroyInstance) vkDestroyInstance(instance, nullptr);
    dlclose(vk);
}

/* ------------------------------------------------------------------ */
/* Runtime loading (dlopen chain + dlsym)                             */
/* ------------------------------------------------------------------ */

/*
 * Preload the C++ runtime that the llama.cpp shared objects depend on.
 *
 * libllama.so / libggml*.so have DT_NEEDED "libc++_shared.so". The runtime is
 * installed in an app-private directory (filesDir) which is NOT on the dynamic
 * linker's default search path, so a plain dlopen(dir + "/libllama.so") fails
 * with "library not found" for its dependencies.
 *
 * Solution: load libc++_shared.so FIRST with RTLD_GLOBAL so its soname is
 * registered in the namespace; every later DT_NEEDED lookup then resolves to
 * the already-loaded object. Two sources are tried:
 *   1. <runtimeDir>/libc++_shared.so — copied next to the runtime libs
 *      (LlamaRuntimeManager.ensureNativeDeps())
 *   2. the plain soname, resolved via the app's nativeLibraryDir
 *      (android:extractNativeLibs="true" → lib/arm64-v8a/libc++_shared.so)
 *
 * Non-fatal: if neither source works the caller still attempts the dlopen
 * chain and reports the real error.
 */
static void preload_cxx_runtime(const std::string& dir) {
    static std::atomic<bool> attempted(false);
    if (attempted.exchange(true)) return;

    std::string localPath = dir + "/libc++_shared.so";
    void* h = dlopen(localPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (h) {
        LOGI("Preloaded %s (RTLD_GLOBAL)", localPath.c_str());
        return;
    }
    const char* e1 = dlerror();
    std::string errLocal = e1 ? e1 : "unknown";

    h = dlopen("libc++_shared.so", RTLD_NOW | RTLD_GLOBAL);
    if (h) {
        LOGI("Preloaded libc++_shared.so from linker search path");
        return;
    }
    const char* e2 = dlerror();
    std::string errPath = e2 ? e2 : "unknown";

    LOGE("libc++_shared.so preload failed — local: %s | linker: %s",
         errLocal.c_str(), errPath.c_str());
    LOGE("Install directory: %s", dir.c_str());
}

static bool dlopen_lib(const std::string& dir, const char* name, bool required) {
    std::string path = dir + "/" + name;
    void* h = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (h) {
        LOGI("Loaded %s", path.c_str());
        return true;
    }
    const char* err = dlerror();
    if (required) {
        set_error(std::string("Failed to load ") + name + ": " + (err ? err : "unknown"));
        return false;
    }
    LOGI("Optional %s not loaded: %s", name, err ? err : "unknown");
    return false;
}

static bool resolve_symbols(void* h) {
#define SYM(var, name) \
    p_##var = reinterpret_cast<decltype(p_##var)>(dlsym(h, name)); \
    if (p_##var == nullptr) { set_error(std::string("symbol not found: ") + name + " (runtime version mismatch?)"); return false; }

    SYM(llama_backend_init, "llama_backend_init")
    SYM(llama_log_set, "llama_log_set")
    SYM(llama_model_default_params, "llama_model_default_params")
    SYM(llama_model_load_from_file, "llama_model_load_from_file")
    SYM(llama_model_free, "llama_model_free")
    SYM(llama_context_default_params, "llama_context_default_params")
    SYM(llama_init_from_model, "llama_init_from_model")
    SYM(llama_free, "llama_free")
    SYM(llama_n_ctx, "llama_n_ctx")
    SYM(llama_n_batch, "llama_n_batch")
    SYM(llama_get_memory, "llama_get_memory")
    SYM(llama_memory_clear, "llama_memory_clear")
    SYM(llama_model_get_vocab, "llama_model_get_vocab")
    SYM(llama_tokenize, "llama_tokenize")
    SYM(llama_token_to_piece, "llama_token_to_piece")
    SYM(llama_vocab_is_eog, "llama_vocab_is_eog")
    SYM(llama_batch_get_one, "llama_batch_get_one")
    SYM(llama_decode, "llama_decode")
    SYM(llama_synchronize, "llama_synchronize")
    SYM(llama_sampler_chain_init, "llama_sampler_chain_init")
    SYM(llama_sampler_chain_default_params, "llama_sampler_chain_default_params")
    SYM(llama_sampler_chain_add, "llama_sampler_chain_add")
    SYM(llama_sampler_init_top_k, "llama_sampler_init_top_k")
    SYM(llama_sampler_init_top_p, "llama_sampler_init_top_p")
    SYM(llama_sampler_init_temp, "llama_sampler_init_temp")
    SYM(llama_sampler_init_dist, "llama_sampler_init_dist")
    SYM(llama_sampler_sample, "llama_sampler_sample")
    SYM(llama_sampler_free, "llama_sampler_free")
#undef SYM
    return true;
}

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, 0);

    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass local = env->FindClass("java/lang/Boolean");
    g_boolean_class = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    g_boolean_value_method = env->GetMethodID(g_boolean_class, "booleanValue", "()Z");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aide_mobilestudio_ai_api_LlamaRuntimeBridge_nativeLoadRuntime(
        JNIEnv* env, jobject thiz, jstring jdir, jstring jappLibDir) {
    (void)thiz;
    const char* dir = env->GetStringUTFChars(jdir, nullptr);
    if (!dir) { set_error("cannot read runtime directory"); return JNI_FALSE; }
    g_runtime_dir = dir;
    env->ReleaseStringUTFChars(jdir, dir);

    // The app's own libc++_shared.so (already in the APK / native lib dir) is
    // preloaded RTLD_GLOBAL first so DT_NEEDED "libc++_shared.so" lookups in
    // the runtime libs resolve even though the app-private install directory
    // is not on the dynamic linker's default search path.
    if (jappLibDir != nullptr) {
        const char* appLibDir = env->GetStringUTFChars(jappLibDir, nullptr);
        if (appLibDir != nullptr) {
            std::string cxxPath = std::string(appLibDir) + "/libc++_shared.so";
            void* cxx = dlopen(cxxPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
            if (cxx) {
                LOGI("Preloaded %s", cxxPath.c_str());
            } else {
                LOGI("libc++_shared.so preload skipped: %s (usually already loaded)", dlerror() ? dlerror() : "unknown");
            }
            env->ReleaseStringUTFChars(jappLibDir, appLibDir);
        }
    }

    LOGI("Loading llama.cpp runtime from: %s", g_runtime_dir.c_str());

    // DT_NEEDED "libc++_shared.so" must be resolvable before any runtime lib loads
    preload_cxx_runtime(g_runtime_dir);

    // Dependency order: soname-based DT_NEEDED lookups resolve to libs loaded first
    if (!dlopen_lib(g_runtime_dir, "libggml-base.so", true)) return JNI_FALSE;
    dlopen_lib(g_runtime_dir, "libggml-cpu.so", false);
    dlopen_lib(g_runtime_dir, "libggml-vulkan.so", false);
    dlopen_lib(g_runtime_dir, "libggml.so", false);
    if (!dlopen_lib(g_runtime_dir, "libllama.so", true)) return JNI_FALSE;

    void* llama_handle = dlopen((g_runtime_dir + "/libllama.so").c_str(), RTLD_NOW | RTLD_NOLOAD);
    if (!llama_handle) { set_error("libllama.so handle lost after load"); return JNI_FALSE; }
    if (!resolve_symbols(llama_handle)) return JNI_FALSE;

    query_vulkan_gpu();

    p_llama_backend_init();
    p_llama_log_set(runtime_log_callback, nullptr);
    g_backend_init = true;

    g_runtime_loaded = true;
    g_last_error = "none";
    LOGI("llama.cpp runtime ready (vulkan=%s, gpu=%s)",
         g_vulkan_available ? "yes" : "no", g_gpu_name.c_str());
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aide_mobilestudio_ai_api_LlamaRuntimeBridge_nativeRuntimeLoaded(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    return g_runtime_loaded ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_ai_api_LlamaRuntimeBridge_nativeLastError(JNIEnv* env, jobject thiz) {
    (void)thiz;
    return env->NewStringUTF(g_last_error.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aide_mobilestudio_ai_api_LlamaRuntimeBridge_nativeIsVulkanAvailable(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    return g_vulkan_available ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_ai_api_LlamaRuntimeBridge_nativeGetGpuName(JNIEnv* env, jobject thiz) {
    (void)thiz;
    return env->NewStringUTF(g_gpu_name.c_str());
}

/* ------------------------------------------------------------------ */
/* Model load / generate                                              */
/* ------------------------------------------------------------------ */

extern "C" JNIEXPORT jlong JNICALL
Java_com_aide_mobilestudio_ai_api_LlamaRuntimeBridge_nativeLoadModel(
        JNIEnv* env, jobject thiz, jstring jpath, jint n_ctx, jint n_gpu, jint n_threads) {
    (void)thiz;
    if (!g_runtime_loaded || !g_backend_init) {
        set_error("runtime is not loaded");
        return 0;
    }
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) { set_error("cannot read model path"); return 0; }
    LOGI("Loading model: %s (gpu_layers=%d, threads=%d, ctx=%d)", path, n_gpu, n_threads, n_ctx);

    llama_model_params model_params = p_llama_model_default_params();
    model_params.n_gpu_layers = n_gpu; // 0 = CPU only, -1 = all layers on GPU
    model_params.load_mode = LLAMA_LOAD_MODE_AUTO;
    model_params.use_extra_bufts = false;

    llama_model* model = p_llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(jpath, path);

    if (!model) {
        set_error("GGUF model load failed (file corrupted, OOM, or unsupported format)");
        return 0;
    }

    llama_context_params ctx_params = p_llama_context_default_params();
    ctx_params.n_ctx = n_ctx;
    // Prompt prefill runs in chunks of n_batch (see the decode loop below).
    // n_batch = 1 turned every chunk into a single token, so the "batched"
    // loop still paid one transformer pass - and one full vocab projection -
    // per prompt token: measured ~20 tok/s on device, which is minutes of
    // waiting for an ordinary prompt. 256 lets the backend evaluate a real
    // batch while keeping the compute buffers small enough for a phone.
    ctx_params.n_batch = 64;
    ctx_params.n_ubatch = 64;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    ctx_params.cb_eval = nullptr;
    ctx_params.abort_callback = [](void*) -> bool {
        int64_t deadline = g_abort_deadline_ms.load(std::memory_order_relaxed);
        return deadline != 0 && now_ms() > deadline;
    };

    llama_context* ctx = p_llama_init_from_model(model, ctx_params);
    if (!ctx) {
        set_error("context creation failed (reduce n_ctx or GPU layers)");
        p_llama_model_free(model);
        return 0;
    }

    auto* session = new RuntimeSession();
    session->model = model;
    session->ctx = ctx;
    session->nGpuLayers = n_gpu;

    LOGI("Model loaded successfully. Session: %p (gpu_layers=%d)", session, n_gpu);
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aide_mobilestudio_ai_api_LlamaRuntimeBridge_nativeUnloadModel(
        JNIEnv* env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    auto* session = reinterpret_cast<RuntimeSession*>(handle);
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

extern "C" JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_ai_api_LlamaRuntimeBridge_nativeGenerate(
        JNIEnv* env, jobject thiz, jlong handle, jstring jprompt,
        jfloat temp, jfloat top_p, jint top_k, jfloat repeat_penalty, jint seed,
        jobjectArray stop_seq, jobject callback) {
    (void)thiz; (void)stop_seq;
    auto* session = reinterpret_cast<RuntimeSession*>(handle);
    if (!session) return env->NewStringUTF("Error: Invalid session handle");

    const char* prompt_cstr = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(prompt_cstr);
    env->ReleaseStringUTFChars(jprompt, prompt_cstr);

    const llama_vocab* vocab = p_llama_model_get_vocab(session->model);

    std::vector<llama_token> tokens(prompt.size() + 32);
    int32_t n_tokens = p_llama_tokenize(
        vocab, prompt.c_str(), (int32_t)prompt.size(),
        tokens.data(), (int32_t)tokens.size(), true, true);
    if (n_tokens < 0) {
        LOGE("Tokenization failed: %d", n_tokens);
        return env->NewStringUTF("Error: Tokenization failed");
    }
    tokens.resize(n_tokens);

    jclass cb_class = env->GetObjectClass(callback);
    jmethodID cb_method = env->GetMethodID(cb_class, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");

    if (session->smpl) p_llama_sampler_free(session->smpl);
    session->smpl = p_llama_sampler_chain_init(p_llama_sampler_chain_default_params());
    p_llama_sampler_chain_add(session->smpl, p_llama_sampler_init_top_k(top_k > 0 ? top_k : 40));
    p_llama_sampler_chain_add(session->smpl, p_llama_sampler_init_top_p(top_p > 0 ? top_p : 0.95f, 1));
    p_llama_sampler_chain_add(session->smpl, p_llama_sampler_init_temp(temp > 0 ? temp : 0.7f));
    p_llama_sampler_chain_add(session->smpl, p_llama_sampler_init_dist(
        static_cast<uint32_t>(seed > 0 ? seed : time(nullptr))));

    uint32_t n_ctx = p_llama_n_ctx(session->ctx);
    if ((uint32_t)n_tokens > n_ctx) {
        LOGE("Prompt too long: %d tokens, context: %u", n_tokens, n_ctx);
        return env->NewStringUTF("Error: Prompt exceeds context size");
    }

    p_llama_memory_clear(p_llama_get_memory(session->ctx), true);

    struct timespec ts_start, ts_end;
    clock_gettime(CLOCK_MONOTONIC, &ts_start);
    // Feed the prompt in batches instead of one token per llama_decode.
    //
    // The batch is built by hand instead of with llama_batch_get_one, which
    // llama.h calls a transition aid and tells callers to avoid: that helper
    // leaves pos and logits null, so the caller depends on implicit position
    // tracking and on llama.cpp's default of making only the batch's last token
    // an output row (llama-batch.cpp). Positions and output flags are stated
    // explicitly here instead of relying on those defaults.
    //
    // Only the last token of each chunk asks for an output row, and only the
    // final chunk's row - the last prompt token - is what sampling reads
    // afterwards, so the result matches the single-token path.
    //
    // The chunk never exceeds the context's own batch limit: llama_decode
    // asserts n_tokens_all <= n_batch rather than splitting the batch, so a
    // larger chunk would abort the process.
    const int32_t chunk_size = (int32_t)std::max<uint32_t>(1u, p_llama_n_batch(session->ctx));
    LOGI("Decoding prompt with %d tokens (batch=%d)", n_tokens, chunk_size);

    std::vector<llama_token>   b_token(chunk_size);
    std::vector<llama_pos>     b_pos(chunk_size);
    std::vector<int32_t>       b_nseq(chunk_size, 1);
    std::vector<llama_seq_id>  b_seq(chunk_size, 0);
    std::vector<llama_seq_id*> b_seq_ptr(chunk_size);
    std::vector<int8_t>        b_logits(chunk_size, 0);
    for (int32_t k = 0; k < chunk_size; ++k) b_seq_ptr[k] = &b_seq[k];

    int32_t decoded = 0;
    // Tokens in the final prefill batch. That chunk is what holds the prompt's
    // output row, so it is also the batch the first sample reads from.
    int32_t last_chunk = 0;
    for (int32_t i = 0; i < n_tokens; i += chunk_size) {
        const int32_t chunk = std::min<int32_t>(chunk_size, n_tokens - i);
        for (int32_t k = 0; k < chunk; ++k) {
            b_token[k] = tokens[i + k];
            b_pos[k] = i + k;  // memory was cleared, so absolute == chunk offset
            b_logits[k] = 0;
        }
        b_logits[chunk - 1] = 1;
        last_chunk = chunk;

        llama_batch batch{};
        batch.n_tokens = chunk;
        batch.token = b_token.data();
        batch.embd = nullptr;
        batch.pos = b_pos.data();
        batch.n_seq_id = b_nseq.data();
        batch.seq_id = b_seq_ptr.data();
        batch.logits = b_logits.data();
        g_abort_deadline_ms.store(now_ms() + 180000);
        int decode_res = p_llama_decode(session->ctx, batch);
        g_abort_deadline_ms.store(0);
        if (decode_res != 0) {
            LOGE("llama_decode failed at %d/%d, code: %d", i, n_tokens, decode_res);
            if (decode_res == 2) return env->NewStringUTF("Error: Decode aborted (timeout)");
            if (decode_res == 1) return env->NewStringUTF("Error: KV slot full (Decode failed)");
            return env->NewStringUTF("Error: Decode failed (prompt)");
        }
        decoded += chunk;
        if (i > 0) {
            clock_gettime(CLOCK_MONOTONIC, &ts_end);
            long elapsed = (ts_end.tv_sec - ts_start.tv_sec) * 1000 + (ts_end.tv_nsec - ts_start.tv_nsec) / 1000000;
            LOGI("Decoding prompt %d/%d tokens (elapsed: %ldms)", decoded, n_tokens, elapsed);
        }
    }
    p_llama_synchronize(session->ctx);
    if (last_chunk <= 0) {
        LOGE("Prefill finished without a batch to sample from");
        return env->NewStringUTF("Error: prompt prefill produced no output");
    }
    LOGI("Prompt decoded successfully. Starting generation loop.");

    std::string full_response;
    std::string pending;  // bytes of a character whose remaining bytes have not arrived yet
    for (int i = 0; i < 2048; ++i) {
        // llama_sampler_sample() takes a token index inside the last batch, not
        // an output row: get_logits_ith() requires batch.logits[index] to be
        // set and aborts the process otherwise ("invalid logits id 0 ... batch
        // .logits[0] != true"). The prompt's output row belongs to the last
        // token of the final prefill chunk, so the first sample must name that
        // index - 0 was only ever correct while the prompt was one token per
        // decode. Every later step decodes a one-token batch, where 0 is it.
        const int32_t sample_idx = (i == 0) ? last_chunk - 1 : 0;
        llama_token id = p_llama_sampler_sample(session->smpl, session->ctx, sample_idx);
        if (p_llama_vocab_is_eog(vocab, id)) {
            LOGI("EOG token hit at step %d", i);
            break;
        }
        char buf[256];
        int32_t n = p_llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
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
                jboolean cont = env->CallBooleanMethod(res, g_boolean_value_method);
                env->DeleteLocalRef(res);
                if (!cont) break;
            }
        }

        llama_batch one_batch = p_llama_batch_get_one(&id, 1);
        g_abort_deadline_ms.store(now_ms() + 30000);
        int gen_res = p_llama_decode(session->ctx, one_batch);
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

extern "C" JNIEXPORT jobject JNICALL
Java_com_aide_mobilestudio_ai_api_LlamaRuntimeBridge_nativeGetStats(
        JNIEnv* env, jobject thiz, jlong handle) {
    (void)thiz; (void)handle;
    jclass stats_class = env->FindClass("com/aide/mobilestudio/ai/api/LlamaRuntimeBridge$ModelStats");
    if (!stats_class) return nullptr;
    jmethodID ctor = env->GetMethodID(stats_class, "<init>", "(JJJJI)V");
    if (!ctor) return nullptr;
    return env->NewObject(stats_class, ctor, (jlong)0, (jlong)0, (jlong)0, (jlong)0, (jint)0);
}
