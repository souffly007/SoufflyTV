package fr.bonobo.soufflytv.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.bonobo.soufflytv.data.HOUR_MS
import fr.bonobo.soufflytv.data.IptvProvider
import fr.bonobo.soufflytv.data.LibraryEntry
import fr.bonobo.soufflytv.data.LibraryStore
import fr.bonobo.soufflytv.data.SettingsStore
import fr.bonobo.soufflytv.data.isAdultCategory
import fr.bonobo.soufflytv.data.toUserMessage
import fr.bonobo.soufflytv.model.Category
import fr.bonobo.soufflytv.model.Channel
import fr.bonobo.soufflytv.model.ItemType
import fr.bonobo.soufflytv.model.MovieInfo
import fr.bonobo.soufflytv.model.Section
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val ALL_CATEGORIES = "__all__"
const val FAVORITES = "__fav__"
const val RECENTS = "__recent__"

fun isSpecialCategory(id: String) = id == FAVORITES || id == RECENTS || id == ALL_CATEGORIES

/** État de navigation dans une source, conservé pendant la lecture d'une chaîne. */
class BrowserModel(
    val provider: IptvProvider,
    private val scope: CoroutineScope,
    private val sourceId: String,
    private val library: LibraryStore,
    private val settings: SettingsStore,
) {
    val sections: List<Section> = provider.sections

    var section by mutableStateOf(sections.first())
        private set

    /** Catégories du fournisseur (sans les catégories spéciales). */
    private var providerCategories by mutableStateOf<List<Category>>(emptyList())
    private var adultIds: Set<String> = emptySet()

    var loadedOnce by mutableStateOf(false)
        private set
    var selected by mutableStateOf<Category?>(null)
        private set
    var channels by mutableStateOf<List<Channel>>(emptyList())
        private set
    var categoriesLoading by mutableStateOf(false)
        private set
    var channelsLoading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Incrémenté quand le guide TV complet est disponible. */
    var epgVersion by mutableIntStateOf(0)
        private set

    var query by mutableStateOf("")

    /** Écran large : catégories et chaînes côte à côte. */
    var wide by mutableStateOf(true)

    /** Écran étroit : true quand la liste des chaînes remplace celle des catégories. */
    var showChannels by mutableStateOf(false)

    // ---------- Bandeau des films et séries (grand écran, façon TiviMate) ----------

    /** Affiche qui a le focus : le bandeau du haut présente ce film ou cette série. */
    var heroItem by mutableStateOf<Channel?>(null)
    /** Position de l'affiche dans la liste (compteur « 3 / 1017 »). */
    var heroIndex by mutableIntStateOf(0)
    /** Affiche ouverte : au retour, le focus revient sur elle (et non sur la catégorie). */
    var returnFocusId by mutableStateOf<String?>(null)
    private val details = HashMap<String, MovieInfo>()

    fun focusPoster(item: Channel, index: Int) {
        heroItem = item
        heroIndex = index
    }

    /** Fiche du film ou de la série, gardée en mémoire pour un défilement fluide. */
    fun cachedDetails(item: Channel): MovieInfo? = details[item.id]

    suspend fun details(item: Channel): MovieInfo {
        details[item.id]?.let { return it }
        val info = if (item.type == ItemType.SERIES) provider.seriesSummary(item) else provider.movieInfo(item)
        if (details.size > 400) details.clear()
        details[item.id] = info
        return info
    }

    /** Positions de défilement, conservées pendant la lecture. */
    val categoryListState = LazyListState()
    val channelListState = LazyListState()
    val posterGridState = LazyGridState()

    private var started = false
    private var epgRequested = false
    private var categoriesJob: Job? = null
    private var channelsJob: Job? = null
    private var categoriesToken = 0
    private var channelsToken = 0

    /** Catégories affichées : Favoris, Récents, Tout, puis celles du fournisseur (filtre adulte appliqué). */
    val categories: List<Category>
        get() {
            if (!loadedOnce) return emptyList()
            val all = when (section) {
                Section.LIVE -> "Toutes les chaînes"
                Section.MOVIES -> "Tous les films"
                Section.SERIES -> "Toutes les séries"
            }
            val hiddenIds = library.hiddenIds(sourceId, section)
            val list = providerCategories.filterNot {
                (settings.value.hideAdult && it.id in adultIds) || it.id in hiddenIds
            }
            return listOf(Category(FAVORITES, "Favoris"), Category(RECENTS, "Récents"), Category(ALL_CATEGORIES, all)) + list
        }

    val providerCategoryCount: Int get() = categories.size - 3

    /** Nombre de catégories masquées par l'utilisateur dans la rubrique affichée. */
    val hiddenCount: Int get() = library.hiddenIds(sourceId, section).size

    fun start() {
        if (started) return
        started = true
        loadCategories()
    }

    fun retry() {
        error = null
        val current = selected
        if (!loadedOnce || current == null) loadCategories() else select(current)
    }

    fun selectSection(target: Section) {
        if (target == section) return
        section = target
        providerCategories = emptyList()
        loadedOnce = false
        channels = emptyList()
        selected = null
        showChannels = false
        query = ""
        heroItem = null
        returnFocusId = null
        scope.launch { categoryListState.scrollToItem(0) }
        loadCategories()
    }

    fun select(category: Category) {
        val changed = selected?.id != category.id
        selected = category
        showChannels = true
        query = ""
        if (changed) {
            heroItem = null
            heroIndex = 0
            returnFocusId = null
            // nouvelle catégorie : on repart du haut de la liste
            scope.launch {
                channelListState.scrollToItem(0)
                posterGridState.scrollToItem(0)
            }
        }
        channelsJob?.cancel()
        val token = ++channelsToken
        if (category.id == FAVORITES || category.id == RECENTS) {
            channels = emptyList()
            channelsLoading = false
            error = null
            return
        }
        channelsJob = scope.launch {
            channelsLoading = true
            channels = emptyList()
            error = null
            try {
                channels = provider.channels(section, category.id.takeUnless { it == ALL_CATEGORIES })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.toUserMessage()
            } finally {
                if (token == channelsToken) channelsLoading = false
            }
        }
    }

    /** Retour système en écran étroit : revenir aux catégories avant de quitter la source. */
    fun consumeBack(): Boolean {
        if (!wide && showChannels) {
            showChannels = false
            return true
        }
        return false
    }

    /** Liste affichée : favoris / récents de cette rubrique, ou contenu de la catégorie, filtrés. */
    fun visibleChannels(): List<Channel> {
        val base = when (selected?.id) {
            FAVORITES -> fromLibrary(library.favorites, section)
            RECENTS -> fromLibrary(library.recents, section)
            else -> channels
        }
        // Favoris et récents : toujours affichés ; ailleurs, on retire les catégories masquées
        val special = selected?.id == FAVORITES || selected?.id == RECENTS
        val hiddenIds = if (special) emptySet() else library.hiddenIds(sourceId, section)
        val adult = settings.value.hideAdult && adultIds.isNotEmpty()
        val adultFiltered = if (adult || hiddenIds.isNotEmpty()) {
            base.filterNot { ch ->
                val cat = ch.categoryId ?: return@filterNot false
                (adult && cat in adultIds) || cat in hiddenIds
            }
        } else base
        val q = query.trim()
        return if (q.isEmpty()) adultFiltered else adultFiltered.filter { it.name.contains(q, ignoreCase = true) }
    }

    private fun fromLibrary(entries: List<LibraryEntry>, target: Section): List<Channel> {
        // Une source à rubrique unique (M3U) garde tout ; sinon on trie par rubrique
        val singleSection = sections.size == 1
        return entries.filter { it.sourceId == sourceId && (singleSection || it.channel.type in target.types) }
            .map { it.channel }
    }

    // ---------- Liste des chaînes du lecteur (façon TiviMate) ----------

    private fun specialCategories(target: Section) = listOf(
        Category(FAVORITES, "Favoris"),
        Category(RECENTS, "Récents"),
        Category(
            ALL_CATEGORIES,
            when (target) {
                Section.LIVE -> "Toutes les chaînes"
                Section.MOVIES -> "Tous les films"
                Section.SERIES -> "Toutes les séries"
            },
        ),
    )

    /** Groupes d'une rubrique, filtres (adulte, masquées) appliqués, quelle que soit la rubrique affichée. */
    suspend fun groupsFor(target: Section): List<Category> {
        val list = provider.categories(target)
        val hidden = library.hiddenIds(sourceId, target)
        val hideAdult = settings.value.hideAdult
        return specialCategories(target) + list.filterNot { (hideAdult && isAdultCategory(it.name)) || it.id in hidden }
    }

    /** Chaînes d'un groupe pour la liste du lecteur, sans changer ce qu'affiche l'écran des catégories. */
    suspend fun channelsFor(target: Section, category: Category): List<Channel> {
        if (category.id == FAVORITES) return fromLibrary(library.favorites, target)
        if (category.id == RECENTS) return fromLibrary(library.recents, target)
        val base = provider.channels(target, category.id.takeUnless { it == ALL_CATEGORIES })
        val all = provider.categories(target)
        val adult = if (settings.value.hideAdult) all.filter { isAdultCategory(it.name) }.map { it.id }.toSet() else emptySet()
        val hidden = library.hiddenIds(sourceId, target)
        if (adult.isEmpty() && hidden.isEmpty()) return base
        return base.filterNot { ch ->
            val cat = ch.categoryId ?: return@filterNot false
            cat in adult || cat in hidden
        }
    }

    /** Charge le guide complet en arrière-plan (une seule fois). */
    fun ensureEpg() {
        if (epgRequested || !provider.hasLocalEpg || !settings.value.epgInLists) return
        epgRequested = true
        scope.launch {
            // 1. guide enregistré sur l'appareil : affiché tout de suite
            if (safely { provider.loadCachedEpg() } == true) epgVersion++
            // 2. rafraîchi en arrière-plan s'il a plus de 6 h, vérifié toutes les heures
            while (true) {
                safely { provider.preloadEpg() }
                epgVersion++
                delay(HOUR_MS)
            }
        }
    }

    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

    fun isFavorite(channel: Channel): Boolean = library.isFavorite(sourceId, channel.id)

    fun toggleFavorite(channel: Channel) = library.toggleFavorite(sourceId, channel)

    private fun loadCategories() {
        categoriesJob?.cancel()
        channelsJob?.cancel()
        val token = ++categoriesToken
        categoriesJob = scope.launch {
            categoriesLoading = true
            error = null
            try {
                val list = provider.categories(section)
                adultIds = list.filter { isAdultCategory(it.name) }.map { it.id }.toSet()
                providerCategories = list
                loadedOnce = true
                if (wide) select(categories.first { it.id == ALL_CATEGORIES })
                if (section == Section.LIVE) ensureEpg()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.toUserMessage()
            } finally {
                if (token == categoriesToken) categoriesLoading = false
            }
        }
    }
}
