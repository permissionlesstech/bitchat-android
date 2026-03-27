package com.bitchat.android.profiling

import com.bitchat.android.location.NigeriaLocation
import com.bitchat.android.nostr.NostrEvent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.google.gson.Gson

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProfilingManagerTest {

    private lateinit var db: AppDatabase
    private lateinit var profilingManager: ProfilingManager

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        db = AppDatabase.getInMemoryDatabase(context)
        profilingManager = ProfilingManager(context, db.profileDao())
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun testScoutingAndMatching() = runBlocking {
        val location = NigeriaLocation("Lagos", "Lagos West", "Ikeja", "Oregun", "Ikeja I")

        profilingManager.scoutPerson(
            name = "John Doe",
            age = 30,
            gender = "Male",
            location = location,
            skills = listOf("Coding", "Security"),
            contact = "john@example.com",
            traits = mapOf("Psychological" to "Analytical"),
            scoutPubkey = "scout1"
        )

        val allProfiles = profilingManager.getAllProfiles()
        assertEquals(1, allProfiles.size)
        assertEquals("John Doe", allProfiles[0].name)

        val matches = profilingManager.matchProfiles(allProfiles, criteriaSkills = listOf("Coding"))
        assertEquals(1, matches.size)
    }

    @Test
    fun testMergeLogic() = runBlocking {
        val location = NigeriaLocation("Lagos", "Lagos West", "Ikeja", "Oregun", "Ikeja I")

        val profiles = listOf(
            ScoutedProfile(
                id = "p1",
                name = "Alice",
                age = 25,
                gender = "Female",
                location = location,
                skills = emptyList(),
                contact = null,
                traits = emptyMap(),
                scoutPubkey = "scout1",
                version = 1
            )
        )

        val event = NostrEvent(
            id = "event1",
            pubkey = "scout1",
            createdAt = 1234567,
            kind = 30005,
            tags = emptyList(),
            content = Gson().toJson(profiles)
        )

        profilingManager.receiveMergeRequest(event)

        val allProfiles = profilingManager.getAllProfiles()
        assertEquals(1, allProfiles.size)
        assertEquals("Alice", allProfiles[0].name)
    }
}
