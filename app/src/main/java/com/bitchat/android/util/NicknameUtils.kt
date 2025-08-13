package com.bitchat.android.util

import kotlin.random.Random

object NicknameUtils {
    fun generateRandomNickname(): String {
        return "anon${Random.nextInt(1000, 9999)}"
    }
}