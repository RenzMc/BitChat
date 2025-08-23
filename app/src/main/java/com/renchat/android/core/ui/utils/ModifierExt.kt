package com.renchat.android.core.ui.utils

import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun Modifier.singleOrTripleClickable(
    onSingleClick: () -> Unit,
    onTripleClick: () -> Unit,
    clickTimeThreshold: Long = 300L
): Modifier = composed {
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var singleClickJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val coroutineScope = rememberCoroutineScope()

    this.clickable {
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastTapTime < clickTimeThreshold) {
            tapCount++
        } else {
            tapCount = 1
        }

        lastTapTime = currentTime

        // Cancel any pending single click action
        singleClickJob?.cancel()
        singleClickJob = null

        when (tapCount) {
            1 -> {
                // Wait to see if more taps come
                singleClickJob = coroutineScope.launch {
                    delay(clickTimeThreshold)
                    if (tapCount == 1) {
                        onSingleClick()
                    }
                }
            }
            3 -> {
                // Triple click detected - execute immediately
                onTripleClick()
                tapCount = 0
            }
        }

        // Reset after threshold if no triple click
        if (tapCount > 3) {
            tapCount = 0
        }
    }
}

/**
 * RainbowText composable that creates an animated rainbow color effect
 * with colors: dark red, terminal green, white, blue
 */
@Composable
fun RainbowText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    // Define the rainbow colors as requested: dark red, terminal green, white, blue
    val rainbowColors = listOf(
        Color(0xFF8B0000), // Dark red
        Color(0xFF00FF00), // Terminal green
        Color(0xFFFFFFFF), // White
        Color(0xFF0080FF)  // Blue
    )
    
    // Create infinite animation
    val infiniteTransition = rememberInfiniteTransition(label = "rainbow")
    val colorIndex by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = rainbowColors.size.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "colorAnimation"
    )
    
    // Calculate current color by interpolating between colors
    val currentColor = remember(colorIndex) {
        val currentIndex = colorIndex.toInt() % rainbowColors.size
        val nextIndex = (currentIndex + 1) % rainbowColors.size
        val fraction = colorIndex - colorIndex.toInt()
        
        val currentRgb = rainbowColors[currentIndex]
        val nextRgb = rainbowColors[nextIndex]
        
        Color(
            red = currentRgb.red + (nextRgb.red - currentRgb.red) * fraction,
            green = currentRgb.green + (nextRgb.green - currentRgb.green) * fraction,
            blue = currentRgb.blue + (nextRgb.blue - currentRgb.blue) * fraction,
            alpha = currentRgb.alpha
        )
    }
    
    Text(
        text = text,
        style = style.copy(color = currentColor),
        modifier = modifier
    )
}