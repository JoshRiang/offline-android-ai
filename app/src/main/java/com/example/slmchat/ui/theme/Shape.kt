package com.example.slmchat.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// iOS-inspired shapes with 12-16dp rounded corners
val SlmShapes = Shapes(
    // Extra small - for chips, small buttons (12dp)
    extraSmall = RoundedCornerShape(12.dp),
    // Small - for text fields, chips (12dp)
    small = RoundedCornerShape(12.dp),
    // Medium - for cards, dialogs, sheets (16dp)
    medium = RoundedCornerShape(16.dp),
    // Large - for bottom sheets, modals (24dp top corners)
    large = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    // Extra large - for full screen modals
    extraLarge = RoundedCornerShape(0.dp)
)