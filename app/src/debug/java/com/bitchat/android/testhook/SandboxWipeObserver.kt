package com.bitchat.android.testhook

import com.bitchat.android.panic.WipeResult

/**
 * Lets a device test look at the sandbox right after the panic's wipe returns, while no identity
 * exists and so before the next one writes its own files. Debug builds only.
 *
 * [afterWipe] runs on the panic's thread inside the identity holder's lock, so it must not read
 * the identity. Set it before the panic and clear it when the test is done.
 */
object SandboxWipeObserver {
    @Volatile
    var afterWipe: ((WipeResult) -> Unit)? = null
}
