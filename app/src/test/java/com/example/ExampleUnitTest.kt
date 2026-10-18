package com.example

import com.example.data.FamilyRepository
import com.example.model.*
import com.example.viewmodel.FamilyViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Unit Tests for FamilyTasks V1.6:
 * Covers all V1.6 Family Membership, Persistent User Profile (FATHER/MOTHER),
 * Safe Startup State Machine, Role-Specific Invitations, and Permission requirements.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {

    // Scenario 1 & 2: New Google user has no family -> shown Create/Join onboarding, does NOT become PARENT automatically
    @Test
    fun v16_newGoogleUserHasNoFamilyAndDoesNotAutoBecomeParent() = runBlocking {
        val repo = FamilyRepository(context = null, firestoreProvider = { null }, authProvider = { null })
        val state = repo.checkAuthenticatedUserMembership(
            uid = "new_google_uid_101",
            email = "newuser@gmail.com",
            displayName = "משתמש חדש"
        )
        assertTrue(
            "New Google user with no Firestore family membership must receive NoMembership state",
            state is FamilyMembershipState.NoMembership
        )
        assertEquals(FamilyMembershipState.NoMembership, repo.membershipState.value)
    }

    // Scenario 3: User explicitly creating a family becomes PARENT
    @Test
    fun v16_userCreatingFamilyExplicitlyBecomesParent() = runBlocking {
        val repo = FamilyRepository(context = null, firestoreProvider = { null }, authProvider = { null })
        val result = repo.createFamilyAsParent(
            familyNameInput = "משפחת לוי",
            parentDisplayName = "אמא לוי"
        )
        assertTrue(result.isSuccess)
        val activeState = result.getOrThrow()
        assertEquals("משפחת לוי", activeState.familyName)
        assertEquals(UserRole.PARENT, activeState.role)
        assertEquals(UserRole.PARENT, repo.currentMember.value.role)
        assertTrue(repo.currentMember.value.isParent)
        assertFalse(repo.currentMember.value.isChild)
        assertEquals(1, repo.members.value.size)
    }

    // Scenario 4 & 5: PARENT creates a CHILD invitation; invited user joins with that code and becomes CHILD, not PARENT
    @Test
    fun v16_parentCreatesChildInvitationAndJoinedUserBecomesChildNotParent() = runBlocking {
        val repo = FamilyRepository(context = null, firestoreProvider = { null }, authProvider = { null })
        repo.createFamilyAsParent("משפחת כהן", "אבא כהן")

        // Parent creates a CHILD invitation
        val inviteRes = repo.createRoleSpecificInvitation(
            invitedRole = UserRole.CHILD,
            invitedNickname = "דניאל"
        )
        assertTrue(inviteRes.isSuccess)
        val invite = inviteRes.getOrThrow()
        assertEquals(UserRole.CHILD, invite.invitedRole)
        assertEquals(InvitationStatus.PENDING, invite.status)
        assertEquals(6, invite.code.length)

        // Pending invitation must appear in pendingInvitations, NOT in active members
        assertEquals(1, repo.pendingInvitations.value.size)
        assertEquals(1, repo.members.value.size)

        // Validate code before joining
        val validation = repo.validateInvitationCode(invite.code, joiningUid = "child_uid_202")
        assertTrue(validation is InvitationValidationResult.Valid)
        val validInvite = (validation as InvitationValidationResult.Valid).invitation
        assertEquals("משפחת כהן", validInvite.familyName)
        assertEquals(UserRole.CHILD, validInvite.invitedRole)

        // Child confirms joining
        val joinRes = repo.redeemInvitationAndJoinFamily(
            rawCode = invite.code,
            memberDisplayName = "דניאל כהן",
            joiningUidOverride = "child_uid_202",
            joiningEmailOverride = "daniel@gmail.com"
        )
        assertTrue(joinRes.isSuccess)
        val childState = joinRes.getOrThrow()
        assertEquals(UserRole.CHILD, childState.role)
        assertEquals(UserRole.CHILD, repo.currentMember.value.role)
        assertTrue(repo.currentMember.value.isChild)
        assertFalse("Invited child must NOT become PARENT or admin", repo.currentMember.value.isParent)
        assertFalse("Invited child must NOT have isAdmin", repo.currentMember.value.isAdmin)

        // Scenario 12: Parent's active member list now includes the joined child, and pending invite is cleared
        assertEquals(2, repo.members.value.size)
        assertTrue(repo.members.value.any { it.id == "child_uid_202" && it.role == UserRole.CHILD })
        assertTrue("Redeemed invite must be removed from pendingInvitations", repo.pendingInvitations.value.isEmpty())
    }

    // Scenario 6: PARENT creates a PARENT invitation and invited user becomes PARENT
    @Test
    fun v16_parentCreatesParentInvitationAndJoinedUserBecomesParent() = runBlocking {
        val repo = FamilyRepository(context = null, firestoreProvider = { null }, authProvider = { null })
        repo.createFamilyAsParent("משפחת ברק", "אבא ברק")

        val invite = repo.createRoleSpecificInvitation(
            invitedRole = UserRole.PARENT,
            invitedNickname = "אמא ברק"
        ).getOrThrow()
        assertEquals(UserRole.PARENT, invite.invitedRole)

        val joinRes = repo.redeemInvitationAndJoinFamily(
            rawCode = invite.code,
            memberDisplayName = "אמא ברק",
            joiningUidOverride = "parent_2_uid",
            joiningEmailOverride = "mom.barak@gmail.com"
        )
        assertTrue(joinRes.isSuccess)
        assertEquals(UserRole.PARENT, joinRes.getOrThrow().role)
        assertTrue(repo.currentMember.value.isParent)
        assertEquals(2, repo.members.value.count { it.isParent })
    }

    // Scenario 7: Invalid, expired, or already redeemed invitation code is rejected
    @Test
    fun v16_invalidExpiredOrRedeemedInvitationCodesAreRejected() = runBlocking {
        val repo = FamilyRepository(context = null, firestoreProvider = { null }, authProvider = { null })
        repo.createFamilyAsParent("משפחת גולן", "אבא גולן")

        // 7a. Non-existent code
        val invalidRes = repo.validateInvitationCode("ZZZZZZ", joiningUid = "uid_test")
        assertTrue(invalidRes is InvitationValidationResult.Invalid)
        assertEquals("NOT_FOUND", (invalidRes as InvitationValidationResult.Invalid).errorCode)

        // 7b. Expired code
        val expiredInvite = repo.createRoleSpecificInvitation(
            invitedRole = UserRole.CHILD,
            customExpirationMs = 1_000L
        ).getOrThrow()
        val futureMillis = expiredInvite.createdAt + 10_000L
        val expiredValidation = repo.validateInvitationCode(
            rawCode = expiredInvite.code,
            joiningUid = "uid_late_child",
            nowMillis = futureMillis
        )
        assertTrue(expiredValidation is InvitationValidationResult.Invalid)
        assertEquals("EXPIRED", (expiredValidation as InvitationValidationResult.Invalid).errorCode)

        // 7c. Already redeemed code
        val validInvite = repo.createRoleSpecificInvitation(invitedRole = UserRole.CHILD).getOrThrow()
        val firstRedeem = repo.redeemInvitationAndJoinFamily(
            rawCode = validInvite.code,
            memberDisplayName = "ילד ראשון",
            joiningUidOverride = "child_first"
        )
        assertTrue(firstRedeem.isSuccess)

        val secondValidation = repo.validateInvitationCode(
            rawCode = validInvite.code,
            joiningUid = "child_second"
        )
        assertTrue(secondValidation is InvitationValidationResult.Invalid)
        assertEquals("ALREADY_REDEEMED", (secondValidation as InvitationValidationResult.Invalid).errorCode)
    }

    // Scenario 8, 9, 10: CHILD cannot create/edit/delete tasks or invite/manage roles, and can complete ONLY their assigned task
    @Test
    fun v16_childRolePermissionsEnforcedStrictly() = runBlocking {
        val repo = FamilyRepository(context = null, firestoreProvider = { null }, authProvider = { null })
        repo.createFamilyAsParent("משפחת אלון", "אבא אלון")
        val parentMember = repo.currentMember.value

        // Create two tasks as PARENT: one assigned to child1, one assigned to child2
        val taskForChild1 = FamilyTask(
            id = "task_c1",
            title = "משימה של ילד 1",
            assignedToMemberId = "child_1",
            dateEpochDay = LocalDate.now().toEpochDay(),
            points = 25
        )
        val taskForChild2 = FamilyTask(
            id = "task_c2",
            title = "משימה של ילד 2",
            assignedToMemberId = "child_2",
            dateEpochDay = LocalDate.now().toEpochDay(),
            points = 30
        )
        assertTrue(repo.addTask(taskForChild1))
        assertTrue(repo.addTask(taskForChild2))

        // Invite and join as child_1
        val invite = repo.createRoleSpecificInvitation(invitedRole = UserRole.CHILD).getOrThrow()
        repo.redeemInvitationAndJoinFamily(
            rawCode = invite.code,
            memberDisplayName = "ילד 1",
            joiningUidOverride = "child_1"
        )
        assertEquals(UserRole.CHILD, repo.currentMember.value.role)

        // Scenario 8: CHILD cannot create, edit, or delete tasks
        val unauthorizedTask = FamilyTask(
            id = "task_unauth",
            title = "משימה אסורה",
            assignedToMemberId = "child_1",
            dateEpochDay = LocalDate.now().toEpochDay()
        )
        assertFalse("CHILD must not be able to create tasks", repo.addTask(unauthorizedTask))
        assertFalse("CHILD must not be able to edit tasks", repo.updateTask(taskForChild1.copy(title = "שונה")))
        assertFalse("CHILD must not be able to delete tasks", repo.deleteTask("task_c1"))

        // Scenario 9: CHILD cannot create invitations or change roles or promote themselves to PARENT
        val childInviteAttempt = repo.createRoleSpecificInvitation(invitedRole = UserRole.CHILD)
        assertTrue("CHILD must not be able to create invitations", childInviteAttempt.isFailure)
        assertFalse("CHILD must not be able to promote themselves to PARENT", repo.changeMemberRole("child_1", UserRole.PARENT))
        assertFalse("CHILD must not be able to demote a PARENT", repo.changeMemberRole(parentMember.id, UserRole.CHILD))

        // Scenario 10: CHILD can mark their own assigned task completed, but NOT another member's task
        assertFalse("CHILD must not be able to complete another member's task", repo.toggleTaskCompletion("task_c2"))
        assertTrue("CHILD must be able to complete their own assigned task", repo.toggleTaskCompletion("task_c1"))
        assertTrue(repo.tasks.value.first { it.id == "task_c1" }.isCompleted)
    }

    // Scenario 11: Last remaining PARENT cannot be removed or demoted
    @Test
    fun v16_lastRemainingParentCannotBeRemovedOrDemoted() = runBlocking {
        val repo = FamilyRepository(context = null, firestoreProvider = { null }, authProvider = { null })
        repo.createFamilyAsParent("משפחת שרון", "אמא שרון")
        val soleParentId = repo.currentMember.value.id

        // Add a child so the family has 2 members (1 PARENT, 1 CHILD)
        val childInvite = repo.createRoleSpecificInvitation(invitedRole = UserRole.CHILD).getOrThrow()
        repo.redeemInvitationAndJoinFamily(
            rawCode = childInvite.code,
            memberDisplayName = "ילד שרון",
            joiningUidOverride = "child_sharon"
        )
        // Switch back to sole PARENT
        val soleParent = repo.members.value.first { it.id == soleParentId }
        repo.switchCurrentMember(soleParent)

        // Attempting to demote or remove the sole remaining PARENT must be rejected
        assertFalse(
            "Sole remaining PARENT must not be demoted to CHILD",
            repo.changeMemberRole(soleParentId, UserRole.CHILD)
        )
        assertFalse(
            "Sole remaining PARENT must not be removed from family",
            repo.removeFamilyMember(soleParentId)
        )
        assertEquals(UserRole.PARENT, repo.members.value.first { it.id == soleParentId }.role)

        // Promoting the second member to PARENT allows one of the two parents to be demoted
        assertTrue(repo.changeMemberRole("child_sharon", UserRole.PARENT))
        assertEquals(2, repo.members.value.count { it.isParent })
        assertTrue(repo.changeMemberRole("child_sharon", UserRole.CHILD))
        assertEquals(1, repo.members.value.count { it.isParent })
    }

    // Domain-Free WhatsApp Share Message Verification (Requirement 7)
    @Test
    fun v16_whatsAppShareMessageContainsFamilyRoleCodeAndNoBrokenDomainLinks() = runBlocking {
        val repo = FamilyRepository(context = null, firestoreProvider = { null }, authProvider = { null })
        repo.createFamilyAsParent("משפחת ישראלי", "אבא")
        val invite = repo.createRoleSpecificInvitation(
            invitedRole = UserRole.CHILD,
            invitedNickname = "נועם"
        ).getOrThrow()

        val messageHe = repo.getWhatsAppInviteShareText(invite, isHebrew = true)
        assertTrue(messageHe.contains("משפחת ישראלי"))
        assertTrue(messageHe.contains("ילד/ה"))
        assertTrue(messageHe.contains(invite.code))
        assertFalse("Share message must not contain broken domain links", messageHe.contains("http://"))
        assertFalse("Share message must not contain broken domain links", messageHe.contains("https://"))
        assertFalse("Share message must not contain Firebase Dynamic Links", messageHe.contains("page.link"))
    }
}
