package com.nightagent.evidence.devtest

import com.nightagent.evidence.access.EvidenceAccessController
import com.nightagent.evidence.access.EvidenceAccessToken
import com.nightagent.evidence.access.EvidenceAccessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Sanitized access status; never contains a plaintext path or key material. */
data class AccessTestOutcome(
    val success: Boolean,
    val statusText: String,
    val mediaType: String? = null,
    val plaintextExists: Boolean = false
)

data class AccessCloseOutcome(
    val hadSession: Boolean,
    val cleanedUp: Boolean,
    val statusText: String
)

/**
 * Development-only adapter around the reusable [EvidenceAccessController].
 *
 * It keeps one active token for the current session, reports only booleans and
 * enum names to the UI, and closes outstanding plaintext on every path.
 */
class Phase5AccessTestController(
    private val accessController: EvidenceAccessController,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private var activeToken: EvidenceAccessToken? = null

    fun hasActiveSession(): Boolean = activeToken?.isReleased == false

    suspend fun open(evidenceId: String): AccessTestOutcome = withContext(ioDispatcher) {
        // Never leave two plaintext sessions alive at once.
        closeActive()

        val result = try {
            accessController.open(evidenceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext AccessTestOutcome(
                success = false,
                statusText = "Access failed (${e.javaClass.simpleName}); no plaintext retained"
            )
        }

        when (result) {
            is EvidenceAccessResult.Ready -> {
                activeToken = result.token
                AccessTestOutcome(
                    success = true,
                    statusText = "Opened. Temporary plaintext exists: ${result.token.plaintextFile.isFile}",
                    mediaType = result.token.mediaType.name,
                    plaintextExists = result.token.plaintextFile.isFile
                )
            }

            is EvidenceAccessResult.Denied -> AccessTestOutcome(
                success = false,
                statusText = "Integrity check did not pass: ${result.status.name}. No decryption attempted."
            )

            is EvidenceAccessResult.Failed -> AccessTestOutcome(
                success = false,
                statusText = "Access failed at ${result.stage.name} " +
                    "(${result.cause?.javaClass?.simpleName ?: "no exception"}). No plaintext retained."
            )
        }
    }

    /** Idempotent; reports whether the temporary file was actually removed. */
    fun close(): AccessCloseOutcome {
        val token = activeToken ?: return AccessCloseOutcome(
            hadSession = false,
            cleanedUp = true,
            statusText = "No active access session"
        )
        val file = token.plaintextFile
        token.close()
        activeToken = null
        val cleanedUp = !file.exists()
        return AccessCloseOutcome(
            hadSession = true,
            cleanedUp = cleanedUp,
            statusText = if (cleanedUp) {
                "Access session closed; temporary plaintext removed"
            } else {
                "Access session closed; temporary plaintext still present"
            }
        )
    }

    /** Lifecycle teardown path: reuse the controller's bulk release. */
    fun releaseAll() {
        activeToken = null
        accessController.releaseAll()
    }

    private fun closeActive() {
        if (activeToken != null) close()
    }
}
