#include "session/zenz_inprocess_client.h"

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <string>
#include <thread>

#include "session/zenz_live_corrector.h"
#include "testing/gunit.h"
#include "zenz/zenz_inprocess_abi.h"

namespace mozc {
namespace session {
namespace {

struct FakeBackend {
  int32_t status = MOZKEY_ZENZ_STATUS_OK;
  std::string value = "今日は";
  uint32_t reported_size_override = 0;
  bool use_size_override = false;
  int sleep_msec = 0;

  std::atomic<int> calls{0};
  std::atomic<bool> finished{false};
  std::string last_prompt;
  uint32_t last_timeout_msec = 0;
  uint32_t last_max_output_chars = 0;
  uint32_t last_capacity = 0;
  bool stop_seen = false;

  static int32_t Convert(void* user_data, const char* prompt,
                         uint32_t prompt_size, uint32_t timeout_msec,
                         uint32_t max_output_chars,
                         MozkeyZenzShouldStopFn should_stop,
                         void* stop_context, char* out_value,
                         uint32_t out_capacity, uint32_t* out_size) {
    FakeBackend* self = static_cast<FakeBackend*>(user_data);
    ++self->calls;
    self->last_prompt.assign(prompt, prompt_size);
    self->last_timeout_msec = timeout_msec;
    self->last_max_output_chars = max_output_chars;
    self->last_capacity = out_capacity;
    if (self->sleep_msec > 0) {
      std::this_thread::sleep_for(std::chrono::milliseconds(self->sleep_msec));
    }
    self->stop_seen = should_stop(stop_context) != 0;
    *out_size = 0;
    self->finished = true;
    if (self->status != MOZKEY_ZENZ_STATUS_OK) {
      return self->status;
    }
    if (self->value.size() <= out_capacity) {
      std::memcpy(out_value, self->value.data(), self->value.size());
    }
    *out_size = self->use_size_override
                    ? self->reported_size_override
                    : static_cast<uint32_t>(self->value.size());
    return MOZKEY_ZENZ_STATUS_OK;
  }
};

ZenzLiveRequest MakeRequest() {
  ZenzLiveRequest request;
  request.generation = 7;
  request.key = "きょうは";
  request.prompt = "\xEE\xB8\x82\xEE\xB8\x80キョウハ\xEE\xB8\x81";
  request.timeout_msec = 250;
  request.max_output_chars = 16;
  return request;
}

class ZenzInProcessClientTest : public ::testing::Test {
 protected:
  void TearDown() override { mozkey_zenz_register_backend(nullptr, nullptr); }
};

TEST_F(ZenzInProcessClientTest, UnavailableWithoutBackend) {
  ZenzInProcessClient client;
  EXPECT_FALSE(client.IsAvailable());

  const ZenzLiveResponse response = client.Convert(MakeRequest());
  EXPECT_FALSE(response.ok);
  EXPECT_FALSE(response.timeout);
  EXPECT_EQ(response.generation, 7u);
  EXPECT_EQ(response.key, "きょうは");
  EXPECT_EQ(response.debug, "inprocess_backend_unavailable");
}

TEST_F(ZenzInProcessClientTest, ForwardsRequestAndReturnsValue) {
  FakeBackend backend;
  mozkey_zenz_register_backend(&FakeBackend::Convert, &backend);
  ZenzInProcessClient client;
  ASSERT_TRUE(client.IsAvailable());

  const ZenzLiveRequest request = MakeRequest();
  const ZenzLiveResponse response = client.Convert(request);
  EXPECT_TRUE(response.ok);
  EXPECT_FALSE(response.timeout);
  EXPECT_EQ(response.value, "今日は");
  EXPECT_EQ(response.generation, 7u);
  EXPECT_EQ(response.key, "きょうは");
  EXPECT_EQ(response.debug, "inprocess_ok");

  EXPECT_EQ(backend.calls.load(), 1);
  EXPECT_EQ(backend.last_prompt, request.prompt);
  EXPECT_EQ(backend.last_timeout_msec, 250u);
  EXPECT_EQ(backend.last_max_output_chars, 16u);
  // Four UTF-8 bytes per requested character.
  EXPECT_EQ(backend.last_capacity, 64u);
  EXPECT_FALSE(backend.stop_seen);
}

TEST_F(ZenzInProcessClientTest, MapsTimeoutAndError) {
  FakeBackend backend;
  mozkey_zenz_register_backend(&FakeBackend::Convert, &backend);
  ZenzInProcessClient client;

  backend.status = MOZKEY_ZENZ_STATUS_TIMEOUT;
  ZenzLiveResponse response = client.Convert(MakeRequest());
  EXPECT_FALSE(response.ok);
  EXPECT_TRUE(response.timeout);
  EXPECT_TRUE(response.value.empty());
  EXPECT_EQ(response.debug, "inprocess_timeout");

  backend.status = MOZKEY_ZENZ_STATUS_ERROR;
  response = client.Convert(MakeRequest());
  EXPECT_FALSE(response.ok);
  EXPECT_FALSE(response.timeout);
  EXPECT_TRUE(response.value.empty());
  EXPECT_EQ(response.debug, "inprocess_error");
}

TEST_F(ZenzInProcessClientTest, RejectsInvalidBackendOutput) {
  FakeBackend backend;
  mozkey_zenz_register_backend(&FakeBackend::Convert, &backend);
  ZenzInProcessClient client;

  backend.use_size_override = true;
  backend.reported_size_override = 0;
  ZenzLiveResponse response = client.Convert(MakeRequest());
  EXPECT_FALSE(response.ok);
  EXPECT_EQ(response.debug, "inprocess_invalid_output");

  // A backend claiming more bytes than the buffer holds must not be trusted.
  backend.reported_size_override = 1u << 20;
  response = client.Convert(MakeRequest());
  EXPECT_FALSE(response.ok);
  EXPECT_TRUE(response.value.empty());
  EXPECT_EQ(response.debug, "inprocess_invalid_output");
}

TEST_F(ZenzInProcessClientTest, RejectsEmptyAndOversizedPrompts) {
  FakeBackend backend;
  mozkey_zenz_register_backend(&FakeBackend::Convert, &backend);
  ZenzInProcessClient client;

  ZenzLiveRequest request = MakeRequest();
  request.prompt.clear();
  EXPECT_EQ(client.Convert(request).debug, "inprocess_empty_prompt");

  request.prompt.assign(64 * 1024 + 1, 'a');
  EXPECT_EQ(client.Convert(request).debug, "prompt_too_large");
  EXPECT_EQ(backend.calls.load(), 0);
}

TEST_F(ZenzInProcessClientTest, StopRequestSkipsBackendAndIsVisibleToIt) {
  FakeBackend backend;
  mozkey_zenz_register_backend(&FakeBackend::Convert, &backend);
  ZenzInProcessClient client;

  client.RequestStop();
  const ZenzLiveResponse response = client.Convert(MakeRequest());
  EXPECT_FALSE(response.ok);
  EXPECT_EQ(response.debug, "inprocess_stopped");
  EXPECT_EQ(backend.calls.load(), 0);

  // A stop arriving while the backend is running is reported by should_stop.
  client.ResetStop();
  backend.sleep_msec = 100;
  std::thread stopper([&client] {
    std::this_thread::sleep_for(std::chrono::milliseconds(20));
    client.RequestStop();
  });
  client.Convert(MakeRequest());
  stopper.join();
  EXPECT_EQ(backend.calls.load(), 1);
  EXPECT_TRUE(backend.stop_seen);
}

TEST_F(ZenzInProcessClientTest, UnregisterWaitsForInFlightRequest) {
  FakeBackend backend;
  backend.sleep_msec = 100;
  mozkey_zenz_register_backend(&FakeBackend::Convert, &backend);
  ZenzInProcessClient client;

  ZenzLiveResponse response;
  std::thread worker([&] { response = client.Convert(MakeRequest()); });
  while (backend.calls.load() == 0) {
    std::this_thread::yield();
  }
  // Must block until the running backend call has returned.
  mozkey_zenz_register_backend(nullptr, nullptr);
  EXPECT_TRUE(backend.finished.load());
  worker.join();
  EXPECT_TRUE(response.ok);

  EXPECT_FALSE(client.IsAvailable());
  EXPECT_EQ(client.Convert(MakeRequest()).debug,
            "inprocess_backend_unavailable");
}

}  // namespace
}  // namespace session
}  // namespace mozc
