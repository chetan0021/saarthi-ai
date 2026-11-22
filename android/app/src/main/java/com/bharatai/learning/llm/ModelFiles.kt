package com.bharatai.learning.llm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Finds the ExecuTorch program (.pte) and the Llama 3 tokenizer on the device.
 * Search order:
 *   1. app-private storage   (files imported through the in-app picker)
 *   2. app external storage  /sdcard/Android/data/com.bharatai.learning/files/models  (adb push target)
 *   3. /data/local/tmp/llama (ExecuTorch demo convention; readable on most devices for debugging)
 */
data class ModelPaths(val pte: File, val tokenizer: File) {
    val sizeMb get() = pte.length() / 1048576.0
    val label get() = pte.nameWithoutExtension
}

object ModelFiles {
    fun dirs(ctx: Context): List<File> = listOfNotNull(
        File(ctx.filesDir, "models"),
        ctx.getExternalFilesDir("models"),
        File("/data/local/tmp/llama"),
    )

    fun find(ctx: Context): ModelPaths? {
        for (d in dirs(ctx)) {
            val files = runCatching { d.listFiles()?.toList() }.getOrNull().orEmpty().filter { it.canRead() }
            val pte = files.filter { it.extension == "pte" }.maxByOrNull { it.lastModified() } ?: continue
            val tok = files.firstOrNull { it.name == "tokenizer.model" }
                ?: files.firstOrNull { it.name.startsWith("tokenizer") && it.extension in setOf("model", "bin", "json") }
                ?: continue
            return ModelPaths(pte, tok)
        }
        return null
    }

    /** Copies a user-picked file (via Storage Access Framework) into app-private storage. */
    fun importFile(ctx: Context, uri: Uri, onProgress: (Float) -> Unit): File {
        val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: "model.pte"
        val total = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst() && i >= 0) c.getLong(i) else -1L
        } ?: -1L
        val dir = File(ctx.filesDir, "models").apply { mkdirs() }
        val out = File(dir, name)
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(1 shl 20)
                var copied = 0L
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    o.write(buf, 0, r)
                    copied += r
                    if (total > 0) onProgress(copied.toFloat() / total)
                }
            }
        }
        return out
    }
}
