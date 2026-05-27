package com.showerideas.aura.data.local

import androidx.room.TypeConverter
import com.showerideas.aura.model.ProfileType

/**
 * Room type converters for non-primitive column types.
 *
 * Register on [AppDatabase] via `@TypeConverters(Converters::class)`.
 *
 * Covered types
 * - [ProfileType] — stored as its [Enum.name] string (e.g. "PERSONAL", "WORK").
 *   The DEFAULT clause in every migration that adds this column uses the same
 *   name-based string, so persisted rows round-trip cleanly.
 *
 * ByteArray? — NOT covered here
 * Room maps `ByteArray` to BLOB natively; nullable `ByteArray?` is also handled
 * without a converter. No entry needed for [KnownPeer.rotationCertificate].
 */
class Converters {

    // ProfileType

    @TypeConverter
    fun fromProfileType(type: ProfileType): String = type.name

    /**
     * Converts a stored string back to [ProfileType].
     *
     * Falls back to [ProfileType.PERSONAL] for any unrecognised value so that
     * a schema addition (e.g. future CUSTOM_2) in a forward-compatible build
     * doesn't crash older installed versions reading the same database file.
     */
    @TypeConverter
    fun toProfileType(name: String): ProfileType =
        runCatching { ProfileType.valueOf(name) }.getOrDefault(ProfileType.PERSONAL)
}
