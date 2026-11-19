package com.bitchat.android.identity.scope

import dagger.hilt.DefineComponent
import dagger.hilt.components.SingletonComponent

/** Holds the objects that belong to one identity. Built by [IdentityHolder]. */
@IdentityScoped
@DefineComponent(parent = SingletonComponent::class)
interface IdentityComponent

@DefineComponent.Builder
interface IdentityComponentBuilder {
    fun build(): IdentityComponent
}
