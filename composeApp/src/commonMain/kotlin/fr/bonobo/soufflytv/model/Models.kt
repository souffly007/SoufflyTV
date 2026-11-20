package fr.bonobo.soufflytv.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Une source ajoutée par l'utilisateur.
 * SoufflyTV n'embarque aucune source : la liste est vide au premier lancement.
 */
@Serializable
sealed class Source {
    abstract val id: String
    abstract val name: String

    @Serializable
    @SerialName("m3u")
    data class M3u(
        override val id: String,
        override val name: String,
        /** Lien de la playlist (vide si la playlist a été importée depuis un fichier). */
        val url: String = "",
        val userAgent: String? = null,
        /** Guide TV XMLTV (sinon celui annoncé par la playlist : url-tvg / x-tvg-url). */
        val epgUrl: String? = null,
        /** Copie locale d'une playlist importée, dans le dossier privé de l'app. */
        val localFile: String? = null,
        val localFileName: String? = null,
    ) : Source()

    @Serializable
    @SerialName("xtream")
    data class Xtream(
        override val id: String,
        override val name: String,
        val server: String,
        val username: String,
        val password: String,
    ) : Source()

    @Serializable
    @SerialName("stalker")
    data class Stalker(
        override val id: String,
        override val name: String,
        val portal: String,
        val mac: String,
        val serialNumber: String? = null,
        val deviceId: String? = null,
        val deviceId2: String? = null,
        val signature: String? = null,
    ) : Source()
}

enum class ItemType {
    LIVE, MOVIE, SERIES, EPISODE;

    val isVod: Boolean get() = this == MOVIE || this == EPISODE
}

enum class Section(val label: String, val types: Set<ItemType>) {
    LIVE("TV en direct", setOf(ItemType.LIVE)),
    MOVIES("Films", setOf(ItemType.MOVIE)),
    SERIES("Séries", setOf(ItemType.SERIES, ItemType.EPISODE)),
}

data class Category(val id: String, val name: String)

@Serializable
data class Channel(
    val id: String,
    val name: String,
    val logo: String? = null,
    val categoryId: String? = null,
    /** URL directe (M3U, Xtream), commande du portail (Stalker) ou identifiant de série. */
    val stream: String,
    val number: Int? = null,
    /** En-têtes HTTP propres à la chaîne (#EXTVLCOPT dans une playlist M3U). */
    val headers: Map<String, String> = emptyMap(),
    val type: ItemType = ItemType.LIVE,
    /** Identifiant guide TV (tvg-id, stream_id Xtream, id Stalker). */
    val epgId: String? = null,
    val plot: String? = null,
    /** Identifiant XMLTV de la chaîne (epg_channel_id Xtream). */
    val tvgId: String? = null,
    /** Note sur 10 donnée par la source (films et séries), affichée sur l'affiche. */
    val rating: String? = null,
)

/** Une émission du guide TV (heures en millisecondes UTC). */
data class Programme(
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String? = null,
) {
    fun isOnAir(now: Long): Boolean = now >= start && now < stop

    fun progress(now: Long): Float =
        if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}

data class Season(val number: Int, val episodes: List<Channel>)

data class SeriesInfo(
    val name: String,
    val plot: String?,
    val cover: String?,
    val meta: String?,
    val seasons: List<Season>,
    val backdrop: String? = null,
    val director: String? = null,
    val cast: String? = null,
)

/** Fiche d'un film : résumé, année, genre, durée… (les champs absents restent à null). */
data class MovieInfo(
    val name: String,
    val plot: String?,
    val cover: String?,
    /** Grande image de fond (Xtream : backdrop_path). */
    val backdrop: String? = null,
    /** « 2014 · Action, Science-fiction · 1 h 57 · ★ 6,1 » */
    val meta: String? = null,
    val director: String? = null,
    val cast: String? = null,
)

/** Ce que le lecteur doit ouvrir. */
data class StreamRequest(
    val url: String,
    val title: String,
    val headers: Map<String, String> = emptyMap(),
)
