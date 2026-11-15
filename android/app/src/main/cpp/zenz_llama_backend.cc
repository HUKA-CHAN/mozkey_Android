#include "zenz_llama_backend.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstring>
#include <string_view>
#include <utility>

#include "llama.h"

namespace mozkey {
namespace zenz {
namespace {

using Clock = std::chrono::steady_clock;

constexpr uint32_t kDefaultTimeoutMsec = 180;
constexpr uint32_t kMinTimeoutMsec = 20;
constexpr uint32_t kMaxTimeoutMsec = 10000;
constexpr uint32_t kDefaultMaxOutputChars = 128;
constexpr uint32_t kMaxOutputChars = 1024;
constexpr int kMinNPredict = 4;
constexpr int kMaxNPredict = 256;

// Markers that terminate generation.  Same list as the desktop scorer's
// CleanGeneratedText(): the Zenz control characters U+EE00..U+EE0F, the usual
// special-token spellings, and line breaks.
constexpr std::array<std::string_view, 22> kStopMarkers = {
    "\xEE\xB8\x80", "\xEE\xB8\x81", "\xEE\xB8\x82", "\xEE\xB8\x83",
    "\xEE\xB8\x84", "\xEE\xB8\x85", "\xEE\xB8\x86", "\xEE\xB8\x87",
    "\xEE\xB8\x88", "\xEE\xB8\x89", "\xEE\xB8\x8A", "\xEE\xB8\x8B",
    "\xEE\xB8\x8C", "\xEE\xB8\x8D", "\xEE\xB8\x8E", "\xEE\xB8\x8F",
    "<s>",          "</s>",         "<unk>",         "<|endoftext|>",
    "\r",           "\n",
};

size_t FindStopMarker(std::string_view text) {
  size_t end = std::string_view::npos;
  for (const std::string_view marker : kStopMarkers) {
    const size_t position = text.find(marker);
    if (position != std::string_view::npos) {
      end = std::min(end, position);
    }
  }
  return end;
}

std::string TrimAsciiWhitespace(std::string text) {
  const auto is_space = [](unsigned char c) {
    return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f' ||
           c == '\v';
  };
  size_t begin = 0;
  while (begin < text.size() && is_space(text[begin])) {
    ++begin;
  }
  size_t end = text.size();
  while (end > begin && is_space(text[end - 1])) {
    --end;
  }
  return text.substr(begin, end - begin);
}

// Returns the byte length of the UTF-8 sequence starting at |lead|, or 0 when
// |lead| is not a valid lead byte.
size_t Utf8SequenceLength(unsigned char lead) {
  if (lead < 0x80) return 1;
  if (lead >= 0xC2 && lead <= 0xDF) return 2;
  if (lead >= 0xE0 && lead <= 0xEF) return 3;
  if (lead >= 0xF0 && lead <= 0xF4) return 4;
  return 0;
}

// Keeps at most |max_chars| complete, well-formed UTF-8 characters.  A
// truncated or malformed tail (possible when generation stops in the middle
// of a multi-byte token) is dropped.
std::string TruncateUtf8ByChars(std::string_view input, uint32_t max_chars) {
  size_t position = 0;
  uint32_t count = 0;
  while (position < input.size() && count < max_chars) {
    const size_t length =
        Utf8SequenceLength(static_cast<unsigned char>(input[position]));
    if (length == 0 || position + length > input.size()) {
      break;
    }
    bool valid = true;
    for (size_t i = 1; i < length; ++i) {
      if ((static_cast<unsigned char>(input[position + i]) & 0xC0) != 0x80) {
        valid = false;
        break;
      }
    }
    if (!valid) {
      break;
    }
    position += length;
    ++count;
  }
  return std::string(input.substr(0, position));
}

struct AbortState {
  Clock::time_point deadline;
  MozkeyZenzShouldStopFn should_stop = nullptr;
  void* stop_context = nullptr;
  bool timed_out = false;
  bool stopped = false;

