package de.axelcypher.asmr.server.imports

/**
 * Leitet Trigger-Tags aus Titel, Beschreibung und den Tags der Quelle ab. Bewusst nur eine feste
 * Liste: die Tags der Plattformen sind zu verrauscht, um sie direkt zu übernehmen.
 */
object TriggerTags {

    private val RULES: List<Pair<String, Regex>> = listOf(
        "Tapping" to "tapping|\\btaps?\\b",
        "Scratching" to "scratch",
        "Whispering" to "whisper",
        "Soft Spoken" to "soft[ -]?spoken",
        "No Talking" to "no[ -]?talking",
        "Mouth Sounds" to "mouth[ -]?sounds?",
        "Ear Cleaning" to "ear[ -]?clean",
        "Ear Massage" to "ear[ -]?massage",
        "Massage" to "\\bmassage",
        "Brushing" to "brush",
        "Hair Play" to "hair[ -]?(play|brushing)",
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

    fun detect(title: String, description: String?, sourceTags: List<String>): List<String> {
        val text = buildString {
            append(title).append('\n')
            description?.let { append(it).append('\n') }
            sourceTags.joinTo(this, "\n")
        }
        return RULES.filter { (_, regex) -> regex.containsMatchIn(text) }.map { it.first }
    }
}
