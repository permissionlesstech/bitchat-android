package com.bitchat.android.services

import com.bitchat.android.identity.scope.IdentityScoped
import com.bitchat.android.mesh.MeshService
import javax.inject.Inject

/**
 * Owns the identity's one [MessageRouter]. The router outlives any mesh: the foreground service
 * stopping or a panic clear replaces the mesh, and the next [getOrAttach] re-points the router and
 * restarts its outbox scheduler.
 */
@IdentityScoped
class MessageRouterHolder @Inject constructor(
    private val newRouter: MessageRouter.Factory,
) {
    @Volatile
    private var router: MessageRouter? = null

    /** The router if one exists. Never builds one and never touches a mesh. */
    fun peek(): MessageRouter? = router

    /**
     * Builds the router on the first call, then points it at [mesh] and makes sure its scheduler
     * runs. It is attached before it is published, so [peek] never returns one without a mesh.
     */
    fun getOrAttach(mesh: MeshService): MessageRouter {
        val current = router ?: synchronized(this) {
            router ?: newRouter.create().also { built ->
                built.attach(mesh)
                built.registerFavoritesListener()
                router = built
            }
        }
        current.attach(mesh)
        return current
    }
}