  bool Check() {
    if (should_stop != nullptr && should_stop(stop_context) != 0) {
      stopped = true;
      return true;
    }
    if (Clock::now() >= deadline) {
      timed_out = true;
      return true;
    }
    return false;
  }
};

bool AbortCallback(void* data) {
  return static_cast<AbortState*>(data)->Check();
}

void SilentLog(enum ggml_log_level /*level*/, const char* /*text*/,
               void* /*user_data*/) {}

std::vector<llama_token> Tokenize(const llama_vocab* vocab,
                                  std::string_view text) {
  // Same flags as llama-server's /completion: add_special, parse_special.
  std::vector<llama_token> tokens(text.size() + 8);
  int32_t count = llama_tokenize(vocab, text.data(),
                                 static_cast<int32_t>(text.size()),
                                 tokens.data(),
                                 static_cast<int32_t>(tokens.size()),
                                 /*add_special=*/true, /*parse_special=*/true);
  if (count < 0) {
    tokens.resize(static_cast<size_t>(-count));
    count = llama_tokenize(vocab, text.data(),
                           static_cast<int32_t>(text.size()), tokens.data(),
                           static_cast<int32_t>(tokens.size()),
                           /*add_special=*/true, /*parse_special=*/true);
  }
  if (count < 0) {
    return {};
  }
  tokens.resize(static_cast<size_t>(count));
  return tokens;
}

bool AppendPiece(const llama_vocab* vocab, llama_token token,
                 std::string* output) {
  char buffer[256];
  int32_t length = llama_token_to_piece(vocab, token, buffer, sizeof(buffer),
                                        /*lstrip=*/0, /*special=*/true);
  if (length < 0) {
    std::string large(static_cast<size_t>(-length), '\0');
    length = llama_token_to_piece(vocab, token, large.data(),
                                  static_cast<int32_t>(large.size()),
                                  /*lstrip=*/0, /*special=*/true);
    if (length < 0) {
      return false;
    }
    output->append(large.data(), static_cast<size_t>(length));
    return true;
  }
  output->append(buffer, static_cast<size_t>(length));
  return true;
}

}  // namespace

std::string CleanGeneratedText(std::string text, uint32_t max_output_chars) {
  const size_t stop = FindStopMarker(text);
  if (stop != std::string_view::npos) {
    text.resize(stop);
  }
  text = TrimAsciiWhitespace(std::move(text));
  return TruncateUtf8ByChars(text, max_output_chars);
}

ZenzLlamaRuntime::~ZenzLlamaRuntime() { Unload(); }

bool ZenzLlamaRuntime::IsLoaded() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return context_ != nullptr;
}

bool ZenzLlamaRuntime::Load(const ZenzRuntimeOptions& options,
                            std::string* error) {
  std::lock_guard<std::mutex> lock(mutex_);
  UnloadLocked();

  static std::once_flag backend_once;
  std::call_once(backend_once, [] {
    llama_log_set(&SilentLog, nullptr);
    llama_backend_init();
  });

  llama_model_params model_params = llama_model_default_params();
  model_params.n_gpu_layers = 0;  // CPU only: predictable on every device.
  model_ = llama_model_load_from_file(options.model_path.c_str(),
                                      model_params);
  if (model_ == nullptr) {
    *error = "model_load_failed";
    return false;
  }

  const int context_size = std::clamp(options.context_size, 64, 4096);
  const int threads = std::clamp(options.threads, 1, 8);

  llama_context_params context_params = llama_context_default_params();
  context_params.n_ctx = static_cast<uint32_t>(context_size);
  context_params.n_batch = static_cast<uint32_t>(context_size);
  context_params.n_threads = threads;
  context_params.n_threads_batch = threads;
  context_params.no_perf = true;
  context_ = llama_init_from_model(model_, context_params);
  if (context_ == nullptr) {
    UnloadLocked();
    *error = "context_init_failed";
    return false;
  }

  sampler_ = llama_sampler_init_greedy();
  if (sampler_ == nullptr) {
    UnloadLocked();
    *error = "sampler_init_failed";
    return false;
  }

  vocab_ = llama_model_get_vocab(model_);
  options_ = options;
  options_.context_size = context_size;
  options_.threads = threads;
  cached_tokens_.clear();
  return true;
}

void ZenzLlamaRuntime::Unload() {
  std::lock_guard<std::mutex> lock(mutex_);
  UnloadLocked();
}

void ZenzLlamaRuntime::UnloadLocked() {
  if (sampler_ != nullptr) {
    llama_sampler_free(sampler_);
    sampler_ = nullptr;
  }
  if (context_ != nullptr) {
    llama_free(context_);
    context_ = nullptr;
  }
  if (model_ != nullptr) {
    llama_model_free(model_);
    model_ = nullptr;
  }
  vocab_ = nullptr;
  cached_tokens_.clear();
}

void ZenzLlamaRuntime::ResetCacheLocked() {
  if (context_ != nullptr) {
    llama_memory_clear(llama_get_memory(context_), /*data=*/true);
  }
  cached_tokens_.clear();
}

int32_t ZenzLlamaRuntime::Convert(const char* prompt, uint32_t prompt_size,
                                  uint32_t timeout_msec,
                                  uint32_t max_output_chars,
                                  MozkeyZenzShouldStopFn should_stop,
                                  void* stop_context, std::string* value,
                                  std::string* debug) {
  value->clear();
  std::lock_guard<std::mutex> lock(mutex_);
  if (context_ == nullptr || vocab_ == nullptr) {
    *debug = "runtime_not_loaded";
    return MOZKEY_ZENZ_STATUS_ERROR;
  }
  if (prompt == nullptr || prompt_size == 0) {
    *debug = "empty_prompt";
    return MOZKEY_ZENZ_STATUS_ERROR;
  }

  timeout_msec = std::clamp<uint32_t>(
      timeout_msec == 0 ? kDefaultTimeoutMsec : timeout_msec, kMinTimeoutMsec,
      kMaxTimeoutMsec);
  max_output_chars = std::clamp<uint32_t>(
      max_output_chars == 0 ? kDefaultMaxOutputChars : max_output_chars, 1,
      kMaxOutputChars);
  const int configured_n_predict =
      std::clamp(options_.n_predict, kMinNPredict, kMaxNPredict);
  int n_predict =
      std::max(kMinNPredict, std::min(configured_n_predict,
                                      static_cast<int>(max_output_chars)));

  AbortState abort_state;
  abort_state.deadline =
      Clock::now() + std::chrono::milliseconds(timeout_msec);
  abort_state.should_stop = should_stop;
  abort_state.stop_context = stop_context;

  const auto finish_aborted = [&]() -> int32_t {
    // An interrupted llama_decode() leaves the KV cache in an unknown state.
    ResetCacheLocked();
    llama_set_abort_callback(context_, nullptr, nullptr);
    if (abort_state.stopped) {
      *debug = "stopped";
      return MOZKEY_ZENZ_STATUS_ERROR;
    }
    *debug = "timeout";
    return MOZKEY_ZENZ_STATUS_TIMEOUT;
  };

  std::vector<llama_token> tokens =
      Tokenize(vocab_, std::string_view(prompt, prompt_size));
  if (tokens.empty()) {
    *debug = "tokenize_failed";
    return MOZKEY_ZENZ_STATUS_ERROR;
  }

  const int n_ctx = static_cast<int>(llama_n_ctx(context_));
  if (static_cast<int>(tokens.size()) >= n_ctx - kMinNPredict) {
    *debug = "prompt_exceeds_context";
    return MOZKEY_ZENZ_STATUS_ERROR;
  }
  n_predict = std::min(n_predict, n_ctx - static_cast<int>(tokens.size()));

  // cache_prompt: reuse the longest common token prefix already in the KV
  // cache, but always re-evaluate at least the last prompt token so that fresh
  // logits are available for sampling.
  size_t keep = 0;
  const size_t comparable = std::min(cached_tokens_.size(), tokens.size());
  while (keep < comparable && cached_tokens_[keep] == tokens[keep]) {
    ++keep;
  }
  if (keep >= tokens.size()) {
    keep = tokens.size() - 1;
  }
  llama_memory_t memory = llama_get_memory(context_);
  if (keep == 0) {
    llama_memory_clear(memory, /*data=*/false);
  } else if (!llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(keep),
                                  -1)) {
    llama_memory_clear(memory, /*data=*/false);
    keep = 0;
  }
  cached_tokens_.resize(keep);

