package com.bitchat.android.parsing

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for MessageParser and CashuTokenParser
 */
class MessageParserTest {
    
    private val messageParser = MessageParser.instance
    
    @Test
    fun testPlainTextMessage() {
        val content = "Hello world! This is a regular message."
        val elements = messageParser.parseMessage(content)
        
        assertEquals(1, elements.size)
        assertTrue(elements[0] is MessageElement.Text)
        assertEquals(content, (elements[0] as MessageElement.Text).content)
    }
    
    @Test
    fun testMessageWithCashuToken() {
        // Test with a mock Cashu token pattern
        val content = "Here's a payment: cashuBeyJ0b2tlbiI6W3sibWludCI6Imh0dHA6Ly9sb2NhbGhvc3Q6MzMzOCIsInByb29mcyI6W3siYW1vdW50IjoyLCJpZCI6IjAwOWExZjI5MzI1M2U0MWUiLCJzZWNyZXQiOiI0MDc5MTViYzIxMmJlNjFhNzdlM2U2ZDJhZWI0YzcyNzk4MGJkYTUxY2QwNmE2YWZjMjllMjg2MTc2OGE3ODM3IiwiQyI6IjAyYmM5MDk3OTk3ZDgxYWZiMmNjNzM0NmI1ZTQzNDVhOTM0NmJkMmE1MDZlYjc5NTg1OThhNzJmMGNmODUxNjNlYSJ9LHsiYW1vdW50Ijo4LCJpZCI6IjAwOWExZjI5MzI1M2U0MWUiLCJzZWNyZXQiOiJmZTE1MTA5MzE0ZTYxZDc3NTZiMGY4ZWUwZjIzYTYyNGFjYWEzZjRlMDQyZjYxNDMzYzcyOGM3MDU3YjkzMWJlIiwiQyI6IjAyOWU4ZTUwNTBiODkwYTdkNmMwOTY4ZGIxNmJjMWQ1ZDVmYTA0MGVhMWRlMjg0ZjZlYzY5ZDYxMjk5ZjY3MTA1OSJ9XX1dLCJ1bml0Ijoic2F0IiwibWVtbyI6IlRoYW5rIHlvdS4ifQ please receive it!"
        val elements = messageParser.parseMessage(content)
        
        // Should find: text "Here's a payment: " + cashu token + text " please receive it!"
        assertTrue(elements.size >= 3)
        
        // Check structure - should have both text and payment elements
        assertTrue(elements.any { it is MessageElement.Text })
        assertTrue(elements.any { it is MessageElement.CashuPayment })
    }
    
    @Test
    fun testMultipleCashuTokensInMessage() {
        val content = "First: cashuBdGVzdDE and second: cashuBdGVzdDI"
        val elements = messageParser.parseMessage(content)
        
        // Should find text + cashu + text + cashu elements
        val cashuElements = elements.filterIsInstance<MessageElement.CashuPayment>()
        assertEquals(2, cashuElements.size)
    }
    
    @Test
    fun testInvalidCashuToken() {
        // This should fall back to creating a token with placeholder data
        val content = "Token: cashuBinvaliddata123"
        val elements = messageParser.parseMessage(content)
        
        val cashuElements = elements.filterIsInstance<MessageElement.CashuPayment>()
        assertEquals(1, cashuElements.size)
        
        // Should have fallback values
        val token = cashuElements[0].token
        assertEquals(100L, token.amount)
        assertEquals("sat", token.unit)
    }
    
    @Test
    fun testCashuTokenParser() {
        val parser = CashuTokenParser()
        
        // Test with invalid token
        val invalidResult = parser.parseToken("invalid")
        assertNull(invalidResult)
        
        // Test with token that doesn't start with cashuB
        val invalidPrefixResult = parser.parseToken("cashuAinvaliddata")
        assertNull(invalidPrefixResult)
        
        // Test with empty token
        val emptyResult = parser.parseToken("cashuB")
        assertNull(emptyResult)
        
        // Test with mock token (will use fallback)
        val mockResult = parser.parseToken("cashuBdGVzdA")
        assertNotNull(mockResult)
        assertEquals(100L, mockResult!!.amount)
        assertEquals("sat", mockResult.unit)
    }
}
