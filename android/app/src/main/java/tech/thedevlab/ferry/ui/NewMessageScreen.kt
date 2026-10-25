package tech.thedevlab.ferry.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp

/** A text to any number, sent from a phone and SIM of the user's choosing (§6.6). [onSent] gets the conversation it went into. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMessageScreen(vm: AppViewModel, onBack: () -> Unit, onSent: (String) -> Unit, modifier: Modifier = Modifier) {
    val options = remember { vm.sendOptions() }
    var number by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var fromId by rememberSaveable { mutableStateOf(vm.store.lastSendOption?.takeIf { id -> options.any { it.id == id } } ?: options.firstOrNull()?.id) }
    val from = options.find { it.id == fromId }
    var picking by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // Spaces, dashes and brackets dropped, so the number matches its conversation.
    val clean = number.filterNot { it in " -()." }
    val validNumber = clean.length >= 3 && clean.drop(1).all(Char::isDigit) && (clean[0].isDigit() || clean[0] == '+')
    val canSend = validNumber && text.isNotBlank() && from != null

    Column(modifier) {
        TopAppBar(
            title = { Text("New message") },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                TextButton({ onSent(vm.sendNew(clean, text.trim(), from!!)) }, enabled = canSend) { Text("Send") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            windowInsets = WindowInsets(0),
        )
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FerryField(
                "To",
                Icons.Default.Phone,
                number,
                { number = it },
                Modifier.focusRequester(focus),
                placeholder = "Phone number",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
            if (options.size > 1) {
                ExposedDropdownMenuBox(picking, { picking = it }) {
                    OutlinedTextField(
                        from?.label.orEmpty(),
                        {},
                        Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                        readOnly = true,
                        label = { Text("From") },
                        leadingIcon = { Icon(Icons.Default.SimCard, null) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(picking) },
                        shape = MaterialTheme.shapes.medium,
                    )
                    ExposedDropdownMenu(picking, { picking = false }) {
                        options.forEach { o ->
                            DropdownMenuItem({ Text(o.label) }, {
                                fromId = o.id
                                picking = false
                            })
                        }
                    }
                }
            }
            OutlinedTextField(
                text,
                { text = it },
                Modifier.fillMaxWidth(),
                placeholder = { Text("Text message") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Message, null) },
                minLines = 3,
                maxLines = 8,
                shape = MaterialTheme.shapes.medium,
                textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            )
        }
    }
}
