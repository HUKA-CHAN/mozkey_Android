#ifndef MOZC_SESSION_ZENZ_INPROCESS_CLIENT_H_
#define MOZC_SESSION_ZENZ_INPROCESS_CLIENT_H_

#include "session/zenz_live_corrector.h"
#include "zenz/zenz_inprocess_abi.h"

#if defined(__GNUC__) || defined(__clang__)
#define MOZKEY_ZENZ_EXPORT __attribute__((visibility("default"), used))
#else
#define MOZKEY_ZENZ_EXPORT
#endif

extern "C" {
// Exported from libmozc.so.  See zenz/zenz_inprocess_abi.h.
MOZKEY_ZENZ_EXPORT void mozkey_zenz_register_backend(
    MozkeyZenzConvertFn convert, void* user_data);
}  // extern "C"

namespace mozc {
namespace session {

// Zenz transport that calls an inference backend living in the same process.
//
// Used on Android, where the IME cannot launch the scorer / llama-server
// helper processes that the desktop transports rely on.  The backend is
// registered at runtime through mozkey_zenz_register_backend(); until then the
// client reports itself as unavailable and ZenzLiveCorrector falls back to the
// plain Mozc result.
class ZenzInProcessClient final : public ZenzClient {
 public:
  ZenzInProcessClient() = default;
  ~ZenzInProcessClient() override = default;

  bool IsAvailable() const override;
  ZenzLiveResponse Convert(const ZenzLiveRequest& request) override;
};

}  // namespace session
}  // namespace mozc

#endif  // MOZC_SESSION_ZENZ_INPROCESS_CLIENT_H_
