package com.bitchat.android.di

import com.bitchat.android.identity.scope.IdentityComponent
import dagger.hilt.DefineComponent
import javax.inject.Qualifier
import javax.inject.Scope

/** Lives as long as one chat session: one instance per [ChatSessionComponent]. */
@Scope
@Retention(AnnotationRetention.BINARY)
annotation class ChatSessionScoped

/**
 * Marks a dependency that belongs to the current chat session, so a consumer cannot be given one
 * by accident. Bridged from [ChatSessionComponent] by [ChatSessionBindings]. Apart from a ViewModel,
 * which a panic discards, a consumer takes `@CurrentSession Provider<T>` and calls `get()` when used.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION)
annotation class CurrentSession

/**
 * Holds the objects of one chat session, inside the identity they belong to. It ends sooner than
 * the identity when the person leaves the app. Built by `ChatSessionHolder`.
 */
@ChatSessionScoped
@DefineComponent(parent = IdentityComponent::class)
interface ChatSessionComponent

@DefineComponent.Builder
interface ChatSessionComponentBuilder {
    fun build(): ChatSessionComponent
}
