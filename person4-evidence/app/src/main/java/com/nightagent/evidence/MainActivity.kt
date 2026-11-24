package com.nightagent.evidence

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nightagent.evidence.capture.AudioRecordingScreen
import com.nightagent.evidence.capture.CameraScreen
import com.nightagent.evidence.capture.VideoRecordingScreen
import com.nightagent.evidence.access.EvidenceAccessController
import com.nightagent.evidence.devtest.Phase5AccessTestController
import com.nightagent.evidence.devtest.Phase5DeletionTestController
import com.nightagent.evidence.devtest.SharedPreferencesPhase5TestRecordStore
import com.nightagent.evidence.crypto.AndroidAtomicFilePublisher
import com.nightagent.evidence.crypto.AndroidEvidenceDiagnosticLogger
import com.nightagent.evidence.crypto.AndroidEvidenceKeyManager
import com.nightagent.evidence.crypto.EvidenceEncryptionEngine
import com.nightagent.evidence.crypto.RecordingEvidenceDiagnosticLogger
import com.nightagent.evidence.consent.ConsentGate
import com.nightagent.evidence.consent.ConsentScreen
import com.nightagent.evidence.consent.PersistentConsentRepository
import com.nightagent.evidence.integrity.EvidenceIntegrityVerifier
import com.nightagent.evidence.integration.CombinedEvidenceAuthorization
import com.nightagent.evidence.integration.EvidenceAuthorization
import com.nightagent.evidence.metadata.EvidenceMetadata
import com.nightagent.evidence.metadata.EvidenceMetadataDatabase
import com.nightagent.evidence.metadata.RoomEvidenceMetadataRepository
import com.nightagent.evidence.permission.AndroidPermissionStateProvider
import com.nightagent.evidence.permission.rememberPermissionRequester
import com.nightagent.evidence.retention.EvidenceRetentionController
import com.nightagent.evidence.storage.EncryptedAudioStore
import com.nightagent.evidence.storage.EncryptedPhotoStore
import com.nightagent.evidence.storage.EncryptedVideoStore
import com.nightagent.evidence.storage.LocalAudioStore
import com.nightagent.evidence.storage.LocalPhotoStore
import com.nightagent.evidence.storage.LocalVideoStore
import com.nightagent.evidence.storage.Nge1EvidenceEncryptor
import com.nightagent.evidence.ui.EvidenceReadyScreen
import com.nightagent.evidence.ui.theme.EvidenceTheme
import com.nightagent.evidence.vault.EvidenceDetailsScreen
import com.nightagent.evidence.vault.EvidenceVaultScreen

private enum class EvidenceScreen {
    READY,
    CONSENT,
    CAMERA,
    AUDIO,
    VIDEO,
    VAULT,
    EVIDENCE_DETAILS
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EvidenceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // One shared instance of each Phase 2 component; no duplicate
                    // consent repository, permission system or requester.
                    val consentRepository = remember {
                        PersistentConsentRepository.create(this@MainActivity)
                    }
                    val permissionStateProvider = remember {
                        AndroidPermissionStateProvider(this@MainActivity)
                    }
                    val permissionRequester = rememberPermissionRequester()
                    val authorization = remember {
                        EvidenceAuthorization(
                            ConsentGate(consentRepository),
                            permissionStateProvider
                        )
                    }
                    // Video with audio needs two consent decisions, so it uses a
                    // thin adapter over the same shared authorization instance.
                    val videoAuthorization = remember {
                        CombinedEvidenceAuthorization(authorization)
                    }
                    // Photo, audio and video publication share one Keystore-backed
                    // engine, one no-replace publisher and one NGE1 adapter.
                    // The recording logger forwards to logcat AND remembers the
                    // first failure of each capture so the capture screens can
                    // show a sanitized diagnostic in-app (no ADB required).
                    val diagnosticLogger = remember {
                        RecordingEvidenceDiagnosticLogger(AndroidEvidenceDiagnosticLogger())
                    }
                    val evidenceKeyManager = remember { AndroidEvidenceKeyManager(logger = diagnosticLogger) }
                    val atomicFilePublisher = remember {
                        AndroidAtomicFilePublisher(logger = diagnosticLogger)
                    }
                    val encryptionEngine = remember {
                        EvidenceEncryptionEngine(
                            keyAccess = evidenceKeyManager,
                            publisher = atomicFilePublisher,
                            logger = diagnosticLogger
                        )
                    }
                    val evidenceEncryptor = remember { Nge1EvidenceEncryptor(encryptionEngine) }
                    // One process-wide Room database and repository are shared by
                    // photo, audio and video; no per-capture database is created.
                    val metadataRepository = remember {
                        RoomEvidenceMetadataRepository(
                            EvidenceMetadataDatabase.getInstance(this@MainActivity).metadataDao()
                        )
                    }
                    // The Vault reuses the same engine and repository; verification
                    // is read-only and never opens another database.
                    val integrityVerifier = remember {
                        EvidenceIntegrityVerifier.withEngine(
                            filesDir = filesDir,
                            metadataRepository = metadataRepository,
                            engine = encryptionEngine
                        )
                    }
                    // Phase 5 reusable controllers, also used by the isolated
                    // physical-device validation UI in this harness only.
                    val accessController = remember {
                        EvidenceAccessController(
                            filesDir,
                            metadataRepository,
                            integrityVerifier,
                            encryptionEngine
                        )
                    }
                    val retentionController = remember {
                        EvidenceRetentionController(filesDir, metadataRepository)
                    }
                    val accessTestController = remember {
                        Phase5AccessTestController(accessController)
                    }
                    val deletionTestController = remember {
                        Phase5DeletionTestController(
                            filesDir = filesDir,
                            encryptor = evidenceEncryptor,
                            metadataRepository = metadataRepository,
                            retentionController = retentionController,
                            recordStore = SharedPreferencesPhase5TestRecordStore(
                                this@MainActivity
                            )
                        )
                    }
                    // Activity teardown always releases any temporary plaintext.
                    DisposableEffect(Unit) {
                        onDispose { accessController.releaseAll() }
                    }

