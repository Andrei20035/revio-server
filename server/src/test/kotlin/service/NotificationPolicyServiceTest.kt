package service

import com.revio.server.features.notification.NotificationCategory
import com.revio.server.features.notification.NotificationPolicyInput
import com.revio.server.features.notification.NotificationPolicyService
import com.revio.server.features.notification.NotificationVerdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Exhaustive coverage for NotificationPolicyService — the highest rule-density component in the
 * push notifications system (plan §18, step 3.3). Pure function, no DB/network involved.
 */
class NotificationPolicyServiceTest {

    private val service = NotificationPolicyService()
    private val quietStart: LocalTime = LocalTime.of(0, 0)
    private val quietEnd: LocalTime = LocalTime.of(8, 0)
    private val bucharest = ZoneId.of("Europe/Bucharest")

    private fun instantAt(date: LocalDate, time: LocalTime, zone: ZoneId): OffsetDateTime =
        date.atTime(time).atZone(zone).toOffsetDateTime()

    private fun baseInput(
        category: NotificationCategory = NotificationCategory.LIKES,
        categoryEnabled: Boolean = true,
        now: OffsetDateTime = instantAt(LocalDate.of(2026, 6, 15), LocalTime.of(12, 0), bucharest),
        zone: ZoneId? = bucharest,
        hourlyCount: Int = 0,
        dailyCount: Int = 0,
        otherSocialHourlyCount: Int = 0,
        otherSocialDailyCount: Int = 0,
        dailyTotalCount: Int = 0,
        cooldownActive: Boolean = false,
    ) = NotificationPolicyInput(
        category = category,
        categoryEnabled = categoryEnabled,
        now = now,
        zone = zone,
        quietStart = quietStart,
        quietEnd = quietEnd,
        hourlyCount = hourlyCount,
        dailyCount = dailyCount,
        otherSocialHourlyCount = otherSocialHourlyCount,
        otherSocialDailyCount = otherSocialDailyCount,
        dailyTotalCount = dailyTotalCount,
        cooldownActive = cooldownActive,
    )

    // ---------------------------------------------------------------------
    // ACCOUNT bypass — exempt from prefs, quiet hours, and caps entirely.
    // ---------------------------------------------------------------------

    @Test
    fun `ACCOUNT dispatches even with prefs off, in quiet hours, and caps saturated`() {
        val decision = service.evaluate(
            baseInput(
                category = NotificationCategory.ACCOUNT,
                categoryEnabled = false,
                now = instantAt(LocalDate.of(2026, 6, 15), LocalTime.of(3, 0), bucharest),
                hourlyCount = 999,
                dailyCount = 999,
                dailyTotalCount = 999,
                cooldownActive = true,
            ),
        )
        assertEquals(NotificationVerdict.DISPATCH, decision.verdict)
        assertNull(decision.notBefore)
    }

    // ---------------------------------------------------------------------
    // Prefs
    // ---------------------------------------------------------------------

    @Test
    fun `category disabled by user prefs suppresses`() {
        val decision = service.evaluate(baseInput(categoryEnabled = false))
        assertEquals(NotificationVerdict.SUPPRESS, decision.verdict)
    }

    @Test
    fun `category enabled by user prefs proceeds past the prefs gate`() {
        val decision = service.evaluate(baseInput(categoryEnabled = true))
        assertEquals(NotificationVerdict.DISPATCH, decision.verdict)
    }

    // ---------------------------------------------------------------------
    // Quiet hours boundaries (D5, window 00:00-08:00, non-circular)
    // ---------------------------------------------------------------------

    @Test
    fun `23_59_59 passes through quiet hours`() {
        val decision = service.evaluate(
            baseInput(now = instantAt(LocalDate.of(2026, 6, 15), LocalTime.of(23, 59, 59), bucharest)),
        )
        assertEquals(NotificationVerdict.DISPATCH, decision.verdict)
    }

    @Test
    fun `00_00_00 exactly is deferred`() {
        val decision = service.evaluate(
            baseInput(now = instantAt(LocalDate.of(2026, 6, 15), LocalTime.of(0, 0, 0), bucharest)),
        )
        assertEquals(NotificationVerdict.DEFER, decision.verdict)
    }

