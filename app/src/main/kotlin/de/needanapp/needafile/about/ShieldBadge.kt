package de.needanapp.needafile.about

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.needanapp.needafile.R

/**
 * A badge drawn like the "flat" badges of shields.io (https://shields.io/badges): grey label on the left, colored
 * message on the right, small corners, a faint top-down sheen and shadowed text. Drawn locally, so the about screen
 * works offline and does not contact any server.
 */
@Composable
fun ShieldBadge(label: String, message: String, color: Color, onClick: (() -> Unit)? = null) {
    val description = stringResource(R.string.label_value, label, message)
    Box(
        Modifier
            .clip(RoundedCornerShape(3.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .clearAndSetSemantics {
                contentDescription = description
                if (onClick != null) role = Role.Button
            },
    ) {
        Row(Modifier.height(20.dp)) {
            BadgePart(label, LABEL_GREY)
            BadgePart(message, color)
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(SHEEN_TOP, SHEEN_BOTTOM))))
    }
}

@Composable
private fun BadgePart(text: String, background: Color) {
    // like shields.io: white text with a dark shadow, dark text with a light shadow on bright colors
    val isBright = background.luminance() > BRIGHT_LUMINANCE
    Box(Modifier.background(background).padding(horizontal = 6.dp).height(20.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            maxLines = 1,
            style = TextStyle(
                fontSize = 11.sp,
                color = if (isBright) DARK_TEXT else Color.White,
                shadow = Shadow(color = (if (isBright) Color.White else Color.Black).copy(alpha = TEXT_SHADOW_ALPHA), offset = Offset(0f, 1.5f)),
            ),
        )
    }
}

/** Colors and texts of the badges, following shields.io. */
object Shields {
    val BLUE = Color(0xFF007EC6)
    val ORANGE = Color(0xFFFE7D37)
    val GREEN = Color(0xFF97CA00)
    val LIGHT_GREY = Color(0xFF9F9F9F)

    private val PRE_RELEASE = Regex("alpha|beta|rc|dev|pre|snapshot", RegexOption.IGNORE_CASE)
    private val COMMIT_HASH = Regex("^[0-9a-f]{7,40}$")
    private const val SHORT_HASH_LENGTH = 7

    /** Version text as shields.io prints it: "v" before numbers, commit hashes shortened. */
    fun formatVersion(version: String) = when {
        COMMIT_HASH.matches(version) && version.any { it.isLetter() } -> version.take(SHORT_HASH_LENGTH)
        version.firstOrNull()?.isDigit() == true -> "v$version"
        else -> version
    }

    /** shields.io colors versions orange while they are unstable (0.x or pre-releases), blue afterwards. */
    fun versionColor(version: String) = if (version.startsWith("0.") || PRE_RELEASE.containsMatchIn(version)) ORANGE else BLUE

    /** shields.io colors copyleft licenses orange and permissive ones green. */
    fun licenseColor(isCopyleft: Boolean) = if (isCopyleft) ORANGE else GREEN
}

private val LABEL_GREY = Color(0xFF555555)
private val DARK_TEXT = Color(0xFF333333)
private val SHEEN_TOP = Color(0x1ABBBBBB)
private val SHEEN_BOTTOM = Color(0x1A000000)
private const val TEXT_SHADOW_ALPHA = 0.3f

// shields.io switches to dark text above this brightness (their formula is close to relative luminance)
private const val BRIGHT_LUMINANCE = 0.45f
