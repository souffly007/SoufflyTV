package fr.bonobo.soufflytv.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.bonobo.soufflytv.model.Channel
import fr.bonobo.soufflytv.platform.cancelReminderNotification
import fr.bonobo.soufflytv.platform.readAppFile
import fr.bonobo.soufflytv.platform.scheduleReminderNotification
import fr.bonobo.soufflytv.platform.writeAppFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Rappel d'une émission à venir (guide TV) : la chaîne à lancer et l'émission. */
@Serializable
data class Reminder(
    val sourceId: String,
    val channel: Channel,
    /** Nom affiché de la chaîne (« TF1 », sans « FHD »…). */
    val channelName: String,
    val title: String,
    val start: Long,
    val stop: Long,
) {
    val key: String get() = "$sourceId|${channel.id}|$start"
}

@Serializable
private data class ReminderFile(val reminders: List<Reminder> = emptyList())

/** Rappels enregistrés (reminders.json) ; sur Android, une notification est aussi programmée. */
class ReminderStore(private val scope: CoroutineScope) {

    private val json = Json { ignoreUnknownKeys = true }

    var reminders by mutableStateOf<List<Reminder>>(emptyList())
        private set

    suspend fun load() {
        val text = readAppFile(FILE) ?: return
        reminders = try {
            json.decodeFromString(ReminderFile.serializer(), text).reminders
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Rappel déjà posé sur cette émission (une des versions de la chaîne). */
    fun find(sourceId: String, channelIds: Collection<String>, start: Long): Reminder? =
        reminders.firstOrNull { it.sourceId == sourceId && it.start == start && it.channel.id in channelIds }

    fun add(reminder: Reminder) {
        if (reminders.any { it.key == reminder.key }) return
        reminders = (reminders + reminder).sortedBy { it.start }
        runCatching { scheduleReminderNotification(reminder) }
        save()
    }

    fun remove(reminder: Reminder) {
        reminders = reminders.filterNot { it.key == reminder.key }
        runCatching { cancelReminderNotification(reminder) }
        save()
    }

    /** Retire les rappels d'émissions terminées. */
    fun cleanup(now: Long) {
        val kept = reminders.filter { it.stop > now }
        if (kept.size != reminders.size) {
            reminders = kept
            save()
        }
    }

    private fun save() {
        val snapshot = ReminderFile(reminders)
        scope.launch { writeAppFile(FILE, json.encodeToString(ReminderFile.serializer(), snapshot)) }
    }

    private companion object {
        const val FILE = "reminders.json"
    }
}
