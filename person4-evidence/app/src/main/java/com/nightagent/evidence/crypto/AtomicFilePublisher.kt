package com.nightagent.evidence.crypto

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/** Publishes a fully written temporary file without overwriting an existing target. */
interface AtomicFilePublisher {
    @Throws(IOException::class)
    fun publish(temporary: File, target: File)
}

/**
 * Android implementation for minSdk 24.
 *
 * `Os.link` is intentionally not used: SELinux forbids untrusted apps from
 * creating hard links, even inside their own app-private directories. Instead,
 * this publisher atomically claims the unique target name with
 * `O_WRONLY | O_CREAT | O_EXCL`, copies the already fully written temporary
 * bytes, forces them to disk, and only then removes the temporary name.
 *
 * `O_EXCL` provides the no-replace guarantee: a racing creator receives EEXIST
 * and the existing file is never modified. There is deliberately no
 * copy-and-delete fallback that could overwrite an existing evidence file.
 */
class AndroidAtomicFilePublisher(
    private val logger: EvidenceDiagnosticLogger = SilentEvidenceDiagnosticLogger
) : AtomicFilePublisher {

    @Throws(IOException::class)
    override fun publish(temporary: File, target: File) {
        validatePaths(temporary, target)
        logger.stage(OPERATION, "PUBLISH_START")

        // Advisory only. O_EXCL below is the actual no-replace guarantee.
        if (target.exists()) {
            throw IOException("Refusing to overwrite existing evidence file")
        }

        val mode = OsConstants.S_IRUSR or OsConstants.S_IWUSR
        val descriptor = try {
            logger.stage(OPERATION, "PUBLISH_CREATE_EXCLUSIVE")
            Os.open(
                target.path,
                OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL,
                mode
            )
        } catch (e: ErrnoException) {
            logger.failure(OPERATION, "PUBLISH_CREATE_EXCLUSIVE", e)
            if (e.errno == OsConstants.EEXIST) {
                throw IOException("Refusing to overwrite existing evidence file")
            }
            throw IOException("Exclusive evidence file creation failed (errno ${e.errno})")
        }

        try {
            logger.stage(OPERATION, "PUBLISH_COPY")
            FileInputStream(temporary).use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    var written = 0
                    while (written < read) {
                        val count = Os.write(descriptor, buffer, written, read - written)
                        if (count <= 0) throw IOException("Exclusive evidence write made no progress")
                        written += count
                    }
                }
            }

            logger.stage(OPERATION, "PUBLISH_FSYNC")
            Os.fsync(descriptor)
        } catch (e: ErrnoException) {
            logger.failure(OPERATION, "PUBLISH_WRITE_OR_SYNC", e)
            throw IOException("Exclusive evidence publication failed (errno ${e.errno})")
        } catch (e: IOException) {
            logger.failure(OPERATION, "PUBLISH_WRITE_OR_SYNC", e)
            throw e
        } finally {
            try {
                Os.close(descriptor)
            } catch (e: ErrnoException) {
                logger.failure(OPERATION, "PUBLISH_CLOSE", e)
                throw IOException("Could not close published evidence file (errno ${e.errno})")
            }
        }

        // Publication already succeeded. Temporary cleanup must not be reported
        // as a publication failure because that would deny a valid evidence file.
        try {
            logger.stage(OPERATION, "PUBLISH_REMOVE_TEMP")
            Os.remove(temporary.path)
        } catch (e: ErrnoException) {
            logger.failure(OPERATION, "PUBLISH_REMOVE_TEMP", e)
            if (!temporary.delete()) {
                temporary.deleteOnExit()
            }
        }
        logger.stage(OPERATION, "PUBLISH_SUCCESS")
    }

    private fun validatePaths(temporary: File, target: File) {
        if (!temporary.isFile) {
            throw IOException("Temporary evidence file is missing or not a file")
        }
        if (temporary.canonicalFile.path == target.canonicalFile.path) {
            throw IOException("Temporary and target paths must be different")
        }

        val targetParent = target.canonicalFile.parentFile?.canonicalPath
            ?: throw IOException("Evidence target must have a parent directory")
        val temporaryParent = temporary.canonicalFile.parentFile?.canonicalPath
            ?: throw IOException("Temporary file must have a parent directory")
        val prefix = if (targetParent.endsWith(File.separator)) {
            targetParent
        } else {
            targetParent + File.separator
        }
        if (temporaryParent != targetParent && !temporaryParent.startsWith(prefix)) {
            throw IOException("Temporary file must be inside the target directory tree")
        }
    }

    private companion object {
        const val OPERATION = "PUBLISH"
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
