package com.revio.server.core.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Wire format is `HH:mm` (e.g. "08:00"), not [DateTimeFormatter.ISO_LOCAL_TIME]'s `HH:mm:ss` —
 * [LocalTime] always carries an explicit (possibly zero) second field, so ISO_LOCAL_TIME never
 * omits it the way it does for a genuinely absent value. Minute precision is all quiet-hours
 * settings need.
 */
object LocalTimeSerializer : KSerializer<LocalTime> {
    private val formatter = DateTimeFormatter.ofPattern("HH:mm")

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LocalTime", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalTime) {
        encoder.encodeString(value.format(formatter))
    }

    override fun deserialize(decoder: Decoder): LocalTime {
        return LocalTime.parse(decoder.decodeString(), formatter)
    }
}
