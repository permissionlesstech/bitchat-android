package com.bitchat.android.identity

import android.content.Context

enum class UserRole {
    STANDARD,
    PROFILER,
    GROUP_ADMIN,
    SUPER_ADMIN
}

class RoleManager(private val context: Context) {
    private val identityManager = SecureIdentityStateManager(context)

    fun getCurrentRole(): UserRole {
        val roleStr = identityManager.getSecureValue("user_role") ?: return UserRole.STANDARD
        return try {
            UserRole.valueOf(roleStr)
        } catch (e: Exception) {
            UserRole.STANDARD
        }
    }

    fun setRole(role: UserRole) {
        identityManager.storeSecureValue("user_role", role.name)
    }

    fun canScout(): Boolean = getCurrentRole() != UserRole.STANDARD
    fun canMerge(): Boolean = getCurrentRole() == UserRole.GROUP_ADMIN || getCurrentRole() == UserRole.SUPER_ADMIN
    fun isSuperAdmin(): Boolean = getCurrentRole() == UserRole.SUPER_ADMIN
}
