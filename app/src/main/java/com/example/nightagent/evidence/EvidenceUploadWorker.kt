package com.example.nightagent.evidence

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.nightagent.firebase.FirebaseConfig
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that uploads all evidence files for a given sosId.
 *
 * Firebase Storage layout:
 *   evidence/{userId}/{sosId}/audio/file.m4a
 *   evidence/{userId}/{sosId}/photos/photo.jpg
 *   evidence/{userId}/{sosId}/video/video.mp4
 *
 * Firestore record written to:
 *   evidence/{sosId}  → { userId, sosId, timestamp, fileUrls, hashes }
 */
class EvidenceUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sosId  = inputData.getString(KEY_SOS_ID)  ?: return@withContext Result.failure()
        val userId = inputData.getString(KEY_USER_ID) ?: return@withContext Result.failure()
        if (userId.isBlank() || userId == "unknown") {
            Log.w(TAG, "Refusing to upload evidence without an authenticated user")
            return@withContext Result.failure()
        }

        val evidenceDir = File(applicationContext.getExternalFilesDir(null), "Evidence/$sosId")
        migrateLegacyTempAudio(evidenceDir)
        if (!evidenceDir.exists()) return@withContext Result.failure()

        val evidenceManager = EvidenceManager(applicationContext)
        val uploadedUrls = mutableMapOf<String, String>()
        val hashes       = mutableMapOf<String, String>()
        val fileSizes    = mutableMapOf<String, Long>()

        try {
            // Walk all sub-directories (audio/, photos/, video/)
            evidenceDir.walkTopDown()
                .filter { it.isFile && it.length() > 0 }
                .forEach { file ->
                    val relativePath = file.relativeTo(evidenceDir).path
                    val storagePath  = "evidence/$userId/$sosId/$relativePath"

                    val contentType = contentTypeForFile(file) ?: run {
                        Log.w(TAG, "Skipping $relativePath: unsupported extension '${file.extension}'")
                        return@forEach
                    }
                    val metadata = StorageMetadata.Builder()
                        .setContentType(contentType)
                        .build()
                    val storageRef = FirebaseConfig.storage.reference.child(storagePath)
                    val snapshot   = storageRef.putStream(file.inputStream(), metadata).await()
                    val url        = snapshot.storage.downloadUrl.await().toString()

                    uploadedUrls[relativePath] = url
                    hashes[relativePath]       = evidenceManager.sha256(file)
                    fileSizes[relativePath]    = file.length()

                    Log.d(TAG, "Uploaded: $relativePath → $url")
                }

            // Write Firestore evidence record
            val record = mutableMapOf<String, Any>(
                "userId"    to userId,
                "sosId"     to sosId,
                "timestamp" to System.currentTimeMillis(),
                "fileUrls"  to uploadedUrls,
                "hashes"    to hashes,
                "fileSizes" to fileSizes
            )
            val latitude = inputData.getString(KEY_LATITUDE)?.toDoubleOrNull()
            val longitude = inputData.getString(KEY_LONGITUDE)?.toDoubleOrNull()
            if (latitude != null && longitude != null) {
                record["latitude"] = latitude
                record["longitude"] = longitude
            }
            FirebaseConfig.firestore
                .collection("evidence")
                .document(sosId)
                .set(record, SetOptions.merge())
                .await()

            Log.d(TAG, "Evidence record written for $sosId")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed: ${e.message}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG         = "EvidenceUploadWorker"
        const val KEY_SOS_ID          = "sos_id"
        const val KEY_USER_ID         = "user_id"
        const val KEY_LATITUDE        = "latitude"
        const val KEY_LONGITUDE       = "longitude"
        const val UPLOAD_TAG          = "evidence_upload"

        /**
         * Resolve a Firebase Storage content type from a file's extension.
         * Returns null for unrecognized extensions so that the upload is
         * denied rather than falling back to a permissive type like
         * application/octet-stream.
         */
        fun contentTypeForFile(file: File): String? {
            val ext = file.extension.lowercase()
            return when (ext) {
                "m4a" -> "audio/m4a"
                "aac" -> "audio/aac"
                "mp4" -> "video/mp4"
                "mov" -> "video/quicktime"
                "avi" -> "video/x-msvideo"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                else  -> null
            }
        }

        /**
         * Older recording sessions wrote chunks to Evidence/temp before the SOS
         * id was available. Move them under the canonical session directory so
         * a single upload pass covers all evidence for this SOS.
         */
        private fun migrateLegacyTempAudio(evidenceDir: File) {
            val tempDir = File(evidenceDir.parentFile, "temp")
            val targetDir = File(evidenceDir, "audio")
            val chunks = tempDir.takeIf { it.exists() }
                ?.listFiles { file -> file.isFile && file.extension.equals("m4a", true) }
                ?: return
            chunks.forEach { file ->
                targetDir.mkdirs()
                if (!file.renameTo(File(targetDir, file.name))) {
                    Log.w(TAG, "Could not migrate legacy audio chunk ${file.name}")
                }
            }
        }

        fun enqueue(
            context: Context,
            sosId: String,
            userId: String,
            latitude: Double? = null,
            longitude: Double? = null
        ) {
            if (userId.isBlank() || userId == "unknown") {
                Log.w(TAG, "Not enqueuing evidence upload without an authenticated user")
                return
            }
            val data = workDataOf(
                KEY_SOS_ID to sosId,
                KEY_USER_ID to userId,
                KEY_LATITUDE to latitude?.toString(),
                KEY_LONGITUDE to longitude?.toString()
            )
            val request = OneTimeWorkRequestBuilder<EvidenceUploadWorker>()
                .setInputData(data)
                .addTag(UPLOAD_TAG)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "evidence_upload_$sosId",
                    ExistingWorkPolicy.REPLACE,
                    request
                )
        }
    }
}
