package com.example.nightagent.guardian.utils

fun normalizePhoneNumber(phone: String): String {
    var normalized = phone
        .replace(" ", "")
        .replace("-", "")
        .replace("(", "")
        .replace(")", "")
    if (normalized.startsWith("+91")) {
        normalized = normalized.substring(3)
    }
    return normalized
}