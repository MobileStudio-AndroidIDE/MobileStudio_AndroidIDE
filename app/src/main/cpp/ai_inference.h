#ifndef AI_INFERENCE_H
#define AI_INFERENCE_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ai_context ai_context_t;

typedef struct {
    int n_ctx;
    int use_gpu;
    int use_npu;
    int n_threads;
    
    void (*callback)(const char *token, int token_len, void *user_data);
    void (*status_callback)(const void *status, void *user_data);
    void *callback_data;
} ai_config_t;

typedef struct {
    int max_tokens;
    float temperature;
    float top_p;
    int top_k;
    float repeat_penalty;
    const char **stop_sequences;
    int n_stop_sequences;
} ai_generate_config_t;

typedef struct {
    float tokens_per_sec;
    long long total_tokens;
    int is_generating;
} ai_status_t;

int ai_context_create(ai_context_t **out_ctx, const ai_config_t *config);
void ai_context_destroy(ai_context_t *ctx);

int ai_generate_start(ai_context_t *ctx, const char *prompt, const ai_generate_config_t *gen_config);
int ai_generate_stop(ai_context_t *ctx);

float ai_get_tokens_per_sec(const ai_context_t *ctx);
int ai_get_generated_text(const ai_context_t *ctx, char *out_buf, size_t buf_sz);
int ai_get_status(const ai_context_t *ctx, void *out_status);

#ifdef __cplusplus
}
#endif

#endif