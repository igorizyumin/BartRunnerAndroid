package `in`.izyum.bart.data

import `in`.izyum.bart.model.Itinerary
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Pure file-backed persistence for the disposable followed-trip state. */
class FollowedTripStore @JvmOverloads constructor(
    private val storageFile: File,
    private val objectMapper: ObjectMapper = ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
) {
    fun loadItinerary(): Itinerary? {
        if (!storageFile.exists()) return null
        return try {
            storageFile.inputStream().use { input ->
                objectMapper.readValue(input, FollowedTripRecord::class.java).toItinerary()
            }
        } catch (exception: Exception) {
            delete()
            null
        }
    }

    fun saveItinerary(itinerary: Itinerary?) {
        if (itinerary == null) {
            delete()
            return
        }

        saveRecord(FollowedTripRecord.fromItinerary(itinerary))
    }

    private fun saveRecord(record: FollowedTripRecord) {
        val parent = storageFile.parentFile ?: File(".")
        parent.mkdirs()
        val temporaryFile = File(parent, storageFile.name + ".tmp")
        try {
            temporaryFile.outputStream().use { output ->
                objectMapper.writeValue(output, record)
            }
            replaceStorageFile(temporaryFile)
        } catch (exception: Exception) {
            temporaryFile.delete()
            throw exception
        }
    }

    private fun replaceStorageFile(temporaryFile: File) {
        try {
            Files.move(
                temporaryFile.toPath(),
                storageFile.toPath(),
                REPLACE_EXISTING,
                ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporaryFile.toPath(),
                storageFile.toPath(),
                REPLACE_EXISTING,
            )
        }
    }

    private fun delete() {
        if (storageFile.exists() && !storageFile.delete()) {
            throw IllegalStateException("Could not delete followed trip state")
        }
    }
}
