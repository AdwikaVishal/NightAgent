package com.nightagent.evidence.metadata

import java.io.File

/** Resolves only app-private, relative paths to published NGE1 containers. */
object EncryptedContainerPath {

    fun resolve(filesDir: File, relativePath: String): File? {
        if (relativePath.isBlank() || !relativePath.endsWith(".ng1", ignoreCase = true)) {
            return null
        }
        if (File(relativePath).isAbsolute) return null

        return try {
            val base = filesDir.canonicalFile
            val target = File(filesDir, relativePath).canonicalFile
            if (target.toPath().startsWith(base.toPath())) target else null
        } catch (e: Exception) {
            null
        }
    }
}
