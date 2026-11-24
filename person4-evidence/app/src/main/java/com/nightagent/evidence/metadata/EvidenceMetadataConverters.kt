package com.nightagent.evidence.metadata

import androidx.room.TypeConverter
import com.nightagent.evidence.model.EvidenceType

/** Stores small enums as stable names so the schema remains readable. */
class EvidenceMetadataConverters {

    @TypeConverter
    fun mediaTypeToString(value: EvidenceType): String = value.name

    @TypeConverter
    fun stringToMediaType(value: String): EvidenceType = EvidenceType.valueOf(value)

    @TypeConverter
    fun registrationStatusToString(value: RegistrationStatus): String = value.name

    @TypeConverter
    fun stringToRegistrationStatus(value: String): RegistrationStatus =
        RegistrationStatus.valueOf(value)
}
