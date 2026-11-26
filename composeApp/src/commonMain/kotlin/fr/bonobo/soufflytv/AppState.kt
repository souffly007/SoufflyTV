package fr.bonobo.soufflytv

import fr.bonobo.soufflytv.data.ParentalLock
import fr.bonobo.soufflytv.data.ParentalControl
import kotlinx.coroutines.delay
import fr.bonobo.soufflytv.data.ReminderStore
import fr.bonobo.soufflytv.data.Reminder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.bonobo.soufflytv.data.AccountBackup
import fr.bonobo.soufflytv.data.AccountBackupCodec
import fr.bonobo.soufflytv.data.EpgCache
import fr.bonobo.soufflytv.data.IptvProvider
import fr.bonobo.soufflytv.data.LibraryStore
import fr.bonobo.soufflytv.data.SettingsStore
import fr.bonobo.soufflytv.data.SourceRepository
import fr.bonobo.soufflytv.data.createHttpClient
import fr.bonobo.soufflytv.data.createProvider
import fr.bonobo.soufflytv.data.normalizeUrl
import fr.bonobo.soufflytv.model.Channel
import fr.bonobo.soufflytv.model.ItemType
import fr.bonobo.soufflytv.model.Section
import fr.bonobo.soufflytv.model.Source
import fr.bonobo.soufflytv.platform.deleteAppFile
import fr.bonobo.soufflytv.platform.nowMillis
import fr.bonobo.soufflytv.platform.readAppFile
import fr.bonobo.soufflytv.platform.writeAppFile
import fr.bonobo.soufflytv.ui.BrowserModel
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Restauration : comptes ajoutés / mis à jour, et [full] si favoris, historique et réglages ont suivi. */
data class ImportSummary(val added: Int, val updated: Int, val full: Boolean = false)

sealed interface Screen {
    data object Sources : Screen
    data class AddSource(val editing: Source? = null) : Screen
    data object Settings : Screen
    data class Browse(val source: Source) : Screen
    data class Series(val source: Source, val series: Channel) : Screen
    /** Fiche d'un film ; [channels]/[index] : la liste d'où il vient, transmise au lecteur. */
    data class Movie(val source: Source, val channels: List<Channel>, val index: Int) : Screen
    data class ManageCategories(val source: Source, val section: Section) : Screen
    data class Player(val source: Source, val channels: List<Channel>, val index: Int) : Screen
}

/** État global : navigation, sources, bibliothèque, réglages, connexions ouvertes. */
class AppState {

    private val scope = MainScope()

    /** Ordinateur : boîtes de dialogue dessinées dans la fenêtre (Échap ferme celle du dessus). */
    val dialogs = fr.bonobo.soufflytv.ui.DialogHost()
    private val repository = SourceRepository()
    private val http = createHttpClient()

    val library = LibraryStore(scope)
    val settings = SettingsStore(scope)
    /** Rappels posés depuis le guide TV (prévenir N minutes avant, selon les réglages). */
    val reminders = ReminderStore(scope, leadMs = { settings.value.reminderMinutes * 60_000L })
    /** Contrôle parental (code PIN). */
    val parental = ParentalControl(settings)

    /** Rappel dont l'émission commence : bandeau « Ça commence » affiché par-dessus l'écran. */
    var dueReminder by mutableStateOf<Reminder?>(null)
        private set
    val sources: StateFlow<List<Source>> = repository.sources

    var loaded by mutableStateOf(false)
        private set

    var screen: Screen by mutableStateOf<Screen>(Screen.Sources)
        private set

    private val backStack = mutableStateListOf<Screen>()

    val canGoBack: Boolean get() = backStack.isNotEmpty()

    /** Ordinateur : bascule la fenêtre en plein écran. */
    var toggleFullscreen: (() -> Unit)? by mutableStateOf<(() -> Unit)?>(null)
    /** Ordinateur : passer en plein écran (true) ou en revenir (false). */
    var setFullscreen: ((Boolean) -> Unit)? by mutableStateOf<((Boolean) -> Unit)?>(null)
    /** Ordinateur : la fenêtre est en plein écran. */
    var isFullscreen by mutableStateOf(false)

    /** Android : passe en image dans l'image (null si l'appareil ne le permet pas). */
    var enterPictureInPicture: (() -> Unit)? by mutableStateOf<(() -> Unit)?>(null)

    var inPictureInPicture by mutableStateOf(false)

    /** Le lecteur referme d'abord ses panneaux (liste des chaînes, commandes) avant de se fermer. */
    var playerBackHandler: (() -> Boolean)? = null

