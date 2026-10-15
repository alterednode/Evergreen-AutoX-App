package com.romangarms.autoxlive.data

import java.io.File
import java.security.MessageDigest

data class Saved<T>(val value: T, val fetchedAt: Long)

// The last answer to each GET the app shows, so a phone that loses the server
// keeps its results. Keyed by the full URL, so another server's answers never
// mix in.
class ResponseCache(private val directory: File) {
    fun store(url: String, data: ByteArray) {
        directory.mkdirs()
        val file = file(url)
        val temp = File(directory, "${file.name}.tmp")
        runCatching {
            temp.writeBytes(data)
            if (!temp.renameTo(file)) temp.delete()
        }
    }

    fun load(url: String): Saved<ByteArray>? {
        val file = file(url)
        val data = runCatching { file.readBytes() }.getOrNull() ?: return null
        return Saved(data, file.lastModified())
    }

    fun prune(olderThan: Long) {
        directory.listFiles()?.forEach { if (it.lastModified() < olderThan) it.delete() }
    }

    private fun file(url: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        return File(directory, digest.joinToString("") { "%02x".format(it) } + ".json")
    }
}

class CacheReader {
    @Volatile var oldest: Long? = null
        private set

    @Synchronized fun note(fetchedAt: Long) {
        oldest = minOf(oldest ?: fetchedAt, fetchedAt)
    }
}
