package com.google.android.apps.inputmethod.libs.mozc.session

/**
 * JNI binding of libmozc.so.
 *
 * The fully qualified class name is fixed by `src/android/jni/mozcjni.cc`
 * (`Java_com_google_android_apps_inputmethod_libs_mozc_session_MozcJNI_initialize`),
 * so this file must stay in this package even though the rest of the app lives
 * in `org.mozkey.inputmethod`.
 */
object MozcJNI {
    /** Registers [evalCommand], [onPostLoad] and [getDataVersion]. */
    @JvmStatic
    external fun initialize(): Boolean

    /** Evaluates a serialized `mozc.commands.Command` and returns the serialized result. */
    @JvmStatic
    external fun evalCommand(command: ByteArray): ByteArray

    /** Creates the engine. Must be called once after [initialize]. */
    @JvmStatic
    external fun onPostLoad(userProfileDirectoryPath: String, dataFilePath: String?): Boolean

    @JvmStatic
    external fun getDataVersion(): String
}
