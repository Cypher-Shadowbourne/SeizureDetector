package com.pbess.qrphotostudioultra.seizuredetector.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val DarkBackground = Color(0xFF0F0F12)
val CardSurface = Color(0xFF1E1E24)
val AccentPrimary = Color(0xFF6366F1) // Indigo/Blue
val AccentSecondary = Color(0xFF8B5CF6) // Violet
val AccentTertiary = Color(0xFFD946EF) // Magenta
val ErrorColor = Color(0xFFEF4444)
val SuccessColor = Color(0xFF10B981)

val PrimaryGradient = Brush.horizontalGradient(listOf(AccentPrimary, AccentSecondary))
val AlertGradient = Brush.horizontalGradient(listOf(ErrorColor, AccentTertiary))