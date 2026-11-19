package fr.bonobo.soufflytv.platform

import fr.bonobo.soufflytv.data.IptvException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Téléchargement en flux (guide XMLTV) : suit les redirections http ↔ https,
 * décompresse le gzip (détecté par sa signature) et transmet le texte par morceaux.
 */
internal suspend fun streamTextJvm(url: String, headers: Map<String, String>, onChunk: (String) -> Unit) =
    withContext(Dispatchers.IO) {
        var current = url
        var connection: HttpURLConnection? = null
        try {
            for (hop in 0 until 6) {
                val c = (URL(current).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = false
                    setRequestProperty("Accept-Encoding", "gzip")
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                val code = c.responseCode
                if (code in 300..399) {
                    val location = c.getHeaderField("Location")
                    c.disconnect()
                    if (location.isNullOrBlank()) throw IptvException("Guide TV : redirection invalide")
                    current = URL(URL(current), location).toString()
                    continue
                }
                if (code !in 200..299) {
                    c.disconnect()
                    throw IptvException("Guide TV : erreur HTTP $code")
                }
                connection = c
                break
            }
            val c = connection ?: throw IptvException("Guide TV : trop de redirections")
            var input: InputStream = BufferedInputStream(c.inputStream, 64 * 1024)
            input.mark(4)
            val b1 = input.read()
            val b2 = input.read()
            input.reset()
            if (b1 == 0x1f && b2 == 0x8b) input = GZIPInputStream(input, 64 * 1024)
            InputStreamReader(input, Charsets.UTF_8).use { reader ->
                val buffer = CharArray(32 * 1024)
                while (true) {
                    ensureActive()
                    val n = reader.read(buffer)
                    if (n < 0) break
                    onChunk(String(buffer, 0, n))
                }
            }
        } finally {
            connection?.disconnect()
        }
        Unit
    }
