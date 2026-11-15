// Host-side test of the Android Zenz backend against the real pinned model.
#include <chrono>
#include <cstdio>
#include <cstring>
#include <string>

#include "zenz_llama_backend.h"

using mozkey::zenz::CleanGeneratedText;
using mozkey::zenz::ZenzLlamaRuntime;
using mozkey::zenz::ZenzRuntimeOptions;

static int g_failures = 0;
#define CHECK(cond)                                                    \
  do {                                                                 \
    if (!(cond)) {                                                     \
      std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);      \
      ++g_failures;                                                    \
    }                                                                  \
  } while (0)

static int32_t NeverStop(void*) { return 0; }
static int32_t AlwaysStop(void*) { return 1; }

static std::string Prompt(const std::string& left, const std::string& kana) {
  // U+EE02 + left_context + U+EE00 + reading_katakana + U+EE01
  return std::string("\xEE\xB8\x82") + left + "\xEE\xB8\x80" + kana +
         "\xEE\xB8\x81";
}

static int32_t Run(ZenzLlamaRuntime& rt, const std::string& prompt,
                   uint32_t timeout, std::string* value, std::string* debug,
                   double* msec, MozkeyZenzShouldStopFn stop = &NeverStop) {
  const auto t0 = std::chrono::steady_clock::now();
  const int32_t s = rt.Convert(prompt.data(), prompt.size(), timeout, 128, stop,
                               nullptr, value, debug);
  *msec = std::chrono::duration<double, std::milli>(
              std::chrono::steady_clock::now() - t0)
              .count();
  return s;
}

int main(int argc, char** argv) {
  // --- pure helpers
  CHECK(CleanGeneratedText("今日は\xEE\xB8\x81残り", 128) == "今日は");
  CHECK(CleanGeneratedText("  abc\nxyz", 128) == "abc");
  CHECK(CleanGeneratedText("あいうえお", 3) == "あいう");
  CHECK(CleanGeneratedText("あい\xE3\x81", 128) == "あい");  // truncated tail
  CHECK(CleanGeneratedText("</s>", 128).empty());

  ZenzLlamaRuntime rt;
  std::string value, debug, error;
  double msec = 0;

  // --- not loaded
  CHECK(!rt.IsLoaded());
  CHECK(Run(rt, Prompt("", "テスト"), 1000, &value, &debug, &msec) ==
        MOZKEY_ZENZ_STATUS_ERROR);
  CHECK(debug == "runtime_not_loaded");

  // --- missing model
  ZenzRuntimeOptions bad;
  bad.model_path = "/nonexistent/model.gguf";
  CHECK(!rt.Load(bad, &error));
  CHECK(error == "model_load_failed");
  CHECK(!rt.IsLoaded());

  if (argc < 2) {
    std::printf("no model given; failures=%d\n", g_failures);
    return g_failures == 0 ? 0 : 1;
  }

  ZenzRuntimeOptions options;
  options.model_path = argv[1];
  options.threads = 2;
  const auto l0 = std::chrono::steady_clock::now();
  CHECK(rt.Load(options, &error));
  std::printf("load: %.0f ms (%s)\n",
              std::chrono::duration<double, std::milli>(
                  std::chrono::steady_clock::now() - l0)
                  .count(),
              error.c_str());
  CHECK(rt.IsLoaded());

  struct Case {
    const char* left;
    const char* kana;
  } cases[] = {
      {"", "キョウハイイテンキデスネ"},
      {"", "キョウハイイテンキデスネ"},  // identical prompt: full cache hit
      {"", "キョウハイイテンキデスネ、サンポニイキマショウ"},  // shared prefix
      {"明日の会議の", "シリョウヲジュンビスル"},
      {"プログラムを", "カイハツスル"},
  };
  for (const Case& c : cases) {
    const int32_t s =
        Run(rt, Prompt(c.left, c.kana), 5000, &value, &debug, &msec);
    std::printf("[%d %-7s %6.0f ms] %s|%s -> %s\n", s, debug.c_str(), msec,
                c.left, c.kana, value.c_str());
    CHECK(s == MOZKEY_ZENZ_STATUS_OK);
    CHECK(!value.empty());
  }

  // --- timeout: budget far below one forward pass
  {
    const int32_t s = Run(rt, Prompt("全く別の文脈で", "ジカンギレヲカクニンスル"),
                          20, &value, &debug, &msec);
    std::printf("[%d %-7s %6.0f ms] timeout case\n", s, debug.c_str(), msec);
    CHECK(s == MOZKEY_ZENZ_STATUS_TIMEOUT || s == MOZKEY_ZENZ_STATUS_OK);
    if (s == MOZKEY_ZENZ_STATUS_TIMEOUT) CHECK(value.empty());
  }
  // --- cancellation
  {
    const int32_t s = Run(rt, Prompt("", "キャンセルスル"), 5000, &value, &debug,
                          &msec, &AlwaysStop);
    std::printf("[%d %-7s %6.0f ms] stop case\n", s, debug.c_str(), msec);
    CHECK(s == MOZKEY_ZENZ_STATUS_ERROR);
    CHECK(debug == "stopped");
  }
  // --- still healthy after an aborted request
  {
    const int32_t s = Run(rt, Prompt("", "キョウハイイテンキデスネ"), 5000, &value,
                          &debug, &msec);
    std::printf("[%d %-7s %6.0f ms] after abort -> %s\n", s, debug.c_str(), msec,
                value.c_str());
    CHECK(s == MOZKEY_ZENZ_STATUS_OK);
  }
  // --- C trampoline, including a too-small output buffer
  {
    const std::string p = Prompt("", "キョウハイイテンキデスネ");
    char out[512];
    uint32_t n = 0;
    int32_t s = ZenzLlamaRuntime::ConvertTrampoline(
        &rt, p.data(), p.size(), 5000, 128, &NeverStop, nullptr, out,
        sizeof(out), &n);
    CHECK(s == MOZKEY_ZENZ_STATUS_OK && n > 0);
    std::printf("trampoline -> %.*s\n", static_cast<int>(n), out);
    char tiny[8];
    s = ZenzLlamaRuntime::ConvertTrampoline(&rt, p.data(), p.size(), 5000, 128,
                                            &NeverStop, nullptr, tiny,
                                            sizeof(tiny), &n);
    CHECK(s == MOZKEY_ZENZ_STATUS_OK && n <= sizeof(tiny) && n % 3 == 0);
  }

  rt.Unload();
  CHECK(!rt.IsLoaded());
  std::printf("failures=%d\n", g_failures);
  return g_failures == 0 ? 0 : 1;
}
