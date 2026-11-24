package fr.bonobo.soufflytv.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Listes Xtream (chaînes, films, séries) lues directement en objets, sans construire l'arbre JSON complet.
 * Avec des dizaines de milliers d'entrées, l'arbre JSON prenait plusieurs fois la taille de la réponse
 * et pouvait saturer la mémoire d'un téléphone ou d'une TV (« OutOfMemoryError »).
 */

/** Texte d'un champ envoyé tantôt en chaîne, tantôt en nombre ou booléen ; "" pour un objet ou un tableau. */
internal object LooseString : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LooseString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val json = decoder as? JsonDecoder ?: return decoder.decodeString()
        val element = json.decodeJsonElement()
        return if (element is JsonPrimitive && element !is JsonNull) element.content else ""
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

/** Une entrée des listes get_live_streams / get_vod_streams / get_series (seuls les champs utiles). */
@Serializable
internal class XtItem(
    @SerialName("stream_id") @Serializable(with = LooseString::class) val streamId: String? = null,
    @SerialName("series_id") @Serializable(with = LooseString::class) val seriesId: String? = null,
    @Serializable(with = LooseString::class) val name: String? = null,
    @SerialName("stream_icon") @Serializable(with = LooseString::class) val streamIcon: String? = null,
    @Serializable(with = LooseString::class) val cover: String? = null,
    @SerialName("category_id") @Serializable(with = LooseString::class) val categoryId: String? = null,
    @Serializable(with = LooseString::class) val num: String? = null,
    @SerialName("epg_channel_id") @Serializable(with = LooseString::class) val epgChannelId: String? = null,
    @SerialName("tv_archive") @Serializable(with = LooseString::class) val tvArchive: String? = null,
    @SerialName("tv_archive_duration") @Serializable(with = LooseString::class) val tvArchiveDuration: String? = null,
    @SerialName("container_extension") @Serializable(with = LooseString::class) val containerExtension: String? = null,
    @Serializable(with = LooseString::class) val plot: String? = null,
    @Serializable(with = LooseString::class) val rating: String? = null,
    @Serializable(with = LooseString::class) val added: String? = null,
    @SerialName("last_modified") @Serializable(with = LooseString::class) val lastModified: String? = null,
) {
    companion object {
        /** Même entrée à partir d'un objet JSON déjà lu (réponses inhabituelles). */
        fun from(o: JsonObject) = XtItem(
            streamId = o.s("stream_id"), seriesId = o.s("series_id"), name = o.s("name"),
            streamIcon = o.s("stream_icon"), cover = o.s("cover"), categoryId = o.s("category_id"),
            num = o.s("num"), epgChannelId = o.s("epg_channel_id"), tvArchive = o.s("tv_archive"),
            tvArchiveDuration = o.s("tv_archive_duration"), containerExtension = o.s("container_extension"),
            plot = o.s("plot"), rating = o.s("rating"), added = o.s("added"), lastModified = o.s("last_modified"),
        )
    }
}

/** Valeur nettoyée (espaces, "null" écrit en toutes lettres) ; null si vide. */
internal fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

private val ItemsJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/**
 * Liste d'entrées : tableau JSON ou objet indexé ({"1": {…}, "2": {…}}).
 * null si la réponse n'a pas cette forme (on retombe alors sur la lecture JSON classique).
 */
internal fun decodeXtItems(text: String): List<XtItem>? {
    val start = text.indexOfFirst { it == '{' || it == '[' }
    if (start < 0) return null
    val body = if (start == 0) text else text.substring(start)
    return try {
        if (body[0] == '[') {
            ItemsJson.decodeFromString(ListSerializer(XtItem.serializer()), body)
        } else {
            ItemsJson.decodeFromString(MapSerializer(String.serializer(), XtItem.serializer()), body).values.toList()
        }
    } catch (e: Exception) {
        null
    }
}
