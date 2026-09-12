# MediaPipe / LLM
-keep class com.google.mediapipe.tasks.genai.llminference.** { *; }
-dontwarn com.google.mediapipe.**
# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.**
