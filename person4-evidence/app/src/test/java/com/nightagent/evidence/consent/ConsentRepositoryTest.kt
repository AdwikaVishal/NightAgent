package com.nightagent.evidence.consent

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.nightagent.evidence.model.ConsentKey
import com.nightagent.evidence.model.EvidenceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ConsentRepositoryTest {

    private class StoreHandle(file: File) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file }
        )
    }

    private fun newFile(): File =
        File.createTempFile("consent_test_${UUID.randomUUID()}", ".preferences_pb").apply {
            deleteOnExit()
        }

    @Test
    fun consent_is_absent_and_disabled_by_default() = runTest {
        val handle = StoreHandle(newFile())
        val repository = PersistentConsentRepository(handle.store)

        val record = repository.get(ConsentKey.capture(EvidenceType.PHOTO))

        assertFalse(record.isGranted)
        assertNull(record.grantedAt)
        assertNull(record.revokedAt)
        assertEquals(0, record.version)
        handle.scope.cancel()
    }

    @Test
    fun granting_consent_records_timestamp_and_version() = runTest {
        val handle = StoreHandle(newFile())
        val repository = PersistentConsentRepository(handle.store)
        val key = ConsentKey.capture(EvidenceType.AUDIO)

        repository.grant(key)
        val record = repository.get(key)

        assertTrue(record.isGranted)
        assertNotNull(record.grantedAt)
        assertNull(record.revokedAt)
        assertEquals(ConsentTerms.CURRENT_VERSION, record.version)
        handle.scope.cancel()
    }

    @Test
    fun revoking_consent_persists_revoked_timestamp() = runTest {
        val handle = StoreHandle(newFile())
        val repository = PersistentConsentRepository(handle.store)
        val key = ConsentKey.capture(EvidenceType.PHOTO)

        repository.grant(key)
        repository.revoke(key)
        val record = repository.get(key)

        assertFalse(record.isGranted)
        assertNotNull(record.revokedAt)
        assertEquals(ConsentTerms.CURRENT_VERSION, record.version)
        handle.scope.cancel()
    }

    @Test
    fun consent_survives_repository_recreation() = runTest {
        val handle = StoreHandle(newFile())
        val firstRepository = PersistentConsentRepository(handle.store)
        val key = ConsentKey.capture(EvidenceType.VIDEO)

        firstRepository.grant(key)
        val recreatedRepository = PersistentConsentRepository(handle.store)
        val record = recreatedRepository.get(key)

        assertTrue(record.isGranted)
        assertEquals(ConsentTerms.CURRENT_VERSION, record.version)
        handle.scope.cancel()
    }

    @Test
    fun consent_survives_new_datastore_over_same_file() = runTest {
        val file = newFile()
        val first = StoreHandle(file)
        PersistentConsentRepository(first.store)
            .grant(ConsentKey.capture(EvidenceType.PHOTO))
        first.scope.cancel()
        first.scope.coroutineContext[Job]?.join()

        val second = StoreHandle(file)
        val record = PersistentConsentRepository(second.store)
            .get(ConsentKey.capture(EvidenceType.PHOTO))

        assertTrue(record.isGranted)
        assertEquals(ConsentTerms.CURRENT_VERSION, record.version)
        second.scope.cancel()
    }

    @Test
    fun capture_consents_are_independent_per_evidence_type() = runTest {
        val handle = StoreHandle(newFile())
        val repository = PersistentConsentRepository(handle.store)
        val photo = ConsentKey.capture(EvidenceType.PHOTO)
        val audio = ConsentKey.capture(EvidenceType.AUDIO)
        val video = ConsentKey.capture(EvidenceType.VIDEO)

        repository.grant(photo)

        assertTrue(repository.get(photo).isGranted)
        assertFalse(repository.get(audio).isGranted)
        assertFalse(repository.get(video).isGranted)
        handle.scope.cancel()
    }

    @Test
    fun upload_consent_is_independent_of_capture_consent() = runTest {
        val handle = StoreHandle(newFile())
        val repository = PersistentConsentRepository(handle.store)

        repository.grant(ConsentKey.capture(EvidenceType.PHOTO))
        repository.grant(ConsentKey.capture(EvidenceType.AUDIO))
        repository.grant(ConsentKey.capture(EvidenceType.VIDEO))

        assertFalse(repository.get(ConsentKey.CLOUD_UPLOAD).isGranted)

        repository.grant(ConsentKey.CLOUD_UPLOAD)
        assertTrue(repository.get(ConsentKey.CLOUD_UPLOAD).isGranted)
        handle.scope.cancel()
    }

    @Test
    fun revoking_upload_does_not_revoke_capture_consent() = runTest {
        val handle = StoreHandle(newFile())
        val repository = PersistentConsentRepository(handle.store)
        val photo = ConsentKey.capture(EvidenceType.PHOTO)

        repository.grant(photo)
        repository.grant(ConsentKey.CLOUD_UPLOAD)
        repository.revoke(ConsentKey.CLOUD_UPLOAD)

        assertTrue(repository.get(photo).isGranted)
        assertFalse(repository.get(ConsentKey.CLOUD_UPLOAD).isGranted)
        handle.scope.cancel()
    }
}
