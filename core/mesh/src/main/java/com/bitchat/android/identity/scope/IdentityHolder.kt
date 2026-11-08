package com.bitchat.android.identity.scope

import dagger.hilt.EntryPoints
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Owns the process's current [IdentityComponent]. It is built on the first read and the same one
 * is returned afterwards.
 */
@Singleton
class IdentityHolder @Inject constructor(
    private val builder: Provider<IdentityComponentBuilder>,
) {
    val current: IdentityComponent by lazy { builder.get().build() }
}

/** Reads [T], an `@EntryPoint @InstallIn(IdentityComponent::class)` interface, from the current identity. */
inline fun <reified T : Any> IdentityHolder.entryPoint(): T = EntryPoints.get(current, T::class.java)
