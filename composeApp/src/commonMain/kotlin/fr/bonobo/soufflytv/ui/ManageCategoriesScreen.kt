package fr.bonobo.soufflytv.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.bonobo.soufflytv.AppState
import fr.bonobo.soufflytv.data.CategoryGroup
import fr.bonobo.soufflytv.data.groupCategories
import fr.bonobo.soufflytv.data.toUserMessage
import fr.bonobo.soufflytv.model.Category
import fr.bonobo.soufflytv.model.Section
import fr.bonobo.soufflytv.model.Source
import fr.bonobo.soufflytv.ui.theme.Cm
import fr.bonobo.soufflytv.ui.theme.MonoLabel
import kotlinx.coroutines.CancellationException

/**
 * Choix des catégories affichées pour une source et une rubrique :
 * par pays / groupe d'un coup, tout masquer / tout afficher, ou une par une.
 */
@Composable
fun ManageCategoriesScreen(state: AppState, source: Source, section: Section) {
    val provider = remember(source) { state.provider(source) }
    val library = state.library
    var categories by remember { mutableStateOf<List<Category>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(retry) {
        error = null
        try {
            categories = provider.categories(section)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.toUserMessage()
        }
    }

    val hidden = library.hiddenIds(source.id, section)
    val all = categories.orEmpty()
    val groups = remember(all) { groupCategories(all) }
    val q = query.trim()
    val shown = if (q.isEmpty()) all else all.filter { it.name.contains(q, ignoreCase = true) }

    fun setHidden(ids: Collection<String>, hide: Boolean) = library.setHidden(source.id, section, ids, hide)

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = "Catégories · ${section.label}",
            subtitle = "${all.size - all.count { it.id in hidden }} / ${all.size} AFFICHÉES",
            onBack = { state.back() },
        )

        val err = error
        when {
            err != null -> ErrorBox(err, onRetry = { retry++ })
            categories == null -> LoadingBox("Chargement des catégories…")
            all.isEmpty() -> EmptyHint("Aucune catégorie dans cette rubrique")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Pill("Tout afficher", selected = false) { setHidden(all.map { it.id }, hide = false) }
                        Pill("Tout masquer", selected = false) { setHidden(all.map { it.id }, hide = true) }
                    }
                }

                if (groups.isNotEmpty()) {
                    item {
                        SectionLabel("PAR PAYS / GROUPE", Modifier.padding(start = 16.dp, top = 6.dp))
                        Text(
                            "Touche un groupe pour le masquer ou le réafficher en entier",
                            color = Cm.TextMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                    }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(groups) { group ->
                                GroupChip(group, hidden) { anyVisible ->
                                    setHidden(group.categories.map { it.id }, hide = anyVisible)
                                }
                            }
                        }
                    }
                }

                item {
                    SearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "Rechercher une catégorie",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                if (q.isNotEmpty() && shown.isNotEmpty()) {
                    item {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Pill("Afficher les ${shown.size} trouvées", selected = false) {
                                setHidden(shown.map { it.id }, hide = false)
                            }
                            Pill("Masquer les ${shown.size} trouvées", selected = false) {
                                setHidden(shown.map { it.id }, hide = true)
                            }
                        }
                    }
                }

                item { SectionLabel("CATÉGORIES", Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp)) }
                items(shown, key = { it.id }) { category ->
                    val visible = category.id !in hidden
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 1.dp)
                            .tvClickable { setHidden(listOf(category.id), hide = visible) }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = visible,
                            onCheckedChange = { checked -> setHidden(listOf(category.id), hide = !checked) },
                            colors = CheckboxDefaults.colors(checkedColor = Cm.Cyan, checkmarkColor = Cm.OnCyan),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            category.name,
                            color = if (visible) Cm.Text else Cm.TextMuted,
                            textDecoration = if (visible) null else TextDecoration.LineThrough,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Puce d'un groupe : cyan si tout est affiché, barrée si tout est masqué, pointillée si mélangé. */
@Composable
private fun GroupChip(group: CategoryGroup, hidden: Set<String>, onToggle: (anyVisible: Boolean) -> Unit) {
    val hiddenCount = group.categories.count { it.id in hidden }
    val total = group.categories.size
    val allVisible = hiddenCount == 0
    val allHidden = hiddenCount == total
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .border(1.dp, if (allVisible) Cm.Cyan else Cm.Outline, shape)
            .tvClickable(selected = allVisible, shape = shape) { onToggle(!allHidden) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            group.name,
            color = when {
                allVisible -> Cm.Cyan
                allHidden -> Cm.TextMuted
                else -> Cm.Text
            },
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textDecoration = if (allHidden) TextDecoration.LineThrough else null,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            when {
                allVisible -> "$total affichées"
                allHidden -> "$total masquées"
                else -> "${total - hiddenCount}/$total"
            },
            style = MonoLabel,
            color = Cm.TextMuted,
        )
    }
}
