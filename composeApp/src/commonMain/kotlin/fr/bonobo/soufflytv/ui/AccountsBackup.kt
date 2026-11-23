package fr.bonobo.soufflytv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.bonobo.soufflytv.AppState
import fr.bonobo.soufflytv.data.AccountBackupCodec
import fr.bonobo.soufflytv.data.BackupException
import fr.bonobo.soufflytv.data.backupFileName
import fr.bonobo.soufflytv.data.hostOf
import fr.bonobo.soufflytv.data.toUserMessage
import fr.bonobo.soufflytv.platform.SaveResult
import fr.bonobo.soufflytv.platform.nowMillis
import fr.bonobo.soufflytv.platform.rememberFilePicker
import fr.bonobo.soufflytv.platform.rememberFileSaver
import fr.bonobo.soufflytv.ui.theme.Cm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed interface BackupStep {
    data object Export : BackupStep
    data object ImportChoice : BackupStep
    data object ImportLink : BackupStep
    data class ImportPassword(val fileName: String, val content: String) : BackupStep
}

/**
 * Réglages → Comptes : sauvegarde chiffrée des sources dans un fichier, et restauration
 * depuis un fichier ou depuis un lien (TV sans explorateur de fichiers).
 */
@Composable
fun AccountsBackupSection(state: AppState) {
    val sources by state.sources.collectAsState()
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<BackupStep?>(null) }
    var busy by remember { mutableStateOf(false) }
    var dialogError by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // message, erreur ?

    fun open(next: BackupStep?) {
        step = next
        dialogError = null
    }

    val saver = rememberFileSaver { result ->
        status = when (result) {
            is SaveResult.Saved -> "Sauvegarde enregistrée : ${result.where}" to false
            SaveResult.Cancelled -> null
            is SaveResult.Failed -> result.message to true
        }
    }
    val picker = rememberFilePicker(title = "Choisir une sauvegarde SoufflyTV") { picked ->
        if (picked != null) open(BackupStep.ImportPassword(picked.name, picked.content))
    }

    Text(
        "Garde une copie chiffrée de tes comptes (M3U, Xtream, portails MAC) pour les retrouver " +
            "après une réinstallation ou les copier sur un autre appareil.",
        color = Cm.TextMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 14.dp),
    )
    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(enabled = sources.isNotEmpty(), onClick = { open(BackupStep.Export) }) {
            Text("Sauvegarder les comptes")
        }
        OutlinedButton(onClick = { open(BackupStep.ImportChoice) }) { Text("Restaurer") }
    }
    status?.let { (message, isError) ->
        Text(
            message,
            color = if (isError) Cm.Error else Cm.Success,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
    }

    when (val current = step) {
        null -> Unit

        BackupStep.Export -> {
            var password by remember { mutableStateOf("") }
            var confirm by remember { mutableStateOf("") }
            val tooShort = password.length < AccountBackupCodec.MIN_PASSWORD_LENGTH
            val mismatch = confirm.isNotEmpty() && confirm != password
            BackupDialog(
                title = "Sauvegarder ${sources.size} compte${if (sources.size > 1) "s" else ""}",
                busy = busy,
                error = dialogError,
                confirmLabel = "Enregistrer",
                confirmEnabled = !tooShort && confirm == password,
                onConfirm = {
                    busy = true
                    scope.launch {
                        try {
                            val content = state.exportAccounts(password)
                            open(null)
                            saver(backupFileName(nowMillis()), content)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            dialogError = e.toUserMessage()
                        } finally {
                            busy = false
                        }
                    }
                },
                onDismiss = { open(null) },
            ) {
                Text(
                    "Choisis un mot de passe d'au moins ${AccountBackupCodec.MIN_PASSWORD_LENGTH} caractères. " +
                        "Il sera demandé pour restaurer : sans lui, la sauvegarde est illisible, même pour toi.",
                    color = Cm.TextMuted,
                    fontSize = 13.sp,
                )
                PasswordField("Mot de passe de la sauvegarde", password) { password = it }
                PasswordField(
                    "Confirmer le mot de passe", confirm,
                    error = if (mismatch) "Les deux mots de passe sont différents" else null,
                ) { confirm = it }
            }
        }

        BackupStep.ImportChoice -> BackupDialog(
            title = "Restaurer des comptes",
            busy = false,
            error = dialogError,
            confirmLabel = null,
            confirmEnabled = false,
            onConfirm = {},
            onDismiss = { open(null) },
        ) {
            Text(
                "Les comptes de la sauvegarde sont ajoutés à ceux déjà présents ; un compte déjà présent est mis à jour.",
                color = Cm.TextMuted,
                fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    if (!picker()) {
                        dialogError = "Pas d'explorateur de fichiers sur cet appareil : passe par un lien"
                        step = BackupStep.ImportLink
                    }
                }) { Text("Depuis un fichier") }
                OutlinedButton(onClick = { open(BackupStep.ImportLink) }) { Text("Depuis un lien") }
            }
        }

        BackupStep.ImportLink -> {
            var url by remember { mutableStateOf("") }
            BackupDialog(
                title = "Restaurer depuis un lien",
                busy = busy,
                error = dialogError,
                confirmLabel = "Télécharger",
                confirmEnabled = url.isNotBlank(),
                onConfirm = {
                    busy = true
                    dialogError = null
                    scope.launch {
                        try {
                            val content = state.downloadText(url.trim())
                            open(BackupStep.ImportPassword(hostOf(url.trim()), content))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            dialogError = "Téléchargement impossible : ${e.toUserMessage()}"
                        } finally {
                            busy = false
                        }
                    }
                },
                onDismiss = { open(null) },
            ) {
                Text(
                    "Pratique pour une TV : dépose le fichier de sauvegarde sur un site ou un serveur à toi, " +
                        "puis saisis son adresse ici. Le fichier reste illisible sans le mot de passe.",
                    color = Cm.TextMuted,
                    fontSize = 13.sp,
                )
                RemoteTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "Adresse du fichier",
                    placeholder = "https://exemple.com/SoufflyTV-comptes.json",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
            }
        }

        is BackupStep.ImportPassword -> {
            var password by remember(current) { mutableStateOf("") }
            BackupDialog(
                title = "Restaurer « ${current.fileName} »",
                busy = busy,
                error = dialogError,
                confirmLabel = "Restaurer",
                confirmEnabled = password.isNotEmpty(),
                onConfirm = {
                    busy = true
                    dialogError = null
                    scope.launch {
                        try {
                            val summary = state.importAccounts(current.content, password)
                            open(null)
                            val total = summary.added + summary.updated
                            status = buildString {
                                append("$total compte${if (total > 1) "s" else ""} restauré${if (total > 1) "s" else ""}")
                                if (summary.added > 0 && summary.updated > 0) {
                                    append(" (${summary.added} ajouté${if (summary.added > 1) "s" else ""}, ")
                                    append("${summary.updated} mis à jour)")
                                }
                            } to false
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: BackupException) {
                            dialogError = e.message
                        } catch (e: Throwable) {
                            dialogError = e.toUserMessage()
                        } finally {
                            busy = false
                        }
                    }
                },
                onDismiss = { open(null) },
            ) {
                Text("Mot de passe choisi lors de la sauvegarde :", color = Cm.TextMuted, fontSize = 13.sp)
                PasswordField("Mot de passe de la sauvegarde", password) { password = it }
            }
        }
    }
}

@Composable
private fun BackupDialog(
    title: String,
    busy: Boolean,
    error: String?,
    confirmLabel: String?,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    AppAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = title,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Cm.Cyan, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Patiente…", color = Cm.TextMuted, fontSize = 13.sp)
                    }
                }
                if (error != null) Text(error, color = Cm.Error, fontSize = 13.sp)
            }
        },
        confirmButton = {
            if (confirmLabel != null) {
                TextButton(enabled = confirmEnabled && !busy, onClick = onConfirm) { Text(confirmLabel) }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(if (confirmLabel == null) "Fermer" else "Annuler") }
        },
    )
}

@Composable
private fun PasswordField(label: String, value: String, error: String? = null, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    Column {
        RemoteTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            label = label,
            hidden = !visible,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (visible) KeyboardType.Text else KeyboardType.Password,
                autoCorrectEnabled = false,
            ),
            isError = error != null,
            supporting = error,
        )
        Row(
            Modifier.padding(top = 2.dp).tvClickable { visible = !visible }.padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = visible,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = Cm.Cyan, checkmarkColor = Cm.OnCyan),
            )
            Spacer(Modifier.width(6.dp))
            Text("Afficher", color = Cm.Text, fontSize = 13.sp)
        }
    }
}
