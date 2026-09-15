package com.example.slmchat.ui.theme

import androidx.compose.ui.graphics.Color

// iOS-inspired color palette
// Light theme - iOS style blues, clean whites, subtle grays
val IosBlue = Color(0xFF007AFF)           // Primary iOS blue
val IosBlueLight = Color(0xFF5AC8FA)      // Light blue for accents
val IosBlueDark = Color(0xFF0056CC)       // Darker blue for pressed states
val IosGreen = Color(0xFF34C759)          // Success green
val IosRed = Color(0xFFFF3B30)            // Error/destructive red
val IosOrange = Color(0xFFFF9F0A)         // Warning orange
val IosGray = Color(0xFF8E8E93)           // Secondary label gray
val IosGray2 = Color(0xFFAEAEB2)          // Tertiary label gray
val IosGray3 = Color(0xFFC7C7CC)          // Separator gray
val IosGray4 = Color(0xFFD1D1D6)          // Light separator
val IosGray5 = Color(0xFFE5E5EA)          // Background gray (light mode)
val IosGray6 = Color(0xFFF2F2F7)          // System group background (light mode)
val IosWhite = Color(0xFFFFFFFF)          // Pure white
val IosBlack = Color(0xFF000000)          // Pure black

// Dark theme - iOS dark mode colors
val IosBlueDarkMode = Color(0xFF0A84FF)   // Primary blue in dark mode
val IosBlueLightDarkMode = Color(0xFF64D2FF) // Light blue in dark mode
val IosGreenDarkMode = Color(0xFF30D158)  // Success green dark
val IosRedDarkMode = Color(0xFFFF453A)    // Error red dark
val IosOrangeDarkMode = Color(0xFFFF9F0A) // Warning orange dark
val IosGrayDark1 = Color(0xFF8E8E93)      // Secondary label dark
val IosGrayDark2 = Color(0xFF636366)      // Tertiary label dark
val IosGrayDark3 = Color(0xFF48484A)      // Separator dark
val IosGrayDark4 = Color(0xFF3A3A3C)      // Separator dark 2
val IosGrayDark5 = Color(0xFF1C1C1E)      // Background dark (system background)
val IosGrayDark6 = Color(0xFF2C2C2E)      // Secondary background (group background)
val IosGrayDark7 = Color(0xFF3A3A3C)      // Tertiary background (card background)

// Semantic colors for light theme
val LightPrimary = IosBlue
val LightOnPrimary = IosWhite
val LightPrimaryContainer = Color(0xFFD6E4F0) // Light blue container
val LightOnPrimaryContainer = Color(0xFF001D35)
val LightSecondary = IosBlueLight
val LightOnSecondary = IosWhite
val LightSecondaryContainer = Color(0xFFDAE8FC)
val LightOnSecondaryContainer = Color(0xFF001F30)
val LightTertiary = IosGreen
val LightOnTertiary = IosWhite
val LightTertiaryContainer = Color(0xFFD3EED3)
val LightOnTertiaryContainer = Color(0xFF002000)
val LightError = IosRed
val LightOnError = IosWhite
val LightErrorContainer = Color(0xFFF9DEDC)
val LightOnErrorContainer = Color(0xFF410002)
val LightBackground = IosWhite
val LightOnBackground = Color(0xFF1C1C1E)
val LightSurface = IosWhite
val LightOnSurface = Color(0xFF1C1C1E)
val LightSurfaceVariant = IosGray6
val LightOnSurfaceVariant = Color(0xFF48484A)
val LightOutline = IosGray3
val LightOutlineVariant = IosGray4
val LightShadow = Color(0x33000000)  // 20% black
val LightScrim = Color(0x99000000)   // 60% black
val LightInverseSurface = Color(0xFF2C2C2E)
val LightInverseOnSurface = IosWhite
val LightSurfaceContainer = IosGray6
val LightSurfaceContainerHigh = IosGray5
val LightSurfaceContainerHighest = IosGray4

// Semantic colors for dark theme
val DarkPrimary = IosBlueDarkMode
val DarkOnPrimary = IosWhite
val DarkPrimaryContainer = Color(0xFF003366)
val DarkOnPrimaryContainer = Color(0xFFD6E4F0)
val DarkSecondary = IosBlueLightDarkMode
val DarkOnSecondary = IosBlack
val DarkSecondaryContainer = Color(0xFF003D5E)
val DarkOnSecondaryContainer = Color(0xFFDAE8FC)
val DarkTertiary = IosGreenDarkMode
val DarkOnTertiary = IosBlack
val DarkTertiaryContainer = Color(0xFF003800)
val DarkOnTertiaryContainer = Color(0xFFD3EED3)
val DarkError = IosRedDarkMode
val DarkOnError = IosWhite
val DarkErrorContainer = Color(0xFF690005)
val DarkOnErrorContainer = Color(0xFFF2B8B5)
val DarkBackground = IosGrayDark5
val DarkOnBackground = IosWhite
val DarkSurface = IosGrayDark5
val DarkOnSurface = IosWhite
val DarkSurfaceVariant = IosGrayDark7
val DarkOnSurfaceVariant = IosGrayDark1
val DarkOutline = IosGrayDark3
val DarkOutlineVariant = IosGrayDark4
val DarkShadow = Color(0x33000000)
val DarkScrim = Color(0x99000000)
val DarkInverseSurface = IosWhite
val DarkInverseOnSurface = IosBlack
val DarkSurfaceContainer = IosGrayDark6
val DarkSurfaceContainerHigh = IosGrayDark7
val DarkSurfaceContainerHighest = Color(0xFF48484A)