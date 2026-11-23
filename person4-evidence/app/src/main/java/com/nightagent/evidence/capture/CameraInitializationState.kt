package com.nightagent.evidence.capture

/** Explicit CameraX setup state shown by photo and video screens. */
enum class CameraInitializationState {
    INITIALIZING,
    READY,
    INITIALIZATION_FAILED,
    UNAVAILABLE
}
