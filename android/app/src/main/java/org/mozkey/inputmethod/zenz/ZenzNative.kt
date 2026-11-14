package org.mozkey.inputmethod.zenz

/** JNI binding of libmozkey_zenz.so (see `app/src/main/cpp/zenz_jni.cc`). */
internal object ZenzNative {
    /** Returns null on success, otherwise a short error code. */
    @JvmStatic
    external fun nativeLoad(modelPath: String, threads: Int, contextSize: Int): String?

    @JvmStatic
    external fun nativeUnload()

    @JvmStatic
    external fun nativeIsLoaded(): Boolean
}
