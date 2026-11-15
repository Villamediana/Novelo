// The search by meaning, on the phone: a text in, its meaning as numbers out (EmbeddingGemma 2
// through llama.cpp, the model's own pooling). No Android or JNI here, so the same code runs in
// the app (novelo_engine.cpp) and in a test on the computer (tools/sense-check).
//
// One text at a time per Sense (the Kotlin side holds a mutex). The vector comes back as the
// model gives it: cutting it shorter and normalizing is the caller's.

#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "llama.h"

namespace novelo_sense {

struct Sense {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    int n_ctx = 0;
    int n_embd = 0;
};

inline void free_sense(Sense * s) {
    if (!s) return;
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

/** The model at [path], ready to embed texts of up to [n_ctx] tokens; null when it can't load or doesn't pool. */
inline Sense * load(const char * path, int threads, int n_ctx) {
    llama_backend_init();
    auto * s = new Sense();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    s->model = llama_model_load_from_file(path, mp);
    if (!s->model) { free_sense(s); return nullptr; }
    s->vocab = llama_model_get_vocab(s->model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) n_ctx;
    cp.n_batch = (uint32_t) n_ctx;
    cp.n_ubatch = (uint32_t) n_ctx;
    cp.n_seq_max = 1;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.embeddings = true;
    cp.pooling_type = LLAMA_POOLING_TYPE_UNSPECIFIED; // the model's own (mean, for EmbeddingGemma)
    cp.no_perf = true;
    s->ctx = llama_init_from_model(s->model, cp);
    if (!s->ctx || llama_pooling_type(s->ctx) == LLAMA_POOLING_TYPE_NONE) { free_sense(s); return nullptr; }
    s->n_ctx = n_ctx;
    s->n_embd = llama_model_n_embd_out(s->model);
    return s;
}

/**
 * The meaning of [text] (UTF-8, its task prefix already in it) into [out]: the model's tokens with
 * its own special ones (begin and end), cut at the context's size keeping the end token. False
 * when it couldn't (empty, or the model failed).
 */
inline bool embed(Sense * s, const std::string & text, std::vector<float> & out) {
    if (!s || text.empty()) return false;
    std::vector<llama_token> toks(text.size() + 8);
    int n = llama_tokenize(s->vocab, text.data(), (int32_t) text.size(), toks.data(), (int32_t) toks.size(), true, true);
    if (n < 0) {
        toks.resize((size_t) -n);
        n = llama_tokenize(s->vocab, text.data(), (int32_t) text.size(), toks.data(), (int32_t) toks.size(), true, true);
    }
    if (n <= 0) return false;
    toks.resize((size_t) n);
    if ((int) toks.size() > s->n_ctx) {
        // Too long: the start of it, with the end token the model expects last.
        const llama_token last = toks.back();
        const bool special = last == llama_vocab_eos(s->vocab) || last == llama_vocab_sep(s->vocab);
        toks.resize((size_t) s->n_ctx);
        if (special) toks.back() = last;
    }
    llama_memory_clear(llama_get_memory(s->ctx), true);
    llama_batch b = llama_batch_get_one(toks.data(), (int32_t) toks.size());
    if (llama_decode(s->ctx, b) != 0) return false;
    const float * e = llama_get_embeddings_seq(s->ctx, 0);
    if (!e) return false;
    out.assign(e, e + s->n_embd);
    return true;
}

} // namespace novelo_sense
