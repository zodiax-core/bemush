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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun ThemeLoadingOverlay(
    targetTheme: AppTheme,
    onLoadingComplete: () -> Unit,
) {
    var progress by remember { mutableFloatStateOf(0.1f) }
    var statusText by remember { mutableStateOf("INITIALIZING ENGINE...") }

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "progress",
    )

    LaunchedEffect(targetTheme) {
        statusText = if (targetTheme == AppTheme.PIXEL_8BIT) "LOADING 8-BIT ENGINE..." else "RESTORING DARK ENGINE..."
        delay(300)
        progress = 0.45f
        statusText = if (targetTheme == AppTheme.PIXEL_8BIT) "LOADING RETRO PALETTE..." else "UNLOADING PALETTE..."
        delay(400)
        progress = 0.85f
        statusText = "COMPILING SHADERS..."
        delay(350)
        progress = 1.0f
        statusText = "SWITCH COMPLETE!"
        delay(250)
        onLoadingComplete()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF120826),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Pixel Icon Badge
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (targetTheme == AppTheme.PIXEL_8BIT) PixelYellow else Color(0xFF1E3A5F))
                        .border(3.dp, if (targetTheme == AppTheme.PIXEL_8BIT) PixelCyan else Color.White, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = "Loading",
                        tint = if (targetTheme == AppTheme.PIXEL_8BIT) Color.Black else Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                Text(
                    text = if (targetTheme == AppTheme.PIXEL_8BIT) "🕹️ 8-BIT RETRO" else "⚡ DEFAULT DARK",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                    color = PixelYellow,
                    fontFamily = PressStart2PFontFamily,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = statusText,
                    fontSize = 9.sp,
                    color = PixelCyan,
                    fontFamily = PressStart2PFontFamily,
                    textAlign = TextAlign.Center,
                    lineHeight = 15.sp,
                )

                Spacer(modifier = Modifier.height(36.dp))

                // Arcade Retro Progress Bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF1D0E3D))
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

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "${(animatedProgress * 100).toInt()}%",
                    fontSize = 12.sp,
                    color = PixelMagenta,
                    fontFamily = PressStart2PFontFamily,
                )
            }
        }
    }
}
