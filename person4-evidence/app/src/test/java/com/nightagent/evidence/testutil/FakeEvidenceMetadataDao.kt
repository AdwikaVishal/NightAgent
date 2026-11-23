package com.nightagent.evidence.testutil

import com.nightagent.evidence.metadata.EvidenceMetadataDao
import com.nightagent.evidence.metadata.EvidenceMetadataEntity
import com.nightagent.evidence.metadata.RegistrationStatus
import com.nightagent.evidence.model.EvidenceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** In-memory DAO used to exercise repository logic on the JVM. */
class FakeEvidenceMetadataDao : EvidenceMetadataDao {

    private val rows = LinkedHashMap<String, EvidenceMetadataEntity>()
    private val rowsFlow = MutableStateFlow<List<EvidenceMetadataEntity>>(emptyList())

    var findError: Exception? = null
    var insertError: Exception? = null
    var updateError: Exception? = null
    var deleteError: Exception? = null
    var observeError: Exception? = null
    var insertHook: ((EvidenceMetadataEntity) -> Unit)? = null

    override suspend fun findById(evidenceId: String): EvidenceMetadataEntity? {
        findError?.let { throw it }
        return rows[evidenceId]
    }

    override suspend fun insert(entity: EvidenceMetadataEntity) {
        insertHook?.invoke(entity)
        insertError?.let { throw it }
        if (rows.containsKey(entity.evidenceId)) {
            throw IllegalStateException("Duplicate evidence id: ${entity.evidenceId}")
        }
        rows[entity.evidenceId] = entity
        publish()
    }

    override suspend fun updateRegistrationStatus(
        evidenceId: String,
        status: RegistrationStatus
    ): Int {
        updateError?.let { throw it }
        val current = rows[evidenceId] ?: return 0
        rows[evidenceId] = current.copy(registrationStatus = status)
        publish()
        return 1
    }

    override suspend fun deleteById(evidenceId: String): Int {
        deleteError?.let { throw it }
        if (!rows.containsKey(evidenceId)) return 0
        rows.remove(evidenceId)
        publish()
        return 1
    }

    override suspend fun all(): List<EvidenceMetadataEntity> = rows.values.toList()

    override fun observeAll(): Flow<List<EvidenceMetadataEntity>> {
        val error = observeError
        if (error != null) return flow { throw error }
        return rowsFlow
    }

    override fun observeByMediaType(
        mediaType: EvidenceType
    ): Flow<List<EvidenceMetadataEntity>> = observeAll().map { entities ->
        entities.filter { it.mediaType == mediaType }
    }

    fun stored(evidenceId: String): EvidenceMetadataEntity? = rows[evidenceId]

    fun seed(entity: EvidenceMetadataEntity) {
        rows[entity.evidenceId] = entity
        publish()
    }

    private fun publish() {
        rowsFlow.value = rows.values.sortedByDescending { it.createdAtMillis }
    }
}
