package fr.bonobo.soufflytv.data

import fr.bonobo.soufflytv.model.Category
import fr.bonobo.soufflytv.model.MovieInfo
import fr.bonobo.soufflytv.model.Channel
import fr.bonobo.soufflytv.model.Programme
import fr.bonobo.soufflytv.model.Section
import fr.bonobo.soufflytv.model.SeriesInfo
import fr.bonobo.soufflytv.model.Source
import fr.bonobo.soufflytv.model.StreamRequest
import io.ktor.client.HttpClient

/** Ce que l'interface attend de chaque type de source. */
interface IptvProvider {
    /** Rubriques disponibles (TV en direct, Films, Séries). */
    val sections: List<Section>

    suspend fun categories(section: Section): List<Category>

    /** [categoryId] à null = tout le contenu de la rubrique. */
    suspend fun channels(section: Section, categoryId: String?): List<Channel>

    /** Transforme une chaîne en URL lisible (immédiat pour M3U/Xtream, appel au portail pour Stalker). */
    suspend fun resolve(channel: Channel): StreamRequest

    /** Guide TV d'une chaîne (émissions triées par heure). */
    suspend fun epg(channel: Channel): List<Programme> = emptyList()

    /** true si tout le guide est chargé d'un coup (XMLTV) : on peut l'afficher dans les listes. */
    val hasLocalEpg: Boolean get() = false

    /** Charge le guide enregistré sur l'appareil ; true s'il y en a un. */
    suspend fun loadCachedEpg(): Boolean = false

    /** Télécharge le guide complet en arrière-plan (XMLTV) s'il manque ou est ancien. */
    suspend fun preloadEpg() {}

    /** Émission en cours, depuis le guide déjà chargé (sans réseau). */
    fun epgNow(channel: Channel): Programme? = null

    /** Fiche d'un film. Par défaut : ce que la liste fournit déjà (titre, affiche, résumé éventuel). */
    suspend fun movieInfo(movie: Channel): MovieInfo = MovieInfo(movie.name, movie.plot, movie.logo)

    /**
     * Présentation d'une série (bandeau des affiches). Par défaut : ce que la liste fournit,
     * sans charger les épisodes.
     */
    suspend fun seriesSummary(series: Channel): MovieInfo =
        MovieInfo(series.name, series.plot, series.logo, meta = movieMetaLine(null, null, null, series.rating))

    /** Saisons et épisodes d'une série. */
    suspend fun seriesInfo(series: Channel): SeriesInfo =
        throw IptvException("Cette source ne propose pas de séries")
}

fun createProvider(source: Source, http: HttpClient): IptvProvider = when (source) {
    is Source.M3u -> M3uProvider(source, http)
    is Source.Xtream -> XtreamProvider(source, http)
    is Source.Stalker -> StalkerProvider(source, http)
}
