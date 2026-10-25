package tech.thedevlab.ferry.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.thedevlab.ferry.data.Filters

/** Mute or unmute a conversation's sender or app, for a conversation's menus. */
@Composable
fun MuteMenuItem(vm: AppViewModel, threadKey: String, label: String, muted: Boolean, onDone: () -> Unit) {
    val target = Filters.target(threadKey) ?: return
    if (muted) {
        DropdownMenuItem({ Text("Unmute") }, { onDone(); vm.unmute(target) }, leadingIcon = { Icon(Icons.Default.Notifications, null) })
    } else {
        DropdownMenuItem({ Text("Mute notifications") }, { onDone(); vm.mute(threadKey, label) }, leadingIcon = { Icon(Icons.Default.NotificationsOff, null) })
    }
}

/** Settings → Muted: suggestions and automatic muting, muted senders and apps, muted words, and words to suggest. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MutedScreen(vm: AppViewModel, storeVersion: Long, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val mutes by vm.mutes.collectAsStateWithLifecycle()
    val suggestions by vm.suggestedWords.collectAsStateWithLifecycle()
    val suggest = remember(storeVersion) { vm.store.suggestMuting }
    val auto = remember(storeVersion) { vm.store.autoMuteMarketing }
    var word by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(mutes) { vm.loadSuggestions() }
    val muted = mutes.filter { it.kind == Filters.SENDER || it.kind == Filters.APP }
    val words = mutes.filter { it.kind == Filters.WORD }
    fun addWord() {
        if (word.isNotBlank()) vm.addMutedWord(word)
        word = ""
    }

    Column(modifier) {
        TopAppBar(
            title = { Text("Muted") },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            windowInsets = WindowInsets(0),
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item {
                FormSection(footer = "Muted messages still arrive in your inbox, without sound or a banner.") {
                    SwitchRow("Suggest muting", suggest) { vm.store.suggestMuting = it }
                    FormDivider()
                    SwitchRow("Mute likely marketing", auto) { vm.store.autoMuteMarketing = it }
                }
            }
            item {
                FormSection("Senders and apps", footer = if (muted.isEmpty()) "Mute a conversation from its menu." else null) {
                    if (muted.isEmpty()) {
                        LabeledRow("Nothing muted", "")
                    }
                    muted.forEachIndexed { i, m ->
                        if (i > 0) FormDivider()
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(m.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            TextButton({ vm.unmute(Filters.Target(m.kind, m.value)) }) { Text("Unmute") }
                        }
                    }
                }
            }
            item {
                FormSection("Words", footer = "Texts containing these arrive muted, except from your contacts and conversations you've replied to.") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            word,
                            { word = it },
                            Modifier.weight(1f),
                            placeholder = { Text("Add a word or phrase") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { addWord() }),
                        )
                        IconButton(::addWord, enabled = word.isNotBlank()) { Icon(Icons.Default.Add, "Add") }
                    }
                    words.forEach { w ->
                        FormDivider()
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(w.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            IconButton({ vm.removeMutedWord(w.value) }) { Icon(Icons.Default.Close, "Remove") }
                        }
                    }
                }
            }
            if (suggestions.isNotEmpty()) {
                item {
                    FormSection("Suggested words", footer = "Common in the senders you muted. Tap to add; × to dismiss.") {
                        FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            suggestions.forEach { s ->
                                AssistChip(
                                    onClick = { vm.addMutedWord(s) },
                                    label = { Text(s) },
                                    trailingIcon = {
                                        Icon(Icons.Default.Close, "Dismiss", Modifier.clickable { vm.skipSuggestion(s) })
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onChange)
    }
}
