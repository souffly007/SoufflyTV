package fr.bonobo.soufflytv.data

import fr.bonobo.soufflytv.model.Source
import fr.bonobo.soufflytv.platform.readSecureFile
import fr.bonobo.soufflytv.platform.writeAppFile
import fr.bonobo.soufflytv.platform.writeSecureFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Sources enregistrées dans sources.json (chiffré sur Android).
 * Le fichier n'existe pas au premier lancement : aucune liste n'est fournie.
 */
class SourceRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        encodeDefaults = true
    }
    private val serializer = ListSerializer(Source.serializer())
    private val mutex = Mutex()

    private val _sources = MutableStateFlow<List<Source>>(emptyList())
    val sources: StateFlow<List<Source>> = _sources.asStateFlow()

    suspend fun load() = mutex.withLock {
        val text = readSecureFile(FILE) ?: return@withLock
        try {
            _sources.value = json.decodeFromString(serializer, text)
        } catch (e: Exception) {
            // Fichier abîmé : on le met de côté au lieu de l'écraser
            writeAppFile("$FILE.bak", text)
            _sources.value = emptyList()
        }
    }

    suspend fun save(source: Source) = mutex.withLock {
        val current = _sources.value
        val index = current.indexOfFirst { it.id == source.id }
        _sources.value = if (index >= 0) current.toMutableList().also { it[index] = source } else current + source
        persist()
    }

    suspend fun remove(id: String) = mutex.withLock {
        _sources.value = _sources.value.filterNot { it.id == id }
        persist()
    }

    private suspend fun persist() {
        writeSecureFile(FILE, json.encodeToString(serializer, _sources.value))
    }

    private companion object {
        const val FILE = "sources.json"
    }
}
