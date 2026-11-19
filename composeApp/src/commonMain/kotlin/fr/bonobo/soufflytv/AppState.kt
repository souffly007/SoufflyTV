package fr.bonobo.soufflytv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.bonobo.soufflytv.data.EpgCache
import fr.bonobo.soufflytv.data.IptvProvider
import fr.bonobo.soufflytv.data.LibraryStore
import fr.bonobo.soufflytv.data.SettingsStore
import fr.bonobo.soufflytv.data.SourceRepository
import fr.bonobo.soufflytv.data.createHttpClient
import fr.bonobo.soufflytv.data.createProvider
import fr.bonobo.soufflytv.model.Channel
import fr.bonobo.soufflytv.model.ItemType
import fr.bonobo.soufflytv.model.Section
import fr.bonobo.soufflytv.model.Source
import fr.bonobo.soufflytv.platform.deleteAppFile
import fr.bonobo.soufflytv.ui.BrowserModel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Sources : Screen
    data class AddSource(val editing: Source? = null) : Screen
    data object Settings : Screen
    data class Browse(val source: Source) : Screen
    data class Series(val source: Source, val series: Channel) : Screen
    data class ManageCategories(val source: Source, val section: Section) : Screen
    data class Player(val source: Source, val channels: List<Channel>, val index: Int) : Screen
}

/** État global : navigation, sources, bibliothèque, réglages, connexions ouvertes. */
class AppState {

    private val scope = MainScope()
    private val repository = SourceRepository()
    private val http = createHttpClient()

    val library = LibraryStore(scope)
    val settings = SettingsStore(scope)
    val sources: StateFlow<List<Source>> = repository.sources

    var loaded by mutableStateOf(false)
        private set

    var screen: Screen by mutableStateOf<Screen>(Screen.Sources)
        private set

    private val backStack = mutableStateListOf<Screen>()

    val canGoBack: Boolean get() = backStack.isNotEmpty()

    /** Ordinateur : bascule la fenêtre en plein écran. */
    var toggleFullscreen: (() -> Unit)? by mutableStateOf<(() -> Unit)?>(null)

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
            repository.load()
            loaded = true
            if (settings.value.resumeLastChannel) resumeLastChannel()
        }
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

    fun navigate(target: Screen) {
        backStack += screen
        screen = target
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
        return browsers.getOrPut(source.id) { BrowserModel(provider, scope, source.id, library, settings) }
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

    fun close() {
        scope.cancel()
        http.close()
    }
}
