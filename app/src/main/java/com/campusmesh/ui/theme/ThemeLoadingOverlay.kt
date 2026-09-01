package com.campusmesh.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun ThemeLoadingOverlay(
    targetTheme: AppTheme,
    onLoadingComplete: () -> Unit,
) {
    var progress by remember { mutableFloatStateOf(0.1f) }
    var statusText by remember { mutableStateOf("INITIALIZING THEME ENGINE...") }

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "progress",
    )

    LaunchedEffect(targetTheme) {
        statusText = if (targetTheme == AppTheme.PIXEL_8BIT) "INITIALIZING 8-BIT PIXEL ENGINE..." else "RESTORING DEFAULT DARK ENGINE..."
        delay(300)
        progress = 0.45f
        statusText = if (targetTheme == AppTheme.PIXEL_8BIT) "LOADING RETRO COLOR PALETTES..." else "UNLOADING RETRO ASSETS..."
        delay(400)
        progress = 0.85f
        statusText = "COMPILING SHADERS & STYLES..."
        delay(350)
        progress = 1.0f
        statusText = "THEME SWITCH COMPLETE!"
        delay(250)
        onLoadingComplete()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0D0D1A),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .padding(32.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Pixel Icon Badge
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (targetTheme == AppTheme.PIXEL_8BIT) PixelYellow else Color(0xFF1E3A5F))
                        .border(3.dp, if (targetTheme == AppTheme.PIXEL_8BIT) PixelCyan else Color.White, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = "Loading",
                        tint = if (targetTheme == AppTheme.PIXEL_8BIT) Color.Black else Color.White,
                        modifier = Modifier.size(44.dp),
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = if (targetTheme == AppTheme.PIXEL_8BIT) "🕹️ 8-BIT RETRO ENGINE" else "⚡ DEFAULT DARK THEME",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    color = PixelYellow,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = statusText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = PixelCyan,
                    fontFamily = FontFamily.Monospace,
                )

                Spacer(modifier = Modifier.height(36.dp))

                // Arcade Retro Progress Bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF1A1A2E))
                        .border(2.dp, PixelMagenta, RoundedCornerShape(4.dp))
                        .padding(3.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxSize(),
                        color = PixelYellow,
                        trackColor = Color.Transparent,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "${(animatedProgress * 100).toInt()}%",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = PixelMagenta,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