  llama_set_abort_callback(context_, &AbortCallback, &abort_state);

  if (abort_state.Check()) {
    return finish_aborted();
  }
  if (llama_decode(context_,
                   llama_batch_get_one(
                       tokens.data() + keep,
                       static_cast<int32_t>(tokens.size() - keep))) != 0) {
    if (abort_state.timed_out || abort_state.stopped) {
      return finish_aborted();
    }
    ResetCacheLocked();
    llama_set_abort_callback(context_, nullptr, nullptr);
    *debug = "decode_prompt_failed";
    return MOZKEY_ZENZ_STATUS_ERROR;
  }
  cached_tokens_ = tokens;

  std::string generated;
  bool stop_marker_found = false;
  for (int i = 0; i < n_predict; ++i) {
    if (abort_state.Check()) {
      return finish_aborted();
    }
    llama_token token = llama_sampler_sample(sampler_, context_, -1);
    if (llama_vocab_is_eog(vocab_, token)) {
      break;
    }
    if (!AppendPiece(vocab_, token, &generated)) {
      break;
    }
    if (FindStopMarker(generated) != std::string_view::npos) {
      stop_marker_found = true;
      break;
    }
    if (i + 1 >= n_predict) {
      break;
    }
    if (llama_decode(context_, llama_batch_get_one(&token, 1)) != 0) {
      if (abort_state.timed_out || abort_state.stopped) {
        return finish_aborted();
      }
      ResetCacheLocked();
      llama_set_abort_callback(context_, nullptr, nullptr);
      *debug = "decode_token_failed";
      return MOZKEY_ZENZ_STATUS_ERROR;
    }
    cached_tokens_.push_back(token);
  }
  llama_set_abort_callback(context_, nullptr, nullptr);

