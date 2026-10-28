package io.embodit.jugglecoach.ui.jc.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.Chip
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.jugglecoach.ui.jc.debug.probe
import io.embodit.jugglecoach.ui.theme.JcColors

/** Panel padding and key sizes (design: Material.Regular glass root, 16 dp padding, 56 x 48 keys). */
internal val PanelPadding = 16.dp
internal val KeyWidth = 56.dp
internal val KeyHeight = 48.dp

/** Glass root of a panel: clipped shape, Material.Regular, padding. */
@Composable
internal fun Glass(
    width: Dp,
    height: Dp,
    corner: Dp = 32.dp,
    material: Material = Material.Regular,
    padding: Dp = PanelPadding,
    spacing: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.size(width, height).clip(RoundedCornerShape(corner)).backgroundMaterial(enable = true, style = material).padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        content()
    }
}

/** Type roles of the design that differ from the stock PicoTheme roles (design `typography.roles`). */
internal object JcType {
    /** Name badge, chip values: titleMedium 16 / 20 at 600, tabular figures. */
    val title: TextStyle
        @Composable get() = PicoTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    /** Chip labels, tile names, plates: labelLarge 14 / 18 at 600, tabular figures. */
    val figure: TextStyle
        @Composable get() = PicoTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    /** The count-in digit on the panel: displaySmall 36 / 44 at 600, tabular figures (design `typography.roles`). */
    val countdown: TextStyle
        @Composable
        get() = PicoTheme.typography.displaySmall.copy(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    /** Hint line: labelLarge 14 / 18 at 600. */
    val hint: TextStyle
        @Composable get() = PicoTheme.typography.labelLarge.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)

    /** Chip text and card lines: labelMedium 14 / 18 at 600 (never below 14 sp at 0.7 - 0.9 m), tabular figures. */
    val chip: TextStyle
        @Composable get() = PicoTheme.typography.labelMedium.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    /** Menu labels: bodyMedium 14 / 18 at 500. */
    val label: TextStyle
        @Composable get() = PicoTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)

    /** Hints, dialog body, snackbar: bodyLarge 16 / 24 at 500. */
    val body: TextStyle
        @Composable get() = PicoTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum, lnum")
}

/** A 56 x 48 icon key (previous, next, more). */
@Composable
internal fun Key(name: String, iconRes: Int, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val content = if (enabled) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelQuaternary
    IconButton(
        onClick = onClick,
        modifier = Modifier.probe(name, onClick),
        colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.fillPrimary, contentColor = PicoTheme.colorScheme.labelPrimaryLight),
        size = IconButtonDefaults.iconButtonSize(width = KeyWidth, height = KeyHeight),
        enabled = enabled,
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = description, modifier = Modifier.size(24.dp), tint = content)
    }
}

/**
 * A worded button: capsule, 48 dp high. [primary] = white fill with ink text (the confirming action),
 * [danger] = error fill with ink text; otherwise the tertiary fill with glass text.
 */
@Composable
internal fun WordButton(
    name: String,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    minWidth: Dp = 104.dp,
    height: Dp = 48.dp,
    iconRes: Int? = null,
) {
    val container =
        when {
            danger -> PicoTheme.colorScheme.error
            primary -> PicoTheme.colorScheme.labelPrimaryLight
            else -> PicoTheme.colorScheme.fillTertiary
        }
    val content = if (primary || danger) JcColors.Ink else if (enabled) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelQuaternary
    Button(
        onClick = onClick,
        modifier = modifier.probe(name, onClick),
        size = ButtonDefaults.Regular.copy(height = height, minWidth = minWidth),
        shape = CircleShape,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        if (iconRes != null) {
            Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(20.dp), tint = content)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text = text, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis, style = JcType.title)
    }
}

/** Title row of a panel: an optional leading key, the title, an optional trailing key. */
@Composable
internal fun TitleRow(title: String, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (leading != null) leading()
        Text(text = title, color = PicoTheme.colorScheme.labelPrimary, style = JcType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (trailing != null) trailing()
    }
}

/** The main button: interaction-yellow fill, ink icon and text (design `spatialUiMap.mainButton`; ink on yellow 11.6 : 1). */
@Composable
internal fun MainButton(name: String, label: String, iconRes: Int, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ink = JcColors.Ink
    Button(
        onClick = { if (enabled) onClick() },
        modifier = modifier.alpha(if (enabled) 1f else 0.45f).probe(name, onClick),
        size = ButtonDefaults.Regular.copy(height = 48.dp, minWidth = 120.dp),
        shape = CircleShape,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = ink),
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(20.dp), tint = ink)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, style = JcType.title)
    }
}

/** A SpatialUI segment control; selected = interaction yellow at 26 %. Disabled = dimmed, presses ignored. */
@Composable
internal fun Segments(items: List<Pair<String, String>>, selected: Int?, onSelect: (Int) -> Unit, width: Dp, height: Dp = 48.dp, enabled: Boolean = true) {
    val itemWidth = width / items.size
    SegmentControl(modifier = Modifier.width(width).height(height).alpha(if (enabled) 1f else 0.45f), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        items.forEachIndexed { i, (name, text) ->
            val onClick = { if (enabled) onSelect(i) }
            SegmentItem(
                selected = selected == i,
                onClick = onClick,
                modifier = Modifier.width(itemWidth).height(height).probe(name) { onSelect(i) },
                textStyle = PicoTheme.typography.labelLarge,
                colors =
                    SegmentControlDefaults.colors(
                        itemBackgroundColor = Color.Transparent,
                        itemContentColor = PicoTheme.colorScheme.labelPrimary,
                        selectedItemBackgroundColor = PicoTheme.colorScheme.interaction.copy(alpha = 0.26f),
                        selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
                    ),
                title = {
                    // design-style: inherited-content-color SegmentItem
                    Text(text = text, maxLines = 1, style = PicoTheme.typography.labelLarge)
                },
            )
        }
    }
}

/** A small SpatialUI chip that only shows a value (counter, "last 10"). */
@Composable
internal fun ValueChip(text: String, dim: Boolean = false) {
    Chip(
        label = { Text(text = text, maxLines = 1, softWrap = false, style = JcType.chip, color = if (dim) PicoTheme.colorScheme.labelSecondary else PicoTheme.colorScheme.labelPrimary) },
        onClick = {},
        modifier = Modifier.height(32.dp),
    )
}

/** The 24 dp dwell ring on a card / button (domain drawing, not a control): track + interaction-yellow arc from 12 o'clock. */
@Composable
internal fun DwellRing(progress: Float, modifier: Modifier = Modifier) {
    val track = PicoTheme.colorScheme.labelQuaternary
    val arc = PicoTheme.colorScheme.interaction
    Canvas(modifier = modifier.size(24.dp)) {
        val w = 3.dp.toPx()
        val inset = w / 2f
        val sz = Size(size.width - w, size.height - w)
        drawArc(track, 0f, 360f, false, topLeft = Offset(inset, inset), size = sz, style = Stroke(w))
        if (progress > 0f) drawArc(arc, -90f, 360f * progress.coerceIn(0f, 1f), false, topLeft = Offset(inset, inset), size = sz, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** A 12 dp colour dot (legend line: the colour itself never carries the words). */
@Composable
internal fun Dot(color: Color) {
    Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(color))
}
