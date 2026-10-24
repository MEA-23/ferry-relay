package tech.thedevlab.ferry.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import android.text.format.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Illustrations are drawn on white, so they sit on a white rounded card in both themes. */
@Composable
fun Illustration(@DrawableRes res: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(MaterialTheme.shapes.large)
            .background(Color.White),
    ) {
        Image(
            painterResource(res),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth,
        )
    }
}

/** An icon in a softly tinted circle. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** A white card with no elevation — the app's basic container. */
@Composable
fun FerryCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    if (onClick != null) {
        Card(onClick, modifier.fillMaxWidth(), colors = colors, shape = MaterialTheme.shapes.medium) { content() }
    } else {
        Card(modifier.fillMaxWidth(), colors = colors, shape = MaterialTheme.shapes.medium) { content() }
    }
}

/** Tappable card row: badge, title and description, chevron. */
@Composable
fun ChoiceRow(icon: ImageVector, tint: Color, title: String, body: String, selected: Boolean? = null, onClick: () -> Unit) {
    val border = if (selected == true) Modifier.drawBehind {
        drawRoundRect(tint, cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
    } else {
        Modifier
    }
    FerryCard(border, onClick) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tint, size = 48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // One of a set to pick from shows a radio mark; a row that moves on shows a chevron.
            when (selected) {
                null -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
                true -> Icon(Icons.Default.CheckCircle, "Selected", tint = tint)
                false -> Icon(Icons.Default.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** Small rounded label, e.g. a SIM name. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(50), color = color.copy(alpha = 0.14f)) {
        Text(
            text,
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/** A dot that gently pulses — "live" indicator on the status card. */
@Composable
fun PulseDot(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(1f, 2.4f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "scale")
    val alpha by transition.animateFloat(0.5f, 0f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "alpha")
    Box(modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(10.dp).drawBehind {
                drawCircle(color.copy(alpha = alpha), radius = size.minDimension / 2 * scale)
            },
        )
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
    }
}

/** Centered illustration with a title and a line of text. */
@Composable
fun EmptyState(@DrawableRes art: Int, title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Illustration(art, Modifier.fillMaxWidth())
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/**
 * A labelled text field in the app's card style: label above, a soft filled box that gains an
 * accent border while focused, an optional clear button and a hint underneath.
 */
@Composable
fun FerryField(
    label: String,
    icon: ImageVector,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    hint: String? = null,
    singleLine: Boolean = true,
    monospace: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        OutlinedTextField(
            value,
            onValueChange,
            Modifier.fillMaxWidth(),
            textStyle = if (monospace) MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
            placeholder = { Text(placeholder, color = colors.outline) },
            leadingIcon = { Icon(icon, null, tint = if (focused) colors.primary else colors.onSurfaceVariant) },
            trailingIcon = if (value.isNotEmpty() && focused) {
                { IconButton({ onValueChange("") }) { Icon(Icons.Default.Cancel, "Clear", tint = colors.outline) } }
            } else {
                null
            },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 2,
            maxLines = if (singleLine) 1 else 4,
            keyboardOptions = keyboardOptions,
            interactionSource = interaction,
            shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = colors.surfaceContainerLow,
                unfocusedContainerColor = colors.surfaceContainerLow,
                focusedBorderColor = colors.primary,
                unfocusedBorderColor = Color.Transparent,
            ),
        )
        hint?.let { Text(it, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
    }
}

/**
 * A grouped section like an iOS form: small header, rows on one rounded card, a footnote under it.
 * Rows inside are separated with [FormDivider].
 */
@Composable
fun FormSection(header: String? = null, footer: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        header?.let {
            Text(
                it.uppercase(),
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FerryCard { Column { content() } }
        footer?.let {
            Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun FormDivider(start: Dp = 16.dp) {
    HorizontalDivider(Modifier.padding(start = start), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

/** A label on the left and its value on the right, like iOS's LabeledContent. */
@Composable
fun LabeledRow(label: String, value: String, monospace: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = if (monospace) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** A filter chip as a capsule: filled with the accent when selected, a soft fill otherwise. */
@Composable
fun CapsuleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) colors.primary else colors.surfaceContainerHigh,
        contentColor = if (selected) colors.onPrimary else colors.onSurface,
        modifier = Modifier.semantics { this.selected = selected },
    ) {
        Text(label, Modifier.padding(horizontal = 14.dp, vertical = 7.dp), style = MaterialTheme.typography.labelLarge)
    }
}

/** A rounded search field with a magnifier, for under a top bar. */
@Composable
fun SearchField(query: String, onQuery: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, onFocus: (Boolean) -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        query,
        onQuery,
        modifier.onFocusChanged { onFocus(it.isFocused) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        decorationBox = { field ->
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(colors.surfaceContainerHigh).padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    field()
                }
                if (query.isNotEmpty()) {
                    Icon(Icons.Default.Cancel, "Clear", Modifier.size(18.dp).clip(CircleShape).clickable { onQuery("") }, tint = colors.outline)
                }
            }
        },
    )
}

/** What a message is, as a white symbol on a coloured circle: green for texts, blue for calls, purple for apps. */
@Composable
fun KindIcon(type: String, size: Dp = 40.dp) {
    val (icon, color) = when (type) {
        "sms" -> Icons.AutoMirrored.Filled.Message to Color(0xFF34C759)
        "call" -> Icons.Default.Call to Color(0xFF0A84FF)
        "notification" -> Icons.Default.Apps to Color(0xFFAF52DE)
        else -> Icons.Default.QuestionMark to Color.Gray
    }
    Box(
        Modifier.size(size).clip(CircleShape).background(Brush.verticalGradient(listOf(color.copy(alpha = 0.85f), color))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.45f))
    }
}

/** "14:02" today, "Mon" this week, "12 Oct" otherwise. */
@Composable
fun listTime(time: Long): String {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when {
        date == today -> DateFormat.getTimeFormat(context).format(Date(time))
        date.isAfter(today.minusDays(7)) -> date.format(DateTimeFormatter.ofPattern("EEE"))
        else -> date.format(DateTimeFormatter.ofPattern("d MMM"))
    }
}
