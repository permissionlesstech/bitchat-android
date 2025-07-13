package com.bitchat

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Generate a consistent color for a username based on their peer ID or nickname
 * Returns colors that work well on both light and dark backgrounds
 */

class ColorTest {
    fun getUsernameColor(identifier: String): Color {
        // Hash the identifier to get a consistent number
        val hash = identifier.hashCode().toUInt()

        // Terminal-friendly colors that work on both black and white backgrounds
        val colors = listOf(
            Color(0xFF00FF00), // Bright Green
            Color(0xFF00FFFF), // Cyan
            Color(0xFFFFFF00), // Yellow
            Color(0xFFFF00FF), // Magenta
            Color(0xFF0080FF), // Bright Blue
            Color(0xFFFF8000), // Orange
            Color(0xFF80FF00), // Lime Green
            Color(0xFF8000FF), // Purple
            Color(0xFFFF0080), // Pink
            Color(0xFF00FF80), // Spring Green
            Color(0xFF80FFFF), // Light Cyan
            Color(0xFFFF8080), // Light Red
            Color(0xFF8080FF), // Light Blue
            Color(0xFFFFFF80), // Light Yellow
            Color(0xFFFF80FF), // Light Magenta
            Color(0xFF80FF80), // Light Green
        )

        // Use modulo to get consistent color for same identifier
        return colors[(hash % colors.size.toUInt()).toInt()]
    }

    @Test
    fun is_username_derived_color_consistent() {

        println("Testing username color function:")

        val testUsers = listOf("alice", "bob", "charlie", "diana", "eve")

        testUsers.forEach { user ->
            val color = getUsernameColor(user)
            println("User '$user' gets color: ${color.value.toString(16).uppercase()}")
        }

        val `alice'sColor` =  getUsernameColor(testUsers[0])
        val `bob'sColor` = getUsernameColor(testUsers[1])
        val `charlie'sColor` = getUsernameColor(testUsers[2])
        val `diana'sColor` = getUsernameColor(testUsers[3])
        val `eve'sColor` = getUsernameColor(testUsers[4])

        // Test consistency - same user should always get same color
        println("\nTesting consistency:")
        repeat(3) {
            val `alice's_color` = getUsernameColor(testUsers[0])
            val `bob's_color` = getUsernameColor(testUsers[1])
            val `charlie's_color` = getUsernameColor(testUsers[2])
            val `diana's_color` = getUsernameColor(testUsers[3])
            val `eve's_color` = getUsernameColor(testUsers[4])


            assertEquals(`alice'sColor`, `alice's_color`)
            assertEquals(`bob'sColor`, `bob's_color`)
            assertEquals(`charlie'sColor`, `charlie's_color`)
            assertEquals(`diana'sColor`, `diana's_color`)
            assertEquals(`eve'sColor`, `eve's_color`)

            println("Alice color (test ${it + 1}): ${`alice'sColor`.value.toString(16).uppercase()}")
        }
    }
}