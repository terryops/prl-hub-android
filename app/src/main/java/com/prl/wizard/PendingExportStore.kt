package com.prl.wizard

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** Export text kept in no-backup storage while Android's document picker owns the foreground. */
class PendingExportStore(private val directory: File) {
    fun stage(text: String): String {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot stage export")
        val name = "export-${UUID.randomUUID()}.txt"
        val file = File(directory, name)
        try {
            file.outputStream().use {
                it.write(text.toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return name
    }

    fun open(name: String): InputStream =
        file(name)?.inputStream() ?: throw IOException("Missing staged export")

    fun discard(name: String) {
        file(name)?.delete()
    }

    /** Remove exports abandoned with an old task, keeping the picker restored from saved state. */
    fun discardExcept(keep: String?) {
        directory.listFiles()?.filter { NAME.matches(it.name) && it.name != keep }?.forEach { it.delete() }
    }

    private fun file(name: String): File? = if (NAME.matches(name)) File(directory, name) else null

    companion object {
        private val NAME = Regex("export-[0-9a-f-]{36}\\.txt")
    }
}
