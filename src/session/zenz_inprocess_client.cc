#include "session/zenz_inprocess_client.h"

#include <algorithm>
#include <cstdint>
#include <mutex>
#include <shared_mutex>
#include <string>
#include <utility>

#include "absl/time/clock.h"
#include "absl/time/time.h"

namespace mozc {
namespace session {
namespace {

// UTF-8 needs at most four bytes per Unicode character.
constexpr uint32_t kMaxBytesPerChar = 4;
constexpr uint32_t kDefaultMaxOutputChars = 128;
constexpr uint32_t kMaxOutputCharsLimit = 1024;
constexpr size_t kMaxPromptBytes = 64 * 1024;

struct BackendRegistry {
  // Shared while a request is running, exclusive while the backend is being
  // replaced.  This gives mozkey_zenz_register_backend() the documented
  // "previous user_data is no longer used after return" guarantee.
  std::shared_mutex mutex;
  MozkeyZenzConvertFn convert = nullptr;
  void* user_data = nullptr;
};

BackendRegistry& Registry() {
  // Intentionally leaked: worker threads may still probe the registry while
  // static destructors run at process exit.
  static BackendRegistry* registry = new BackendRegistry();
  return *registry;
}

int32_t ShouldStop(void* stop_context) {
  const ZenzClient* client = static_cast<const ZenzClient*>(stop_context);
  return (client != nullptr && client->IsStopRequested()) ? 1 : 0;
}

}  // namespace

bool ZenzInProcessClient::IsAvailable() const {
  BackendRegistry& registry = Registry();
  std::shared_lock<std::shared_mutex> lock(registry.mutex);
  return registry.convert != nullptr;
}

ZenzLiveResponse ZenzInProcessClient::Convert(const ZenzLiveRequest& request) {
  ZenzLiveResponse response;
  response.generation = request.generation;
  response.key = request.key;

  if (request.prompt.empty()) {
    response.debug = "inprocess_empty_prompt";
    return response;
  }
  if (request.prompt.size() > kMaxPromptBytes) {
    response.debug = "prompt_too_large";
    return response;
  }
  if (IsStopRequested()) {
    response.debug = "inprocess_stopped";
    return response;
  }

  const uint32_t max_output_chars = std::clamp<uint32_t>(
      request.max_output_chars == 0 ? kDefaultMaxOutputChars
                                    : request.max_output_chars,
      1, kMaxOutputCharsLimit);
  std::string buffer(static_cast<size_t>(max_output_chars) * kMaxBytesPerChar,
                     '\0');
  uint32_t out_size = 0;

  const absl::Time start = absl::Now();
  int32_t status = MOZKEY_ZENZ_STATUS_ERROR;
  {
    BackendRegistry& registry = Registry();
    std::shared_lock<std::shared_mutex> lock(registry.mutex);
    if (registry.convert == nullptr) {
      response.debug = "inprocess_backend_unavailable";
      return response;
    }
    status = registry.convert(
        registry.user_data, request.prompt.data(),
        static_cast<uint32_t>(request.prompt.size()), request.timeout_msec,
        max_output_chars, &ShouldStop, static_cast<ZenzClient*>(this),
        buffer.data(), static_cast<uint32_t>(buffer.size()), &out_size);
  }
  response.latency = absl::Now() - start;

  switch (status) {
    case MOZKEY_ZENZ_STATUS_OK:
      if (out_size == 0 || out_size > buffer.size()) {
        response.debug = "inprocess_invalid_output";
        return response;
      }
      buffer.resize(out_size);
      response.value = std::move(buffer);
      response.ok = true;
      response.debug = "inprocess_ok";
      return response;
    case MOZKEY_ZENZ_STATUS_TIMEOUT:
      response.timeout = true;
      response.debug = "inprocess_timeout";
      return response;
    default:
      response.debug = "inprocess_error";
      return response;
  }
}

}  // namespace session
}  // namespace mozc

extern "C" void mozkey_zenz_register_backend(MozkeyZenzConvertFn convert,
                                             void* user_data) {
  mozc::session::BackendRegistry& registry = mozc::session::Registry();
  std::unique_lock<std::shared_mutex> lock(registry.mutex);
  registry.convert = convert;
  registry.user_data = convert == nullptr ? nullptr : user_data;
}
