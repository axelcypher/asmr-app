package de.axelcypher.asmr.api

/** Stärke eines Triggers in der Bewertungsmatrix; 0 heißt "kommt nicht vor" und wird nicht gespeichert. */
const val MAX_TRIGGER_LEVEL = 10

/** Startwert für automatisch erkannte Trigger. */
const val DETECTED_TRIGGER_LEVEL = 5

/**
 * Alle Trigger, die der Editor als Regler anbietet (eigene Trigger sind zusätzlich möglich).
 * Gruppiert, damit die Liste im Editor überschaubar bleibt.
 */
val TRIGGER_CATALOG: List<Pair<String, List<String>>> = listOf(
    "Stimme" to listOf("Whispering", "Soft Spoken", "Talking", "No Talking", "Humming", "Singing", "Breathing"),
    "Mund" to listOf("Kisses", "Licking", "Mouth Sounds", "Ear Eating"),
    "Ohren & Berührung" to listOf(
        "Ear Cleaning", "Ear Massage", "Massage", "Brushing", "Hair Play", "Scratching", "Tapping", "Hand Movements",
    ),
    "Gegenstände" to listOf("Crinkles", "Page Turning", "Typing", "Heartbeat"),
    "Atmosphäre" to listOf("Rain", "Water", "Fire", "Layered", "Binaural"),
    "Inhalt" to listOf("Roleplay", "Personal Attention", "Visual Triggers"),
)

val ALL_TRIGGERS: List<String> = TRIGGER_CATALOG.flatMap { it.second }
