package com.nightagent.evidence.storage

import com.nightagent.evidence.crypto.AtomicFilePublisher
import com.nightagent.evidence.crypto.EvidenceEncryptionEngine
import com.nightagent.evidence.crypto.EvidenceMasterKey
import com.nightagent.evidence.crypto.JvmAtomicFilePublisher
import com.nightagent.evidence.crypto.RecordingEvidenceDiagnosticLogger
import com.nightagent.evidence.crypto.TestKeyAccess
import com.nightagent.evidence.crypto.TestMasterKey
import com.nightagent.evidence.crypto.WrappedKey
import com.nightagent.evidence.metadata.RoomEvidenceMetadataRepository
import com.nightagent.evidence.model.EvidenceType
import com.nightagent.evidence.testutil.FakeEvidenceMetadataDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Regression tests for the on-device failure where every capture reports
 * "encryption failed" and the Vault stays empty.
 *
 * The failure happens inside the engine before a verified container is
 * published. These tests reproduce that shape with injected failures and assert
 * that the outcome now carries a sanitized in-app diagnostic naming the exact
 * failing stage, while the plaintext is preserved and no metadata is registered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EncryptedEvidenceDiagnosticTest {

    @Test
    fun keystoreWrapFailureIsDiagnosedAndPlaintextPreserved() = runTest {
        val filesDir = filesDir()
        val plaintext = plaintext(filesDir)
        val dao = FakeEvidenceMetadataDao()
        val recorder = RecordingEvidenceDiagnosticLogger()
        // Simulates a Keystore master key that cannot wrap the data key on-device.
        val failingKey = object : EvidenceMasterKey {
            override val version: Int = 1
            override fun wrap(dataKey: ByteArray): WrappedKey =
                throw NullPointerException("simulated keystore wrap failure")
            override fun unwrap(wrapped: WrappedKey): ByteArray =
                throw IllegalStateException("not reached")
        }
        val store = store(
            filesDir,
            dao,
            recorder,
            engine(
                recorder,
                keyAccess = TestKeyAccess(emptyList(), currentVersion = 1, encryptionKey = failingKey)
            )
        )

        val outcome = store.encryptAndPublish(plaintext)

        val failure = outcome as EncryptedEvidenceOutcome.Failure
        assertEquals(EncryptedEvidenceFailure.ENCRYPTION_FAILED, failure.reason)
        val diagnostic = failure.diagnostic
        assertNotNull(diagnostic)
        diagnostic!!
        assertEquals("PHOTO", diagnostic.operation)
        assertEquals("WRAP_DATA_KEY", diagnostic.stage)
        assertEquals("NullPointerException", diagnostic.exceptionClass)
        assertFalse(diagnostic.targetPublished)
        assertFalse(diagnostic.metadataRegistrationAttempted)

        // Plaintext preserved, no published target, no metadata registered.
        assertTrue(plaintext.isFile)
        assertFalse(File(directory(filesDir), "fixed.ng1").exists())
        assertTrue(dao.all().isEmpty())
    }

    @Test
    fun publicationFailureIsDiagnosedAtPublishStage() = runTest {
        val filesDir = filesDir()
        val plaintext = plaintext(filesDir)
        val dao = FakeEvidenceMetadataDao()
        val recorder = RecordingEvidenceDiagnosticLogger()
        val failingPublisher = object : AtomicFilePublisher {
            override fun publish(temporary: File, target: File) {
                throw IOException("simulated publication failure")
            }
        }
        val store = store(
            filesDir,
            dao,
            recorder,
            engine(recorder, publisher = failingPublisher)
        )

        val outcome = store.encryptAndPublish(plaintext)

        val failure = outcome as EncryptedEvidenceOutcome.Failure
        assertEquals(EncryptedEvidenceFailure.ENCRYPTION_FAILED, failure.reason)
        val diagnostic = failure.diagnostic
        assertNotNull(diagnostic)
        diagnostic!!
        assertEquals("PHOTO", diagnostic.operation)
        assertEquals("PUBLISH", diagnostic.stage)
        assertEquals("IOException", diagnostic.exceptionClass)
        assertFalse(diagnostic.targetPublished)
        assertFalse(diagnostic.metadataRegistrationAttempted)
        assertTrue(plaintext.isFile)
        assertTrue(dao.all().isEmpty())
    }

    @Test
    fun successfulPublicationCarriesNoDiagnostic() = runTest {
        val filesDir = filesDir()
        val plaintext = plaintext(filesDir)
        val dao = FakeEvidenceMetadataDao()
        val recorder = RecordingEvidenceDiagnosticLogger()
        val store = store(filesDir, dao, recorder, engine(recorder))

        val outcome = store.encryptAndPublish(plaintext)

        assertTrue(outcome is EncryptedEvidenceOutcome.Success)
        assertNull(outcome.diagnosticOrNull())
        assertFalse(plaintext.exists())
        assertEquals(1, dao.all().size)
    }

    @Test
    fun diagnosticTextIsSanitized() = runTest {
        val filesDir = filesDir()
        val plaintext = plaintext(filesDir)
        val dao = FakeEvidenceMetadataDao()
        val recorder = RecordingEvidenceDiagnosticLogger()
        val failingKey = object : EvidenceMasterKey {
            override val version: Int = 1
            override fun wrap(dataKey: ByteArray): WrappedKey =
                throw IllegalStateException("secret-path /data/user/0 key-material")
            override fun unwrap(wrapped: WrappedKey): ByteArray =
                throw IllegalStateException("not reached")
        }
        val store = store(
            filesDir,
            dao,
            recorder,
            engine(
                recorder,
                keyAccess = TestKeyAccess(emptyList(), currentVersion = 1, encryptionKey = failingKey)
            )
        )

        val outcome = store.encryptAndPublish(plaintext) as EncryptedEvidenceOutcome.Failure
        val text = outcome.diagnostic!!.toDisplayText()

        assertTrue(text.contains("WRAP_DATA_KEY"))
        assertTrue(text.contains("IllegalStateException"))
        // No private paths, raw exception messages or key material may leak.
        assertFalse(text.contains(plaintext.path))
        assertFalse(text.contains(filesDir.path))
        assertFalse(text.contains("secret-path"))
        assertFalse(text.contains("key-material"))
        assertFalse(text.contains("/data/user/0"))
    }

    @Test
    fun silentLoggerProducesNoDiagnosticAndKeepsOutcomeShape() = runTest {
        val filesDir = filesDir()
        val plaintext = plaintext(filesDir)
        val dao = FakeEvidenceMetadataDao()
        val failingKey = object : EvidenceMasterKey {
            override val version: Int = 1
            override fun wrap(dataKey: ByteArray): WrappedKey =
                throw NullPointerException("simulated")
            override fun unwrap(wrapped: WrappedKey): ByteArray =
                throw IllegalStateException("not reached")
        }
        // No recorder installed: outcome must equal the original diagnostic-free shape.
        val store = EncryptedEvidenceStore(
            filesDir = filesDir,
            encryptor = Nge1EvidenceEncryptor(
                EvidenceEncryptionEngine(
                    TestKeyAccess(emptyList(), currentVersion = 1, encryptionKey = failingKey),
                    JvmAtomicFilePublisher(),
                    16
                )
            ),
            metadataRepository = RoomEvidenceMetadataRepository(dao),
            encryptedDirectory = "evidence-encrypted/photos",
            mediaType = EvidenceType.PHOTO,
            newEncryptedName = { "fixed.ng1" }
        )

        val outcome = store.encryptAndPublish(plaintext)

        assertEquals(
            EncryptedEvidenceOutcome.Failure(EncryptedEvidenceFailure.ENCRYPTION_FAILED),
            outcome
        )
        assertTrue(plaintext.isFile)
    }

    private fun store(
        filesDir: File,
        dao: FakeEvidenceMetadataDao,
        recorder: RecordingEvidenceDiagnosticLogger,
        engine: EvidenceEncryptionEngine
    ): EncryptedEvidenceStore = EncryptedEvidenceStore(
        filesDir = filesDir,
        encryptor = Nge1EvidenceEncryptor(engine),
        metadataRepository = RoomEvidenceMetadataRepository(dao),
        encryptedDirectory = "evidence-encrypted/photos",
        mediaType = EvidenceType.PHOTO,
        newEncryptedName = { "fixed.ng1" },
        logger = recorder
    )

    private fun engine(
        recorder: RecordingEvidenceDiagnosticLogger,
        keyAccess: TestKeyAccess = TestKeyAccess(listOf(TestMasterKey.seeded(0x55))),
        publisher: AtomicFilePublisher = JvmAtomicFilePublisher()
    ): EvidenceEncryptionEngine = EvidenceEncryptionEngine(keyAccess, publisher, 16, recorder)

    private fun directory(filesDir: File): File = File(filesDir, "evidence-encrypted/photos")

    private fun filesDir(): File = Files.createTempDirectory("encrypted_diagnostic_test").toFile()

    private fun plaintext(directory: File): File =
        File(directory, "clip.jpg").apply { writeBytes(ByteArray(512) { it.toByte() }) }
}
