package fr.remotecontrol.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import fr.remotecontrol.ui.common.SectionLabel
import fr.remotecontrol.ui.common.WorkRow
import fr.remotecontrol.ui.common.highlighted
import fr.remotecontrol.ui.theme.Fraunces
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import fr.remotecontrol.mirror.PublishTvView
import fr.remotecontrol.net.TvViewDto
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.remotecontrol.data.db.WorkSummary
import fr.remotecontrol.discovery.ServerState
import fr.remotecontrol.ui.common.CatalogueSwitch
import fr.remotecontrol.ui.common.RcIcons
import fr.remotecontrol.ui.common.ScreenHeader
import fr.remotecontrol.ui.common.Texts
import fr.remotecontrol.ui.theme.Inter
import fr.remotecontrol.ui.theme.RcColors

/**
 * Maquette « Recherche » : champ arrondi, aide, puis les résultats instantanés en sections (PERSONNES,
 * ŒUVRES, AUSSI CONNU SOUS), la saisie en laiton dans les noms et les titres. Toucher une personne
 * remplace la saisie par son nom.
 */
@Composable
fun SearchScreen(
    state: SearchUi,
    /** Vue publiée sur la télé ([SearchViewModel.tvView]), en fin de frappe. */
    tvView: TvViewDto?,
    server: ServerState,
    onQuery: (String) -> Unit,
    onPlayableOnly: (Boolean) -> Unit,
    onOpen: (WorkSummary) -> Unit,
) {
    // Le texte saisi reste local : le relire dans l'état du ViewModel (asynchrone) ferait sauter le curseur.
    var text by rememberSaveable { mutableStateOf(state.query) }
    val setText: (String) -> Unit = {
        text = it
        onQuery(it)
    }
    PublishTvView(tvView)
    Column(Modifier.fillMaxSize().background(RcColors.bg)) {
        ScreenHeader(Texts.navSearch, server)
        SearchField(text, state.query, setText, onQuery)
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterEnd) {
            CatalogueSwitch(state.playableOnly, onPlayableOnly)
        }
        val answered = state.query.isNotBlank() && state.resultsFor == state.query
        val shown = state.resultsFor
        val sections = state.sections
        // Une seule section (que des œuvres) : pas de titre de section, comme une liste simple.
        val titled = sections.people.isNotEmpty() || sections.aka.isNotEmpty()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (answered && state.results.isEmpty()) {
                item(key = "none") {
                    Text(
                        Texts.noResults,
                        style = MaterialTheme.typography.bodyMedium,
                        color = RcColors.text2,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                    )
                }
            }
            if (sections.people.isNotEmpty()) {
                item(key = "h-people") { Heading(Texts.sectionPeople) }
                items(sections.people, key = { "p-${it.name}" }) { person -> PersonRow(person, shown) { setText(person.name) } }
            }
            if (sections.works.isNotEmpty()) {
                if (titled) item(key = "h-works") { Heading(Texts.sectionWorks) }
                items(sections.works, key = { it.work.id }, contentType = { "work" }) { hit ->
                    WorkRow(hit.work, null, onOpen, highlight = shown)
                }
            }
            if (sections.aka.isNotEmpty()) {
                item(key = "h-aka") { Heading(Texts.sectionAka) }
                items(sections.aka, key = { "a-${it.work.id}" }, contentType = { "work" }) { aka ->
                    WorkRow(aka.work, null, onOpen, highlight = shown, note = Texts.altTitle(aka.kind == AltKind.Original, aka.title))
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    SectionLabel(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() })
}

/** Personne : initiales sur un disque, nom (saisie en laiton), « Réalisation · 2 œuvres », chevron. */
@Composable
private fun PersonRow(person: PersonHit, query: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable(onClickLabel = Texts.searchPerson, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(RcColors.surface3), contentAlignment = Alignment.Center) {
            Text(
                Texts.initials(person.name),
                style = TextStyle(fontFamily = Fraunces, fontSize = 18.sp),
                color = RcColors.accentStrong,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                highlighted(person.name, query),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                Texts.personRole(person.directing, person.workCount),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
                color = RcColors.text2,
            )
        }
        Icon(RcIcons.ChevronRight, contentDescription = null, tint = RcColors.text3, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SearchField(text: String, query: String, onText: (String) -> Unit, onQuery: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        if (text != query) onQuery(text) // saisie restaurée alors que le ViewModel est neuf
        if (text.isEmpty()) focus.requestFocus()
    }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(RcColors.surface2)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(RcIcons.Search, contentDescription = null, tint = RcColors.text2, modifier = Modifier.size(24.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                val style = TextStyle(fontFamily = Inter, fontSize = 16.sp, color = RcColors.text)
                if (text.isEmpty()) Text(Texts.searchHint, style = style.copy(color = RcColors.text3), maxLines = 1)
                BasicTextField(
                    value = text,
                    onValueChange = onText,
                    singleLine = true,
                    textStyle = style,
                    cursorBrush = SolidColor(RcColors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .semantics { contentDescription = Texts.search },
                )
            }
            if (text.isNotEmpty()) {
                IconButton(onClick = {
                    onText("")
                    focus.requestFocus()
                }) {
                    Icon(RcIcons.Clear, contentDescription = Texts.clear, tint = RcColors.text2)
                }
            }
        }
        Text(
            Texts.searchHelp,
            style = MaterialTheme.typography.bodySmall,
            color = RcColors.text3,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
        )
    }
}
