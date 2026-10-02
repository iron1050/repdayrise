package com.repdayrise.app

import com.repdayrise.app.data.model.Habit
import com.repdayrise.app.data.model.HabitType
import com.repdayrise.app.data.model.ScheduleType
import com.repdayrise.app.data.sharing.ApiException
import com.repdayrise.app.data.sharing.SharingApi
import com.repdayrise.app.data.sharing.SharingJson
import com.repdayrise.app.data.sharing.SharingRepository
import com.repdayrise.app.data.sharing.Snapshot
import com.repdayrise.app.domain.HabitLogic
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class SharingTest {
    private val today = LocalDate.of(2026, 9, 15)

    private val read = Habit(id = 1, name = "Read", note = "private thoughts", startDate = today.minusDays(30))
    private val water = Habit(id = 2, name = "Water", type = HabitType.COUNT, goal = 8.0, unit = "glasses", startDate = today.minusDays(30))
    private val retired = Habit(id = 3, name = "Old", archived = true, startDate = today.minusDays(30))
    private val swim = Habit(id = 4, name = "Swim", scheduleType = ScheduleType.WEEKLY, timesPerPeriod = 2, startDate = today.minusDays(30))
    private val entries = mapOf(
        1L to mapOf(today.toEpochDay() to 1.0, today.minusDays(900).toEpochDay() to 1.0),
        2L to mapOf(today.toEpochDay() to 4.0, today.plusDays(1).toEpochDay() to 8.0),
        3L to mapOf(today.toEpochDay() to 1.0),
    )

    private fun snapshot(excluded: Set<Long> = emptySet()) =
        Snapshot.build(listOf(read, water, retired, swim), entries, excluded, weekStartsMonday = false, today = today)

    @Test
    fun snapshotLeavesOutArchivedExcludedOldAndFutureData() {
        val s = snapshot(excluded = setOf(4L))
        assertEquals(listOf(1L, 2L), s.habits.map { it.id })
        assertEquals(setOf(today.toEpochDay()), s.entries[1L]!!.keys)
        assertEquals(setOf(today.toEpochDay()), s.entries[2L]!!.keys)
        assertNull(s.entries[3L])
    }

    @Test
    fun snapshotNeverCarriesNotes() {
        assertTrue("private thoughts" !in SharingJson.encodeToString(snapshot()))
    }

    @Test
    fun snapshotSurvivesTheWireAndDrivesTheSameLogic() {
        val decoded = SharingJson.decodeFromString<Snapshot>(SharingJson.encodeToString(snapshot()))
        assertEquals(snapshot(), decoded)
        val habits = decoded.habits.map { it.toHabit() }
        assertEquals(ScheduleType.WEEKLY, habits.first { it.id == 4L }.scheduleType)
        val logic = HabitLogic(DayOfWeek.SUNDAY)
        // Read done, water half done, swim due but untouched.
        assertEquals(0.5f, logic.dayProgress(habits, decoded.entries, today), 0.001f)
        assertEquals(1 to 3, logic.dayCounts(habits, decoded.entries, today))
    }

    @Test
    fun unknownFieldsFromANewerAppAreIgnored() {
        val s = SharingJson.decodeFromString<Snapshot>("""{"schema":2,"mood":"great","habits":[{"id":7,"name":"Run","type":"SPRINT","emoji":"x"}]}""")
        assertEquals(HabitType.CHECK, s.habits.single().toHabit().type)
    }

    @Test
    fun codesAreExtractedFromWhateverGetsPasted() {
        assertEquals("ABCDEFGHJK", SharingRepository.extractCode(" abcde-fghjk "))
        assertEquals("ABCDEFGHJK", SharingRepository.extractCode("dayrise://join/ABCDEFGHJK"))
        assertEquals("ABCDEFGHJK", SharingRepository.extractCode("Be my partner https://x.workers.dev/j/ABCDEFGHJK\n\nOr enter"))
    }

    @Test
    fun serverAddressesAreTidied() {
        assertEquals("", SharingRepository.normalizeUrl("  "))
        assertEquals("https://x.workers.dev", SharingRepository.normalizeUrl(" x.workers.dev/ "))
        assertEquals("http://localhost:8787", SharingRepository.normalizeUrl("http://localhost:8787"))
        assertEquals("https://x.dev/j/ABCDEFGHJK", SharingRepository.inviteLink("https://x.dev", "ABCDE-FGHJK"))
    }

    /** Talks to a real backend. Runs only when DAYRISE_TEST_SERVER is set, e.g. http://localhost:8787. */
    @Test
    fun clientAndServerAgreeOnTheWireFormat() = runTest {
        val base = System.getenv("DAYRISE_TEST_SERVER").orEmpty()
        assumeTrue("DAYRISE_TEST_SERVER not set", base.isNotBlank())
        val api = SharingApi()
        val share = api.createShare(base, "Owner")
        api.publish(base, share.id, share.token, "Owner", snapshot())

        val joined = api.join(base, share.code, "Partner")
        assertEquals(share.id, joined.shareId)
        assertEquals("Owner", joined.ownerName)

        val feed = api.feed(base, joined.shareId, joined.token, -1)!!
        assertEquals(snapshot(), feed.snapshot)
        assertEquals(1, feed.version)
        assertNull(api.feed(base, joined.shareId, joined.token, feed.version))

        val status = api.status(base, share.id, share.token)
        assertEquals(share.code, status.code)
        assertEquals(listOf("Partner"), status.subscribers.map { it.name })

        val newCode = api.rotateCode(base, share.id, share.token)
        assertTrue(newCode != share.code)

        api.removePartner(base, share.id, share.token, status.subscribers.single().id)
        val locked = runCatching { api.feed(base, joined.shareId, joined.token, -1) }.exceptionOrNull()
        assertEquals(401, (locked as ApiException).status)

        val again = api.join(base, newCode, "Partner")
        api.unsubscribe(base, again.token)
        api.deleteShare(base, share.id, share.token)
        assertEquals(401, (runCatching { api.status(base, share.id, share.token) }.exceptionOrNull() as ApiException).status)
    }
}
