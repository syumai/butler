package dev.syumai.butler

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.MessageDigest

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
     * only for the log line. [requiredSha256] validates required files before reusing a ready tree or
     * installing a fresh copy. Returns the destination directory. */
    fun unpack(context: Context, assetDir: String, marker: String, label: String,
        requiredSha256: Map<String, String> = emptyMap()): File {
        val dest = File(context.filesDir, assetDir)
        val markerFile = File(dest, MARKER)
        if (markerFile.exists() && markerFile.readText() == marker) {
            try {
                verifyFiles(dest, requiredSha256)
                return dest
            } catch (e: IOException) {
                Log.w("Butler", "$label incomplete or corrupt; re-unpacking from APK: ${e.message}")
            }
        }
        val started = System.currentTimeMillis()
        val temp = File(context.filesDir, "$assetDir.tmp")
        try {
            temp.deleteRecursively()
            temp.mkdirs()
            copyAssetDir(context, assetDir, assetDir, temp)
            verifyFiles(temp, requiredSha256)
            File(temp, MARKER).writeText(marker)
            dest.deleteRecursively()
            check(temp.renameTo(dest)) { "Could not install unpacked $label" }
        } catch (e: Exception) {
            Log.e("Butler", "Could not unpack $label from APK assets/$assetDir to ${dest.absolutePath}. " +
                "Check packaged assets, free storage and rebuild with fetchDeps enabled.", e)
            temp.deleteRecursively(); dest.deleteRecursively()
            throw e
        }
        Log.i("Butler", "$label unpacked in ${System.currentTimeMillis() - started}ms")
        return dest
    }

    /** Also validates an existing .ready tree, so a previous incomplete APK cannot poison updates. */
    internal fun verifyFiles(root: File, requiredSha256: Map<String, String>) {
        for ((path, expected) in requiredSha256) {
            val file = File(root, path)
            if (!file.isFile) throw IOException("Missing required asset: ${file.absolutePath}")
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) throw IOException(
                "SHA-256 mismatch for ${file.absolutePath}: expected $expected, got $actual")
        }
    }

    private fun copyAssetDir(context: Context, rootAssetDir: String, assetPath: String, destRoot: File) {
        val entries = context.assets.list(assetPath) ?: emptyArray()
        if (entries.isEmpty()) {
            // A leaf file: assets.list() on a file path returns an empty array too, so try opening it.
            val relative = assetPath.removePrefix("$rootAssetDir/")
            val out = File(destRoot, relative)
            out.parentFile?.mkdirs()
            try {
                context.assets.open(assetPath).use { input -> out.outputStream().use { input.copyTo(it) } }
            } catch (e: IOException) {
                throw IOException("Could not copy APK asset $assetPath to ${out.absolutePath}", e)
            }
            return
        }
        for (entry in entries) copyAssetDir(context, rootAssetDir, "$assetPath/$entry", destRoot)
    }
}
