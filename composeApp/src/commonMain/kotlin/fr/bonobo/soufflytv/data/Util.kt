package fr.bonobo.soufflytv.data

import fr.bonobo.soufflytv.model.Source
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.Url
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

const val DEFAULT_USER_AGENT = "VLC/3.0.21 LibVLC/3.0.21"
const val MAG_USER_AGENT =
    "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

/** Erreur « métier » dont le message peut être montré tel quel à l'utilisateur. */
class IptvException(message: String) : Exception(message)

fun createHttpClient(): HttpClient = HttpClient {
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 60_000
        requestTimeoutMillis = 180_000
    }
    followRedirects = true
    expectSuccess = false
}

fun newId(): String = Random.nextLong().toULong().toString(16)

/** Ajoute http:// si l'utilisateur a oublié le schéma. */
fun normalizeUrl(input: String): String {
    val t = input.trim()
    return if ("://" in t) t else "http://$t"
}

/** http(s)://hôte[:port] */
fun originOf(url: Url): String = buildString {
    append(url.protocol.name)
    append("://")
    append(url.host)
    if (url.port != url.protocol.defaultPort) {
        append(':')
        append(url.port)
    }
}

fun hostOf(input: String): String =
    runCatching { Url(normalizeUrl(input)).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: input.trim()

// ---------- Adresse MAC ----------

private val MAC_REGEX = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

/** "001a79aabbcc" ou "00-1A-79-AA-BB-CC" → "00:1A:79:AA:BB:CC" */
fun normalizeMac(input: String): String {
    val hex = input.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }
    return if (hex.length == 12) hex.chunked(2).joinToString(":") else input.trim().uppercase()
}

fun isValidMac(input: String): Boolean = MAC_REGEX.matches(normalizeMac(input))

/** Extrait serveur / identifiant / mot de passe d'une URL Xtream complète (get.php?username=…). */
fun parseXtreamUrl(input: String): Triple<String, String, String>? {
    if (!input.contains("username=")) return null
    val url = runCatching { Url(normalizeUrl(input)) }.getOrNull() ?: return null
    val user = url.parameters["username"] ?: return null
    val pass = url.parameters["password"] ?: return null
    return Triple(originOf(url), user, pass)
}

// ---------- Libellés ----------

val Source.kindLabel: String
    get() = when (this) {
        is Source.M3u -> "M3U"
        is Source.Xtream -> "XTREAM"
        is Source.Stalker -> "STALKER"
    }

val Source.detail: String
    get() = when (this) {
        is Source.M3u -> hostOf(url)
        is Source.Xtream -> "$username @ ${hostOf(server)}"
        is Source.Stalker -> "$mac @ ${hostOf(portal)}"
    }

// ---------- JSON tolérant (les serveurs IPTV sont rarement rigoureux) ----------

internal val LenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** Valeur texte d'un champ, qu'il soit envoyé en nombre ou en chaîne. */
internal fun JsonObject.s(key: String): String? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content.trim().takeIf { it.isNotEmpty() && it != "null" }
}

/** Liste d'objets, que le serveur réponde par un tableau ou par un objet indexé. */
internal fun JsonElement?.asObjects(): List<JsonObject> = when (this) {
    is JsonArray -> mapNotNull { it as? JsonObject }
    is JsonObject -> values.mapNotNull { it as? JsonObject }
    else -> emptyList()
}

/** Parse en ignorant d'éventuels caractères parasites avant le JSON. */
internal fun parseJsonOrNull(text: String): JsonElement? {
    val start = text.indexOfFirst { it == '{' || it == '[' }
    if (start < 0) return null
    return try {
        LenientJson.parseToJsonElement(text.substring(start))
    } catch (e: Exception) {
        null
    }
}

/** Textes encodés en base64 par Xtream (guide TV) ; renvoie le texte brut s'il ne l'est pas. */
@OptIn(ExperimentalEncodingApi::class)
internal fun decodeBase64OrRaw(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val decoded = try {
        Base64.Default.decode(value.trim()).decodeToString()
    } catch (e: IllegalArgumentException) {
        return value.trim()
    }
    return if ('\uFFFD' in decoded) value.trim() else decoded.trim().ifEmpty { null }
}

private val ADULT_REGEX = Regex("(?i)(\\badult|xxx|\\+ ?18|18 ?\\+|porn|for adults|adultes|\\bsexy?\\b)")

/** Catégorie à masquer quand le filtre adulte est actif. */
fun isAdultCategory(name: String): Boolean = ADULT_REGEX.containsMatchIn(name)

// ---------- Messages d'erreur lisibles ----------

fun Throwable.toUserMessage(): String = when (this) {
    is IptvException -> message ?: "Erreur"
    is HttpRequestTimeoutException -> "Le serveur ne répond pas (délai dépassé)"
    else -> {
        val name = this::class.simpleName.orEmpty()
        when {
            name.contains("UnknownHost") || name.contains("UnresolvedAddress") -> "Adresse du serveur introuvable"
            name.contains("Timeout") -> "Le serveur ne répond pas (délai dépassé)"
            name.contains("Connect") -> "Connexion au serveur impossible"
            name.contains("SSL") || name.contains("Certificate") -> "Erreur de sécurité (certificat SSL)"
            else -> "Erreur réseau : ${message ?: name.ifEmpty { "inconnue" }}"
        }
    }
}

/** 117 → « 1 h 57 », 45 → « 45 min ». */
fun runtimeLabel(totalMinutes: Int): String? = when {
    totalMinutes <= 0 -> null
    totalMinutes < 60 -> "$totalMinutes min"
    else -> "${totalMinutes / 60} h ${(totalMinutes % 60).toString().padStart(2, '0')}"
}

/** Durée Xtream « 01:57:10 » ou « 117 » (minutes) → minutes. */
fun parseRuntimeMinutes(text: String?): Int? {
    val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parts = t.split(':').map { it.trim().toIntOrNull() ?: return null }
    return when (parts.size) {
        1 -> parts[0]
        2 -> parts[0] * 60 + parts[1]
        3 -> parts[0] * 60 + parts[1] + if (parts[2] >= 30) 1 else 0
        else -> null
    }?.takeIf { it > 0 }
}

/** Note sur 10 : « 6.1 » → « 6,1 » ; null pour 0, N/A… */
fun ratingValue(text: String?): String? {
    val value = text?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0.0 && it <= 10.0 } ?: return null
    val rounded = kotlin.math.round(value * 10).toInt()
    return "${rounded / 10},${rounded % 10}"
}

/** « 6.1 » → « ★ 6,1 » */
fun ratingLabel(text: String?): String? = ratingValue(text)?.let { "★ $it" }

/** Ligne d'infos d'un film : année · genre · durée · note. */
fun movieMetaLine(year: String?, genre: String?, minutes: Int?, rating: String?): String? =
    listOfNotNull(
        year?.trim()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) },
        genre?.trim()?.takeIf { it.isNotEmpty() && it != "N/A" },
        minutes?.let(::runtimeLabel),
        ratingLabel(rating),
    ).joinToString(" · ").ifEmpty { null }

private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")

/** Bande-annonce fournie par la source : identifiant YouTube (« dQw4w9WgXcQ ») ou lien complet → lien. */
fun trailerUrl(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true) -> value
        YOUTUBE_ID.matches(value) -> "https://www.youtube.com/watch?v=$value"
        else -> null
    }
}
