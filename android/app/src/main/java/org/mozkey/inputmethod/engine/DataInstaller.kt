package org.mozkey.inputmethod.engine

import android.content.Context
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Copies bundled assets (dictionary data, Zenz model) into app-private storage.
 *
 * Both libmozc.so and llama.cpp need a real file path they can mmap, so the
 * assets cannot be used in place. A stamp file records which APK build the copy
 * came from, and the copy is refreshed after every app update.
 */
object DataInstaller {

    /**
     * Returns the installed file, or null when the APK does not contain [assetPath]
     * (for example a build made without the Zenz model).
     */
    @Throws(IOException::class)
    fun installIfPresent(context: Context, assetPath: String, subDirectory: String): File? {
        val directory = File(context.filesDir, subDirectory)
        val target = File(directory, File(assetPath).name)
        val stampFile = File(directory, target.name + ".stamp")
        val stamp = buildStamp(context)

        if (target.isFile && stampFile.isFile && stampFile.readText() == stamp) {
            return target
        }
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create $directory")
        }

        val temporary = File(directory, target.name + ".tmp")
        try {
            context.assets.open(assetPath).use { input ->
                temporary.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 1 shl 16)
                    output.fd.sync()
                }
            }
        } catch (e: FileNotFoundException) {
            temporary.delete()
            return null
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("Cannot move $temporary to $target")
        }
        stampFile.writeText(stamp)
        return target
    }

    private fun buildStamp(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return "${info.longVersionCode}:${info.lastUpdateTime}"
    }
}
