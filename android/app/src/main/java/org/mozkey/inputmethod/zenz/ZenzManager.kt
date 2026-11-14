package org.mozkey.inputmethod.zenz

import android.content.Context
import android.util.Log
import org.mozkey.inputmethod.engine.DataInstaller
import java.util.concurrent.Executors

/**
 * Owns the in-process Zenz (llama.cpp) runtime.
 *
 * The model is bundled as an APK asset, so the app needs no network permission.
 * Loading happens off the main thread; libmozc.so must already be loaded because
 * the runtime registers itself with it.
 */
class ZenzManager(private val appContext: Context) {

    enum class State { UNLOADED, LOADING, LOADED, MODEL_MISSING, FAILED }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mozkey-zenz").apply { isDaemon = true }
    }

    @Volatile
    var state: State = State.UNLOADED
        private set

    @Volatile
    var lastError: String? = null
        private set

    val isLoaded: Boolean get() = state == State.LOADED

    /** Loads the model if it is not loaded yet. [onDone] runs on the Zenz thread. */
    fun loadAsync(onDone: (Boolean) -> Unit) {
        if (state == State.LOADED || state == State.LOADING) {
            if (state == State.LOADED) onDone(true)
            return
        }
        state = State.LOADING
        executor.execute {
            val ok = loadBlocking()
            onDone(ok)
        }
    }

    /** Frees the model. Used when the system asks the process to trim memory. */
    fun unloadAsync(onDone: () -> Unit = {}) {
        executor.execute {
            if (libraryLoaded) {
                ZenzNative.nativeUnload()
            }
            if (state == State.LOADED || state == State.LOADING) {
                state = State.UNLOADED
            }
            onDone()
        }
    }

    private fun loadBlocking(): Boolean {
        val model = try {
            DataInstaller.installIfPresent(appContext, MODEL_ASSET, "zenz")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install the Zenz model", e)
            null
        }
        if (model == null) {
            state = State.MODEL_MISSING
            lastError = "model_missing"
            return false
        }
        return try {
            if (!libraryLoaded) {
                System.loadLibrary("mozkey_zenz")
                libraryLoaded = true
            }
            val error = ZenzNative.nativeLoad(model.absolutePath, recommendedThreads(), CONTEXT_SIZE)
            if (error == null) {
                state = State.LOADED
                lastError = null
                true
            } else {
                state = State.FAILED
                lastError = error
                false
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "libmozkey_zenz.so is not available", e)
            state = State.FAILED
            lastError = "native_library_missing"
            false
        }
    }

    private fun recommendedThreads(): Int {
        // Leave the little cores and the UI thread alone.
        val cores = Runtime.getRuntime().availableProcessors()
        return (cores / 2).coerceIn(2, 4)
    }

    companion object {
        private const val TAG = "MozkeyZenz"

        /** Written by android/scripts/prepare_native.sh. */
        const val MODEL_ASSET = "zenz/zenz-v3.2-small-Q5_K_M.gguf"

        /** Same as the desktop llama-server (`-c 256`). */
        private const val CONTEXT_SIZE = 256

        @Volatile
        private var libraryLoaded = false
    }
}