    @Test
    fun `07_59_59 is deferred`() {
        val decision = service.evaluate(
            baseInput(now = instantAt(LocalDate.of(2026, 6, 15), LocalTime.of(7, 59, 59), bucharest)),
        )
        assertEquals(NotificationVerdict.DEFER, decision.verdict)
    }

    @Test
    fun `08_00_00 exactly passes through quiet hours`() {
        val decision = service.evaluate(
            baseInput(now = instantAt(LocalDate.of(2026, 6, 15), LocalTime.of(8, 0, 0), bucharest)),
        )
        assertEquals(NotificationVerdict.DISPATCH, decision.verdict)
    }

    @Test
    fun `deferral target is quiet_end on the same local calendar day (no jitter), never the next day`() {
        val evalDate = LocalDate.of(2026, 6, 15)
        val decision = service.evaluate(
            baseInput(now = instantAt(evalDate, LocalTime.of(2, 30), bucharest)),
            jitterMinutes = { 0L },
        )
        assertEquals(NotificationVerdict.DEFER, decision.verdict)
        assertNotNull(decision.notBefore)
        val expected = evalDate.atTime(quietEnd).atZone(bucharest).toOffsetDateTime()
        assertEquals(expected.toInstant(), decision.notBefore!!.toInstant())
    }

    // ---------------------------------------------------------------------
    // Jitter (plan §14 / §18 step 5.4)
    // ---------------------------------------------------------------------

    @Test
    fun `jitter shifts the deferral target later by the injected amount`() {
        val evalDate = LocalDate.of(2026, 6, 15)
        val decision = service.evaluate(
            baseInput(now = instantAt(evalDate, LocalTime.of(2, 30), bucharest)),
            jitterMinutes = { 17L },
        )
        assertEquals(NotificationVerdict.DEFER, decision.verdict)
        val expected = evalDate.atTime(quietEnd).atZone(bucharest).toOffsetDateTime().plusMinutes(17)
        assertEquals(expected.toInstant(), decision.notBefore!!.toInstant())
    }

    @Test
    fun `default jitter always lands within 0 to 20 minutes of quiet_end`() {
        val evalDate = LocalDate.of(2026, 6, 15)
        val expected = evalDate.atTime(quietEnd).atZone(bucharest).toOffsetDateTime()
        repeat(200) {
            val decision = service.evaluate(baseInput(now = instantAt(evalDate, LocalTime.of(2, 30), bucharest)))
            val notBefore = decision.notBefore!!
            assertFalse(notBefore.toInstant().isBefore(expected.toInstant()), "jitter must never move the target earlier")
            assertFalse(
                notBefore.toInstant().isAfter(expected.toInstant().plusSeconds(20 * 60)),
                "jitter must never exceed 20 minutes, was $notBefore",
            )
        }
    }

    @Test
    fun `zero jitter at the DISPATCH path (outside quiet hours) never applies — notBefore stays null`() {
        val decision = service.evaluate(
            baseInput(now = instantAt(LocalDate.of(2026, 6, 15), LocalTime.of(12, 0), bucharest)),
            jitterMinutes = { 20L },
        )
        assertEquals(NotificationVerdict.DISPATCH, decision.verdict)
        assertNull(decision.notBefore)
    }

    // ---------------------------------------------------------------------
    // Timezones
    // ---------------------------------------------------------------------

    @Test
    fun `far-ahead timezone Pacific Kiritimati (UTC+14) computes quiet hours in its own local time`() {
        val kiritimati = ZoneId.of("Pacific/Kiritimati")
        // 12:00 UTC on 2026-06-15 is 02:00 on 2026-06-16 in UTC+14 -> inside quiet hours.
        val now = OffsetDateTime.of(2026, 6, 15, 12, 0, 0, 0, ZoneOffset.UTC)
        val decision = service.evaluate(baseInput(now = now, zone = kiritimati))
        assertEquals(NotificationVerdict.DEFER, decision.verdict)
    }

    @Test
    fun `far-behind timezone Pacific Niue (UTC-11) computes quiet hours in its own local time`() {
        val niue = ZoneId.of("Pacific/Niue")
        // 09:00 UTC is 22:00 the previous day in UTC-11 -> outside quiet hours.
        val now = OffsetDateTime.of(2026, 6, 15, 9, 0, 0, 0, ZoneOffset.UTC)
        val decision = service.evaluate(baseInput(now = now, zone = niue))
        assertEquals(NotificationVerdict.DISPATCH, decision.verdict)
    }

