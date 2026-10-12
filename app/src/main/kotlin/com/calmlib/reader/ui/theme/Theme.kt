package com.calmlib.reader.ui.theme

import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationInstance
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope

private val EinkColorScheme = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    secondary = Color(0xFF666666),
    onSecondary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF333333),
    outline = Color(0xFFDDDDDD),
    outlineVariant = Color(0xFFEEEEEE),
)

/** No press feedback at all. A ripple is an animation, and e-ink paints it as
 *  a grey smear plus extra refreshes. */
private object NoIndication : Indication {
    private object Instance : IndicationInstance {
        override fun ContentDrawScope.drawIndication() {
            drawContent()
        }
    }

    @Composable
    override fun rememberUpdatedInstance(interactionSource: InteractionSource): IndicationInstance = Instance
}

@Composable
fun CalmLibTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = EinkColorScheme) {
        // Inside MaterialTheme, which provides its own ripple as LocalIndication.
        CompositionLocalProvider(LocalIndication provides NoIndication, content = content)
    }
}
