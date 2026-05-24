package com.bitchat.android.profiling

object Traits {
    val PSYCHOLOGICAL = listOf("Extrovert", "Introvert", "Analytical", "Empathetic", "Resilient")
    val MENTAL = listOf("Logical", "Creative", "Strategic", "Focused")
    val PHYSICAL = listOf("Athletic", "Stamina", "Agile", "Strong")
    val EDUCATIONAL = listOf("Primary", "Secondary", "Tertiary", "Vocational", "Self-taught")

    val ALL_TRAITS = mapOf(
        "Psychological" to PSYCHOLOGICAL,
        "Mental" to MENTAL,
        "Physical" to PHYSICAL,
        "Educational" to EDUCATIONAL
    )
}
