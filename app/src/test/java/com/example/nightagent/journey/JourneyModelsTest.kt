package com.example.nightagent.journey

import com.example.nightagent.journey.model.*
import org.junit.Assert.*
import org.junit.Test

class JourneyModelsTest {

    @Test
    fun journeyPoint_validatesCoordinatesAndTimestamp() {
        assertTrue(JourneyPoint(37.0, -122.0, timestamp = 1).isValid())
        assertFalse(JourneyPoint(91.0, 0.0, timestamp = 1).isValid())
        assertFalse(JourneyPoint(0.0, 181.0, timestamp = 1).isValid())
        assertFalse(JourneyPoint(0.0, 0.0, timestamp = 1).isValid())
        assertFalse(JourneyPoint(37.0, -122.0, timestamp = 0).isValid())
    }

    @Test
    fun journeyStatusEnum_defaultsToIdleOnUnknown() {
        val j = Journey(status = "UNKNOWN")
        assertEquals(JourneyStatus.IDLE, j.statusEnum())
    }

    @Test
    fun checkInStatusEnum_defaultsToPendingOnUnknown() {
        val c = CheckInRecord(status = "BAD")
        assertEquals(CheckInStatus.PENDING, c.statusEnum())
    }

    @Test
    fun defaultConfig_hasSaneValues() {
        val cfg = JourneyConfig()
        assertTrue(cfg.checkInIntervalMinutes >= 1)
        assertTrue(cfg.checkInResponseTimeoutSeconds >= 30)
        assertTrue(cfg.deviationThresholdMeters > 0f)
        assertTrue(cfg.minLocationAccuracyMeters > 0f)
    }
}
