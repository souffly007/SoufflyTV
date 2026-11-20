package fr.bonobo.soufflytv.data

import fr.bonobo.soufflytv.model.Source
import fr.bonobo.soufflytv.platform.passwordDecrypt
import fr.bonobo.soufflytv.platform.passwordEncrypt
import fr.bonobo.soufflytv.platform.secureRandomBytes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Sauvegarde des comptes (sources M3U, Xtream, Stalker) dans un fichier chiffré par mot de passe.
 *
 * Le fichier est un petit JSON lisible par SoufflyTV sur Android, TV, Linux et Windows :
 * {
 *   "format": "soufflytv-comptes", "version": 1,
 *   "kdf": "PBKDF2-HMAC-SHA256", "iterations": 210000,
 *   "salt": "…", "iv": "…", "data": "…"      ← base64, contenu chiffré en AES-256-GCM
 * }
 * Sans le mot de passe, identifiants et adresses sont illisibles.
 */
@Serializable
data class AccountBackup(
    val exportedAt: Long,
    val sources: List<Source>,
    /** Playlists M3U importées depuis un fichier : nom du fichier interne → contenu. */
    val playlists: Map<String, String> = emptyMap(),
)

@Serializable
private data class BackupEnvelope(
    val format: String,
    val version: Int,
    val kdf: String,
    val iterations: Int,
    val salt: String,
    val iv: String,
    @SerialName("data") val payload: String,
)

class BackupException(message: String) : Exception(message)

object AccountBackupCodec {

    private const val FORMAT = "soufflytv-comptes"
    private const val VERSION = 1
    private const val KDF = "PBKDF2-HMAC-SHA256"
    private const val ITERATIONS = 210_000
    const val MIN_PASSWORD_LENGTH = 6

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        encodeDefaults = true
    }
    private val prettyJson = Json(json) { prettyPrint = true }

    /** Calcul long (dérivation de clé) : à appeler hors du thread de l'interface. */
    @OptIn(ExperimentalEncodingApi::class)
    fun encode(backup: AccountBackup, password: String): String {
        require(password.length >= MIN_PASSWORD_LENGTH) { "Mot de passe trop court" }
        val salt = secureRandomBytes(16)
        val iv = secureRandomBytes(12)
        val plain = json.encodeToString(AccountBackup.serializer(), backup).encodeToByteArray()
        val encrypted = passwordEncrypt(plain, password, salt, iv, ITERATIONS)
        val envelope = BackupEnvelope(
            format = FORMAT,
            version = VERSION,
            kdf = KDF,
            iterations = ITERATIONS,
            salt = Base64.Default.encode(salt),
            iv = Base64.Default.encode(iv),
            payload = Base64.Default.encode(encrypted),
        )
        return prettyJson.encodeToString(BackupEnvelope.serializer(), envelope) + "\n"
    }

    /** Lève [BackupException] avec un message lisible si le fichier ou le mot de passe ne conviennent pas. */
    @OptIn(ExperimentalEncodingApi::class)
    fun decode(text: String, password: String): AccountBackup {
        val envelope = try {
            json.decodeFromString(BackupEnvelope.serializer(), text.trim())
        } catch (e: Exception) {
            throw BackupException("Ce fichier n'est pas une sauvegarde de comptes SoufflyTV")
        }
        if (envelope.format != FORMAT) throw BackupException("Ce fichier n'est pas une sauvegarde de comptes SoufflyTV")
        if (envelope.version > VERSION || envelope.kdf != KDF) {
            throw BackupException("Sauvegarde faite par une version plus récente de SoufflyTV : mets l'application à jour")
        }
        if (envelope.iterations !in 10_000..5_000_000) throw BackupException("Sauvegarde abîmée")

        val (salt, iv, encrypted) = try {
            Triple(
                Base64.Default.decode(envelope.salt),
                Base64.Default.decode(envelope.iv),
                Base64.Default.decode(envelope.payload),
            )
        } catch (e: IllegalArgumentException) {
            throw BackupException("Sauvegarde abîmée")
        }
        val plain = try {
            passwordDecrypt(encrypted, password, salt, iv, envelope.iterations)
        } catch (e: Exception) {
            // AES-GCM vérifie l'intégrité : mot de passe faux ou fichier modifié
            throw BackupException("Mot de passe incorrect")
        }
        return try {
            json.decodeFromString(AccountBackup.serializer(), plain.decodeToString())
        } catch (e: Exception) {
            throw BackupException("Sauvegarde abîmée")
        }
    }
}

/** « SoufflyTV-comptes-2026-11-19.json » */
fun backupFileName(epochMillis: Long): String {
    val days = epochMillis.floorDiv(86_400_000L)
    val (year, month, day) = civilFromDays(days)
    fun two(n: Int) = n.toString().padStart(2, '0')
    return "SoufflyTV-comptes-$year-${two(month)}-${two(day)}.json"
}

/** Jour depuis le 1er janvier 1970 → (année, mois, jour), calendrier grégorien. */
internal fun civilFromDays(daysSinceEpoch: Long): Triple<Int, Int, Int> {
    val z = daysSinceEpoch + 719_468
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
    return Triple(year, month, day)
}
