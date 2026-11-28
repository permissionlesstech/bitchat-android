package com.bitchat.android.nostr

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrResponseTest {

    @Test
    fun parsesClosedResponse() {
        val arr = JsonParser.parseString("""["CLOSED","sub-1","auth-required: login"]""").asJsonArray
        val response = NostrResponse.fromJsonArray(arr)
        assertTrue(response is NostrResponse.Closed)
        response as NostrResponse.Closed
        assertEquals("sub-1", response.subscriptionId)
        assertEquals("auth-required: login", response.message)
    }

    @Test
    fun parsesAuthResponse() {
        val arr = JsonParser.parseString("""["AUTH","challenge-token-123"]""").asJsonArray
        val response = NostrResponse.fromJsonArray(arr)
        assertTrue(response is NostrResponse.Auth)
        assertEquals("challenge-token-123", (response as NostrResponse.Auth).challenge)
    }
}
