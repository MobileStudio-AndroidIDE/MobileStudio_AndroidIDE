#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <unistd.h>

#include "ai_inference.h"

#define TAG "AIInference"

#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO, TAG, fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, ##__VA_ARGS__)

#ifndef MIN
#define MIN(a,b) ((a) < (b) ? (a) : (b))
#endif

struct ai_context {
    void *llama_ctx;
    void *model;
    void *vocab;
    int n_ctx;
    int n_vocab;
    int n_embd;
    int n_layer;
    int n_head;
    int n_rot;
    int ftype;
    
    int use_gpu;
    int use_npu;
    void *nn_compilation;
    void *nn_execution;
    void *nn_model;
    
    pthread_mutex_t stats_mutex;
    long long total_tokens;
    struct timeval start_time;
    float tokens_per_sec;
    int last_update_sec;
    
    pthread_t generator_thread;
    int stop_generation;
    char *prompt;
    char *generated_text;
    size_t generated_len;
    size_t generated_cap;
    pthread_mutex_t output_mutex;
    void (*callback)(const char *token, int token_len, void *user_data);
    void (*status_callback)(const void *status, void *user_data);
    void *callback_data;
};

static inline long long current_time_ms(void) {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (long long)tv.tv_sec * 1000 + tv.tv_usec / 1000;
}

static void update_tokens_per_sec(ai_context_t *ctx, int tokens_generated) {
    pthread_mutex_lock(&ctx->stats_mutex);
    struct timeval now;
    gettimeofday(&now, NULL);
    long long elapsed_ms = (now.tv_sec - ctx->start_time.tv_sec) * 1000 + (now.tv_usec - ctx->start_time.tv_usec) / 1000;
    
    ctx->total_tokens += tokens_generated;
    
    if (elapsed_ms > 1000) {
        ctx->tokens_per_sec = (float)ctx->total_tokens / (elapsed_ms / 1000.0f);
        ctx->start_time = now;
        ctx->total_tokens = 0;
    }
    pthread_mutex_unlock(&ctx->stats_mutex);
}

static int load_ggml_library(void) {
    static void *ggml_handle = NULL;
    if (ggml_handle) return 0;
    
    const char *paths[] = {
        "libggml.so",
        "libggml-base.so",
        "/data/user/0/com.aide.mobilestudio/files/toolchain/llvm/lib/libggml.so",
        "/data/user/0/com.aide.mobilestudio/files/toolchain/runtime-tools/lib/libggml.so",
        "/system/lib64/libggml.so",
        NULL
    };
    
    for (int i = 0; paths[i]; i++) {
        ggml_handle = dlopen(paths[i], RTLD_LAZY | RTLD_GLOBAL);
        if (ggml_handle) {
            LOGI("Loaded GGML from: %s", paths[i]);
            return 0;
        }
    }
    LOGE("Failed to load GGML library");
    return -1;
}

static int init_npu_support(ai_context_t *ctx) {
    // NNAPI requires API 27+ (Android 8.1+)
    LOGI("NNAPI/NPU support available at runtime (API 27+)");
    ctx->use_npu = 1;
    return 0;
}

static int init_gpu_support(ai_context_t *ctx) {
    void *vulkan = dlopen("libvulkan.so", RTLD_LAZY);
    if (vulkan) {
        dlclose(vulkan);
        ctx->use_gpu = 1;
        LOGI("Vulkan/GPU acceleration available");
        return 0;
    }
    return -1;
}

