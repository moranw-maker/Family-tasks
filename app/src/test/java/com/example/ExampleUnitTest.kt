package com.example

import com.example.data.FamilyRepository
import com.example.model.*
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun testInitialFamilySetup() {
        val repo = FamilyRepository()
        val members = repo.members.value

        assertTrue("Should have initial members", members.isNotEmpty())
        val primaryAdmin = members.find { it.role == UserRole.PRIMARY_ADMIN }
        assertNotNull("Should have a primary admin", primaryAdmin)
        assertTrue(primaryAdmin!!.isAdmin)
    }

    @Test
    fun testTaskCompletionAndPoints() {
        val repo = FamilyRepository()
        val initialTasks = repo.tasks.value
        val task = initialTasks.first()

        assertFalse(task.isCompleted)
        repo.toggleTask(task.id)

        val updatedTask = repo.tasks.value.find { it.id == task.id }
        assertNotNull(updatedTask)
        assertTrue("Task should be completed", updatedTask!!.isCompleted)

        val latestAlert = repo.latestAlert.value
        assertNotNull("Should emit completion alert", latestAlert)
        assertEquals(NotificationType.TASK_COMPLETED, latestAlert!!.type)
        assertNotNull("Should specify who completed task", latestAlert.completedByMemberName)
    }

    @Test
    fun testFamilyMemberLimit() {
        val repo = FamilyRepository()
        var currentCount = repo.members.value.size
        
        while (currentCount < 10) {
            val added = repo.addMember("Member $currentCount", "m$currentCount@family.com", UserRole.MEMBER_KID, "👦")
            assertTrue("Should allow adding up to 10 members", added)
            currentCount++
        }
        
        assertEquals(10, repo.members.value.size)
        val added11th = repo.addMember("Extra Member", "extra@family.com", UserRole.MEMBER_KID, "👦")
        assertFalse("Should not allow more than 10 members", added11th)
    }

    @Test
    fun testTaskRoomAndFirestoreModel() {
        val task = Task(
            taskName = "Do Homework",
            dueDate = "2026-10-20",
            assignedFamilyMember = "Maya",
            isCompleted = false
        )

        assertEquals("Do Homework", task.taskName)
        assertEquals("Do Homework", task.name)
        assertEquals("2026-10-20", task.dueDate)
        assertEquals("Maya", task.assignedFamilyMember)
        assertFalse(task.isCompleted)
        assertFalse(task.completionStatus)

        // Verify default no-argument constructor for Firestore serialization
        val emptyTask = Task()
        assertEquals("", emptyTask.taskName)
        assertEquals("", emptyTask.dueDate)
        assertEquals("", emptyTask.assignedFamilyMember)
        assertFalse(emptyTask.isCompleted)

        // Verify conversion to and from FamilyTask
        val domainTask = task.toFamilyTask()
        assertEquals(task.taskName, domainTask.title)
        assertEquals(task.isCompleted, domainTask.isCompleted)

        val restoredTask = Task.fromFamilyTask(domainTask, assignedMemberName = "Maya")
        assertEquals(task.taskName, restoredTask.taskName)
        assertEquals(task.isCompleted, restoredTask.isCompleted)
        assertEquals("Maya", restoredTask.assignedFamilyMember)

        // Verify IgnoreExtraProperties runtime annotation
        val ignoreExtraProps = Task::class.java.getAnnotation(com.google.firebase.firestore.IgnoreExtraProperties::class.java)
        assertNotNull("Task must be annotated with @IgnoreExtraProperties for Firestore", ignoreExtraProps)
    }
}
