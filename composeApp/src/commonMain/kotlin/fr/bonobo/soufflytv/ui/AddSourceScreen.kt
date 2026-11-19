package fr.bonobo.soufflytv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.bonobo.soufflytv.AppState
import fr.bonobo.soufflytv.data.hostOf
import fr.bonobo.soufflytv.data.isValidMac
import fr.bonobo.soufflytv.data.newId
import fr.bonobo.soufflytv.data.normalizeMac
import fr.bonobo.soufflytv.data.parseXtreamUrl
import fr.bonobo.soufflytv.data.toUserMessage
import fr.bonobo.soufflytv.model.Source
import fr.bonobo.soufflytv.platform.nowMillis
import fr.bonobo.soufflytv.platform.rememberFilePicker
import fr.bonobo.soufflytv.platform.writeAppFile
import fr.bonobo.soufflytv.ui.theme.Cm
import fr.bonobo.soufflytv.ui.theme.MonoLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class Kind(val title: String, val hint: String) {
    M3U("Playlist", "M3U · M3U8"),
    XTREAM("Xtream Codes", "Identifiant + mot de passe"),
    STALKER("Portail MAC", "Stalker · Ministra"),
}

private sealed interface TestResult {
    data object Running : TestResult
    data class Ok(val message: String) : TestResult
    data class Failed(val message: String) : TestResult
}

