package com.dougkeen.bart.data

import com.dougkeen.bart.model.Departure
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

/** Pure file-backed persistence for the disposable followed-trip state. */
class FollowedTripStore @JvmOverloads constructor(
    private val storageFile: File,
    private val objectMapper: ObjectMapper = ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
) {
    fun load(): Departure? {
        if (!storageFile.exists()) {
            return null
        }
        return try {
            storageFile.inputStream().use { input ->
                objectMapper.readValue(input, FollowedTripRecord::class.java)
                    .toDeparture()
            }
        } catch (exception: Exception) {
            delete()
            null
        }
    }

    fun save(departure: Departure?) {
        if (departure == null) {
            delete()
            return
        }

        storageFile.parentFile?.mkdirs()
        val temporaryFile = File(storageFile.parentFile,
            storageFile.name + ".tmp")
        try {
            temporaryFile.outputStream().use { output ->
                objectMapper.writeValue(output,
                    FollowedTripRecord.fromDeparture(departure))
            }
            if (storageFile.exists() && !storageFile.delete()) {
                throw IllegalStateException("Could not replace followed trip state")
            }
            if (!temporaryFile.renameTo(storageFile)) {
                throw IllegalStateException("Could not save followed trip state")
            }
        } catch (exception: Exception) {
            temporaryFile.delete()
            throw exception
        }
    }

    private fun delete() {
        if (storageFile.exists() && !storageFile.delete()) {
            throw IllegalStateException("Could not delete followed trip state")
        }
    }
}