                    val photoStore = remember { LocalPhotoStore(filesDir) }
                    val encryptedPhotoStore = remember {
                        EncryptedPhotoStore(
                            filesDir,
                            evidenceEncryptor,
                            metadataRepository,
                            logger = diagnosticLogger
                        )
                    }
                    val audioStore = remember { LocalAudioStore(filesDir) }
                    val encryptedAudioStore = remember {
                        EncryptedAudioStore(
                            filesDir,
                            evidenceEncryptor,
                            metadataRepository,
                            logger = diagnosticLogger
                        )
                    }
                    val videoStore = remember { LocalVideoStore(filesDir) }
                    val encryptedVideoStore = remember {
                        EncryptedVideoStore(
                            filesDir,
                            evidenceEncryptor,
                            metadataRepository,
                            logger = diagnosticLogger
                        )
                    }

                    var currentScreen by remember { mutableStateOf(EvidenceScreen.READY) }
                    var selectedEvidence by remember { mutableStateOf<EvidenceMetadata?>(null) }

                    when (currentScreen) {
                        EvidenceScreen.READY -> EvidenceReadyScreen(
                            onManageConsent = { currentScreen = EvidenceScreen.CONSENT },
                            onOpenCamera = { currentScreen = EvidenceScreen.CAMERA },
                            onOpenAudio = { currentScreen = EvidenceScreen.AUDIO },
                            onOpenVideo = { currentScreen = EvidenceScreen.VIDEO },
                            onOpenVault = { currentScreen = EvidenceScreen.VAULT }
                        )

                        EvidenceScreen.CONSENT -> ConsentScreen(
                            repository = consentRepository,
                            permissionStateProvider = permissionStateProvider,
                            permissionRequester = permissionRequester,
                            onBack = { currentScreen = EvidenceScreen.READY }
                        )

                        EvidenceScreen.CAMERA -> CameraScreen(
                            authorization = authorization,
                            permissionStateProvider = permissionStateProvider,
                            permissionRequester = permissionRequester,
                            photoStore = photoStore,
                            encryptedPhotoStore = encryptedPhotoStore,
                            onBack = { currentScreen = EvidenceScreen.READY },
                            onOpenConsent = { currentScreen = EvidenceScreen.CONSENT }
                        )

                        EvidenceScreen.AUDIO -> AudioRecordingScreen(
                            authorization = authorization,
                            permissionStateProvider = permissionStateProvider,
                            permissionRequester = permissionRequester,
                            audioStore = audioStore,
                            encryptedAudioStore = encryptedAudioStore,
                            onBack = { currentScreen = EvidenceScreen.READY },
                            onOpenConsent = { currentScreen = EvidenceScreen.CONSENT }
                        )

                        EvidenceScreen.VIDEO -> VideoRecordingScreen(
                            videoAuthorization = videoAuthorization,
                            permissionStateProvider = permissionStateProvider,
                            permissionRequester = permissionRequester,
                            videoStore = videoStore,
                            encryptedVideoStore = encryptedVideoStore,
                            onBack = { currentScreen = EvidenceScreen.READY },
                            onOpenConsent = { currentScreen = EvidenceScreen.CONSENT }
                        )

                        EvidenceScreen.VAULT -> EvidenceVaultScreen(
                            repository = metadataRepository,
                            deletionTestController = deletionTestController,
                            onOpenDetails = { record ->
                                selectedEvidence = record
                                currentScreen = EvidenceScreen.EVIDENCE_DETAILS
                            },
                            onBack = { currentScreen = EvidenceScreen.READY }
                        )

                        EvidenceScreen.EVIDENCE_DETAILS -> {
                            val selected = selectedEvidence
                            if (selected != null) {
                                EvidenceDetailsScreen(
                                    initialRecord = selected,
                                    repository = metadataRepository,
                                    verifier = integrityVerifier,
                                    accessTestController = accessTestController,
                                    onBack = { currentScreen = EvidenceScreen.VAULT }
                                )
                            } else {
                                LaunchedEffect(Unit) {
                                    currentScreen = EvidenceScreen.VAULT
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
