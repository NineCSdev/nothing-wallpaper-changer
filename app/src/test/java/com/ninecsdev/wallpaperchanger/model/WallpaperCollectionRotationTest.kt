package com.ninecsdev.wallpaperchanger.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class WallpaperCollectionRotationTest {

    @Test
    fun perLock_alwaysRotates() {
        val collection = WallpaperCollection(
            name = "Test",
            type = CollectionType.MANUAL,
            rotationFrequency = RotationFrequency.PER_LOCK,
            lastWallpaperChangeAt = ZonedDateTime.of(2026, 3, 14, 10, 0, 0, 0, ZoneId.of("UTC"))
                .toInstant()
                .toEpochMilli()
        )

        val now = ZonedDateTime.of(2026, 3, 14, 10, 1, 0, 0, ZoneId.of("UTC"))
            .toInstant()
            .toEpochMilli()

        assertTrue(collection.shouldRotateAt(now, ZoneId.of("UTC")))
    }

    @Test
    fun perDay_doesNotRotateAgainOnSameDate() {
        val zone = ZoneId.of("UTC")
        val lastChanged = ZonedDateTime.of(2026, 3, 14, 0, 1, 0, 0, zone).toInstant().toEpochMilli()
        val now = ZonedDateTime.of(2026, 3, 14, 23, 59, 0, 0, zone).toInstant().toEpochMilli()

        val collection = WallpaperCollection(
            name = "Daily",
            type = CollectionType.MANUAL,
            rotationFrequency = RotationFrequency.PER_DAY,
            lastWallpaperChangeAt = lastChanged
        )

        assertFalse(collection.shouldRotateAt(now, zone))
    }

    @Test
    fun hourly_doesNotRotateBeforeOneHour() {
        val zone = ZoneId.of("UTC")
        val lastChanged = ZonedDateTime.of(2026, 3, 14, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val now = ZonedDateTime.of(2026, 3, 14, 10, 59, 59, 0, zone).toInstant().toEpochMilli()

        val collection = WallpaperCollection(
            name = "Hourly",
            type = CollectionType.MANUAL,
            rotationFrequency = RotationFrequency.HOURLY,
            lastWallpaperChangeAt = lastChanged
        )

        assertFalse(collection.shouldRotateAt(now, zone))
    }

    @Test
    fun hourly_rotatesAfterOneHour() {
        val zone = ZoneId.of("UTC")
        val lastChanged = ZonedDateTime.of(2026, 3, 14, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val now = ZonedDateTime.of(2026, 3, 14, 11, 0, 0, 0, zone).toInstant().toEpochMilli()

        val collection = WallpaperCollection(
            name = "Hourly",
            type = CollectionType.MANUAL,
            rotationFrequency = RotationFrequency.HOURLY,
            lastWallpaperChangeAt = lastChanged
        )

        assertTrue(collection.shouldRotateAt(now, zone))
    }

    @Test
    fun perDay_rotatesOnNextDate() {
        val zone = ZoneId.of("UTC")
        val lastChanged = ZonedDateTime.of(2026, 3, 14, 23, 59, 0, 0, zone).toInstant().toEpochMilli()
        val now = ZonedDateTime.of(2026, 3, 15, 0, 0, 1, 0, zone).toInstant().toEpochMilli()

        val collection = WallpaperCollection(
            name = "Daily",
            type = CollectionType.MANUAL,
            rotationFrequency = RotationFrequency.PER_DAY,
            lastWallpaperChangeAt = lastChanged
        )

        assertTrue(collection.shouldRotateAt(now, zone))
    }
}