@Composable
fun AddSourceScreen(state: AppState, editing: Source?) {
    val m3u = editing as? Source.M3u
    val xtream = editing as? Source.Xtream
    val stalker = editing as? Source.Stalker

    val id = remember { editing?.id ?: newId() }
    var kind by remember {
        mutableStateOf(
            when (editing) {
                is Source.Xtream -> Kind.XTREAM
                is Source.Stalker -> Kind.STALKER
                else -> Kind.M3U
            }
        )
    }
    var name by remember { mutableStateOf(editing?.name ?: "") }

    // M3U
    var fromFile by remember { mutableStateOf(m3u?.localFile != null) }
    var m3uUrl by remember { mutableStateOf(m3u?.url ?: "") }
    var userAgent by remember { mutableStateOf(m3u?.userAgent ?: "") }
    var epgUrl by remember { mutableStateOf(m3u?.epgUrl ?: "") }
    var localFile by remember { mutableStateOf(m3u?.localFile) }
    var localFileName by remember { mutableStateOf(m3u?.localFileName) }
    var importing by remember { mutableStateOf(false) }

    // Xtream
    var server by remember { mutableStateOf(xtream?.server ?: "") }
    var username by remember { mutableStateOf(xtream?.username ?: "") }
    var password by remember { mutableStateOf(xtream?.password ?: "") }

    // Stalker
    var portal by remember { mutableStateOf(stalker?.portal ?: "") }
    var mac by remember { mutableStateOf(stalker?.mac ?: "") }
    var showAdvanced by remember {
        mutableStateOf(stalker?.let { it.serialNumber != null || it.deviceId != null || it.signature != null } ?: false)
    }
    var serial by remember { mutableStateOf(stalker?.serialNumber ?: "") }
    var deviceId by remember { mutableStateOf(stalker?.deviceId ?: "") }
    var deviceId2 by remember { mutableStateOf(stalker?.deviceId2 ?: "") }
    var signature by remember { mutableStateOf(stalker?.signature ?: "") }

    var test by remember { mutableStateOf<TestResult?>(null) }
    val scope = rememberCoroutineScope()

    val picker = rememberFilePicker { picked ->
        if (picked == null) {
            importing = false
            return@rememberFilePicker
        }
        scope.launch {
            if (!picked.content.contains("#EXTINF", ignoreCase = true)) {
                test = TestResult.Failed("« ${picked.name} » n'est pas une playlist M3U")
            } else {
                // nom unique : une nouvelle importation invalide la playlist en cache
                val file = "playlist-$id-${nowMillis()}.m3u"
                writeAppFile(file, picked.content)
                localFile = file
                localFileName = picked.name
                if (name.isBlank()) name = picked.name.substringBeforeLast('.')
            }
            importing = false
        }
    }

    val macOk = isValidMac(mac)
    val draft: Source? = when (kind) {
        Kind.M3U -> when {
            fromFile && localFile != null -> Source.M3u(
                id, name.ifBlank { localFileName ?: "Playlist" }, "", userAgent.trim().ifBlank { null },
                epgUrl.trim().ifBlank { null }, localFile, localFileName,
            )
            !fromFile && m3uUrl.isNotBlank() -> Source.M3u(
                id, name.ifBlank { hostOf(m3uUrl) }, m3uUrl.trim(), userAgent.trim().ifBlank { null },
                epgUrl.trim().ifBlank { null },
            )
            else -> null
        }
        Kind.XTREAM -> if (server.isBlank() || username.isBlank() || password.isBlank()) null else
            Source.Xtream(id, name.ifBlank { hostOf(server) }, server.trim(), username.trim(), password)
        Kind.STALKER -> if (portal.isBlank() || !macOk) null else
            Source.Stalker(
                id, name.ifBlank { hostOf(portal) }, portal.trim(), normalizeMac(mac),
                serial.trim().ifBlank { null }, deviceId.trim().ifBlank { null },
                deviceId2.trim().ifBlank { null }, signature.trim().ifBlank { null },
            )
    }

    // Le résultat d'un test ne vaut que pour la configuration testée
    LaunchedEffect(draft) { if (test !is TestResult.Running) test = null }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = if (editing == null) "Nouvelle source" else "Modifier la source", onBack = { state.back() })

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 580.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (editing == null) {
                    SectionLabel("TYPE DE SOURCE")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Kind.entries.forEach { k ->
                            KindCard(k, selected = k == kind, modifier = Modifier.weight(1f)) { kind = k }
                        }
                    }
                }

                Field("Nom (facultatif)", name, { name = it }, placeholder = "Ma source", keyboardType = KeyboardType.Text)

                when (kind) {
                    Kind.M3U -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("Lien", selected = !fromFile) { fromFile = false }
                            Pill("Fichier", selected = fromFile) { fromFile = true }
                        }
                        if (fromFile) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .border(1.dp, Cm.Outline, RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(AppIcons.File, contentDescription = null, tint = Cm.Cyan)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    localFileName ?: "Aucun fichier choisi",
                                    color = if (localFileName != null) Cm.Text else Cm.TextMuted,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (importing) {
                                    CircularProgressIndicator(Modifier.size(18.dp), color = Cm.Cyan, strokeWidth = 2.dp)
                                } else {
                                    TextButton(onClick = {
                                        importing = true
                                        picker()
                                    }) { Text(if (localFileName == null) "Choisir" else "Changer") }
                                }
                            }
                        } else {
                            Field("URL de la playlist", m3uUrl, { m3uUrl = it }, placeholder = "http://exemple.com/playlist.m3u")
                        }
                        Field(
                            "Guide TV XMLTV (facultatif)", epgUrl, { epgUrl = it },
                            placeholder = "http://exemple.com/guide.xml.gz",
                            supporting = "Sinon, celui annoncé par la playlist (url-tvg) est utilisé",
                        )
                        Field(
                            "User-Agent (facultatif)", userAgent, { userAgent = it },
                            placeholder = "Laisser vide pour la valeur par défaut",
                            keyboardType = KeyboardType.Text,
                        )
                    }

                    Kind.XTREAM -> {
                        Field(
                            "Serveur", server,
                            { input ->
                                // Coller un lien complet get.php?username=…&password=… remplit tout
                                val parsed = parseXtreamUrl(input)
                                if (parsed != null) {
                                    server = parsed.first
                                    username = parsed.second
                                    password = parsed.third
                                } else {
                                    server = input
                                }
                            },
                            placeholder = "http://exemple.com:8080",
                            supporting = "Astuce : tu peux coller directement un lien get.php complet",
                        )
                        Field("Identifiant", username, { username = it }, keyboardType = KeyboardType.Text)
                        Field("Mot de passe", password, { password = it }, password = true)
                    }

                    Kind.STALKER -> {
                        Field("URL du portail", portal, { portal = it }, placeholder = "http://exemple.com:8080/c/")
                        Field(
                            "Adresse MAC", mac, { mac = it },
                            placeholder = "00:1A:79:00:00:00",
                            keyboardType = KeyboardType.Text,
                            isError = mac.isNotBlank() && !macOk,
                            supporting = if (mac.isNotBlank() && !macOk) "Format attendu : 00:1A:79:XX:XX:XX"
                            else "L'adresse MAC enregistrée chez ton fournisseur",
                        )
                        TextButton(onClick = { showAdvanced = !showAdvanced }) {
                            Text(if (showAdvanced) "Masquer les options avancées" else "Options avancées (numéro de série, device ID)")
                        }
                        if (showAdvanced) {
                            Field("Numéro de série (SN)", serial, { serial = it }, keyboardType = KeyboardType.Text)
                            Field("Device ID", deviceId, { deviceId = it }, keyboardType = KeyboardType.Text)
                            Field("Device ID 2", deviceId2, { deviceId2 = it }, keyboardType = KeyboardType.Text)
                            Field("Signature", signature, { signature = it }, keyboardType = KeyboardType.Text)
                        }
                    }
                }

                when (val t = test) {
                    TestResult.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Cm.Cyan, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Test en cours…", color = Cm.TextMuted, fontSize = 14.sp)
                    }
                    is TestResult.Ok -> Text(t.message, color = Cm.Success, fontSize = 14.sp)
                    is TestResult.Failed -> Text(t.message, color = Cm.Error, fontSize = 14.sp)
                    null -> Unit
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                    OutlinedButton(
                        enabled = draft != null && test != TestResult.Running,
                        onClick = {
                            val source = draft ?: return@OutlinedButton
                            scope.launch {
                                test = TestResult.Running
                                test = try {
                                    TestResult.Ok(state.testSource(source))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    TestResult.Failed(e.toUserMessage())
                                }
                            }
                        },
                    ) { Text("Tester") }

                    Button(
                        enabled = draft != null,
                        modifier = Modifier.weight(1f),
                        onClick = { draft?.let(state::saveSource) },
                    ) { Text("Enregistrer") }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    "SoufflyTV est un simple lecteur : il ne fournit aucune chaîne. " +
                        "N'utilise que des sources auxquelles tu as légalement accès. " +
                        "Les identifiants restent sur cet appareil (chiffrés sur Android).",
                    color = Cm.TextMuted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun KindCard(kind: Kind, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .border(1.dp, if (selected) Cm.Cyan else Cm.Outline, shape)
            .background(Cm.Surface, shape)
            .tvClickable(selected = selected, shape = shape, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(
            kind.title,
            color = if (selected) Cm.Cyan else Cm.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Text(kind.hint, color = Cm.TextMuted, fontSize = 11.sp, maxLines = 2)
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Uri,
    password: Boolean = false,
    isError: Boolean = false,
    supporting: String? = null,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        label = { Text(label) },
        placeholder = if (placeholder.isNotEmpty()) {
            { Text(placeholder, color = Cm.TextMuted.copy(alpha = 0.7f)) }
        } else null,
        isError = isError,
        supportingText = if (supporting != null) {
            { Text(supporting) }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
        visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (password) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        AppIcons.Visibility,
                        contentDescription = if (visible) "Masquer" else "Afficher",
                        tint = if (visible) Cm.Cyan else Cm.TextMuted,
                    )
                }
            }
        } else null,
    )
}
