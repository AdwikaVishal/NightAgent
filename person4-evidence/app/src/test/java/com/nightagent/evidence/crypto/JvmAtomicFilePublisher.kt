package com.nightagent.evidence.crypto

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * JVM test stand-in for [AndroidAtomicFilePublisher].
 *
 * It uses the same exclusive-create-and-copy strategy as production, so the
 * no-replace contract is exercised by JVM tests. It never uses hard links and
 * never replaces an existing target.
 */
class JvmAtomicFilePublisher : AtomicFilePublisher {

    @Throws(IOException::class)
    override fun publish(temporary: File, target: File) {
        validatePaths(temporary, target)

        // Advisory only. CREATE_NEW below is the actual no-replace guarantee.
        if (target.exists()) {
            throw IOException("Refusing to overwrite existing evidence file")
        }

        try {
            Files.newOutputStream(
                target.toPath(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            ).use { output ->
                Files.copy(temporary.toPath(), output)
            }
        } catch (e: FileAlreadyExistsException) {
            throw IOException("Refusing to overwrite existing evidence file")
        } catch (e: IOException) {
            throw IOException("Exclusive evidence publication failed")
        }

        try {
            Files.delete(temporary.toPath())
        } catch (e: IOException) {
            temporary.deleteOnExit()
        }
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
}
