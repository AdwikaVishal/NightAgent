package com.example.nightagent.ui.demo

/**
 * Demo data for screens whose backend is not implemented yet.
 *
 * Everything here is clearly labelled "Demo Data" in the UI so it can never be
 * mistaken for real user data. When the backend for a feature lands, delete the
 * demo lists and swap the screen over to its repository.
 */

// ── Guardians ────────────────────────────────────────────────────────────────

data class Guardian(
    val id: String,
    val name: String,
    val phone: String,
    val relation: String,
    val isPrimary: Boolean,
    val isAvailable: Boolean,
    val lastSeen: String
)

// ── Journeys / check-ins ─────────────────────────────────────────────────────

data class JourneyRecord(
    val id: String,
    val date: String,
    val from: String,
    val to: String,
    val distanceKm: Double,
    val durationMin: Int,
    val checkIns: Int,
    val status: String   // "Completed" | "Completed with alert" | "Ongoing"
)

data class GuardianAlert(
    val id: String,
    val guardianName: String,
    val time: String,
    val message: String,
    val isUnread: Boolean
)

// ── Evidence vault ───────────────────────────────────────────────────────────

data class EvidenceRecord(
    val id: String,
    val sosId: String,
    val date: String,
    val time: String,
    val audioFiles: Int,
    val photoFiles: Int,
    val videoFiles: Int,
    val sizeMb: Double,
    val location: String,
    val sha256Short: String,
    val uploaded: Boolean
)

object DemoData {

    val guardians = listOf(
        Guardian(
            id = "g1", name = "Mom", phone = "+91 98765 43210",
            relation = "Family", isPrimary = true, isAvailable = true,
            lastSeen = "Online now"
        ),
        Guardian(
            id = "g2", name = "Aditi Sharma", phone = "+91 98123 45670",
            relation = "Friend", isPrimary = false, isAvailable = true,
            lastSeen = "Online now"
        ),
        Guardian(
            id = "g3", name = "Rahul Verma", phone = "+91 99001 12233",
            relation = "Roommate", isPrimary = false, isAvailable = false,
            lastSeen = "Last seen 2h ago"
        ),
        Guardian(
            id = "g4", name = "Dad", phone = "+91 98765 11221",
            relation = "Family", isPrimary = false, isAvailable = true,
            lastSeen = "Online now"
        )
    )

    val guardianAlerts = listOf(
        GuardianAlert(
            id = "a1", guardianName = "Aditi Sharma", time = "Today, 9:42 PM",
            message = "Checked in — reached home safely.", isUnread = false
        ),
        GuardianAlert(
            id = "a2", guardianName = "Mom", time = "Yesterday, 11:05 PM",
            message = "Opened your live location link.", isUnread = true
        ),
        GuardianAlert(
            id = "a3", guardianName = "Rahul Verma", time = "12 Mar, 10:18 PM",
            message = "Listened to live audio for 4 min.", isUnread = false
        )
    )

    val journeys = listOf(
        JourneyRecord(
            id = "j1", date = "Today", from = "Office, Tech Park",
            to = "Home, MG Road", distanceKm = 2.4, durationMin = 32,
            checkIns = 3, status = "Ongoing"
        ),
        JourneyRecord(
            id = "j2", date = "Yesterday", from = "Cafe Blue",
            to = "Hostel", distanceKm = 1.1, durationMin = 14,
            checkIns = 1, status = "Completed"
        ),
        JourneyRecord(
            id = "j3", date = "13 Mar", from = "Metro Station",
            to = "Home, MG Road", distanceKm = 0.8, durationMin = 11,
            checkIns = 1, status = "Completed with alert"
        ),
        JourneyRecord(
            id = "j4", date = "12 Mar", from = "Library",
            to = "Home, MG Road", distanceKm = 1.9, durationMin = 25,
            checkIns = 2, status = "Completed"
        )
    )

    val evidenceRecords = listOf(
        EvidenceRecord(
            id = "e1", sosId = "1710340200000", date = "13 Mar 2024", time = "10:18 PM",
            audioFiles = 6, photoFiles = 3, videoFiles = 1, sizeMb = 24.6,
            location = "12.9716, 77.5946", sha256Short = "9f2c…a41b", uploaded = true
        ),
        EvidenceRecord(
            id = "e2", sosId = "1710253800000", date = "12 Mar 2024", time = "11:05 PM",
            audioFiles = 4, photoFiles = 0, videoFiles = 0, sizeMb = 9.8,
            location = "12.9352, 77.6245", sha256Short = "c07d…88e2", uploaded = true
        ),
        EvidenceRecord(
            id = "e3", sosId = "1710167400000", date = "11 Mar 2024", time = "9:31 PM",
            audioFiles = 2, photoFiles = 1, videoFiles = 0, sizeMb = 6.1,
            location = "12.9081, 77.6476", sha256Short = "31ab…f0c9", uploaded = false
        )
    )
}
