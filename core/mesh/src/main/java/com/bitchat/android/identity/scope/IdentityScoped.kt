package com.bitchat.android.identity.scope

import javax.inject.Qualifier
import javax.inject.Scope

/** Lives as long as one identity: one instance per [IdentityComponent]. */
@Scope
@Retention(AnnotationRetention.BINARY)
annotation class IdentityScoped

/**
 * Marks a dependency that belongs to the current identity, so a consumer cannot be given one by
 * accident from the process-wide graph. Bridged from [IdentityComponent] by the module that
 * owns the type.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION)
annotation class CurrentIdentity