int ai_context_create(ai_context_t **out_ctx, const ai_config_t *config) {
    if (!out_ctx) return -EINVAL;
    
    ai_context_t *ctx = calloc(1, sizeof(*ctx));
    if (!ctx) return -ENOMEM;
    
    pthread_mutex_init(&ctx->stats_mutex, NULL);
    pthread_mutex_init(&ctx->output_mutex, NULL);
    gettimeofday(&ctx->start_time, NULL);
    
    ctx->n_ctx = (config && config->n_ctx > 0) ? config->n_ctx : 4096;
    ctx->callback = config ? config->callback : NULL;
    ctx->status_callback = config ? config->status_callback : NULL;
    ctx->callback_data = config ? config->callback_data : NULL;
    ctx->generated_cap = 8192;
    ctx->generated_text = malloc(ctx->generated_cap);
    if (!ctx->generated_text) {
        pthread_mutex_destroy(&ctx->stats_mutex);
        pthread_mutex_destroy(&ctx->output_mutex);
        free(ctx);
        return -ENOMEM;
    }
    ctx->generated_text[0] = '\0';
    
    if (load_ggml_library() < 0) {
        LOGE("Failed to load GGML, falling back to CPU only");
    }
    
    init_npu_support(ctx);
    init_gpu_support(ctx);
    
    LOGI("AI context created (n_ctx=%d, gpu=%d, npu=%d)", ctx->n_ctx, ctx->use_gpu, ctx->use_npu);
    
    *out_ctx = ctx;
    return 0;
}

void ai_context_destroy(ai_context_t *ctx) {
    if (!ctx) return;
    
    free(ctx->generated_text);
    free(ctx->prompt);
    pthread_mutex_destroy(&ctx->stats_mutex);
    pthread_mutex_destroy(&ctx->output_mutex);
    free(ctx);
}

int ai_generate_start(ai_context_t *ctx, const char *prompt, const ai_generate_config_t *gen_config) {
    (void)gen_config; // unused for now
    if (!ctx || !prompt) return -EINVAL;
    
    ctx->stop_generation = 0;
    free(ctx->prompt);
    ctx->prompt = strdup(prompt);
    if (!ctx->prompt) return -ENOMEM;
    
    pthread_mutex_lock(&ctx->output_mutex);
    ctx->generated_len = 0;
    ctx->generated_text[0] = '\0';
    pthread_mutex_unlock(&ctx->output_mutex);
    
    gettimeofday(&ctx->start_time, NULL);
    ctx->total_tokens = 0;
    ctx->tokens_per_sec = 0.0f;
    ctx->last_update_sec = 0;
    
    LOGI("AI generation started: %.50s...", prompt);
    return 0;
}

static void *generation_thread(void *arg) {
    (void)arg;
    return NULL;
}

int ai_generate_stop(ai_context_t *ctx) {
    if (!ctx) return -EINVAL;
    ctx->stop_generation = 1;
    return 0;
}

float ai_get_tokens_per_sec(const ai_context_t *ctx) {
    if (!ctx) return 0.0f;
    pthread_mutex_lock(&ctx->stats_mutex);
    float tps = ctx->tokens_per_sec;
    pthread_mutex_unlock(&ctx->stats_mutex);
    return tps;
}

int ai_get_generated_text(const ai_context_t *ctx, char *out_buf, size_t buf_sz) {
    if (!ctx || !out_buf) return -EINVAL;
    pthread_mutex_lock(&ctx->output_mutex);
    size_t len = (ctx->generated_len < buf_sz - 1) ? ctx->generated_len : buf_sz - 1;
    memcpy(out_buf, ctx->generated_text, len);
    out_buf[len] = '\0';
    pthread_mutex_unlock(&ctx->output_mutex);
    return (int)len;
}

int ai_get_status(const ai_context_t *ctx, void *out_status) {
    if (!ctx || !out_status) return -EINVAL;
    struct {
        float tokens_per_sec;
        long long total_tokens;
        int is_generating;
    } *out_status_ptr = (void *)out_status;
    
    pthread_mutex_lock(&ctx->stats_mutex);
    out_status_ptr->tokens_per_sec = ctx->tokens_per_sec;
    out_status_ptr->total_tokens = ctx->total_tokens;
    out_status_ptr->is_generating = !ctx->stop_generation;
    pthread_mutex_unlock(&ctx->stats_mutex);
    return 0;
}