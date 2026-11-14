// JNI entry points of libmozkey_zenz.so.
//
// Loads the Zenz GGUF model with llama.cpp inside the IME process and hands
// the runtime to libmozc.so through the C ABI in zenz/zenz_inprocess_abi.h.

#include <dlfcn.h>
#include <jni.h>

#include <mutex>
#include <string>

#include <android/log.h>

#include "zenz/zenz_inprocess_abi.h"
#include "zenz_llama_backend.h"

namespace {

constexpr char kLogTag[] = "MozkeyZenz";

std::mutex g_mutex;
mozkey::zenz::ZenzLlamaRuntime* g_runtime = nullptr;
bool g_registered = false;

// libmozc.so is already loaded by MozcJNI; dlopen() only returns its handle.
MozkeyZenzRegisterBackendFn FindRegisterFunction() {
  void* handle = dlopen("libmozc.so", RTLD_NOW);
  if (handle == nullptr) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "dlopen(libmozc.so): %s",
                        dlerror());
    return nullptr;
  }
  void* symbol = dlsym(handle, MOZKEY_ZENZ_REGISTER_BACKEND_SYMBOL);
  if (symbol == nullptr) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                        "libmozc.so does not export %s",
                        MOZKEY_ZENZ_REGISTER_BACKEND_SYMBOL);
    return nullptr;
  }
  return reinterpret_cast<MozkeyZenzRegisterBackendFn>(symbol);
}

void UnloadLocked() {
  if (g_registered) {
    if (MozkeyZenzRegisterBackendFn fn = FindRegisterFunction()) {
      // Blocks until an in-flight request has returned.
      fn(nullptr, nullptr);
    }
    g_registered = false;
  }
  if (g_runtime != nullptr) {
    g_runtime->Unload();
  }
}

std::string ToStdString(JNIEnv* env, jstring value) {
  if (value == nullptr) {
    return std::string();
  }
  const char* chars = env->GetStringUTFChars(value, nullptr);
  if (chars == nullptr) {
    return std::string();
  }
  std::string result(chars);
  env->ReleaseStringUTFChars(value, chars);
  return result;
}

}  // namespace

extern "C" {

// Returns null on success, otherwise a short machine-readable error code.
JNIEXPORT jstring JNICALL
Java_org_mozkey_inputmethod_zenz_ZenzNative_nativeLoad(JNIEnv* env, jclass,
                                                       jstring model_path,
                                                       jint threads,
                                                       jint context_size) {
  std::lock_guard<std::mutex> lock(g_mutex);
  UnloadLocked();
  if (g_runtime == nullptr) {
    g_runtime = new mozkey::zenz::ZenzLlamaRuntime();
  }

  mozkey::zenz::ZenzRuntimeOptions options;
  options.model_path = ToStdString(env, model_path);
  options.threads = threads;
  options.context_size = context_size;

  std::string error;
  if (!g_runtime->Load(options, &error)) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "load failed: %s",
                        error.c_str());
    return env->NewStringUTF(error.c_str());
  }

  const MozkeyZenzRegisterBackendFn register_backend = FindRegisterFunction();
  if (register_backend == nullptr) {
    g_runtime->Unload();
    return env->NewStringUTF("register_symbol_not_found");
  }
  register_backend(&mozkey::zenz::ZenzLlamaRuntime::ConvertTrampoline,
                   g_runtime);
  g_registered = true;
  __android_log_print(ANDROID_LOG_INFO, kLogTag, "Zenz runtime registered");
  return nullptr;
}

JNIEXPORT void JNICALL
Java_org_mozkey_inputmethod_zenz_ZenzNative_nativeUnload(JNIEnv*, jclass) {
  std::lock_guard<std::mutex> lock(g_mutex);
  UnloadLocked();
}

JNIEXPORT jboolean JNICALL
Java_org_mozkey_inputmethod_zenz_ZenzNative_nativeIsLoaded(JNIEnv*, jclass) {
  std::lock_guard<std::mutex> lock(g_mutex);
  return (g_registered && g_runtime != nullptr && g_runtime->IsLoaded())
             ? JNI_TRUE
             : JNI_FALSE;
}

}  // extern "C"
