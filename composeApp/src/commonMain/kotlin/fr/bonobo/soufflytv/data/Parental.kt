package fr.bonobo.soufflytv.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/*
 * Contrôle parental : un code PIN à 4 chiffres protège les catégories adultes, les réglages
 * et les comptes. Une fois le code saisi, tout reste ouvert jusqu'à la fermeture de l'appli
 * (ou « Verrouiller maintenant »). Le code n'est jamais enregistré en clair : seulement son empreinte.
 */

/** Ce que le code PIN peut protéger. */
enum class ParentalLock { ADULT, SETTINGS, ACCOUNTS }

/** Demande de code en attente : affichée par-dessus l'écran, [onSuccess] lancé après le bon code. */
class PinPrompt(val reason: String, val onSuccess: () -> Unit)

class ParentalControl(private val settings: SettingsStore) {

    /** Code saisi pendant cette session : plus rien n'est demandé jusqu'à la fermeture. */
    var unlocked by mutableStateOf(false)
        private set

    var prompt by mutableStateOf<PinPrompt?>(null)
        private set

    val enabled: Boolean get() = settings.value.parental && settings.value.pinHash.isNotEmpty()

    /** true si [what] est protégé et pas encore déverrouillé. */
    fun locks(what: ParentalLock): Boolean {
        if (!enabled || unlocked) return false
        val s = settings.value
        return when (what) {
            ParentalLock.ADULT -> s.lockAdult
            ParentalLock.SETTINGS -> s.lockSettings
            ParentalLock.ACCOUNTS -> s.lockAccounts
        }
    }

    /** Lance [action] tout de suite, ou après le code si [what] est protégé. */
    fun guard(what: ParentalLock, reason: String, action: () -> Unit) {
        if (locks(what)) prompt = PinPrompt(reason, action) else action()
    }

    /** Demande le code quoi qu'il arrive (changer le code, couper le contrôle parental…). */
    fun ask(reason: String, action: () -> Unit) {
        if (enabled) prompt = PinPrompt(reason, action) else action()
    }

    fun matches(pin: String): Boolean = enabled && pinHash(pin) == settings.value.pinHash

    /** Code saisi dans la fenêtre : true s'il est bon (la fenêtre se ferme et l'action se lance). */
    fun submit(pin: String): Boolean {
        if (!matches(pin)) return false
        unlocked = true
        val pending = prompt
        prompt = null
        pending?.onSuccess?.invoke()
        return true
    }

    fun cancel() {
        prompt = null
    }

    fun lockNow() {
        unlocked = false
    }

    /** Nouveau code (création ou changement) : le contrôle parental est actif, la session reste ouverte. */
    fun setPin(pin: String) {
        settings.update { it.copy(parental = true, pinHash = pinHash(pin)) }
        unlocked = true
    }

    fun disable() {
        settings.update { it.copy(parental = false, pinHash = "") }
        unlocked = false
    }
}

/** Empreinte du code PIN (SHA-256 avec un sel propre à SoufflyTV). */
fun pinHash(pin: String): String =
    sha256("soufflytv-pin|$pin".encodeToByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/** SHA-256 (FIPS 180-4), en Kotlin pur pour fonctionner sur toutes les plateformes. */
internal fun sha256(message: ByteArray): ByteArray {
    val k = longArrayOf(
        0x428a2f98L, 0x71374491L, 0xb5c0fbcfL, 0xe9b5dba5L, 0x3956c25bL, 0x59f111f1L, 0x923f82a4L, 0xab1c5ed5L,
        0xd807aa98L, 0x12835b01L, 0x243185beL, 0x550c7dc3L, 0x72be5d74L, 0x80deb1feL, 0x9bdc06a7L, 0xc19bf174L,
        0xe49b69c1L, 0xefbe4786L, 0x0fc19dc6L, 0x240ca1ccL, 0x2de92c6fL, 0x4a7484aaL, 0x5cb0a9dcL, 0x76f988daL,
        0x983e5152L, 0xa831c66dL, 0xb00327c8L, 0xbf597fc7L, 0xc6e00bf3L, 0xd5a79147L, 0x06ca6351L, 0x14292967L,
        0x27b70a85L, 0x2e1b2138L, 0x4d2c6dfcL, 0x53380d13L, 0x650a7354L, 0x766a0abbL, 0x81c2c92eL, 0x92722c85L,
        0xa2bfe8a1L, 0xa81a664bL, 0xc24b8b70L, 0xc76c51a3L, 0xd192e819L, 0xd6990624L, 0xf40e3585L, 0x106aa070L,
        0x19a4c116L, 0x1e376c08L, 0x2748774cL, 0x34b0bcb5L, 0x391c0cb3L, 0x4ed8aa4aL, 0x5b9cca4fL, 0x682e6ff3L,
        0x748f82eeL, 0x78a5636fL, 0x84c87814L, 0x8cc70208L, 0x90befffaL, 0xa4506cebL, 0xbef9a3f7L, 0xc67178f2L,
    ).map { it.toInt() }
    val h = longArrayOf(
        0x6a09e667L, 0xbb67ae85L, 0x3c6ef372L, 0xa54ff53aL, 0x510e527fL, 0x9b05688cL, 0x1f83d9abL, 0x5be0cd19L,
    ).map { it.toInt() }.toIntArray()
    val bitLength = message.size.toLong() * 8
    val padded = ByteArray(((message.size + 9 + 63) / 64) * 64)
    message.copyInto(padded)
    padded[message.size] = 0x80.toByte()
    for (i in 0 until 8) padded[padded.size - 1 - i] = (bitLength ushr (8 * i)).toByte()

    val w = IntArray(64)
    for (chunk in padded.indices step 64) {
        for (i in 0 until 16) {
            val j = chunk + i * 4
            w[i] = ((padded[j].toInt() and 0xff) shl 24) or ((padded[j + 1].toInt() and 0xff) shl 16) or
                ((padded[j + 2].toInt() and 0xff) shl 8) or (padded[j + 3].toInt() and 0xff)
        }
        for (i in 16 until 64) {
            val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
            val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }
        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
        var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
        for (i in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = hh + s1 + ch + k[i] + w[i]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + maj
            hh = g; g = f; f = e; e = d + t1
            d = c; c = b; b = a; a = t1 + t2
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d
        h[4] += e; h[5] += f; h[6] += g; h[7] += hh
    }
    val out = ByteArray(32)
    for (i in 0 until 8) {
        out[i * 4] = (h[i] ushr 24).toByte()
        out[i * 4 + 1] = (h[i] ushr 16).toByte()
        out[i * 4 + 2] = (h[i] ushr 8).toByte()
        out[i * 4 + 3] = h[i].toByte()
    }
    return out
}