    @Test
    fun `null timezone suppresses scheduled categories (discovery, reminders)`() {
        val discoveryDecision = service.evaluate(baseInput(category = NotificationCategory.DISCOVERY, zone = null))
        val remindersDecision = service.evaluate(baseInput(category = NotificationCategory.REMINDERS, zone = null))
        assertEquals(NotificationVerdict.SUPPRESS, discoveryDecision.verdict)
        assertEquals(NotificationVerdict.SUPPRESS, remindersDecision.verdict)
    }

    @Test
    fun `null timezone still dispatches social categories (likes, comments)`() {
        val likesDecision = service.evaluate(baseInput(category = NotificationCategory.LIKES, zone = null))
        val commentsDecision = service.evaluate(baseInput(category = NotificationCategory.COMMENTS, zone = null))
        assertEquals(NotificationVerdict.DISPATCH, likesDecision.verdict)
        assertEquals(NotificationVerdict.DISPATCH, commentsDecision.verdict)
    }

    // ---------------------------------------------------------------------
    // DST transitions (Europe/Bucharest: spring-forward late March, fall-back late October)
    // ---------------------------------------------------------------------

    @Test
    fun `spring-forward DST night is handled without losing or duplicating the quiet window`() {
        // Romania spring-forward 2026: 2026-03-29, clocks jump 03:00 -> 04:00 EEST.
        val beforeJump = instantAt(LocalDate.of(2026, 3, 29), LocalTime.of(2, 30), bucharest)
        val afterJump = instantAt(LocalDate.of(2026, 3, 29), LocalTime.of(8, 0), bucharest)
        assertEquals(NotificationVerdict.DEFER, service.evaluate(baseInput(now = beforeJump)).verdict)
        assertEquals(NotificationVerdict.DISPATCH, service.evaluate(baseInput(now = afterJump)).verdict)
    }

    @Test
    fun `fall-back DST night is handled without losing or duplicating the quiet window`() {
        // Romania fall-back 2026: 2026-10-25, clocks step back 04:00 -> 03:00 EET.
        val duringQuietHours = instantAt(LocalDate.of(2026, 10, 25), LocalTime.of(1, 0), bucharest)
        val afterQuietHours = instantAt(LocalDate.of(2026, 10, 25), LocalTime.of(8, 0), bucharest)
        assertEquals(NotificationVerdict.DEFER, service.evaluate(baseInput(now = duringQuietHours)).verdict)
        assertEquals(NotificationVerdict.DISPATCH, service.evaluate(baseInput(now = afterQuietHours)).verdict)
    }

    // ---------------------------------------------------------------------
    // Frequency caps (§15)
    // ---------------------------------------------------------------------

    @Test
    fun `social hourly cap of 3 suppresses the 4th send in the same hour`() {
        val underCap = service.evaluate(baseInput(category = NotificationCategory.LIKES, hourlyCount = 2))
        val atCap = service.evaluate(baseInput(category = NotificationCategory.LIKES, hourlyCount = 3))
        assertEquals(NotificationVerdict.DISPATCH, underCap.verdict)
        assertEquals(NotificationVerdict.SUPPRESS, atCap.verdict)
    }

    @Test
    fun `social daily cap of 8 suppresses the 9th send that day`() {
        val underCap = service.evaluate(baseInput(category = NotificationCategory.COMMENTS, dailyCount = 7))
        val atCap = service.evaluate(baseInput(category = NotificationCategory.COMMENTS, dailyCount = 8))
        assertEquals(NotificationVerdict.DISPATCH, underCap.verdict)
        assertEquals(NotificationVerdict.SUPPRESS, atCap.verdict)
    }

    @Test
    fun `absolute daily cap of 10 suppresses any non-ACCOUNT category once reached`() {
        val underCap = service.evaluate(baseInput(category = NotificationCategory.DISCOVERY, dailyTotalCount = 9))
        val atCap = service.evaluate(baseInput(category = NotificationCategory.DISCOVERY, dailyTotalCount = 10))
        assertEquals(NotificationVerdict.DISPATCH, underCap.verdict)
        assertEquals(NotificationVerdict.SUPPRESS, atCap.verdict)
    }

