package com.example.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.AppScreen
import com.example.ui.theme.LocalSoftGlassColors

/**
 * High-resolution 3D Velvet Crystal WebP icon system for FamilyTasks — Velvet Edition.
 *
 * Uses ultra-crisp, transparent WebP assets with Velvet Satin finish, deep sapphire-violet
 * gradients, pearl highlights, and soft ambient luminescence across all 6 bottom navigation
 * items and core task/reminder UI surfaces.
 */
object VelvetCrystalIcons {

    @DrawableRes
    fun navDrawableForScreen(screen: AppScreen): Int {
        return when (screen) {
            AppScreen.HOME -> R.drawable.ic_velvet_tasks
            AppScreen.CALENDAR -> R.drawable.ic_velvet_calendar
            AppScreen.TIMETABLE -> R.drawable.ic_velvet_timetable
            AppScreen.ALARMS -> R.drawable.ic_velvet_alarms
            AppScreen.FILES -> R.drawable.ic_velvet_files
            AppScreen.ADMIN_DASHBOARD,
            AppScreen.POINTS_MANAGEMENT,
            AppScreen.SETTINGS,
            AppScreen.AI_ASSISTANT -> R.drawable.ic_velvet_family
        }
    }
}

/**
 * Renders a Velvet Crystal 3D navigation icon with smooth spring scale, subtle satin glow
 * when selected, and refined contrast modulation when unselected.
 */
@Composable
fun VelvetCrystalNavIcon(
    screen: AppScreen,
    contentDescription: String?,
    isSelected: Boolean,
    size: Dp = 26.dp,
    modifier: Modifier = Modifier
) {
    val glass = LocalSoftGlassColors.current
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.10f else 0.96f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "velvetNavIconScale"
    )
    val iconAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1.0f else if (glass.isDark) 0.78f else 0.82f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "velvetNavIconAlpha"
    )

    val unselectedFilter = if (!isSelected) {
        val matrix = ColorMatrix().apply {
            setToSaturation(0.75f)
        }
        ColorFilter.colorMatrix(matrix)
    } else {
        null
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size + 4.dp)
    ) {
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(size + 4.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                glass.primaryAccent.copy(alpha = if (glass.isDark) 0.34f else 0.22f),
                                glass.velvetVioletAccent.copy(alpha = if (glass.isDark) 0.18f else 0.10f),
                                Color.Transparent
                            )
                        ),
                        CircleShape
                    )
            )
        }
        Image(
            painter = painterResource(id = VelvetCrystalIcons.navDrawableForScreen(screen)),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            colorFilter = unselectedFilter,
            modifier = Modifier
                .size(size)
                .scale(scale)
                .alpha(iconAlpha)
        )
    }
}

/**
 * Renders a Velvet Crystal 3D icon asset anywhere in the app (task cards, reminder badges,
 * points chips, filter buttons, and action buttons).
 */
@Composable
fun VelvetCrystal3DAssetIcon(
    @DrawableRes drawableRes: Int,
    contentDescription: String? = null,
    size: Dp = 20.dp,
    modifier: Modifier = Modifier,
    withSoftGlow: Boolean = false
) {
    val glass = LocalSoftGlassColors.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(if (withSoftGlow) size + 4.dp else size)
    ) {
        if (withSoftGlow) {
            Box(
                modifier = Modifier
                    .size(size + 4.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                glass.primaryAccent.copy(alpha = if (glass.isDark) 0.28f else 0.18f),
                                Color.Transparent
                            )
                        ),
                        CircleShape
                    )
            )
        }
        Image(
            painter = painterResource(id = drawableRes),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size)
        )
    }
}
