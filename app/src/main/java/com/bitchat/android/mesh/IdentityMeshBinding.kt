package com.bitchat.android.mesh

/**
 * The [MeshDelegateBinding] of whoever stays while the identity changes, such as an Activity.
 *
 * It keeps one mesh per identity, read when it is made and when the identity has changed: within
 * one identity the UI stays on the mesh it read, as [MeshDelegateBinding] always did. The next
 * [attach] after the identity changed leaves the old mesh and binds the new one. [detachIfAttached]
 * never reads anything new, so detaching with nothing attached builds nothing.
 *
 * [generation] is the identity whose start-up has finished, so nothing binds to a new identity's mesh
 * before that identity has restored what it stored.
 *
 * Confined to the main thread, like the lifecycle callbacks that call it.
 */
class IdentityMeshBinding(
    private val generation: () -> Int,
    private val mesh: () -> MeshService,
    private val delegate: () -> MeshDelegate,
) {
    // Read before the mesh: if a replacement lands in between, the mesh looks older than it is and
    // the next attach reads it again. The other order would pin it to an ended identity.
    private var heldFor = generation()
    private var held = mesh()
    private var binding: MeshDelegateBinding? = null

    fun attach() {
        val now = generation()
        if (now != heldFor) {
            binding?.detachIfAttached()
            binding = null
            // Noted only once the read worked, so a failed read is tried again at the next attach
            // rather than leaving the new identity's delegate on the old mesh.
            held = mesh()
            heldFor = now
        }
        val current = binding ?: MeshDelegateBinding(held, delegate()).also { binding = it }
        current.attach()
    }

    fun detachIfAttached() {
        binding?.detachIfAttached()
    }
}
