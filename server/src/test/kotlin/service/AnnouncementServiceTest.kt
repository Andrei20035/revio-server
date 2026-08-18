package service

import com.revio.server.features.announcement.AnnouncementService
import com.revio.server.features.announcement.IUserAnnouncementDAO
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class AnnouncementServiceTest {

    @Test
    fun `acknowledge with an unknown key throws IllegalArgumentException`() {
        val dao = mockk<IUserAnnouncementDAO>(relaxed = true)
        val service = AnnouncementService(dao)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.acknowledge(UUID.randomUUID(), "NOT_A_REAL_KEY") }
        }
    }
}
