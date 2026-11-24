package com.nightagent.evidence.devtest

import com.nightagent.evidence.metadata.EncryptedContainerPath
import com.nightagent.evidence.metadata.EvidenceMetadataFactory
import com.nightagent.evidence.metadata.EvidenceMetadataRepository
import com.nightagent.evidence.metadata.MetadataCreationResult
import com.nightagent.evidence.metadata.MetadataRegistrationResult
import com.nightagent.evidence.model.EvidenceType
import com.nightagent.evidence.retention.DeletionResult
import com.nightagent.evidence.retention.EvidenceRetentionController
import com.nightagent.evidence.storage.EvidenceEncryptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

data class TestRecordCreateOutcome(
    val success: Boolean,
    val statusText: String,
    val evidenceId: String? = null
)

data class TestRecordDeleteOutcome(
    val success: Boolean,
    val statusText: String,
    val encryptedFileRemoved: Boolean,
    val metadataRemoved: Boolean
)

/**
 * Development-only creation/deletion harness for ONE synthetic evidence record.
 *
 * The generated container lives in a dedicated directory, is never given a
 * capture-style name, and the only id that may be deleted is the id persisted
 * by this harness. Real capture records cannot be selected or deleted here.
 */
class Phase5DeletionTestController(
    private val filesDir: File,
    private val encryptor: EvidenceEncryptor,
    private val metadataRepository: EvidenceMetadataRepository,
    private val retentionController: EvidenceRetentionController,
    private val recordStore: Phase5TestRecordStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    fun currentTestEvidenceId(): String? = recordStore.get()

    suspend fun create(): TestRecordCreateOutcome = withContext(ioDispatcher) {
        val stagingDirectory = File(filesDir, STAGING_DIRECTORY)
        val targetDirectory = File(filesDir, TEST_DIRECTORY)
        if (!ensureDirectory(stagingDirectory) || !ensureDirectory(targetDirectory)) {
            return@withContext TestRecordCreateOutcome(
                false, "Could not prepare test directories; nothing was created"
            )
        }

        val unique = UUID.randomUUID().toString()
        val plaintext = File(stagingDirectory, "${TEST_FILE_PREFIX}${unique}.jpg")
        val relativePath = "$TEST_DIRECTORY/${TEST_FILE_PREFIX}${unique}.ng1"
        val target = File(filesDir, relativePath)
        if (target.exists()) {
            return@withContext TestRecordCreateOutcome(
                false, "Unique test target already exists; nothing was overwritten"
            )
        }

        try {
            plaintext.writeBytes(ByteArray(SYNTHETIC_BYTES) { (it % 251).toByte() })
            encryptor.encrypt(plaintext, target, EvidenceType.PHOTO)

            val created = EvidenceMetadataFactory.fromPublishedContainer(filesDir, relativePath)
            if (created !is MetadataCreationResult.Created) {
                deleteQuietly(target)
                return@withContext TestRecordCreateOutcome(
                    false, "Test metadata could not be built; no test record was registered"
                )
            }

            when (metadataRepository.register(created.metadata)) {
                MetadataRegistrationResult.Registered,
                MetadataRegistrationResult.AlreadyRegistered -> Unit

                else -> {
                    deleteQuietly(target)
                    return@withContext TestRecordCreateOutcome(
                        false, "Test metadata registration failed; no test record is active"
                    )
                }
            }

            recordStore.save(created.metadata.evidenceId)
            TestRecordCreateOutcome(
                success = true,
                statusText = "Temporary test record created (${created.metadata.mediaType.name})",
                evidenceId = created.metadata.evidenceId
            )
        } catch (e: CancellationException) {
            deleteQuietly(target)
            throw e
        } catch (e: Exception) {
            deleteQuietly(target)
            TestRecordCreateOutcome(
                false, "Test record creation failed (${e.javaClass.simpleName}); no record is active"
            )
        } finally {
            // Synthetic staging bytes only; never a captured/user file.
            deleteQuietly(plaintext)
        }
    }

    suspend fun delete(): TestRecordDeleteOutcome = withContext(ioDispatcher) {
        val evidenceId = recordStore.get()
            ?: return@withContext TestRecordDeleteOutcome(
                false, "No temporary test record exists; nothing was deleted", true, true
            )

        val metadata = try {
            metadataRepository.findById(evidenceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext TestRecordDeleteOutcome(
                false, "Test record lookup failed (${e.javaClass.simpleName})", false, false
            )
        } ?: run {
            recordStore.clear()
            return@withContext TestRecordDeleteOutcome(
                false, "Test metadata was already absent; harness reference cleared", true, true
            )
        }

        val container = EncryptedContainerPath.resolve(filesDir, metadata.containerRelativePath)
        val dedicatedDirectory = try {
            File(filesDir, TEST_DIRECTORY).canonicalFile
        } catch (e: IOException) {
            null
        }
        val isHarnessContainer = container != null &&
            dedicatedDirectory != null &&
            container.canonicalFile.parentFile == dedicatedDirectory &&
            container.name.startsWith(TEST_FILE_PREFIX) &&
            container.name.endsWith(".ng1", ignoreCase = true)

        if (!isHarnessContainer) {
            // Refuse to touch anything that is not this harness's own container.
            return@withContext TestRecordDeleteOutcome(
                false, "Refusing to delete: stored path is not a Phase 5 test container", false, false
            )
        }

        val deletion = retentionController.delete(evidenceId)
        val fileRemoved = !container.exists()
        val metadataRemoved = try {
            metadataRepository.findById(evidenceId) == null
        } catch (e: Exception) {
            false
        }

        if (fileRemoved && metadataRemoved) {
            recordStore.clear()
            TestRecordDeleteOutcome(true, "Test record deleted: file and metadata both removed", true, true)
        } else {
            val label = when (deletion) {
                is DeletionResult.Deleted -> "reported deleted"
                is DeletionResult.NotFound -> "metadata not found"
                is DeletionResult.PartialFailure -> "partial failure"
                is DeletionResult.Failed -> "failed at ${deletion.stage.name}"
            }
            TestRecordDeleteOutcome(
                false,
                "Test deletion $label. File removed: ${if (fileRemoved) "yes" else "no"}; " +
                    "metadata removed: ${if (metadataRemoved) "yes" else "no"}",
                fileRemoved,
                metadataRemoved
            )
        }
    }

    private fun ensureDirectory(directory: File): Boolean =
        directory.exists() || directory.mkdirs()

    private fun deleteQuietly(file: File) {
        runCatching {
            if (file.exists() && !file.delete()) file.deleteOnExit()
        }
    }

    companion object {
        /** Dedicated, clearly identified location for harness-only containers. */
        const val TEST_DIRECTORY: String = "evidence-encrypted/phase5-devtest"
        const val STAGING_DIRECTORY: String = "phase5-devtest-staging"
        const val TEST_FILE_PREFIX: String = "phase5_devtest_"
        private const val SYNTHETIC_BYTES: Int = 512
    }
}
