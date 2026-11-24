package com.example.nightagent.model

data class Guardian(
    val guardianId: String,
    val userId: String?,
    val name: String,
    val phoneNumber: String,
    val email: String?,
    val relationship: Relationship,
    val isPrimary: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

fun Guardian.isValidPhone(): Boolean {
    return phoneNumber.matches(Regex("^\\+?[1-9]\\d{1,14}$")) && phoneNumber.length >= 8
}

fun Guardian.isValidName(): Boolean {
    return name.trim().length >= 2 && name.trim().length <= 50
}

enum class Relationship(val displayName: String, val icon: String = "person") {
    Mother("Mother", "face"),
    Father("Father", "face"),
    Spouse("Spouse", "favorite"),
    Child("Child", "child_care"),
    Sibling("Sibling", "group"),
    Friend("Friend", "person"),
    Other("Other", "person");

    companion object {
        fun fromString(s: String): Relationship = when (s.lowercase()) {
            "mother" -> Mother
            "father" -> Father
            "spouse" -> Spouse
            "child" -> Child
            "sibling" -> Sibling
            "friend" -> Friend
            else -> Other
        }

        val allValues: List<Relationship>
            = listOf(Mother, Father, Spouse, Child, Sibling, Friend, Other)
    }
}