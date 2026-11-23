package fr.bonobo.soufflytv.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.bonobo.soufflytv.data.ChannelGroup
import fr.bonobo.soufflytv.data.HOUR_MS
import fr.bonobo.soufflytv.data.groupVariants
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val ALL_CATEGORIES = "__all__"
/** Nombre de cartes par rangée de l'accueil. */
private const val HOME_ROW = 24
private const val SEARCH_LIMIT = 40
const val FAVORITES = "__fav__"
const val RECENTS = "__recent__"
/** TV en direct : « En ce moment », les émissions en cours rangées par groupe (avec un guide TV). */
const val NOW_ON = "__now__"

/** Pages en plus des rubriques : Ma liste, Historique, Recherche. */
enum class ExtraPage { MY_LIST, HISTORY, SEARCH, GUIDE, REPLAY }

/** Guide TV : émission choisie (focus ou toucher) ; [start] à null = la chaîne elle-même. */
data class GuideSelection(val groupKey: String, val start: Long?)

/** Résultats d'une recherche dans tout le compte. */
data class SearchResults(val live: List<Channel>, val movies: List<Channel>, val series: List<Channel>) {
    val isEmpty: Boolean get() = live.isEmpty() && movies.isEmpty() && series.isEmpty()
}

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

    // ---------- Accueil (grand écran) : À la une, Continuer à regarder, Nouveautés… ----------

    /** Grand écran : la page d'accueil de contenus est affichée (sinon, une rubrique TV / Films / Séries). */
    var showHome by mutableStateOf(true)
    var homeNewMovies by mutableStateOf<List<Channel>>(emptyList())
        private set
    var homeNewSeries by mutableStateOf<List<Channel>>(emptyList())
        private set
    var homeTopMovies by mutableStateOf<List<Channel>>(emptyList())
        private set
    var homeLoading by mutableStateOf(false)
        private set
    private var homeLoaded = false
    /** Carte ouverte depuis l'accueil (« rangée|id ») : au retour, le focus revient dessus. */
    var homeReturnKey by mutableStateOf<String?>(null)

    /** ▶ depuis la barre latérale : entre dans le contenu (catégories ou accueil). */
    var enterContent: (() -> Boolean)? = null

    /**
     * Augmente à chaque choix dans la barre latérale : l'accueil ou la colonne des catégories
     * reprend alors le focus (sinon il restait sur la barre, ouverte par-dessus le contenu).
     */
    var contentFocusToken by mutableIntStateOf(0)
        private set

    /** Page en plus affichée (Ma liste, Historique, Recherche), ou null. */
    var extraPage by mutableStateOf<ExtraPage?>(null)
        private set

    fun openExtra(page: ExtraPage) {
        extraPage = page
        contentFocusToken++
    }

    /** Recherche : texte tapé et derniers résultats, gardés au retour d'une fiche ou du lecteur. */
    var searchText by mutableStateOf("")
    var searchResults by mutableStateOf<SearchResults?>(null)

    // ---------- Guide TV (grille) ----------

    /** Groupes de chaînes proposés en haut du guide (Favoris, Récents, Toutes, puis ceux du fournisseur). */
    var guideCategories by mutableStateOf<List<Category>>(emptyList())
        private set
    var guideCategory by mutableStateOf<Category?>(null)
        private set
    /** Lignes du guide : une par chaîne, ses versions SD / HD / UHD regroupées. */
    var guideGroups by mutableStateOf<List<ChannelGroup>>(emptyList())
        private set
    var guideLoading by mutableStateOf(false)
        private set
    var guideError by mutableStateOf<String?>(null)
        private set
    /** Heure au bord gauche de la grille (0 = pas encore placée). */
    var guideViewStart by mutableLongStateOf(0L)
    /** Émission sélectionnée : présentée en haut ; au retour du lecteur, le focus y revient. */
    var guideSelection by mutableStateOf<GuideSelection?>(null)
    val guideListState = LazyListState()
    private var guideJob: Job? = null
    private var guideStarted = false

    /** À l'ouverture de la page : guide complet demandé, groupes chargés (une seule fois). */
    fun prepareGuide() {
        ensureEpg(force = true)
        if (guideStarted) return
        guideStarted = true
        scope.launch {
            guideLoading = true
            val groups = safely { groupsFor(Section.LIVE) }
            if (groups == null) {
                guideError = "Impossible de charger les groupes de chaînes"
                guideLoading = false
                guideStarted = false
                return@launch
            }
            guideCategories = groups
            // Favoris s'il y en a, sinon toutes les chaînes
            val hasFavorites = library.favorites.any { it.sourceId == sourceId && it.channel.type == ItemType.LIVE }
            selectGuideCategory(groups.first { it.id == if (hasFavorites) FAVORITES else ALL_CATEGORIES })
        }
    }

    fun selectGuideCategory(category: Category) {
        guideCategory = category
        guideSelection = null
        guideJob?.cancel()
        guideJob = scope.launch {
            guideLoading = true
            guideError = null
            try {
                val list = channelsFor(Section.LIVE, category).filter { it.type == ItemType.LIVE }
                guideGroups = withContext(Dispatchers.Default) { groupVariants(list) }
                guideListState.scrollToItem(0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                guideError = e.toUserMessage()
                guideGroups = emptyList()
            }
            guideLoading = false
        }
    }

    // ---------- Replay ----------

    /** true si le fournisseur propose le replay sur au moins une chaîne : le menu Replay s'affiche. */
    var replayAvailable by mutableStateOf(false)
        private set
    /** Chaînes avec archives, regroupées (SD / HD… sur une ligne), et les groupes qui en contiennent. */
    var replayGroups by mutableStateOf<List<ChannelGroup>>(emptyList())
        private set
    var replayCategories by mutableStateOf<List<Category>>(emptyList())
        private set
    var replayCategory by mutableStateOf<Category?>(null)
        private set
    var replayLoading by mutableStateOf(false)
        private set
    /** Chaîne choisie (clé du groupe) et jour choisi (« Aujourd'hui », « Hier »…). */
    var replayGroupKey by mutableStateOf<String?>(null)
    var replayDay by mutableStateOf<String?>(null)
    val replayListState = LazyListState()
    private var replayAll: List<Channel> = emptyList()
    private var replayStarted = false
    private var replayChecked = false

    /** Vérifie une fois, en arrière-plan, si le fournisseur propose le replay. */
    fun checkReplay() {
        if (replayChecked || Section.LIVE !in sections) return
        replayChecked = true
        scope.launch { replayAvailable = safely { provider.hasReplay() } == true }
    }

    /** À l'ouverture de la page Replay : chaînes avec archives et leurs groupes. */
    fun prepareReplay() {
        if (replayStarted) return
        replayStarted = true
        scope.launch {
            replayLoading = true
            val all = safely { channelsFor(Section.LIVE, Category(ALL_CATEGORIES, "")) }.orEmpty()
                .filter { it.type == ItemType.LIVE && it.archiveDays > 0 }
            replayAll = all
            val withReplay = all.mapNotNull { it.categoryId }.toSet()
            val groups = safely { provider.categories(Section.LIVE) }.orEmpty().filter { it.id in withReplay }
            replayCategories = listOf(Category(ALL_CATEGORIES, "Toutes")) + groups
            selectReplayCategory(replayCategories.first())
            replayLoading = false
            if (all.isEmpty()) replayStarted = false
        }
    }

    fun selectReplayCategory(category: Category) {
        replayCategory = category
        val list = if (category.id == ALL_CATEGORIES) replayAll else replayAll.filter { it.categoryId == category.id }
        scope.launch {
            replayGroups = withContext(Dispatchers.Default) { groupVariants(list) }
            if (replayGroups.none { it.key == replayGroupKey }) {
                replayGroupKey = if (wide) replayGroups.firstOrNull()?.key else null
                replayDay = null
            }
            replayListState.scrollToItem(0)
        }
    }

    /** TV en direct, grand écran : chaîne présentée (et lue en petit) dans l'aperçu au-dessus de la liste. */
    var previewChannel by mutableStateOf<Channel?>(null)

    /** Recherche dans les chaînes, films et séries du compte (catégories masquées et adultes exclues). */
    suspend fun search(text: String): SearchResults {
        val q = text.trim()
        if (q.length < 2) return SearchResults(emptyList(), emptyList(), emptyList())
        val all = Category(ALL_CATEGORIES, "")
        suspend fun find(target: Section): List<Channel> =
            if (target !in sections) emptyList()
            else (safely { channelsFor(target, all) } ?: emptyList())
                .filter { it.name.contains(q, ignoreCase = true) }
                .take(SEARCH_LIMIT)
        return SearchResults(find(Section.LIVE), find(Section.MOVIES), find(Section.SERIES))
    }

    fun goHome() {
        extraPage = null
        showHome = true
        homeReturnKey = null
        returnFocusId = null
        contentFocusToken++
    }

    /** Rangées de l'accueil : films et séries récents (date d'ajout du fournisseur), films les mieux notés. */
    fun loadHome() {
        if (homeLoaded) return
        homeLoaded = true
        homeLoading = true
        scope.launch {
            val all = Category(ALL_CATEGORIES, "")
            if (Section.MOVIES in sections) {
                safely { channelsFor(Section.MOVIES, all) }?.let { movies ->
                    homeNewMovies = newest(movies)
                    homeTopMovies = movies.filter { ratingOf(it) != null }
                        .sortedByDescending { ratingOf(it) }
                        .take(HOME_ROW)
                }
            }
            if (Section.SERIES in sections) {
                safely { channelsFor(Section.SERIES, all) }?.let { homeNewSeries = newest(it) }
            }
            homeLoading = false
        }
    }

    private fun newest(list: List<Channel>): List<Channel> =
        if (list.any { it.added != null }) list.sortedByDescending { it.added ?: 0L }.take(HOME_ROW) else list.take(HOME_ROW)

    private fun ratingOf(item: Channel): Double? =
        item.rating?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0.0 && it <= 10.0 }

    // ---------- Bandeau des films et séries (grand écran, façon TiviMate) ----------

    /** Affiche qui a le focus : le bandeau du haut présente ce film ou cette série. */
    var heroItem by mutableStateOf<Channel?>(null)
    /** Position de l'affiche dans la liste (compteur « 3 / 1017 »). */
    var heroIndex by mutableIntStateOf(0)
    /** Affiche ouverte : au retour, le focus revient sur elle (et non sur la catégorie). */
    var returnFocusId by mutableStateOf<String?>(null)
    private val details = HashMap<String, MovieInfo>()

    /**
     * ▶ depuis la colonne des catégories : va sur l'affiche présentée dans le bandeau.
     * (Le bandeau occupe le haut : sans cela, ▶ partait vers les onglets du menu.)
     * Fourni par la grille quand elle est affichée ; renvoie true si la touche est prise en charge.
     */
    var enterPosters: (() -> Boolean)? = null

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
            val nowOn = if (section == Section.LIVE && provider.hasLocalEpg) listOf(Category(NOW_ON, "En ce moment")) else emptyList()
            return nowOn + listOf(Category(FAVORITES, "Favoris"), Category(RECENTS, "Récents"), Category(ALL_CATEGORIES, all)) + list
        }

    val providerCategoryCount: Int get() = categories.count { it.id !in setOf(NOW_ON, FAVORITES, RECENTS, ALL_CATEGORIES) }

    /** Nombre de catégories masquées par l'utilisateur dans la rubrique affichée. */
    val hiddenCount: Int get() = library.hiddenIds(sourceId, section).size

    fun start() {
        if (started) return
        started = true
        loadCategories()
        checkReplay()
    }

    fun retry() {
        error = null
        val current = selected
        if (!loadedOnce || current == null) loadCategories() else select(current)
    }

    fun selectSection(target: Section) {
        showHome = false
        extraPage = null
        // Rubrique choisie dans la barre : le focus va sur sa catégorie, pas sur une affiche ouverte avant
        returnFocusId = null
        contentFocusToken++
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
                // « En ce moment » s'appuie sur toutes les chaînes, regroupées ensuite par groupe
                channels = provider.channels(section, category.id.takeUnless { it == ALL_CATEGORIES || it == NOW_ON })
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
        // Replay sur smartphone : de la liste des émissions à celle des chaînes
        if (extraPage == ExtraPage.REPLAY && !wide && replayGroupKey != null) {
            replayGroupKey = null
            replayDay = null
            return true
        }
        // Ma liste, Historique, Recherche : retour à l'accueil
        if (extraPage != null) {
            goHome()
            return true
        }
        if (!wide && showChannels && !showHome) {
            showChannels = false
            return true
        }
        // Retour depuis TV / Films / Séries : accueil de contenus
        if (!showHome) {
            goHome()
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
    fun ensureEpg(force: Boolean = false) {
        // [force] : la page Guide TV le demande même si « programme dans les listes » est coupé
        if (epgRequested || !provider.hasLocalEpg || (!force && !settings.value.epgInLists)) return
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
