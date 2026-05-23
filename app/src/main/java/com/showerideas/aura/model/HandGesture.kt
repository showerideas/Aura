package com.showerideas.aura.model

/**
 * Hand gestures detected by the MediaPipe GestureRecognizer.
 *
 * These labels are used only for real-time UI feedback while the camera is
 * running — the actual authentication credential is the 42-float landmark
 * embedding, not the category name. Two people making a "Victory" sign will
 * produce different embeddings because their hand shapes differ.
 *
 * [mediaPipeLabel] must match the category string emitted by the bundled
 * gesture_recognizer.task model exactly.
 */
enum class HandGesture(
    val displayName: String,
    val emoji: String,
    val mediaPipeLabel: String
) {
    VICTORY     ("Victory",      "\u270c\ufe0f",  "Victory"),
    FIST        ("Fist",         "\u270a",         "Closed_Fist"),
    OPEN_PALM   ("Open Palm",    "\u270b",         "Open_Palm"),
    THUMBS_UP   ("Thumbs Up",    "\ud83d\udc4d",   "Thumb_Up"),
    THUMBS_DOWN ("Thumbs Down",  "\ud83d\udc4e",   "Thumb_Down"),
    POINTING    ("Pointing Up",  "\u261d\ufe0f",   "Pointing_Up"),
    NONE        ("None",         "",               "None");

    companion object {
        private val byLabel: Map<String, HandGesture> =
            entries.associateBy { it.mediaPipeLabel }

        fun fromMediaPipeLabel(label: String): HandGesture = byLabel[label] ?: NONE

        /** Gestures shown to the user as selectable options (excludes NONE). */
        val selectable: List<HandGesture> = entries.filter { it != NONE }
    }
}
