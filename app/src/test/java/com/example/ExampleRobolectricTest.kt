package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.FamilyRepository
import com.example.model.FamilyInvitation
import com.example.model.FamilyMembershipState
import com.example.model.FamilyRole
import com.example.model.FamilyTask
import com.example.model.InvitationStatus
import com.example.model.InvitationValidationResult
import com.example.model.TaskCategory
import com.example.model.TaskPriority
import com.example.model.UserRole
import kotlinx.coroutines.runBlocking
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
    fun `verify build config version name and code incremented to V1_6`() {
        assertEquals("1.6", BuildConfig.VERSION_NAME)
        assertEquals(6, BuildConfig.VERSION_CODE)
        assertEquals("1.7", BuildConfig.NEXT_RELEASE_VERSION_NAME)
        assertEquals(7, BuildConfig.NEXT_RELEASE_VERSION_CODE)
    }

    @Test
    fun `test strictly two canonical roles PARENT and CHILD`() {
        assertEquals(2, UserRole.entries.size)
        assertTrue(UserRole.entries.contains(UserRole.PARENT))
        assertTrue(UserRole.entries.contains(UserRole.CHILD))
        assertTrue(UserRole.PARENT.isParent)
        assertFalse(UserRole.CHILD.isParent)
        assertEquals(UserRole.PARENT, UserRole.fromFirestoreValue("PARENT"))
        assertEquals(UserRole.CHILD, UserRole.fromFirestoreValue("CHILD"))
        // Unknown or tampered role strings default safely to CHILD, never PARENT
        assertEquals(UserRole.CHILD, UserRole.fromFirestoreValue("SUPER_ADMIN_SPOOF"))
    }

    @Test
    fun `test chore completion awards points and pushes notification`() {
        val repository = FamilyRepository()
        val task = FamilyTask(
            id = "test_task_1",
            title = "Clean room",
            category = TaskCategory.BEDROOM,
            assignedToMemberId = "m_kid1",
            dateEpochDay = LocalDate.now().toEpochDay(),
            points = 25,
            priority = TaskPriority.HIGH
        )
        assertTrue(repository.addTask(task))

        val kidBefore = repository.members.value.find { it.id == "m_kid1" }!!
        val pointsBefore = kidBefore.earnedPoints

        assertTrue(repository.toggleTaskCompletion("test_task_1"))

        val kidAfter = repository.members.value.find { it.id == "m_kid1" }!!
        assertEquals(pointsBefore + 25, kidAfter.earnedPoints)

        // Verify push notification generated
        val latestNotif = repository.notifications.value.first()
        assertTrue(latestNotif.message.contains("Clean room"))
    }

    @Test
    fun `test parent vs child permissions enforced in repository`() {
        val repository = FamilyRepository()
        val childMember = repository.members.value.find { it.id == "m_kid1" }!!
        // Switch active profile to CHILD ("m_kid1")
        repository.switchCurrentMember(childMember)
        assertEquals(UserRole.CHILD, repository.currentMember.value.role)
        assertFalse(repository.currentMember.value.isParent)

        val initialTaskCount = repository.tasks.value.size

        // CHILD cannot create tasks
        val childTask = FamilyTask(
            id = "child_illegal_task",
            title = "Illegal Task by Child",
            category = TaskCategory.OTHER,
            assignedToMemberId = "m_kid1",
            dateEpochDay = LocalDate.now().toEpochDay(),
            points = 100
        )
        assertFalse("CHILD must not be able to create tasks", repository.addTask(childTask))
        assertEquals("CHILD must not be able to create tasks", initialTaskCount, repository.tasks.value.size)

        // CHILD cannot delete tasks
        val existingTask = repository.tasks.value.first()
        assertFalse("CHILD must not be able to delete tasks", repository.deleteTask(existingTask.id))
        assertNotNull("CHILD must not be able to delete tasks", repository.tasks.value.find { it.id == existingTask.id })

        // CHILD cannot invite members
        val inviteResult = repository.inviteNewMember("Friend", "👧", UserRole.CHILD, "friend@test.com", null)
        assertNull("CHILD must not be able to invite members", inviteResult)

        // CHILD cannot promote themselves to PARENT
        val selfPromoteSuccess = repository.changeMemberRole("m_kid1", UserRole.PARENT)
        assertFalse("CHILD must not be able to change roles or promote self to PARENT", selfPromoteSuccess)
        assertEquals(UserRole.CHILD, repository.members.value.find { it.id == "m_kid1" }!!.role)
    }

    @Test
    fun `test child can only complete tasks assigned to them`() {
        val repository = FamilyRepository()
        // Parent creates two tasks: one assigned to m_kid1 (CHILD), one assigned to m_kid2 (CHILD)
        val kid1Task = FamilyTask(
            id = "task_for_kid1",
            title = "Kid1 Homework",
            category = TaskCategory.HOMEWORK,
            assignedToMemberId = "m_kid1",
            dateEpochDay = LocalDate.now().toEpochDay(),
            points = 15,
            isCompleted = false
        )
        val kid2Task = FamilyTask(
            id = "task_for_kid2",
            title = "Kid2 Homework",
            category = TaskCategory.HOMEWORK,
            assignedToMemberId = "m_kid2",
            dateEpochDay = LocalDate.now().toEpochDay(),
            points = 15,
            isCompleted = false
        )
        assertTrue(repository.addTask(kid1Task))
        assertTrue(repository.addTask(kid2Task))

        // Switch to m_kid1 (CHILD)
        val kid1Member = repository.members.value.find { it.id == "m_kid1" }!!
        repository.switchCurrentMember(kid1Member)

        // m_kid1 can complete their own assigned task
        assertTrue(repository.toggleTaskCompletion("task_for_kid1"))
        assertTrue(
            "CHILD should be able to mark their own assigned task completed",
            repository.tasks.value.find { it.id == "task_for_kid1" }!!.isCompleted
        )

        // m_kid1 cannot complete m_kid2's task
        assertFalse(repository.toggleTaskCompletion("task_for_kid2"))
        assertFalse(
            "CHILD must not be able to complete a task assigned to another member",
            repository.tasks.value.find { it.id == "task_for_kid2" }!!.isCompleted
        )
    }

    @Test
    fun `test last remaining parent cannot be demoted or removed`() {
        val repository = FamilyRepository()
        // Initially there are two parents: m_dad and m_mom
        val initialParents = repository.members.value.filter { it.role == UserRole.PARENT }
        assertEquals(2, initialParents.size)

        // Demoting the second parent (m_mom) to CHILD succeeds because m_dad remains PARENT
        val demoteSecondParent = repository.changeMemberRole("m_mom", UserRole.CHILD)
        assertTrue("Demoting one parent when another parent exists should succeed", demoteSecondParent)
        assertEquals(UserRole.CHILD, repository.members.value.find { it.id == "m_mom" }!!.role)

        // Now m_dad is the LAST remaining PARENT. Attempting to demote m_dad to CHILD must fail!
        val demoteLastParent = repository.changeMemberRole("m_dad", UserRole.CHILD)
        assertFalse("Demoting the last remaining PARENT must be blocked", demoteLastParent)
        assertEquals(UserRole.PARENT, repository.members.value.find { it.id == "m_dad" }!!.role)

        // Attempting to remove the last remaining PARENT must also fail
        val removeLastParent = repository.removeFamilyMember("m_dad")
        assertFalse("Removing the last remaining PARENT must be blocked", removeLastParent)
        assertNotNull(repository.members.value.find { it.id == "m_dad" })
    }

    @Test
    fun `test role specific invitation lifecycle and pending vs active separation`() = runBlocking {
        val repository = FamilyRepository()
        val initialActiveMembersCount = repository.members.value.size

        // 1. Parent creates a CHILD invitation -> goes to pendingInvitations, NOT active members
        val childInvite = repository.createRoleSpecificInvitation(
            invitedRole = UserRole.CHILD,
            invitedNickname = "רוני"
        ).getOrThrow()

        assertEquals(UserRole.CHILD, childInvite.invitedRole)
        assertEquals(InvitationStatus.PENDING, childInvite.status)
        assertEquals("Pending invitation must not be added as an active member", initialActiveMembersCount, repository.members.value.size)
        assertTrue("Pending invitation must appear in pendingInvitations", repository.pendingInvitations.value.any { it.code == childInvite.code })

        // 2. Validate the invitation code before redemption
        val validation = repository.validateInvitationCode(childInvite.code, joiningUid = "uid_roni_child")
        assertTrue(validation is InvitationValidationResult.Valid)

        // 3. Redeem the CHILD invitation -> assigns CHILD role, marks REDEEMED, and adds to active members
        val joinedState = repository.redeemInvitationAndJoinFamily(
            rawCode = childInvite.code,
            memberDisplayName = "רוני ישראלי",
            joiningUidOverride = "uid_roni_child",
            joiningEmailOverride = "roni@example.com"
        ).getOrThrow()

        assertEquals("Redeemed CHILD invitation must assign CHILD role", UserRole.CHILD, joinedState.role)
        assertFalse("Redeemed invitation must be removed from pendingInvitations", repository.pendingInvitations.value.any { it.code == childInvite.code })
        val roniMember = repository.members.value.find { it.id == "uid_roni_child" }
        assertNotNull("Redeemed member must appear in active members list", roniMember)
        assertEquals(UserRole.CHILD, roniMember!!.role)

        // 4. Reusing an already redeemed invitation must fail validation
        val replayValidation = repository.validateInvitationCode(childInvite.code, joiningUid = "uid_other")
        assertTrue("Reusing a redeemed invitation must fail", replayValidation is InvitationValidationResult.Invalid)
        assertEquals("ALREADY_REDEEMED", (replayValidation as InvitationValidationResult.Invalid).errorCode)

        // 5. Expired invitation created by PARENT must fail validation
        val parentMember = repository.members.value.find { it.id == "m_dad" }!!
        repository.switchCurrentMember(parentMember)
        val expiredInvite = repository.createRoleSpecificInvitation(
            invitedRole = UserRole.PARENT,
            invitedNickname = "סבא",
            customExpirationMs = -10_000L
        ).getOrThrow()
        val expiredValidation = repository.validateInvitationCode(expiredInvite.code, joiningUid = "uid_grandpa")
        assertTrue("Expired invitation must fail validation", expiredValidation is InvitationValidationResult.Invalid)
        assertEquals("EXPIRED", (expiredValidation as InvitationValidationResult.Invalid).errorCode)
    }

    @Test
    fun `test domain free WhatsApp invitation message contains code and role without broken web links`() {
        val repository = FamilyRepository()
        val invitation = FamilyInvitation(
            code = "K9M4P2",
            familyId = "fam_test",
            familyName = "משפחת לוי",
            invitedRole = UserRole.CHILD,
            invitingParentUid = "m_dad",
            invitingParentName = "אבא"
        )
        val childMsgHe = repository.getWhatsAppInviteShareText(
            invitation = invitation,
            isHebrew = true
        )
        assertTrue(childMsgHe.contains("K9M4P2"))
        assertTrue(childMsgHe.contains("משפחת לוי"))
        assertTrue(childMsgHe.contains("ילד/ה"))
        assertFalse("WhatsApp message must not contain broken placeholder domains", childMsgHe.contains("http://"))
        assertFalse("WhatsApp message must not contain broken placeholder domains", childMsgHe.contains("https://"))
    }

    @Test
    fun `test existing FATHER profile and custom family name are never overwritten to MOTHER on Google Sign-In or restart`() = runBlocking {
        val repository = FamilyRepository()

        // 1. User configures family as FATHER with custom family name
        val created = repository.createFamilyAsParent(
            familyNameInput = "משפחת אברהם",
            parentDisplayName = "יוסי (אבא)",
            creatorUidOverride = "uid_father_yossi",
            creatorEmailOverride = "yossi@example.com",
            familyIdentity = com.example.model.FamilyIdentity.FATHER
        ).getOrThrow()

        assertEquals("משפחת אברהם", created.familyName)
        assertEquals(UserRole.PARENT, created.role)
        assertEquals(com.example.model.FamilyIdentity.FATHER, created.familyIdentity)
        assertEquals("משפחת אברהם", repository.familyName.value)
        assertEquals(com.example.model.FamilyIdentity.FATHER, repository.currentMember.value.familyIdentity)
        assertEquals(UserRole.PARENT, repository.currentMember.value.role)
        assertTrue(repository.startupState.value is com.example.model.StartupState.ReadyToEnterDashboard)

        // 2. Simulate app reopen / Google Sign-In callback with blank/null initial family name
        // Must NOT overwrite "משפחת אברהם" with "משפחת ישראלי" or change FATHER to MOTHER!
        repository.onGoogleSignInSuccess(
            email = "yossi@example.com",
            displayName = "Yossi Avraham",
            familyGroupName = null,
            photoUrl = null
        )

        assertEquals(
            "Family name must NOT be reset to default on Google Sign-In callback",
            "משפחת אברהם",
            repository.familyName.value
        )
        assertEquals(
            "Existing FATHER profile must NOT be changed to MOTHER",
            com.example.model.FamilyIdentity.FATHER,
            repository.currentMember.value.familyIdentity
        )
        assertEquals(
            "Both FATHER and MOTHER must retain PARENT authorization role",
            UserRole.PARENT,
            repository.currentMember.value.role
        )
        assertEquals("uid_father_yossi", repository.currentMember.value.id)
    }

    @Test
    fun `test existing MOTHER profile remains MOTHER and retains PARENT authorization role`() = runBlocking {
        val repository = FamilyRepository()

        val created = repository.createFamilyAsParent(
            familyNameInput = "משפחת שרון",
            parentDisplayName = "מיכל (אמא)",
            creatorUidOverride = "uid_mother_michal",
            creatorEmailOverride = "michal@example.com",
            familyIdentity = com.example.model.FamilyIdentity.MOTHER
        ).getOrThrow()

        assertEquals("משפחת שרון", created.familyName)
        assertEquals(UserRole.PARENT, created.role)
        assertEquals(com.example.model.FamilyIdentity.MOTHER, created.familyIdentity)
        assertEquals(com.example.model.FamilyIdentity.MOTHER, repository.currentMember.value.familyIdentity)
        assertEquals("👩", repository.currentMember.value.avatarEmoji)

        // Re-trigger Google Sign-In callback -> MOTHER identity and family name remain intact
        repository.onGoogleSignInSuccess(
            email = "michal@example.com",
            displayName = "Michal Sharon",
            familyGroupName = null,
            photoUrl = null
        )

        assertEquals("משפחת שרון", repository.familyName.value)
        assertEquals(com.example.model.FamilyIdentity.MOTHER, repository.currentMember.value.familyIdentity)
        assertEquals(UserRole.PARENT, repository.currentMember.value.role)
    }

    @Test
    fun `test duplicate family creation is prevented for already active member`() = runBlocking {
        val repository = FamilyRepository()

        val firstCreate = repository.createFamilyAsParent(
            familyNameInput = "משפחת ברק",
            parentDisplayName = "אבא ברק",
            creatorUidOverride = "uid_barak_dad",
            creatorEmailOverride = "barak@example.com",
            familyIdentity = com.example.model.FamilyIdentity.FATHER
        ).getOrThrow()

        // Calling createFamilyAsParent again for the same active member must return the existing family
        // instead of creating a duplicate replacement family or overwriting FATHER with MOTHER
        val secondCreate = repository.createFamilyAsParent(
            familyNameInput = "משפחת ישראלי",
            parentDisplayName = "אמא",
            creatorUidOverride = "uid_barak_dad",
            creatorEmailOverride = "barak@example.com",
            familyIdentity = com.example.model.FamilyIdentity.MOTHER
        ).getOrThrow()

        assertEquals("Must preserve existing familyId and not create a duplicate family", firstCreate.familyId, secondCreate.familyId)
        assertEquals("Must preserve existing familyName", "משפחת ברק", secondCreate.familyName)
        assertEquals("Must preserve existing FATHER identity", com.example.model.FamilyIdentity.FATHER, secondCreate.familyIdentity)
    }

    @Test
    fun `test named Firestore database ID is configured and never falls back to default`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resDatabaseId = context.getString(R.string.firestore_database_id)
        assertEquals(
            "ai-studio-android-familyta-a054dbd1-9c2d-4a83-ad6d-dcfe6fb0d2ca",
            resDatabaseId
        )

        val resolvedWithContext = FamilyRepository.resolveConfiguredDatabaseId(context)
        val resolvedWithoutContext = FamilyRepository.resolveConfiguredDatabaseId(null)
        assertEquals(
            "ai-studio-android-familyta-a054dbd1-9c2d-4a83-ad6d-dcfe6fb0d2ca",
            resolvedWithContext
        )
        assertEquals(
            "ai-studio-android-familyta-a054dbd1-9c2d-4a83-ad6d-dcfe6fb0d2ca",
            resolvedWithoutContext
        )
        assertNotEquals("(default)", resolvedWithContext)
        assertNotEquals("(default)", resolvedWithoutContext)
    }

    @Test
    fun `test FirestoreErrorHandler distinguishes PERMISSION_DENIED UNAVAILABLE NOT_FOUND and redacts sensitive data`() {
        val permDenied = com.google.firebase.firestore.FirebaseFirestoreException(
            "PERMISSION_DENIED: Missing or insufficient permissions for user@example.com with inviteCode=AB12CD and token=eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9",
            com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED
        )
        val classifiedPerm = com.example.util.FirestoreErrorHandler.classifyStartupFailure(
            error = permDenied,
            stage = com.example.util.FirestoreErrorHandler.StartupStage.LOAD_USER_PROFILE,
            collectionPath = "users",
            documentId = "secret_uid_12345"
        )
        assertEquals(
            com.example.util.FirestoreErrorHandler.StartupFailureCategory.PERMISSION_DENIED,
            classifiedPerm.category
        )
        assertEquals("PERMISSION_DENIED", classifiedPerm.errorCode)
        assertEquals("LOAD_USER_PROFILE", classifiedPerm.stage.stageName)
        assertEquals("users/{redacted}", classifiedPerm.documentPath)
        assertEquals(
            "ai-studio-android-familyta-a054dbd1-9c2d-4a83-ad6d-dcfe6fb0d2ca",
            classifiedPerm.databaseId
        )
        assertFalse("Must redact email from diagnostic message", classifiedPerm.sanitizedMessage.contains("user@example.com"))
        assertFalse("Must redact invite code from diagnostic message", classifiedPerm.sanitizedMessage.contains("AB12CD"))
        assertFalse("Must redact JWT token from diagnostic message", classifiedPerm.sanitizedMessage.contains("eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9"))

        val unavailable = com.google.firebase.firestore.FirebaseFirestoreException(
            "UNAVAILABLE: Unable to resolve host firestore.googleapis.com",
            com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE
        )
        val classifiedUnavailable = com.example.util.FirestoreErrorHandler.classifyStartupFailure(
            error = unavailable,
            stage = com.example.util.FirestoreErrorHandler.StartupStage.LOAD_FAMILY_MEMBER,
            collectionPath = "families/fam_1/members",
            documentId = "secret_uid_12345"
        )
        assertEquals(
            com.example.util.FirestoreErrorHandler.StartupFailureCategory.UNAVAILABLE,
            classifiedUnavailable.category
        )
        assertEquals("UNAVAILABLE", classifiedUnavailable.errorCode)

        val notFound = com.google.firebase.firestore.FirebaseFirestoreException(
            "NOT_FOUND: Document does not exist",
            com.google.firebase.firestore.FirebaseFirestoreException.Code.NOT_FOUND
        )
        val classifiedNotFound = com.example.util.FirestoreErrorHandler.classifyStartupFailure(
            error = notFound,
            stage = com.example.util.FirestoreErrorHandler.StartupStage.LOAD_FAMILY_DOCUMENT,
            collectionPath = "families",
            documentId = "fam_1"
        )
        assertEquals(
            com.example.util.FirestoreErrorHandler.StartupFailureCategory.NOT_FOUND,
            classifiedNotFound.category
        )
        assertEquals("NOT_FOUND", classifiedNotFound.errorCode)
    }

    @Test
    fun `test invitation diagnostics redact full codes and failed invitation writes never produce shareable code`() = runBlocking {
        val permDenied = com.google.firebase.firestore.FirebaseFirestoreException(
            "PERMISSION_DENIED: Missing or insufficient permissions for inviteCode=K9M4P2",
            com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED
        )
        val classifiedInviteErr = com.example.util.FirestoreErrorHandler.classifyInvitationFailure(
            error = permDenied,
            stage = com.example.util.FirestoreErrorHandler.InvitationStage.CREATE_INVITATION_WRITE,
            path = "family_invites/K9M4P2",
            rawInviteCode = "K9M4P2"
        )
        assertEquals(
            com.example.util.FirestoreErrorHandler.InvitationFailureCategory.PERMISSION_DENIED,
            classifiedInviteErr.category
        )
        assertEquals("PERMISSION_DENIED", classifiedInviteErr.errorCode)
        assertEquals("CREATE_INVITATION_WRITE", classifiedInviteErr.stage.stageName)
        assertEquals("family_invites/{inviteCode}", classifiedInviteErr.sanitizedPath)
        assertEquals(
            "ai-studio-android-familyta-a054dbd1-9c2d-4a83-ad6d-dcfe6fb0d2ca",
            classifiedInviteErr.databaseId
        )
        assertFalse("Diagnostic message must never contain full invite code", classifiedInviteErr.sanitizedMessage.contains("K9M4P2"))
        assertFalse("Diagnostic path must never contain full invite code", classifiedInviteErr.sanitizedPath.contains("K9M4P2"))

        // Verify that when repository invitation write fails, no shareable code is produced
        val repository = FamilyRepository()
        repository.simulateOfflineNetworkFailure = true
        val failedResult = repository.createRoleSpecificInvitation(
            invitedRole = UserRole.CHILD,
            invitedNickname = "דני",
            customCodeOverride = "NEVER9"
        )
        assertTrue(failedResult.isFailure)
        assertFalse(repository.pendingInvitations.value.any { it.code == "NEVER9" })
        assertNull(repository.inviteNewMember("דני", "👦", UserRole.CHILD, null, null))

        // Once network recovers, retry succeeds and produces a confirmed invitation
        repository.simulateOfflineNetworkFailure = false
        val retryResult = repository.createRoleSpecificInvitation(
            invitedRole = UserRole.CHILD,
            invitedNickname = "דני",
            customCodeOverride = "VALID9"
        )
        assertTrue(retryResult.isSuccess)
        assertEquals("VALID9", retryResult.getOrThrow().code)
        assertTrue(repository.pendingInvitations.value.any { it.code == "VALID9" })
    }
}


