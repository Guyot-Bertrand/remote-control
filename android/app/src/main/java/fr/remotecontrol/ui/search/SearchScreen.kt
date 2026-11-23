package fr.remotecontrol.ui.search

import androidx.compose.foundation.background
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
import fr.remotecontrol.ui.common.WorkList
import fr.remotecontrol.ui.theme.Inter
import fr.remotecontrol.ui.theme.RcColors

/** Maquette « Recherche » : champ arrondi, aide, résultats instantanés (liste d'œuvres). */
@Composable
fun SearchScreen(
    state: SearchUi,
    server: ServerState,
    onQuery: (String) -> Unit,
    onPlayableOnly: (Boolean) -> Unit,
    onOpen: (WorkSummary) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(RcColors.bg)) {
        ScreenHeader(Texts.navSearch, server)
        SearchField(state.query, onQuery)
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterEnd) {
            CatalogueSwitch(state.playableOnly, onPlayableOnly)
        }
        val answered = state.query.isNotBlank() && state.resultsFor == state.query
        WorkList(state.results, onOpen, Modifier.fillMaxSize()) {
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
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    // Le texte saisi reste local : le relire dans l'état du ViewModel (asynchrone) ferait sauter le curseur.
    var text by rememberSaveable { mutableStateOf(query) }
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
                    onValueChange = {
                        text = it
                        onQuery(it)
                    },
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
                    text = ""
                    onQuery("")
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
