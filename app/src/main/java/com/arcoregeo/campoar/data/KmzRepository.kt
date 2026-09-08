package com.arcoregeo.campoar.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

class KmzRepository(context: Context) {
    private val appContext = context.applicationContext
    private val libraryDir = File(appContext.filesDir, "kmz").apply { mkdirs() }
    private val indexFile = File(libraryDir, "index.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun listDocuments(): List<KmzDocument> = withContext(Dispatchers.IO) {
        readIndex().documents.sortedByDescending { it.importedAtEpochMs }
    }

    /** Content URIs often lack ".ifc" in the path — peek STEP header. */
    suspend fun looksLikeIfc(uri: Uri, displayName: String = "", mimeType: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            val name = displayName.ifBlank { queryDisplayName(uri).orEmpty() }.lowercase()
            val mime = (mimeType ?: appContext.contentResolver.getType(uri)).orEmpty().lowercase()
            if (name.endsWith(".ifc") || name.contains(".ifc") || uri.toString().lowercase().contains(".ifc")) {
                return@withContext true
            }
            if (mime.contains("ifc") || mime.contains("step") || mime == "application/x-step") {
                return@withContext true
            }
            appContext.contentResolver.openInputStream(uri)?.use { stream ->
                val buf = ByteArray(48)
                val n = stream.read(buf)
                if (n <= 0) return@use false
                val head = buf.decodeToString(0, n).trimStart().uppercase()
                head.startsWith("ISO-10303-21")
            } ?: false
        }

    fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
        }.getOrNull()
    }

    suspend fun importFromUri(uri: Uri, displayName: String): KmzDocument = withContext(Dispatchers.IO) {
        if (looksLikeIfc(uri, displayName)) {
            error("IFC_FILE")
        }
        val resolvedName = displayName.ifBlank { queryDisplayName(uri) ?: "archivo.kml" }
        appContext.contentResolver.openInputStream(uri)?.use { stream ->
            importFromStream(resolvedName, stream)
        } ?: error("No se pudo abrir el archivo compartido")
    }

    /** Reads the georeferenced footprint out of an IFC solid. */
    suspend fun importIfcFromUri(uri: Uri, displayName: String): KmzDocument = withContext(Dispatchers.IO) {
        val name = displayName.ifBlank { queryDisplayName(uri) ?: "modelo.ifc" }
        val text = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: error("No se pudo abrir el IFC")
        store(IfcGeoParser.parse(name, text))
    }

    suspend fun importFromStream(displayName: String, stream: java.io.InputStream): KmzDocument =
        withContext(Dispatchers.IO) {
            val bytes = stream.readBytes()
            val head = bytes.decodeToString(0, minOf(48, bytes.size)).trimStart().uppercase()
            if (head.startsWith("ISO-10303-21") || displayName.lowercase().endsWith(".ifc")) {
                error("IFC_FILE")
            }
            val parsed = KmzParser.parse(displayName, ByteArrayInputStream(bytes))
            if (parsed.featureCount == 0) {
                error("El archivo no contiene puntos, líneas ni polígonos")
            }
            store(parsed)
        }

    private fun store(document: KmzDocument): KmzDocument {
        val storedName = "${UUID.randomUUID()}.json"
        val stored = document.copy(storedFileName = storedName)
        File(libraryDir, storedName).writeText(json.encodeToString(KmzDocument.serializer(), stored))
        val index = readIndex()
        writeIndex(index.copy(documents = index.documents + stored))
        return stored
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val index = readIndex()
        val remaining = index.documents.filterNot { it.id == id }
        index.documents.firstOrNull { it.id == id }?.storedFileName?.let { name ->
            File(libraryDir, name).delete()
        }
        writeIndex(index.copy(documents = remaining))
    }

    private fun readIndex(): LibraryIndex {
        if (!indexFile.exists()) return LibraryIndex()
        return runCatching {
            json.decodeFromString(LibraryIndex.serializer(), indexFile.readText())
        }.getOrElse { LibraryIndex() }
    }

    private fun writeIndex(index: LibraryIndex) {
        indexFile.writeText(json.encodeToString(LibraryIndex.serializer(), index))
    }
}