    /** Où était le focus (télécommande) avant d'ouvrir un écran : clé d'écran → identifiant. */
    val focusMemory = HashMap<String, String>()

    /** Un fournisseur par source, réutilisé tant que la configuration ne change pas. */
    private val providers = HashMap<String, Pair<Source, IptvProvider>>()
    private val browsers = HashMap<String, BrowserModel>()

    init {
        scope.launch {
            settings.load()
            library.load()
            reminders.load()
            repository.load()
            loaded = true
            if (settings.value.resumeLastChannel) resumeLastChannel()
            watchReminders()
        }
    }

    /** Toutes les 15 s : un rappel dont l'émission commence (dans la minute) s'affiche. */
    private suspend fun watchReminders() {
        while (true) {
            val now = nowMillis()
            reminders.cleanup(now)
            if (dueReminder == null) {
                val lead = settings.value.reminderMinutes * 60_000L
                dueReminder = reminders.reminders.firstOrNull { now >= it.start - lead && now < it.stop }
            }
            delay(15_000)
        }
    }

    /** « Regarder » : lance la chaîne du rappel (remplace la lecture en cours s'il y en a une). */
    fun watchReminder(reminder: Reminder) {
        reminders.remove(reminder)
        dueReminder = null
        val source = sourceById(reminder.sourceId) ?: return
        val target = Screen.Player(source, listOf(reminder.channel), 0)
        if (screen is Screen.Player) screen = target else navigate(target)
    }

    /** « Ignorer » : le rappel est retiré. */
    fun dismissReminder(reminder: Reminder) {
        reminders.remove(reminder)
        dueReminder = null
    }

    /** Démarrage façon TiviMate : on rouvre la source et on relance la dernière chaîne en direct. */
    private fun resumeLastChannel() {
        val entry = library.recents.firstOrNull { it.channel.type == ItemType.LIVE } ?: return
        val source = sourceById(entry.sourceId) ?: return
        val playlist = library.recents
            .filter { it.sourceId == source.id && it.channel.type == ItemType.LIVE }
            .map { it.channel }
        navigate(Screen.Browse(source))
        navigate(Screen.Player(source, playlist, playlist.indexOf(entry.channel).coerceAtLeast(0)))
    }

    // ---------- Navigation ----------

    /** Ouvre un écran ; réglages et comptes demandent le code PIN si le contrôle parental les protège. */
    fun navigate(target: Screen) {
        when (target) {
            Screen.Settings -> parental.guard(ParentalLock.SETTINGS, "Ouvrir les réglages") { go(target) }
            is Screen.ManageCategories -> parental.guard(ParentalLock.SETTINGS, "Modifier les filtres") { go(target) }
            is Screen.AddSource -> parental.guard(ParentalLock.ACCOUNTS, "Ajouter ou modifier un compte") { go(target) }
            else -> go(target)
        }
    }

    private fun go(target: Screen) {
        backStack += screen
        screen = target
    }

    /**
     * Après un changement du guide TV (décalage, mise à jour) : les fournisseurs sont recréés,
     * le guide est relu (avec les nouvelles heures) au prochain affichage.
     */
    fun reloadGuides(clearCache: Boolean) {
        val ids = providers.keys.toList()
        providers.clear()
        browsers.clear()
        if (clearCache) scope.launch { ids.forEach { EpgCache.delete(it) } }
    }

    /** @return false s'il n'y a plus rien en arrière (l'application peut se fermer). */
    fun back(): Boolean {
        val current = screen
        if (current is Screen.Player && playerBackHandler?.invoke() == true) return true
        if (current is Screen.Browse && browsers[current.source.id]?.consumeBack() == true) return true
        if (backStack.isEmpty()) return false
        screen = backStack.removeAt(backStack.lastIndex)
        return true
    }

    /** Retour direct à l'accueil (liste des sources). */
    fun home() {
        backStack.clear()
        screen = Screen.Sources
    }

    fun open(source: Source) = navigate(Screen.Browse(source))

    /** Ouvre un élément : une série affiche ses saisons, le reste part en lecture. */
    fun openItem(source: Source, list: List<Channel>, index: Int) {
        val item = list[index]
        if (item.type == ItemType.SERIES) {
            navigate(Screen.Series(source, item))
        } else if (item.type == ItemType.MOVIE) {
            val playable = list.filter { it.type != ItemType.SERIES }
            navigate(Screen.Movie(source, playable, playable.indexOf(item).coerceAtLeast(0)))
        } else {
            val playable = list.filter { it.type != ItemType.SERIES }
            navigate(Screen.Player(source, playable, playable.indexOf(item).coerceAtLeast(0)))
        }
    }

