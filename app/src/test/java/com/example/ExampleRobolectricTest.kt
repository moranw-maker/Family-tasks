package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.FamilyRepository
import com.example.model.FamilyTask
import com.example.model.TaskCategory
import com.example.model.TaskPriority
import com.example.model.UserRole
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("FamilyTasks", appName)
    }

    @Test
    fun `verify build config version name and code incremented`() {
        assertEquals("1.1", BuildConfig.VERSION_NAME)
        assertEquals(2, BuildConfig.VERSION_CODE)
        assertEquals("1.2", BuildConfig.NEXT_RELEASE_VERSION_NAME)
        assertEquals(3, BuildConfig.NEXT_RELEASE_VERSION_CODE)
    }

    @Test
    fun `test family member addition and 10 members limit`() {
        val repository = FamilyRepository()
        val initialCount = repository.members.value.size
        assertTrue(initialCount < 10)

        // Add members until 10
        val remainingSlots = 10 - initialCount
        for (i in 1..remainingSlots) {
            val success = repository.addMember("Member $i", "member$i@test.com", UserRole.MEMBER_KID, "👦")
            assertTrue("Should allow adding up to 10 members", success)
        }
        assertEquals(10, repository.members.value.size)

        // 11th member must fail
        val overflowSuccess = repository.addMember("Extra Member", "extra@test.com", UserRole.MEMBER_KID, "👦")
        assertFalse("Should not allow more than 10 members", overflowSuccess)
    }

    @Test
    fun `test chore completion awards points and pushes notification`() {
        val repository = FamilyRepository()
        val task = FamilyTask(
            id = "test_task_1",
            title = "Clean room",
            category = TaskCategory.BEDROOM,
            assignedToMemberId = "user_maya",
            dateEpochDay = LocalDate.now().toEpochDay(),
            points = 25,
            priority = TaskPriority.HIGH
        )
        repository.addTask(task)

        val mayaBefore = repository.members.value.find { it.id == "user_maya" }!!
        val pointsBefore = mayaBefore.earnedPoints

        repository.toggleTask("test_task_1")

        val mayaAfter = repository.members.value.find { it.id == "user_maya" }!!
        assertEquals(pointsBefore + 25, mayaAfter.earnedPoints)
        assertEquals(mayaBefore.completedTasksCount + 1, mayaAfter.completedTasksCount)

        // Verify push notification generated
        val latestNotif = repository.notifications.value.first()
        assertTrue(latestNotif.message.contains("25"))
    }

    @Test
    fun `test secondary admin promotion`() {
        val repository = FamilyRepository()
        val david = repository.members.value.find { it.id == "user_david" }!!
        assertEquals(UserRole.SECONDARY_ADMIN, david.role)
        assertTrue(david.isAdmin)
    }
}
