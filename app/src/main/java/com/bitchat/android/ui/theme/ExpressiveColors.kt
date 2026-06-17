package com.bitchat.android.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Material 3 Expressive brand palette for bitchat.
 *
 * Used as the fallback when wallpaper-based dynamic color (Material You) is unavailable
 * (Android < 12). Tuned to feel energetic and modern: an emerald-green primary that nods
 * to bitchat's heritage, a teal secondary, and a warm coral tertiary for expressive accents.
 */

// ---- Light ----
private val ExpPrimaryLight = Color(0xFF006D34)
private val ExpOnPrimaryLight = Color(0xFFFFFFFF)
private val ExpPrimaryContainerLight = Color(0xFF6FFE94)
private val ExpOnPrimaryContainerLight = Color(0xFF00210D)
private val ExpSecondaryLight = Color(0xFF1F6A5E)
private val ExpOnSecondaryLight = Color(0xFFFFFFFF)
private val ExpSecondaryContainerLight = Color(0xFFA7F2E2)
private val ExpOnSecondaryContainerLight = Color(0xFF00201B)
private val ExpTertiaryLight = Color(0xFF9B4434)
private val ExpOnTertiaryLight = Color(0xFFFFFFFF)
private val ExpTertiaryContainerLight = Color(0xFFFFDBD1)
private val ExpOnTertiaryContainerLight = Color(0xFF3A0A02)
private val ExpErrorLight = Color(0xFFBA1A1A)
private val ExpOnErrorLight = Color(0xFFFFFFFF)
private val ExpErrorContainerLight = Color(0xFFFFDAD6)
private val ExpOnErrorContainerLight = Color(0xFF410002)
private val ExpBackgroundLight = Color(0xFFF5FBF3)
private val ExpOnBackgroundLight = Color(0xFF171D18)
private val ExpSurfaceLight = Color(0xFFF5FBF3)
private val ExpOnSurfaceLight = Color(0xFF171D18)
private val ExpSurfaceVariantLight = Color(0xFFDBE5DB)
private val ExpOnSurfaceVariantLight = Color(0xFF404942)
private val ExpOutlineLight = Color(0xFF707972)
private val ExpOutlineVariantLight = Color(0xFFBFC9BF)

val ExpressiveLightColorScheme = lightColorScheme(
    primary = ExpPrimaryLight,
    onPrimary = ExpOnPrimaryLight,
    primaryContainer = ExpPrimaryContainerLight,
    onPrimaryContainer = ExpOnPrimaryContainerLight,
    secondary = ExpSecondaryLight,
    onSecondary = ExpOnSecondaryLight,
    secondaryContainer = ExpSecondaryContainerLight,
    onSecondaryContainer = ExpOnSecondaryContainerLight,
    tertiary = ExpTertiaryLight,
    onTertiary = ExpOnTertiaryLight,
    tertiaryContainer = ExpTertiaryContainerLight,
    onTertiaryContainer = ExpOnTertiaryContainerLight,
    error = ExpErrorLight,
    onError = ExpOnErrorLight,
    errorContainer = ExpErrorContainerLight,
    onErrorContainer = ExpOnErrorContainerLight,
    background = ExpBackgroundLight,
    onBackground = ExpOnBackgroundLight,
    surface = ExpSurfaceLight,
    onSurface = ExpOnSurfaceLight,
    surfaceVariant = ExpSurfaceVariantLight,
    onSurfaceVariant = ExpOnSurfaceVariantLight,
    outline = ExpOutlineLight,
    outlineVariant = ExpOutlineVariantLight,
    surfaceTint = ExpPrimaryLight,
    surfaceDim = Color(0xFFD5DBD4),
    surfaceBright = ExpSurfaceLight,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF5ED),
    surfaceContainer = Color(0xFFE9F0E8),
    surfaceContainerHigh = Color(0xFFE3EAE2),
    surfaceContainerHighest = Color(0xFFDEE4DC),
)

// ---- Dark ----
private val ExpPrimaryDark = Color(0xFF50E07A)
private val ExpOnPrimaryDark = Color(0xFF003918)
private val ExpPrimaryContainerDark = Color(0xFF005225)
private val ExpOnPrimaryContainerDark = Color(0xFF6FFE94)
private val ExpSecondaryDark = Color(0xFF8BD5C6)
private val ExpOnSecondaryDark = Color(0xFF003730)
private val ExpSecondaryContainerDark = Color(0xFF005046)
private val ExpOnSecondaryContainerDark = Color(0xFFA7F2E2)
private val ExpTertiaryDark = Color(0xFFFFB5A3)
private val ExpOnTertiaryDark = Color(0xFF5D180B)
private val ExpTertiaryContainerDark = Color(0xFF7C2E1F)
private val ExpOnTertiaryContainerDark = Color(0xFFFFDBD1)
private val ExpErrorDark = Color(0xFFFFB4AB)
private val ExpOnErrorDark = Color(0xFF690005)
private val ExpErrorContainerDark = Color(0xFF93000A)
private val ExpOnErrorContainerDark = Color(0xFFFFDAD6)
private val ExpBackgroundDark = Color(0xFF0E1511)
private val ExpOnBackgroundDark = Color(0xFFDEE4DC)
private val ExpSurfaceDark = Color(0xFF0E1511)
private val ExpOnSurfaceDark = Color(0xFFDEE4DC)
private val ExpSurfaceVariantDark = Color(0xFF404942)
private val ExpOnSurfaceVariantDark = Color(0xFFBFC9BF)
private val ExpOutlineDark = Color(0xFF8A938A)
private val ExpOutlineVariantDark = Color(0xFF404942)

val ExpressiveDarkColorScheme = darkColorScheme(
    primary = ExpPrimaryDark,
    onPrimary = ExpOnPrimaryDark,
    primaryContainer = ExpPrimaryContainerDark,
    onPrimaryContainer = ExpOnPrimaryContainerDark,
    secondary = ExpSecondaryDark,
    onSecondary = ExpOnSecondaryDark,
    secondaryContainer = ExpSecondaryContainerDark,
    onSecondaryContainer = ExpOnSecondaryContainerDark,
    tertiary = ExpTertiaryDark,
    onTertiary = ExpOnTertiaryDark,
    tertiaryContainer = ExpTertiaryContainerDark,
    onTertiaryContainer = ExpOnTertiaryContainerDark,
    error = ExpErrorDark,
    onError = ExpOnErrorDark,
    errorContainer = ExpErrorContainerDark,
    onErrorContainer = ExpOnErrorContainerDark,
    background = ExpBackgroundDark,
    onBackground = ExpOnBackgroundDark,
    surface = ExpSurfaceDark,
    onSurface = ExpOnSurfaceDark,
    surfaceVariant = ExpSurfaceVariantDark,
    onSurfaceVariant = ExpOnSurfaceVariantDark,
    outline = ExpOutlineDark,
    outlineVariant = ExpOutlineVariantDark,
    surfaceTint = ExpPrimaryDark,
    surfaceDim = ExpSurfaceDark,
    surfaceBright = Color(0xFF343B36),
    surfaceContainerLowest = Color(0xFF090F0C),
    surfaceContainerLow = Color(0xFF171D19),
    surfaceContainer = Color(0xFF1B211D),
    surfaceContainerHigh = Color(0xFF252B27),
    surfaceContainerHighest = Color(0xFF303631),
)
