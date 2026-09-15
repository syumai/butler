package dev.syumai.butler

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Copies an asset directory tree to the same relative path under [Context.filesDir], once, so native
 * code that needs a real filesystem path (Vosk's `Model` constructor, Julius's `-h`/`-hlist`/`-gram`
 * file arguments) can use it directly instead of an asset path. Shared by [VoskWakeDecoder] and
 * [JuliusWakeDecoder] — previously duplicated as `VoskWakeDecoder.unpackModel`/`copyAssetDir`.
 *
 * Copies into a temporary sibling directory first and renames it into place only on full success, so a
 * half-copied tree (e.g. from a process killed mid-copy) is never treated as ready, then writes a
 * `.ready` marker file containing [marker] so later launches skip the copy entirely. [marker] should
 * encode whatever identifies this content's version (e.g. the asset dir name, optionally with
 * `BuildConfig.VERSION_CODE` appended when the asset tree can change between app versions) so that a
 * changed asset tree is detected and re-unpacked rather than silently reused.
 */
object AssetUnpacker {
    private const val MARKER = ".ready"

    /** [assetDir] is a path under `assets/` (e.g. `"vosk/vosk-model-small-ja-0.22"`, `"julius"`); the
     * same relative path is used under [Context.filesDir] as the unpack destination. [label] is used
     * only for the log line. Returns the destination directory. */
    fun unpack(context: Context, assetDir: String, marker: String, label: String): File {
        val dest = File(context.filesDir, assetDir)
        val markerFile = File(dest, MARKER)
        if (markerFile.exists() && markerFile.readText() == marker) return dest
        val started = System.currentTimeMillis()
        val temp = File(context.filesDir, "$assetDir.tmp")
        try {
            temp.deleteRecursively()
            temp.mkdirs()
            copyAssetDir(context, assetDir, assetDir, temp)
            File(temp, MARKER).writeText(marker)
            dest.deleteRecursively()
            check(temp.renameTo(dest)) { "Could not install unpacked $label" }
        } catch (e: Exception) {
            temp.deleteRecursively(); dest.deleteRecursively()
            throw e
        }
        Log.i("Butler", "$label unpacked in ${System.currentTimeMillis() - started}ms")
        return dest
    }

    private fun copyAssetDir(context: Context, rootAssetDir: String, assetPath: String, destRoot: File) {
        val entries = context.assets.list(assetPath) ?: emptyArray()
        if (entries.isEmpty()) {
            // A leaf file: assets.list() on a file path returns an empty array too, so try opening it.
            val relative = assetPath.removePrefix("$rootAssetDir/")
            val out = File(destRoot, relative)
            out.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> out.outputStream().use { input.copyTo(it) } }
            return
        }
        for (entry in entries) copyAssetDir(context, rootAssetDir, "$assetPath/$entry", destRoot)
    }
}
