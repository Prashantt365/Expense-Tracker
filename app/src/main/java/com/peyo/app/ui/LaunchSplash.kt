package com.peyo.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.peyo.app.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The wordmark springing into place on a cold start, then handing over to the app.
 *
 * Kept short on purpose: about three quarters of a second from first frame to [onFinished]. The
 * app is composing underneath the whole time, so this costs no load time; it only covers what
 * would otherwise be a blank frame or two while the first data arrives. The ground is the theme's
 * background, which is also the window background, so the system's launch screen, this and the
 * dashboard read as one movement.
 */
@Composable
fun LaunchSplash(onFinished: () -> Unit) {
    val finish by rememberUpdatedState(onFinished)
    // Starts fully drawn: the system launch screen has already put the mark up, and fading it in
    // from nothing read as a blink at the handover. It settles from slightly small with a bounce.
    val scale = remember { Animatable(0.82f) }
    val sweep = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        coroutineScope {
            launch {
                scale.animateTo(
                    1f,
                    spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow)
                )
            }
            launch {
                delay(180)
                sweep.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
            }
        }
        delay(80)
        finish()
    }

    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Image(
                painterResource(R.drawable.peyo_wordmark),
                contentDescription = "Peyo",
                Modifier.height(64.dp).graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
                colorFilter = ColorFilter.tint(primary)
            )
            // A line drawn out beneath the mark, which is what makes the moment read as the app
            // getting ready rather than a still image left on screen.
            Box(
                Modifier.width(72.dp).height(4.dp).clip(CircleShape)
                    .background(primary.copy(alpha = 0.15f))
            ) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(sweep.value).clip(CircleShape)
                        .background(primary)
                )
            }
        }
    }
}
