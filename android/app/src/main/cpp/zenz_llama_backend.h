#ifndef MOZKEY_ANDROID_ZENZ_LLAMA_BACKEND_H_
#define MOZKEY_ANDROID_ZENZ_LLAMA_BACKEND_H_

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "zenz/zenz_inprocess_abi.h"

struct llama_model;
struct llama_context;
struct llama_sampler;
struct llama_vocab;

namespace mozkey {
namespace zenz {

struct ZenzRuntimeOptions {
  std::string model_path;
  // Matches the desktop llama-server defaults (-c 256 -t 4).
  int context_size = 256;
  int threads = 4;
  // Upper bound of generated tokens for one request.
  int n_predict = 64;
};

// In-process replacement for "mozc_zenz_scorer + llama-server /completion".
//
// Mirrors the request the desktop scorer sends to llama-server:
//   temperature 0 / top_k 1 (greedy), cache_prompt, stop at U+EE00..U+EE0F,
//   CR and LF, followed by the same output clean-up.
class ZenzLlamaRuntime {
 public:
  ZenzLlamaRuntime() = default;
  ~ZenzLlamaRuntime();

  ZenzLlamaRuntime(const ZenzLlamaRuntime&) = delete;
  ZenzLlamaRuntime& operator=(const ZenzLlamaRuntime&) = delete;

  // Loads the model.  Returns false and fills |error| on failure.
  bool Load(const ZenzRuntimeOptions& options, std::string* error);
  void Unload();
  bool IsLoaded() const;

  // Runs one completion.  Returns MOZKEY_ZENZ_STATUS_*.
  int32_t Convert(const char* prompt, uint32_t prompt_size,
                  uint32_t timeout_msec, uint32_t max_output_chars,
                  MozkeyZenzShouldStopFn should_stop, void* stop_context,
                  std::string* value, std::string* debug);

  // C trampoline matching MozkeyZenzConvertFn; |user_data| is the runtime.
  static int32_t ConvertTrampoline(void* user_data, const char* prompt,
                                   uint32_t prompt_size, uint32_t timeout_msec,
                                   uint32_t max_output_chars,
                                   MozkeyZenzShouldStopFn should_stop,
                                   void* stop_context, char* out_value,
                                   uint32_t out_capacity, uint32_t* out_size);

 private:
  void UnloadLocked();
  void ResetCacheLocked();

  mutable std::mutex mutex_;
  ZenzRuntimeOptions options_;
  llama_model* model_ = nullptr;
  llama_context* context_ = nullptr;
  llama_sampler* sampler_ = nullptr;
  const llama_vocab* vocab_ = nullptr;
  // Tokens currently materialized in the KV cache (sequence 0).
  std::vector<int32_t> cached_tokens_;
};

// Exposed for unit tests.
std::string CleanGeneratedText(std::string text, uint32_t max_output_chars);

}  // namespace zenz
}  // namespace mozkey

#endif  // MOZKEY_ANDROID_ZENZ_LLAMA_BACKEND_H_
