package org.mozkey.inputmethod.engine

import android.content.Context as AndroidContext
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.google.android.apps.inputmethod.libs.mozc.session.MozcJNI
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Capability
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Command
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Context as MozcContext
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Input
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.KeyEvent
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Output
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Request
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.SessionCommand
import org.mozc.android.inputmethod.japanese.protobuf.ProtoConfig.Config
import java.io.File

/**
 * Thin asynchronous client of libmozc.so.
 *
 * Every native call runs on one worker thread, which keeps commands strictly
 * ordered and the UI thread free. Results are delivered on the main thread.
 */
class MozcEngine(context: AndroidContext) {

    private val appContext = context.applicationContext
    private val thread = HandlerThread("mozkey-engine").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    // Worker-thread state.
    private var sessionId = 0L
    private var baseConfig: Config? = null

    @Volatile
    var isReady = false
        private set

    @Volatile
    var dataVersion: String = ""
        private set

    /** Loads the native library and dictionary, then creates a session. */
    fun initialize(onReady: (Boolean) -> Unit) {
        worker.post {
            val ok = try {
                initializeOnWorker()
            } catch (t: Throwable) {
                Log.e(TAG, "Engine initialization failed", t)
                false
            }
            isReady = ok
            main.post { onReady(ok) }
        }
    }

    private fun initializeOnWorker(): Boolean {
        val data = DataInstaller.installIfPresent(appContext, DATA_ASSET, "mozc")
        if (data == null) {
            Log.e(TAG, "$DATA_ASSET is not bundled; run android/scripts/prepare_native.sh")
            return false
        }
        val profile = File(appContext.filesDir, "profile")
        if (!profile.isDirectory && !profile.mkdirs()) {
            Log.e(TAG, "Cannot create $profile")
            return false
        }

        // Safer Native DCL (targetSdk 37): only libraries packaged in the APK are loaded.
        System.loadLibrary("mozc")
        if (!MozcJNI.initialize()) return false
        if (!MozcJNI.onPostLoad(profile.absolutePath, data.absolutePath)) return false
        dataVersion = MozcJNI.getDataVersion()

        val created = eval(
            Input.newBuilder()
                .setType(Input.CommandType.CREATE_SESSION)
                .setCapability(
                    Capability.newBuilder()
                        .setTextDeletion(Capability.TextDeletionCapabilityType.DELETE_PRECEDING_TEXT)
                )
        ) ?: return false
        if (!created.hasId()) return false
        sessionId = created.id

        val config = eval(Input.newBuilder().setType(Input.CommandType.GET_CONFIG))
        baseConfig = if (config != null && config.hasConfig()) config.config else Config.getDefaultInstance()
        return true
    }

    /** Sends one key event. [callback] receives the output on the main thread. */
    fun sendKey(key: KeyEvent, context: MozcContext?, callback: (Output) -> Unit) {
        submit(callback) {
            val input = Input.newBuilder()
                .setType(Input.CommandType.SEND_KEY)
                .setId(sessionId)
                .setKey(key)
            if (context != null) input.setContext(context)
            input
        }
    }

    fun sendCommand(command: SessionCommand, context: MozcContext? = null, callback: ((Output) -> Unit)? = null) {
        submit(callback) {
            val input = Input.newBuilder()
                .setType(Input.CommandType.SEND_COMMAND)
                .setId(sessionId)
                .setCommand(command)
            if (context != null) input.setContext(context)
            input
        }
    }

    fun setRequest(request: Request) {
        submit(null) {
            Input.newBuilder()
                .setType(Input.CommandType.SET_REQUEST)
                .setId(sessionId)
                .setRequest(request)
        }
    }

    /**
     * Applies [mutate] on top of the stored configuration. SET_CONFIG replaces the
     * whole configuration, so the previously read values are always carried over.
     */
    fun updateConfig(mutate: (Config.Builder) -> Unit) {
        worker.post {
            if (!isReady) return@post
            val builder = (baseConfig ?: Config.getDefaultInstance()).toBuilder()
            mutate(builder)
            val updated = builder.build()
            if (updated == baseConfig) return@post
            baseConfig = updated
            eval(Input.newBuilder().setType(Input.CommandType.SET_CONFIG).setId(sessionId).setConfig(updated))
        }
    }

    /** Flushes user history and learning data to storage. */
    fun syncData() {
        worker.post {
            if (isReady) eval(Input.newBuilder().setType(Input.CommandType.SYNC_DATA))
        }
    }

    fun clearUserHistory(onDone: () -> Unit) {
        worker.post {
            if (isReady) {
                eval(Input.newBuilder().setType(Input.CommandType.CLEAR_USER_HISTORY))
                eval(Input.newBuilder().setType(Input.CommandType.CLEAR_USER_PREDICTION))
            }
            main.post(onDone)
        }
    }

    private fun submit(callback: ((Output) -> Unit)?, build: () -> Input.Builder) {
        worker.post {
            if (!isReady) return@post
            val output = eval(build()) ?: return@post
            if (callback != null) main.post { callback(output) }
        }
    }

    /** Worker thread only. */
    private fun eval(input: Input.Builder): Output? {
        return try {
            val command = Command.newBuilder()
                .setInput(input)
                .setOutput(Output.getDefaultInstance())
                .build()
            Command.parseFrom(MozcJNI.evalCommand(command.toByteArray())).output
        } catch (t: Throwable) {
            Log.e(TAG, "evalCommand failed", t)
            null
        }
    }

    companion object {
        private const val TAG = "MozkeyEngine"

        /** Written by android/scripts/prepare_native.sh. */
        const val DATA_ASSET = "mozc/mozc.data"
    }
}
