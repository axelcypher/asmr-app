package de.axelcypher.asmr.server.imports

import de.axelcypher.asmr.api.DETECTED_TRIGGER_LEVEL

/**
 * Leitet Trigger aus Titel, Beschreibung und den Tags der Quelle ab, jeweils mit Stärke
 * [DETECTED_TRIGGER_LEVEL]; feinjustiert wird in der App. Bewusst nur eine feste Liste: die Tags
 * der Plattformen sind zu verrauscht, um sie direkt zu übernehmen. Namen wie im Trigger-Katalog.
 */
object TriggerTags {

    private val RULES: List<Pair<String, Regex>> = listOf(
        "Tapping" to "tapping|\\btaps?\\b",
        "Scratching" to "scratch",
        "Whispering" to "whisper",
        "Soft Spoken" to "soft[ -]?spoken",
        "No Talking" to "no[ -]?talking",
        "Kisses" to "\\bkiss",
        "Licking" to "\\blick",
        "Mouth Sounds" to "mouth[ -]?sounds?",
        "Ear Eating" to "ear[ -]?eating",
        "Breathing" to "breath",
        "Humming" to "\\bhumm",
        "Singing" to "\\bsing(ing)?\\b|lullab",
        "Ear Cleaning" to "ear[ -]?clean",
        "Ear Massage" to "ear[ -]?massage",
        "Massage" to "\\bmassage",
        "Brushing" to "brush",
        "Hair Play" to "hair[ -]?(play|brushing)",
        "Heartbeat" to "heart[ -]?beat",
        "Rain" to "\\brain\\b|rainfall|rainy",
        "Water" to "water[ -]?sounds?|\\bliquid",
        "Fire" to "fireplace|crackling",
        "Roleplay" to "role[ -]?play|\\brp\\b",
        "Personal Attention" to "personal[ -]?attention",
        "Crinkles" to "crinkl",
        "Page Turning" to "page[ -]?turn",
        "Typing" to "typing|keyboard",
        "Hand Movements" to "hand[ -]?movements?",
        "Binaural" to "binaural|3dio",
        "Layered" to "layered",
        "Visual Triggers" to "visual[ -]?triggers?",
    ).map { (tag, pattern) -> tag to Regex(pattern, RegexOption.IGNORE_CASE) }

    fun detect(title: String, description: String?, sourceTags: List<String>): Map<String, Int> {
        val text = buildString {
            append(title).append('\n')
            description?.let { append(it).append('\n') }
            sourceTags.joinTo(this, "\n")
        }
        return RULES.filter { (_, regex) -> regex.containsMatchIn(text) }.associate { it.first to DETECTED_TRIGGER_LEVEL }
    }
}
