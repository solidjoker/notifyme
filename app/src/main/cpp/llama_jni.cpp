// Minimal llama.cpp JNI bridge for notifyme (M4 spike).
//
// Design choices, kept deliberately small:
//  - one handle (jlong pointer) per loaded model; CPU backend is statically
//    linked (see CMakeLists.txt), so no backend-dl path setup is needed;
//  - non-streaming: nativeComplete formats system+user with the model chat
//    template, decodes the prompt and runs the full sampling loop here,
//    returning the complete assistant text in one call;
//  - a fresh common_sampler is created for each completion so no sampling
//    history leaks between analyses.

#include <jni.h>

#include <string>
#include <vector>
#include <unistd.h>

#include "llama.h"
#include "common.h"
#include "sampling.h"
#include "chat.h"

#include <android/log.h>

#define LOG_TAG "notifyme-llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int N_THREADS_MIN      = 2;
constexpr int N_THREADS_HEADROOM = 2;
constexpr int DEFAULT_BATCH      = 512;

struct llama_state {
    llama_model   * model   = nullptr;
    llama_context * ctx     = nullptr;
    llama_batch     batch{};
    common_chat_templates_ptr templates;
    int             n_ctx   = 0;
};

void throw_rt(JNIEnv * env, const std::string & msg) {
    env->ThrowNew(env->FindClass("java/lang/RuntimeException"), msg.c_str());
}

int pick_threads() {
    long cores = sysconf(_SC_NPROCESSORS_ONLN);
    if (cores < 1) { cores = 1; }
    long t = cores - N_THREADS_HEADROOM;
    if (t < N_THREADS_MIN) { t = N_THREADS_MIN; }
    return static_cast<int>(t);
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_notifyme_android_LlamaJni_nativeBackendInit(JNIEnv *, jclass) {
    static bool initialized = false;
    if (initialized) { return; }
    llama_log_set(nullptr, nullptr);
    llama_backend_init();
    initialized = true;
    LOGI("llama backend initialized");
}

JNIEXPORT jlong JNICALL
Java_com_notifyme_android_LlamaJni_nativeCreate(
        JNIEnv * env, jclass, jstring jmodel_path, jint jn_ctx) {

    const char * model_path = env->GetStringUTFChars(jmodel_path, nullptr);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(model_path, mparams);
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (!model) {
        throw_rt(env, std::string("无法加载模型: ") + model_path);
        return 0;
    }

    auto * st = new llama_state();
    st->model = model;
    st->n_ctx = jn_ctx;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = jn_ctx;
    cparams.n_batch         = DEFAULT_BATCH;
    cparams.n_ubatch        = DEFAULT_BATCH;
    cparams.n_threads       = pick_threads();
    cparams.n_threads_batch = cparams.n_threads;
    st->ctx = llama_init_from_model(model, cparams);
    if (!st->ctx) {
        llama_model_free(model);
        delete st;
        throw_rt(env, "无法创建推理上下文（可能内存不足）");
        return 0;
    }

    st->batch     = llama_batch_init(DEFAULT_BATCH, 0, 1);
    st->templates = common_chat_templates_init(model, "");

    LOGI("model loaded, n_ctx=%d threads=%d", jn_ctx, cparams.n_threads);
    return reinterpret_cast<jlong>(st);
}

JNIEXPORT jstring JNICALL
Java_com_notifyme_android_LlamaJni_nativeComplete(
        JNIEnv * env, jclass, jlong handle,
        jstring jsystem, jstring juser, jint max_tokens, jfloat temperature) {

    auto * st = reinterpret_cast<llama_state *>(handle);
    if (!st || !st->ctx) {
        throw_rt(env, "引擎句柄无效");
        return nullptr;
    }

    const char * system = env->GetStringUTFChars(jsystem, nullptr);
    const char * user   = env->GetStringUTFChars(juser,   nullptr);

    // Build system (optional) + user messages, then apply the model template.
    std::vector<common_chat_msg> msgs;
    if (system && system[0] != '\0') {
        msgs.push_back({"system", system, {}, {}});
    }
    msgs.push_back({"user", user, {}, {}});

    env->ReleaseStringUTFChars(jsystem, system);
    env->ReleaseStringUTFChars(juser,   user);

    common_chat_templates_inputs inputs;
    inputs.messages              = msgs;
    inputs.add_generation_prompt = true;
    inputs.use_jinja             = false; // match tested legacy-template path
    std::string formatted = common_chat_templates_apply(st->templates.get(), inputs).prompt;

    // Tokenize. Template already adds any required special tokens.
    std::vector<llama_token> prompt_tokens =
        common_tokenize(st->ctx, formatted, /*add_special=*/false, /*parse_special=*/true);

    if (static_cast<int>(prompt_tokens.size()) >= st->n_ctx - 1) {
        throw_rt(env, "输入超过上下文长度，请缩短会话窗口或增大上下文");
        return nullptr;
    }

    // Fresh sampler per completion.
    common_params_sampling sparams;
    sparams.temp = temperature;
    common_sampler * sampler = common_sampler_init(st->model, sparams);

    // Independent completions must not inherit KV cache positions from the
    // previous one: the cache still holds old prompt+generated positions, and
    // decoding a fresh prompt from position 0 on top of it fails (prefill
    // decode error on the second and later calls). Clear it every time.
    llama_memory_clear(llama_get_memory(st->ctx), /*data=*/true);

    // Prefill: decode prompt tokens, request logits only on the last one.
    common_batch_clear(st->batch);
    for (size_t i = 0; i < prompt_tokens.size(); ++i) {
        const bool want_logit = (i == prompt_tokens.size() - 1);
        common_batch_add(st->batch, prompt_tokens[i],
                         static_cast<llama_pos>(i), {0}, want_logit);
    }
    if (llama_decode(st->ctx, st->batch) != 0) {
        common_sampler_free(sampler);
        throw_rt(env, "prefill 解码失败");
        return nullptr;
    }

    llama_pos position = static_cast<llama_pos>(prompt_tokens.size());
    const llama_vocab * vocab = llama_model_get_vocab(st->model);
    std::string out;

    for (int n = 0; n < max_tokens; ++n) {
        llama_token token = common_sampler_sample(sampler, st->ctx, -1);
        common_sampler_accept(sampler, token, true);

        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }
        out += common_token_to_piece(st->ctx, token);

        common_batch_clear(st->batch);
        common_batch_add(st->batch, token, position, {0}, true);
        if (llama_decode(st->ctx, st->batch) != 0) {
            common_sampler_free(sampler);
            throw_rt(env, "生成过程中解码失败");
            return nullptr;
        }
        ++position;
    }

    common_sampler_free(sampler);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_notifyme_android_LlamaJni_nativeDestroy(JNIEnv *, jclass, jlong handle) {
    auto * st = reinterpret_cast<llama_state *>(handle);
    if (!st) { return; }
    st->templates.reset();
    llama_batch_free(st->batch);
    llama_free(st->ctx);
    llama_model_free(st->model);
    delete st;
}

JNIEXPORT jstring JNICALL
Java_com_notifyme_android_LlamaJni_nativeSystemInfo(JNIEnv * env, jclass) {
    return env->NewStringUTF(llama_print_system_info());
}

} // extern "C"