    fun play(source: Source, channels: List<Channel>, index: Int) =
        navigate(Screen.Player(source, channels, index))

    fun sourceById(id: String): Source? = sources.value.firstOrNull { it.id == id }

    // ---------- Sources ----------

    fun provider(source: Source): IptvProvider {
        providers[source.id]?.let { (cached, provider) -> if (cached == source) return provider }
        val provider = createProvider(source, http)
        providers[source.id] = source to provider
        browsers.remove(source.id)
        return provider
    }

    fun browser(source: Source): BrowserModel {
        val provider = provider(source)
        return browsers.getOrPut(source.id) { BrowserModel(provider, scope, source.id, library, settings, parental) }
    }

    /** Teste une source avant enregistrement ; garde la connexion pour éviter un second téléchargement. */
    suspend fun testSource(source: Source): String {
        val provider = provider(source)
        val section = provider.sections.first()
        val categories = provider.categories(section)
        return if (source is Source.M3u) {
            "Connexion réussie · ${provider.channels(section, null).size} chaînes, ${categories.size} catégories"
        } else {
            "Connexion réussie · ${categories.size} catégories"
        }
    }

    fun saveSource(source: Source) {
        scope.launch { repository.save(source) }
        back()
    }

    fun deleteSource(source: Source) {
        providers.remove(source.id)
        browsers.remove(source.id)
        library.removeSource(source.id)
        scope.launch {
            repository.remove(source.id)
            EpgCache.delete(source.id)
            if (source is Source.M3u && source.localFile != null) deleteAppFile(source.localFile)
        }
    }

    // ---------- Sauvegarde des comptes ----------

    /** Fichier chiffré contenant toutes les sources (et les playlists importées depuis un fichier). */
    suspend fun exportAccounts(password: String, full: Boolean = false): String {
        val list = sources.value
        val playlists = list.filterIsInstance<Source.M3u>()
            .mapNotNull { m3u -> m3u.localFile?.let { file -> readAppFile(file)?.let { file to it } } }
            .toMap()
        // Sauvegarde complète : favoris, historique, filtres, rappels et réglages (sans le code parental)
        val backup = AccountBackup(
            exportedAt = nowMillis(),
            sources = list,
            playlists = playlists,
            library = if (full) library.snapshot() else null,
            settings = if (full) settings.value.copy(parental = false, pinHash = "") else null,
            reminders = if (full) reminders.reminders else emptyList(),
        )
        return withContext(Dispatchers.Default) { AccountBackupCodec.encode(backup, password) }
    }

    /**
     * Restaure une sauvegarde : les comptes absents sont ajoutés, ceux déjà présents (même compte) sont mis à jour.
     * Lève [fr.bonobo.soufflytv.data.BackupException] si le fichier ou le mot de passe ne conviennent pas.
     */
    suspend fun importAccounts(text: String, password: String): ImportSummary {
        val backup = withContext(Dispatchers.Default) { AccountBackupCodec.decode(text, password) }
        backup.playlists.forEach { (file, content) -> writeAppFile(file, content) }
        val existing = sources.value.map { it.id }.toSet()
        val updated = backup.sources.count { it.id in existing }
        repository.saveAll(backup.sources)
        // Sauvegarde complète : on ajoute favoris, historique, rappels… et on reprend les réglages,
        // sauf ceux propres à cet appareil (taille du texte, AFR, DNS, plein écran…) et le contrôle parental
        backup.library?.let { library.merge(it) }
        reminders.merge(backup.reminders, nowMillis())
        backup.settings?.let { saved ->
            settings.update { current ->
                saved.copy(
                    parental = current.parental,
                    pinHash = current.pinHash,
                    lockAdult = current.lockAdult,
                    lockSettings = current.lockSettings,
                    lockAccounts = current.lockAccounts,
                    textSize = current.textSize,
                    autoFrameRate = current.autoFrameRate,
                    autoLandscape = current.autoLandscape,
                    autoPip = current.autoPip,
                    autoFullscreen = current.autoFullscreen,
                    dns = current.dns,
                )
            }
        }
        val full = backup.library != null || backup.settings != null
        return ImportSummary(added = backup.sources.size - updated, updated = updated, full = full)
    }

    /** Sauvegarde déposée en ligne (pratique pour une TV sans explorateur de fichiers). */
    suspend fun downloadText(url: String): String {
        val response = http.get(normalizeUrl(url))
        if (!response.status.isSuccess()) error("Le serveur répond ${response.status.value}")
        return response.bodyAsText()
    }

    fun close() {
        scope.cancel()
        http.close()
    }
}
