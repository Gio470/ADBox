@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.adbox.toolkit

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val Violet = Color(0xFF64439C)
private val Ink = Color(0xFF271B39)
@Composable internal fun ADBoxTheme(model: ADBoxViewModel, content: @Composable () -> Unit) {
    val dark = when(model.theme){"dark"->true;"light"->false;else->isSystemInDarkTheme()}
    val colors = if(model.dynamicColors && Build.VERSION.SDK_INT >= 31) {
        if(dark)dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    } else if(dark) darkColorScheme(primary=Color(0xFFD0BCFF),onPrimary=Color(0xFF35204F),primaryContainer=Color(0xFF4C3469),secondary=Color(0xFFD7EDAF),secondaryContainer=Color(0xFF354525),onSecondaryContainer=Color(0xFFE3F4C7),tertiary=Color(0xFFFFB59E),surface=Color(0xFF15121A),surfaceContainer=Color(0xFF211B2A),surfaceContainerHigh=Color(0xFF2D2638),onSurface=Color(0xFFF0E7FA))
    else lightColorScheme(primary=Violet,onPrimary=Color.White,primaryContainer=Color(0xFFEBDFFF),onPrimaryContainer=Ink,secondary=Color(0xFF50672D),secondaryContainer=Color(0xFFDDF5B0),onSecondaryContainer=Color(0xFF243A0C),tertiary=Color(0xFF9B492B),tertiaryContainer=Color(0xFFFFDBCF),surface=Color(0xFFFBF8FF),surfaceContainer=Color(0xFFF1EBF7),surfaceContainerHigh=Color(0xFFE9E1F1),onSurface=Ink)
    MaterialExpressiveTheme(colorScheme=colors, motionScheme=MotionScheme.expressive(), shapes=Shapes(small=RoundedCornerShape(16.dp),medium=RoundedCornerShape(24.dp),large=RoundedCornerShape(28.dp),extraLarge=RoundedCornerShape(36.dp)),content=content)
}
