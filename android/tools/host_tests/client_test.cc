// End-to-end host test: ZenzInProcessClient (libmozc.so side) -> C ABI ->
// ZenzLlamaRuntime (libmozkey_zenz.so side) -> real model.
#include <cstdio>
#include <string>
#include <thread>

#include "session/zenz_inprocess_client.h"
#include "zenz_llama_backend.h"

static int g_failures = 0;
#define CHECK(cond)                                               \
  do {                                                            \
    if (!(cond)) {                                                \
      std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
      ++g_failures;                                               \
    }                                                             \
  } while (0)

int main(int argc, char** argv) {
  using mozc::session::ZenzInProcessClient;
  using mozc::session::ZenzLiveRequest;
  using mozc::session::ZenzLiveResponse;

  ZenzInProcessClient client;
  ZenzLiveRequest request;
  request.generation = 42;
  request.key = "きょうはいいてんきですね";
  request.prompt = std::string("\xEE\xB8\x82") + "\xEE\xB8\x80" +
                   "キョウハイイテンキデスネ" + "\xEE\xB8\x81";
  request.timeout_msec = 5000;
  request.max_output_chars = 128;

  // No backend registered yet.
  CHECK(!client.IsAvailable());
  ZenzLiveResponse r = client.Convert(request);
  CHECK(!r.ok && r.debug == "inprocess_backend_unavailable");
  CHECK(r.generation == 42 && r.key == request.key);

  if (argc < 2) return g_failures == 0 ? 0 : 1;

  mozkey::zenz::ZenzLlamaRuntime runtime;
  mozkey::zenz::ZenzRuntimeOptions options;
  options.model_path = argv[1];
  options.threads = 2;
  std::string error;
  CHECK(runtime.Load(options, &error));

  mozkey_zenz_register_backend(
      &mozkey::zenz::ZenzLlamaRuntime::ConvertTrampoline, &runtime);
  CHECK(client.IsAvailable());

  r = client.Convert(request);
  std::printf("ok=%d timeout=%d debug=%s latency=%lldms value=%s\n", r.ok,
              r.timeout, r.debug.c_str(),
              static_cast<long long>(absl::ToInt64Milliseconds(r.latency)),
              r.value.c_str());
  CHECK(r.ok && !r.timeout && r.value == "今日はいい天気ですね");
  CHECK(r.generation == 42 && r.key == request.key);

  // Timeout is reported as timeout, not as a generic error.
  ZenzLiveRequest slow = request;
  slow.prompt = std::string("\xEE\xB8\x82") + "別の文脈" + "\xEE\xB8\x80" +
                "タイムアウトヲカクニンスル" + "\xEE\xB8\x81";
  slow.timeout_msec = 20;
  r = client.Convert(slow);
  std::printf("ok=%d timeout=%d debug=%s\n", r.ok, r.timeout, r.debug.c_str());
  CHECK(!r.ok && r.timeout && r.value.empty());

  // RequestStop() from another thread cancels (ZenzLiveCorrector::Stop path).
  client.RequestStop();
  r = client.Convert(request);
  CHECK(!r.ok && r.debug == "inprocess_stopped");
  client.ResetStop();

  // Unregister while a request is running: must wait, then become unavailable.
  std::thread worker([&] {
    ZenzLiveResponse w = client.Convert(request);
    CHECK(w.ok);
  });
  std::this_thread::sleep_for(std::chrono::milliseconds(5));
  mozkey_zenz_register_backend(nullptr, nullptr);
  CHECK(!client.IsAvailable());
  worker.join();
  runtime.Unload();

  r = client.Convert(request);
  CHECK(!r.ok && r.debug == "inprocess_backend_unavailable");

  std::printf("failures=%d\n", g_failures);
  return g_failures == 0 ? 0 : 1;
}
