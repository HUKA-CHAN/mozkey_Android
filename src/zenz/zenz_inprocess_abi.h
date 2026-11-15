#ifndef MOZC_ZENZ_ZENZ_INPROCESS_ABI_H_
#define MOZC_ZENZ_ZENZ_INPROCESS_ABI_H_

// In-process Zenz backend ABI.
//
// Windows and macOS run llama.cpp in a separate scorer process and talk to it
// over a named pipe / Unix-domain socket.  Platforms that cannot spawn helper
// processes (Android) instead load the inference runtime into the same process
// as libmozc.so and register it through this plain C interface.
//
// The interface is deliberately free of C++ types so that the runtime library
// (built with CMake against llama.cpp) and libmozc.so (built with Bazel) can
// be compiled by different build systems and C++ standard library settings.
//
// This header must stay self-contained: no Mozc, absl or STL includes.

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Status codes.  The values intentionally match kZenzWireStatus* in
// zenz_wire_protocol.h.
#define MOZKEY_ZENZ_STATUS_OK 0
#define MOZKEY_ZENZ_STATUS_ERROR 1
#define MOZKEY_ZENZ_STATUS_TIMEOUT 2

// Returns non-zero when the caller no longer needs the result and the backend
// should abandon the current request as soon as possible.
typedef int32_t (*MozkeyZenzShouldStopFn)(void* stop_context);

// Runs one completion.
//
//   prompt / prompt_size : UTF-8 Zenz prompt, not NUL terminated.
//   timeout_msec         : wall-clock budget for the whole request.
//   max_output_chars     : upper bound of Unicode characters to generate.
//   should_stop          : cancellation probe; may be called from the backend
//                          at any time until this function returns.
//   out_value            : caller-owned buffer receiving UTF-8 output without
//                          a trailing NUL.
//   out_capacity         : size of |out_value| in bytes.
//   out_size             : number of bytes written to |out_value|.
//
// Returns one of MOZKEY_ZENZ_STATUS_*.  On a non-OK status |*out_size| must be
// zero.  The function may be called from any thread, but libmozc.so never
// issues two calls concurrently.
typedef int32_t (*MozkeyZenzConvertFn)(void* user_data, const char* prompt,
                                       uint32_t prompt_size,
                                       uint32_t timeout_msec,
                                       uint32_t max_output_chars,
                                       MozkeyZenzShouldStopFn should_stop,
                                       void* stop_context, char* out_value,
                                       uint32_t out_capacity,
                                       uint32_t* out_size);

// Symbol name exported by libmozc.so, for dlsym() users.
#define MOZKEY_ZENZ_REGISTER_BACKEND_SYMBOL "mozkey_zenz_register_backend"

// Registers (or, with a null |convert|, unregisters) the process-wide backend.
// Blocks until any in-flight request on the previous backend has returned, so
// after this function returns the previous |user_data| is no longer used.
typedef void (*MozkeyZenzRegisterBackendFn)(MozkeyZenzConvertFn convert,
                                            void* user_data);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // MOZC_ZENZ_ZENZ_INPROCESS_ABI_H_