    @Test
    fun `discovery and reminders are not subject to the social hourly or daily caps`() {
        val discovery = service.evaluate(
            baseInput(category = NotificationCategory.DISCOVERY, hourlyCount = 50, dailyCount = 50),
        )
        val reminders = service.evaluate(
            baseInput(category = NotificationCategory.REMINDERS, hourlyCount = 50, dailyCount = 50),
        )
        assertEquals(NotificationVerdict.DISPATCH, discovery.verdict)
        assertEquals(NotificationVerdict.DISPATCH, reminders.verdict)
    }

    @Test
    fun `a SUPPRESS verdict never carries a notBefore — suppressed events are not rescheduled`() {
        val decision = service.evaluate(baseInput(category = NotificationCategory.LIKES, hourlyCount = 3))
        assertEquals(NotificationVerdict.SUPPRESS, decision.verdict)
        assertNull(decision.notBefore, "a suppressed send must stay suppressed, never rescheduled (plan §15)")
    }

    // ---------------------------------------------------------------------
    // Priority at cap saturation (§15: ACCOUNT > COMMENTS > LIKES > REMINDERS > DISCOVERY)
    // ---------------------------------------------------------------------

    @Test
    fun `a comment still dispatches when the shared hourly cap is already saturated by likes`() {
        val decision = service.evaluate(
            baseInput(category = NotificationCategory.COMMENTS, hourlyCount = 0, otherSocialHourlyCount = 3),
        )
        assertEquals(NotificationVerdict.DISPATCH, decision.verdict)
    }

    @Test
    fun `a like is suppressed once the shared hourly cap is saturated by comments alone`() {
        val decision = service.evaluate(
            baseInput(category = NotificationCategory.LIKES, hourlyCount = 0, otherSocialHourlyCount = 3),
        )
        assertEquals(NotificationVerdict.SUPPRESS, decision.verdict)
    }

    @Test
    fun `a comment is still suppressed once 3 comments of its own already went out this hour`() {
        val decision = service.evaluate(
            baseInput(category = NotificationCategory.COMMENTS, hourlyCount = 3, otherSocialHourlyCount = 0),
        )
        assertEquals(NotificationVerdict.SUPPRESS, decision.verdict, "comments have their own cap too, not unlimited priority")
    }

    @Test
    fun `a comment still dispatches when the shared daily cap is already saturated by likes`() {
        val decision = service.evaluate(
            baseInput(category = NotificationCategory.COMMENTS, dailyCount = 0, otherSocialDailyCount = 8),
        )
        assertEquals(NotificationVerdict.DISPATCH, decision.verdict)
    }

    @Test
    fun `a like is suppressed once the shared daily cap is saturated by comments alone`() {
        val decision = service.evaluate(
            baseInput(category = NotificationCategory.LIKES, dailyCount = 0, otherSocialDailyCount = 8),
        )
        assertEquals(NotificationVerdict.SUPPRESS, decision.verdict)
    }

    // ---------------------------------------------------------------------
    // Cooldown
    // ---------------------------------------------------------------------

    @Test
    fun `active cooldown suppresses regardless of caps`() {
        val decision = service.evaluate(baseInput(cooldownActive = true))
        assertEquals(NotificationVerdict.SUPPRESS, decision.verdict)
    }

    @Test
    fun `inactive cooldown dispatches when nothing else blocks it`() {
        val decision = service.evaluate(baseInput(cooldownActive = false))
        assertEquals(NotificationVerdict.DISPATCH, decision.verdict)
    }

    // ---------------------------------------------------------------------
    // Gate ordering — quiet hours defer wins over caps/cooldown, which are not consulted.
    // ---------------------------------------------------------------------

    @Test
    fun `quiet hours defer takes precedence even when caps are also saturated`() {
        val decision = service.evaluate(
            baseInput(
                now = instantAt(LocalDate.of(2026, 6, 15), LocalTime.of(1, 0), bucharest),
                hourlyCount = 99,
                dailyCount = 99,
                dailyTotalCount = 99,
                cooldownActive = true,
            ),
        )
        assertEquals(NotificationVerdict.DEFER, decision.verdict)
    }
}
