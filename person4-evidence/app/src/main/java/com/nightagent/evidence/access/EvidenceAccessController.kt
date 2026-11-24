package com.nightagent.evidence.access

import com.nightagent.evidence.crypto.EvidenceEncryptionEngine
import com.nightagent.evidence.integrity.EvidenceIntegrityVerifier
import com.nightagent.evidence.integrity.IntegrityStatus
import com.nightagent.evidence.metadata.EncryptedContainerPath
import com.nightagent.evidence.metadata.EvidenceMetadataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Reusable, scoped plaintext access controller.
 *
 * Plaintext is produced only after the existing integrity verifier returns
 * [IntegrityStatus.VERIFIED], and only through the existing NGE1 engine's
 * `decrypt()` (which verifies authentication again and publishes atomically).
 * Output goes to a unique app-private directory with owner-only permissions.
 * Callers must close the returned token; [releaseAll] covers lifecycle teardown.
 *
 * This class contains no Compose or Activity dependency and can be hosted by
 * the main application directly.
 */
class EvidenceAccessController(
    private val filesDir: File,
    private val metadataRepository: EvidenceMetadataRepository,
    private val integrityVerifier: EvidenceIntegrityVerifier,
    private val engine: EvidenceEncryptionEngine,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val accessRoot = File(filesDir, ACCESS_DIRECTORY_NAME)
    private val activeTokens = ConcurrentHashMap<String, EvidenceAccessToken>()

    /** Number of currently outstanding plaintext tokens (for host diagnostics). */
    fun activeTokenCount(): Int = activeTokens.size

    suspend fun open(evidenceId: String): EvidenceAccessResult = withContext(ioDispatcher) {
        if (evidenceId.isBlank()) {
            return@withContext EvidenceAccessResult.Denied(IntegrityStatus.METADATA_NOT_FOUND)
        }

        val verification = integrityVerifier.verify(evidenceId)
        if (verification.status != IntegrityStatus.VERIFIED) {
            return@withContext EvidenceAccessResult.Denied(verification.status)
        }

        val metadata = try {
            metadataRepository.findById(evidenceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext EvidenceAccessResult.Failed(
                EvidenceAccessStage.METADATA, e
            )
        } ?: return@withContext EvidenceAccessResult.Denied(IntegrityStatus.METADATA_NOT_FOUND)

        val container = EncryptedContainerPath.resolve(filesDir, metadata.containerRelativePath)
            ?: return@withContext EvidenceAccessResult.Failed(EvidenceAccessStage.CONTAINER_PATH)

        val directory = File(accessRoot, UUID.randomUUID().toString())
        if (!directory.mkdirs()) {
            return@withContext EvidenceAccessResult.Failed(EvidenceAccessStage.PREPARE_TEMPORARY)
        }
        // Metadata is database-controlled but keep the file name defensive.
        val extension = metadata.originalExtension.filter { it.isLetterOrDigit() }
            .take(MAX_EXTENSION_LENGTH)
            .ifEmpty { "bin" }
        val target = File(directory, "$PLAINTEXT_BASENAME.$extension")

        try {
            engine.decrypt(container, target)
            restrictToOwner(target)
            val token = EvidenceAccessToken(
                tokenId = UUID.randomUUID().toString(),
                evidenceId = evidenceId,
                mediaType = metadata.mediaType,
                originalExtension = extension,
                plaintextFile = target,
                onRelease = ::releaseToken
            )
            activeTokens[token.tokenId] = token
            EvidenceAccessResult.Ready(token)
        } catch (e: CancellationException) {
            deleteRecursively(directory)
            throw e
        } catch (e: Exception) {
            deleteRecursively(directory)
            EvidenceAccessResult.Failed(EvidenceAccessStage.DECRYPT, e)
        }
    }

    /** Idempotent release of a single token, safe to call from any thread. */
    fun release(token: EvidenceAccessToken) = releaseToken(token)

    /** Releases every outstanding token; call from lifecycle teardown. */
    fun releaseAll() {
        activeTokens.values.toList().forEach { it.close() }
    }

    suspend fun releaseAllOnIo() = withContext(ioDispatcher) { releaseAll() }

    private fun releaseToken(token: EvidenceAccessToken) {
        if (activeTokens.remove(token.tokenId) == null) return
        deleteRecursively(token.plaintextFile.parentFile)
    }

    /** Best-effort owner-only access inside an already app-private directory. */
    private fun restrictToOwner(file: File) {
        runCatching {
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setExecutable(false, false)
        }
    }

    private fun deleteRecursively(file: File?) {
        if (file == null) return
        if (file.isDirectory) file.listFiles()?.forEach(::deleteRecursively)
        runCatching {
            if (file.exists() && !file.delete()) file.deleteOnExit()
        }
    }

    companion object {
        private const val ACCESS_DIRECTORY_NAME = ".evidence-access"
        private const val PLAINTEXT_BASENAME = "evidence"
        private const val MAX_EXTENSION_LENGTH = 8
    }
}
