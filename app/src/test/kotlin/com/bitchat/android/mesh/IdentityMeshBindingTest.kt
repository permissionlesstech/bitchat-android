package com.bitchat.android.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock

class IdentityMeshBindingTest {

    /** A service whose `delegate` property keeps what was assigned, like the real one. */
    private fun fakeService(): MeshService {
        var held: MeshDelegate? = null
        return mock {
            on { delegate } doAnswer { held }
            on { delegate = anyOrNull() } doAnswer { held = it.getArgument(0); Unit }
        }
    }

    private var generation = 0
    private val firstMesh = fakeService()
    private val secondMesh = fakeService()
    private var mesh = firstMesh
    private var meshReads = 0
    private val firstDelegate: MeshDelegate = mock()
    private val secondDelegate: MeshDelegate = mock()
    private var delegate = firstDelegate
    private var delegateReads = 0

    private fun binding() = IdentityMeshBinding(
        generation = { generation },
        mesh = {
            meshReads++
            mesh
        },
        delegate = {
            delegateReads++
            delegate
        },
    )

    private fun replaceIdentity() {
        generation++
        mesh = secondMesh
        delegate = secondDelegate
    }

    @Test
    fun `the mesh is read when the binding is made and the delegate at the first attach`() {
        val binding = binding()

        assertEquals(1, meshReads)
        assertEquals(0, delegateReads)

        binding.attach()
        binding.attach()

        assertSame(firstDelegate, firstMesh.delegate)
        assertEquals(1, meshReads)
        assertEquals(1, delegateReads)
    }

    @Test
    fun `detaching with nothing attached builds nothing`() {
        val binding = binding()

        binding.detachIfAttached()

        assertEquals(0, delegateReads)
        assertEquals(1, meshReads)
    }

    @Test
    fun `within one identity it keeps the mesh it read`() {
        val binding = binding()
        // The foreground service stopped and cleared the holder: same identity, another mesh.
        mesh = secondMesh

        binding.attach()

        assertSame(firstDelegate, firstMesh.delegate)
        assertNull(secondMesh.delegate)
    }

    @Test
    fun `an attach after the identity changed leaves the old mesh and binds the new one`() {
        val binding = binding()
        binding.attach()

        replaceIdentity()
        binding.attach()

        assertNull(firstMesh.delegate)
        assertSame(secondDelegate, secondMesh.delegate)
    }

    @Test
    fun `a detach after the identity changed builds nothing for the new identity`() {
        val binding = binding()
        binding.attach()
        replaceIdentity()
        val readsBefore = meshReads to delegateReads

        binding.detachIfAttached()

        assertNull(firstMesh.delegate)
        assertNull(secondMesh.delegate)
        assertEquals(readsBefore, meshReads to delegateReads)
    }

    @Test
    fun `after the rebind pausing and resuming touches only the new mesh`() {
        val binding = binding()
        binding.attach()
        replaceIdentity()
        binding.attach()

        binding.detachIfAttached()
        assertNull(secondMesh.delegate)

        binding.attach()
        assertNull(firstMesh.delegate)
        assertSame(secondDelegate, secondMesh.delegate)
    }
}