  std::string cleaned =
      CleanGeneratedText(std::move(generated), max_output_chars);
  if (cleaned.empty()) {
    *debug = "empty_content";
    return MOZKEY_ZENZ_STATUS_ERROR;
  }
  *value = std::move(cleaned);
  *debug = stop_marker_found ? "ok_stop" : "ok";
  return MOZKEY_ZENZ_STATUS_OK;
}

std::string ZenzLlamaRuntime::Stats() const {
  std::string last_debug;
  {
    std::lock_guard<std::mutex> lock(stat_mutex_);
    last_debug = stat_last_debug_;
  }
  std::string result;
  result += "requests=" + std::to_string(stat_requests_.load()) + "\n";
  result += "ok=" + std::to_string(stat_ok_.load()) + "\n";
  result += "timeout=" + std::to_string(stat_timeout_.load()) + "\n";
  result += "error=" + std::to_string(stat_error_.load()) + "\n";
  result += "last_msec=" + std::to_string(stat_last_msec_.load()) + "\n";
  result += "last_result=" + (last_debug.empty() ? "-" : last_debug);
  return result;
}

int32_t ZenzLlamaRuntime::ConvertTrampoline(
    void* user_data, const char* prompt, uint32_t prompt_size,
    uint32_t timeout_msec, uint32_t max_output_chars,
    MozkeyZenzShouldStopFn should_stop, void* stop_context, char* out_value,
    uint32_t out_capacity, uint32_t* out_size) {
  if (out_size != nullptr) {
    *out_size = 0;
  }
  if (user_data == nullptr || out_value == nullptr || out_size == nullptr) {
    return MOZKEY_ZENZ_STATUS_ERROR;
  }
  ZenzLlamaRuntime* runtime = static_cast<ZenzLlamaRuntime*>(user_data);
  std::string value;
  std::string debug;
  const Clock::time_point start = Clock::now();
  const int32_t status =
      runtime->Convert(prompt, prompt_size, timeout_msec, max_output_chars,
                       should_stop, stop_context, &value, &debug);
  ++runtime->stat_requests_;
  runtime->stat_last_msec_ = static_cast<uint32_t>(
      std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() -
                                                            start)
          .count());
  if (status == MOZKEY_ZENZ_STATUS_OK) {
    ++runtime->stat_ok_;
  } else if (status == MOZKEY_ZENZ_STATUS_TIMEOUT) {
    ++runtime->stat_timeout_;
  } else {
    ++runtime->stat_error_;
  }
  {
    std::lock_guard<std::mutex> lock(runtime->stat_mutex_);
    runtime->stat_last_debug_ = debug;
  }
  if (status != MOZKEY_ZENZ_STATUS_OK) {
    return status;
  }
  if (value.size() > out_capacity) {
    // Never cut a multi-byte character in half.
    value = TruncateUtf8ByChars(value, out_capacity / 4);
    if (value.empty()) {
      return MOZKEY_ZENZ_STATUS_ERROR;
    }
  }
  std::memcpy(out_value, value.data(), value.size());
  *out_size = static_cast<uint32_t>(value.size());
  return MOZKEY_ZENZ_STATUS_OK;
}

}  // namespace zenz
}  // namespace mozkey
