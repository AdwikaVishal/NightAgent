package com.example.nightagent.guardian

import org.junit.Test
import org.junit.Before
import org.junit.Assert
import com.example.nightagent.model.Guardian
import com.example.nightagent.model.Relationship

class SimpleTest {

    @Before
    fun setup() {
        // setup
    }

    @Test
    fun `relationship mother exists`() {
        Assert.assertNotNull("Mother should exist", Relationship.Mother)
    }

    @Test
    fun `guardian can be created`() {
        val g = Guardian(
            guardianId = "1",
            userId = null,
            name = "Test",
            phoneNumber = "+1234567890",
            email = null,
            relationship = Relationship.Mother,
            isPrimary = false,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        Assert.assertNotNull("guardian created", g)
    }
}