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
import com.example.model.TaskCompletionSyncState
import com.example.model.TaskPriority
import com.example.model.UserRole
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.example.viewmodel.FamilyViewModel
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
    fun `verify build config version name and code incremented to V1_0_0`() {
        assertEquals("1.0.0", BuildConfig.VERSION_NAME)
        assertTrue("VERSION_CODE must be strictly greater than V1.6 (6)", BuildConfig.VERSION_CODE >= 7)
        assertEquals("1.0.1", BuildConfig.NEXT_RELEASE_VERSION_NAME)
        assertEquals(BuildConfig.VERSION_CODE + 1, BuildConfig.NEXT_RELEASE_VERSION_CODE)
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

    @Test
    fun `test child task completion shows pending approval and failed write reverts with Hebrew error`() = runBlocking {
        val repository = FamilyRepository()

        val roiChild = repository.members.value.first { it.id == "m_kid1" }
        val dadParent = repository.members.value.first { it.id == "m_dad" }

        // Switch active profile to Child (Roi / m_kid1)
        repository.switchCurrentMember(roiChild)
        val assignedTask = repository.tasks.value.first { it.assignedToMemberId == roiChild.id && !it.isCompleted }

        // 1. Simulate failed write -> must revert to incomplete, set FAILED sync state, and set Hebrew error
        repository.simulateOfflineNetworkFailure = true
        val notifBeforeFail = repository.notifications.value.size
        val failRes = repository.toggleTaskCompletionAsync(assignedTask.id)
        assertTrue("Simulated network failure must fail task completion write", failRes.isFailure)

        val taskAfterFail = repository.tasks.value.first { it.id == assignedTask.id }
        assertFalse("Failed write must restore task to incomplete", taskAfterFail.isCompleted)
        assertEquals(TaskCompletionSyncState.FAILED, repository.taskSyncStates.value[assignedTask.id])
        assertFalse("Failed write must set Hebrew error message", repository.taskOperationError.value.isNullOrBlank())
        assertEquals("Failed write must NOT trigger notification", notifBeforeFail, repository.notifications.value.size)

        // 2. Network recovers -> Child marks task completed -> enters PENDING_APPROVAL (not self-approved)
        repository.simulateOfflineNetworkFailure = false
        val completeRes = repository.toggleTaskCompletionAsync(assignedTask.id)
        assertTrue("Child completion of assigned task must succeed", completeRes.isSuccess)

        val taskAfterChildComplete = repository.tasks.value.first { it.id == assignedTask.id }
        assertTrue("Task must be marked completed after child toggle", taskAfterChildComplete.isCompleted)
        assertFalse("Task must NOT be marked approvedByParent before parent approves", taskAfterChildComplete.approvedByParent)
        assertEquals(TaskCompletionSyncState.PENDING_APPROVAL, repository.taskSyncStates.value[assignedTask.id])
        assertEquals(notifBeforeFail + 1, repository.notifications.value.size)

        // 3. Parent switches in and approves the task -> approvedByParent becomes true and points are awarded
        val roiPointsBefore = repository.members.value.first { it.id == roiChild.id }.earnedPoints
        repository.switchCurrentMember(dadParent)
        val approveRes = repository.approveTaskCompletionAsync(assignedTask.id)
        assertTrue("Parent approval must succeed", approveRes.isSuccess)

        val taskAfterParentApprove = repository.tasks.value.first { it.id == assignedTask.id }
        assertTrue("Task must be completed", taskAfterParentApprove.isCompleted)
        assertTrue("Task must be approvedByParent after parent approval", taskAfterParentApprove.approvedByParent)
        assertEquals(TaskCompletionSyncState.SAVED_APPROVED, repository.taskSyncStates.value[assignedTask.id])

        val roiPointsAfter = repository.members.value.first { it.id == roiChild.id }.earnedPoints
        assertEquals(roiPointsBefore + assignedTask.points, roiPointsAfter)
    }

    @Test
    fun `test automatic semantic versioning 1_0_0 progression and idempotent release allocation`() {
        // 1. When no 1.0.x release tags exist yet, allocateReleaseVersion starts at 1.0.0 with versionCode >= 7
        val initialAlloc = com.example.util.AppVersionManager.allocateReleaseVersion(
            candidateCommitSha = "abc111",
            existingReleases = emptyList()
        )
        assertEquals("1.0.0", initialAlloc.versionName)
        assertEquals(7, initialAlloc.versionCode)
        assertEquals("v1.0.0-vc7", initialAlloc.releaseTag)
        assertFalse(initialAlloc.isAlreadyReleasedForCommit)

        // 2. Subsequent release on a new commit increments patch to 1.0.1 and versionCode to 8
        val secondAlloc = com.example.util.AppVersionManager.allocateReleaseVersion(
            candidateCommitSha = "def222",
            existingReleases = listOf(
                com.example.util.ReleasedTagRecord(
                    versionName = initialAlloc.versionName,
                    versionCode = initialAlloc.versionCode,
                    commitSha = "abc111"
                )
            )
        )
        assertEquals("1.0.1", secondAlloc.versionName)
        assertEquals(8, secondAlloc.versionCode)
        assertEquals("v1.0.1-vc8", secondAlloc.releaseTag)
        assertFalse(secondAlloc.isAlreadyReleasedForCommit)

        // 3. Re-running on the same commit SHA is idempotent and does not create duplicate tags
        val duplicateRunAlloc = com.example.util.AppVersionManager.allocateReleaseVersion(
            candidateCommitSha = "def222",
            existingReleases = listOf(
                com.example.util.ReleasedTagRecord(
                    versionName = initialAlloc.versionName,
                    versionCode = initialAlloc.versionCode,
                    commitSha = "abc111"
                ),
                com.example.util.ReleasedTagRecord(
                    versionName = secondAlloc.versionName,
                    versionCode = secondAlloc.versionCode,
                    commitSha = "def222"
                )
            )
        )
        assertTrue(duplicateRunAlloc.isAlreadyReleasedForCommit)
        assertEquals("1.0.1", duplicateRunAlloc.versionName)
        assertEquals(8, duplicateRunAlloc.versionCode)
    }

    @Test
    fun `test strict 24 hour time formatting without AM PM and date preserving timestamp`() {
        // 1. Strict 24-hour HH:mm formatting
        assertEquals("00:05", com.example.ui.components.TimeFormatUtils.format24Hour(0, 5))
        assertEquals("14:30", com.example.ui.components.TimeFormatUtils.format24Hour(14, 30))
        assertEquals("23:59", com.example.ui.components.TimeFormatUtils.format24Hour(23, 59))

        // 2. Parsing and normalizing legacy AM/PM or single-digit strings into strict 24-hour HH:mm
        assertEquals("05:05", com.example.ui.components.TimeFormatUtils.normalize24HourString("5:05 PM"))
        assertEquals("12:15", com.example.ui.components.TimeFormatUtils.normalize24HourString("12:15 AM"))
        assertEquals("08:30", com.example.ui.components.TimeFormatUtils.normalize24HourString("8:30"))
        assertFalse(com.example.ui.components.TimeFormatUtils.normalize24HourString("09:45 PM").contains("PM"))
        assertFalse(com.example.ui.components.TimeFormatUtils.normalize24HourString("09:45 AM").contains("AM"))

        // 3. Preserving selected date when changing only the time
        val targetDate = LocalDate.of(2026, 10, 25)
        val epochDay = targetDate.toEpochDay()
        val (preservedDayMorning, morningMillis) =
            com.example.ui.components.TimeFormatUtils.combineDateAnd24HourTimeToEpochMillis(epochDay, "08:15")
        val (preservedDayEvening, eveningMillis) =
            com.example.ui.components.TimeFormatUtils.combineDateAnd24HourTimeToEpochMillis(epochDay, "21:45")
        assertEquals(epochDay, preservedDayMorning)
        assertEquals(epochDay, preservedDayEvening)
        assertTrue("Evening timestamp on same date must be after morning timestamp", eveningMillis > morningMillis)

        val zone = java.time.ZoneId.systemDefault()
        val morningZdt = java.time.Instant.ofEpochMilli(morningMillis).atZone(zone)
        val eveningZdt = java.time.Instant.ofEpochMilli(eveningMillis).atZone(zone)
        assertEquals(targetDate, morningZdt.toLocalDate())
        assertEquals(targetDate, eveningZdt.toLocalDate())
        assertEquals(8, morningZdt.hour)
        assertEquals(15, morningZdt.minute)
        assertEquals(21, eveningZdt.hour)
        assertEquals(45, eveningZdt.minute)
    }

    @Test
    fun `test FCM token deterministic ID generation deduplication and logout cleanup`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.example.notifications.FcmTokenManager.clearAllDispatchedEventsForTesting()

        // 1. Deterministic 32-char hex tokenId per device registration token
        val tokenA = "fcm_device_token_alpha_1234567890"
        val tokenB = "fcm_device_token_beta_0987654321"
        val idA1 = com.example.notifications.FcmTokenManager.computeTokenId(tokenA)
        val idA2 = com.example.notifications.FcmTokenManager.computeTokenId(tokenA)
        val idB = com.example.notifications.FcmTokenManager.computeTokenId(tokenB)
        assertEquals(32, idA1.length)
        assertEquals(idA1, idA2)
        assertNotEquals(idA1, idB)

        // 2. Local token persistence and cleanup on logout
        com.example.notifications.FcmTokenManager.cacheTokenLocally(context, tokenA, "uid_test_1")
        assertEquals(tokenA, com.example.notifications.FcmTokenManager.getCachedToken(context))
        com.example.notifications.FcmTokenManager.unregisterCurrentDeviceTokenAsync(context = context)
        assertNull(com.example.notifications.FcmTokenManager.getCachedToken(context))

        // 3. Deduplication prevents duplicate notifications for the same eventKey
        val eventKey = "task_submit_approval:fam_1:task_99:1700000005000"
        assertTrue("First presentation of eventKey must succeed", com.example.notifications.FcmTokenManager.markEventDispatchedIfNew(eventKey))
        assertFalse("Duplicate presentation of same eventKey must be suppressed", com.example.notifications.FcmTokenManager.markEventDispatchedIfNew(eventKey))
    }

    @Test
    fun `test shared file manager duplicate filenames sanitization and storage path safety`() {
        // 1. Path traversal & dangerous characters are stripped
        val sanitized = com.example.files.FamilyFileManagerHelper.sanitizeFileName("../../secret/חוזר:לימודים?.pdf", "pdf")
        assertFalse(sanitized.contains(".."))
        assertFalse(sanitized.contains("/"))
        assertTrue(sanitized.endsWith(".pdf"))

        // 2. Duplicate filenames in the same folder receive (2), (3) suffixes automatically
        val existing = listOf("חוזר_לימודים.pdf", "חוזר_לימודים (2).pdf")
        val deduped = com.example.files.FamilyFileManagerHelper.resolveUniqueDisplayFileName(
            desiredName = "חוזר_לימודים.pdf",
            existingNamesInFolder = existing
        )
        assertEquals("חוזר_לימודים (3).pdf", deduped)

        // 3. Storage path validation enforces family isolation
        val safePath = com.example.files.FamilyFileManagerHelper.buildStoragePath("fam_123", "doc_abc", "report.pdf")
        assertEquals("families/fam_123/files/doc_abc/report.pdf", safePath)
        assertTrue(com.example.files.FamilyFileManagerHelper.isStoragePathSafeForFamily(safePath, "fam_123"))
        assertFalse(com.example.files.FamilyFileManagerHelper.isStoragePathSafeForFamily(safePath, "fam_other"))
        assertFalse(com.example.files.FamilyFileManagerHelper.isStoragePathSafeForFamily("families/fam_123/../fam_other/x.pdf", "fam_123"))
    }

    @Test
    fun `test one tap file opening PDF rendering image preview and secure FileProvider URI`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Generate valid PDF bytes and verify PdfRenderer renders a non-null bitmap page
        val pdfBytes = com.example.files.FamilyFileManagerHelper.generateDocumentPdfBytes(
            title = "דף_עבודה_מתמטיקה.pdf",
            uploaderName = "אבא",
            dateFormatted = "20/10/2026",
            bodyLines = listOf("תרגול שברים פשוטים ובעיות מילוליות")
        )
        assertTrue("Generated PDF must start with %PDF-", String(pdfBytes.take(5).toByteArray()).startsWith("%PDF-"))

        val pdfDoc = com.example.model.FamilyDocument(
            id = "doc_pdf_test_1",
            folderId = "f_school",
            name = "דף_עבודה_מתמטיקה.pdf",
            extension = "PDF",
            sizeFormatted = com.example.files.FamilyFileManagerHelper.formatFileSize(pdfBytes.size.toLong()),
            uploadedByMemberId = "m_dad",
            uploadedDateFormatted = "היום",
            familyId = "fam_test_1",
            uploadedByUid = "m_dad",
            sizeBytes = pdfBytes.size.toLong()
        )
        val cachedPdfFile = com.example.files.FamilyFileManagerHelper.ensureLocalCacheFileForDocument(
            context = context,
            doc = pdfDoc,
            rawBytes = pdfBytes
        )
        assertTrue(cachedPdfFile.exists())
        val renderedPage = com.example.files.FamilyFileManagerHelper.renderPdfFirstPageBitmap(cachedPdfFile)
        assertNotNull("PdfRenderer must render first page of generated PDF", renderedPage)

        // 2. Verify FileProvider generates a secure content:// URI and never exposes file:// or raw paths
        val contentUri = com.example.files.FamilyFileManagerHelper.getSecureContentUriForDocument(
            context = context,
            doc = pdfDoc,
            rawBytes = pdfBytes
        )
        assertEquals("content", contentUri.scheme)
        assertTrue(contentUri.authority!!.endsWith(".fileprovider"))
        assertFalse(contentUri.toString().contains(context.cacheDir.absolutePath))

        // 3. Verify image generation and compact thumbnail Base64 generation
        val imgBytes = com.example.files.FamilyFileManagerHelper.generateSampleImageBytes("טיול_משפחתי.jpg", "נועם")
        assertTrue(imgBytes.isNotEmpty())
        val thumbB64 = com.example.files.FamilyFileManagerHelper.createCompactThumbnailBase64(imgBytes)
        assertNotNull("Image upload must produce a valid compact thumbnail Base64", thumbB64)
    }

    @Test
    fun `test upload cancellation retry and equal PARENT and CHILD Google Drive FamilyTasks permissions`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = FamilyRepository(context = context)

        // Verify least-privilege OAuth scope: only drive.file is requested, never broad drive scope
        val requestedScopeUris = com.example.files.GoogleDriveFamilyService.REQUIRED_OAUTH_SCOPES.map { it.scopeUri }
        assertEquals(
            listOf("https://www.googleapis.com/auth/drive.file"),
            requestedScopeUris
        )
        assertFalse(
            "Broad https://www.googleapis.com/auth/drive scope must not be requested",
            requestedScopeUris.contains("https://www.googleapis.com/auth/drive")
        )

        // 0. Parent connects FamilyTasks Google Drive root folder -> idempotent across retries/restarts
        val driveConfig1 = repository.connectOrSetupFamilyDriveFolder().getOrThrow()
        assertTrue(driveConfig1.isConfigured)
        assertEquals("FamilyTasks", driveConfig1.driveRootFolderName)
        assertTrue(driveConfig1.driveRootFolderId.isNotBlank())

        // Calling connectOrSetupFamilyDriveFolder again reuses the exact same Drive root folder ID (no duplicate folders)
        val driveConfig2 = repository.connectOrSetupFamilyDriveFolder().getOrThrow()
        assertEquals(
            "Must reuse the same authoritative FamilyTasks Drive folder ID and never create duplicates",
            driveConfig1.driveRootFolderId,
            driveConfig2.driveRootFolderId
        )

        // Parent updates child's Google account email and shares FamilyTasks root folder with all members
        val updatedEmailOk = repository.updateMemberGoogleEmail("m_kid1", "noam.family@gmail.com")
        assertTrue(updatedEmailOk)
        val sharedConfig = repository.shareFamilyDriveWithAllMembersSuspend().getOrThrow()
        assertTrue("Sharing FamilyTasks folder with family members must succeed", sharedConfig.isConfigured)
        assertTrue(
            "Child member must be marked as having Google Drive Editor access",
            repository.familyDriveConfig.value.sharedMemberEmails.contains("noam.family@gmail.com")
        )

        // 1. Parent uploads a file -> succeeds and appears in documents
        val parentDocCountBefore = repository.documents.value.size
        val parentUploadRes = repository.uploadFamilyFileInCloud(
            folderId = "f_school",
            rawFileName = "תקנון_בית_ספר.pdf",
            rawExtension = "PDF",
            rawBytes = com.example.files.FamilyFileManagerHelper.generateDocumentPdfBytes(
                title = "תקנון_בית_ספר.pdf",
                uploaderName = "אבא",
                dateFormatted = "היום",
                bodyLines = listOf("תקנון")
            )
        )
        assertTrue(parentUploadRes.isSuccess)
        val parentDoc = parentUploadRes.getOrThrow()
        assertTrue(parentDoc.isDriveSynced)
        assertTrue("Uploaded file must have a Google Drive file ID", parentDoc.driveFileId.isNotBlank())
        assertEquals(parentDocCountBefore + 1, repository.documents.value.size)

        // 2. Upload failure does NOT mark file as uploaded until retry succeeds
        repository.simulateNextFileUploadFailure = true
        val countBeforeFailed = repository.documents.value.size
        val failedUploadId = repository.startFileUpload(
            folderId = "f_school",
            rawFileName = "קובץ_שנכשל.pdf",
            rawExtension = "PDF",
            rawBytes = "test content".toByteArray()
        )
        var attempts = 0
        while (repository.activeUploads.value.find { it.uploadId == failedUploadId }?.status == com.example.model.FileUploadStatus.UPLOADING && attempts < 40) {
            kotlinx.coroutines.delay(25L)
            attempts++
        }
        val failedState = repository.activeUploads.value.find { it.uploadId == failedUploadId }
        assertNotNull(failedState)
        assertEquals(com.example.model.FileUploadStatus.FAILED, failedState!!.status)
        assertEquals("Failed upload must NOT be added to documents list", countBeforeFailed, repository.documents.value.size)

        // Retry the failed upload -> now succeeds and adds to documents list
        var retryCompleted: Result<com.example.model.FamilyDocument>? = null
        repository.retryFileUpload(failedUploadId) { retryCompleted = it }
        attempts = 0
        while (retryCompleted == null && attempts < 60) {
            kotlinx.coroutines.delay(25L)
            attempts++
        }
        assertNotNull(retryCompleted)
        assertTrue("Retried upload must succeed", retryCompleted!!.isSuccess)
        assertEquals(countBeforeFailed + 1, repository.documents.value.size)

        // 3. Upload cancellation stops in-flight upload and never adds unconfirmed file to documents
        repository.simulatedFileUploadStepDelayMs = 250L
        val countBeforeCancel = repository.documents.value.size
        val cancelUploadId = repository.startFileUpload(
            folderId = "f_school",
            rawFileName = "קובץ_מבוטל.pdf",
            rawExtension = "PDF",
            rawBytes = "cancelled content".toByteArray()
        )
        repository.cancelFileUpload(cancelUploadId)
        kotlinx.coroutines.delay(60L)
        repository.simulatedFileUploadStepDelayMs = 0L
        val cancelledState = repository.activeUploads.value.find { it.uploadId == cancelUploadId }
        assertEquals(com.example.model.FileUploadStatus.CANCELLED, cancelledState?.status)
        assertEquals("Cancelled upload must NOT be added to documents list", countBeforeCancel, repository.documents.value.size)

        // 4. Switch to CHILD ("m_kid1"): Verify 403/404 on shared root folder NEVER falls back to a personal Drive folder,
        //    preserves parent's driveRootFolderId, shows a clear Hebrew message, and requires Google Picker verification!
        val childMember = repository.members.value.find { it.id == "m_kid1" }!!
        repository.switchCurrentMember(childMember)

        // Simulate Drive API returning 403/404 before Child has selected the shared FamilyTasks folder in Google Picker
        com.example.files.GoogleDriveFamilyService.simulateUnauthorizedDriveIdForTest(driveConfig1.driveRootFolderId)

        val unauthConnectRes = repository.connectOrSetupFamilyDriveFolder()
        assertTrue(
            "Connecting to shared FamilyTasks folder before Google Picker selection must fail with 403/404 instead of creating a personal folder",
            unauthConnectRes.isFailure
        )
        assertEquals(
            "Must transition to NEEDS_SHARED_FOLDER_PICKER and never create a separate personal folder",
            com.example.model.DriveConnectionStatus.NEEDS_SHARED_FOLDER_PICKER,
            repository.familyDriveConfig.value.connectionStatus
        )
        assertEquals(
            "Parent's authoritative driveRootFolderId must be preserved",
            driveConfig1.driveRootFolderId,
            repository.familyDriveConfig.value.driveRootFolderId
        )
        assertNotNull("pendingDrivePickerRequest must be populated", repository.pendingDrivePickerRequest.value)
        assertTrue(
            "Must show a clear Hebrew message explaining that the user must authorize access to the shared family folder",
            repository.fileOperationError.value.orEmpty().contains("תיקיית המשפחה המשותפת")
        )

        // Attempting to upload a file or create a folder while 403/404 is active MUST NOT silently upload to a personal folder
        val docsBeforeUnauthUpload = repository.documents.value.size
        val unauthUploadRes = repository.uploadFamilyFileInCloud(
            folderId = "f_school",
            rawFileName = "קובץ_ללא_הרשאה.pdf",
            rawExtension = "PDF",
            rawBytes = "unauth test".toByteArray()
        )
        assertTrue("Upload on 403/404 must fail rather than uploading to a separate personal Drive folder", unauthUploadRes.isFailure)
        assertEquals("Failed 403/404 upload must never be disguised in documents list", docsBeforeUnauthUpload, repository.documents.value.size)
        assertTrue(
            "Upload 403/404 error must show clear Hebrew message",
            repository.fileOperationError.value.orEmpty().contains("אין הרשאת גישה לתיקיית המשפחה המשותפת")
        )

        val foldersBeforeUnauthCreate = repository.folders.value.size
        val unauthFolderRes = repository.createSharedFolderInCloud(
            nameHe = "תיקייה ללא הרשאה",
            parentFolderId = "f_school"
        )
        assertTrue("Folder creation on 403/404 must fail rather than creating a separate personal folder", unauthFolderRes.isFailure)
        assertEquals("Failed 403/404 folder must never be disguised in folders list", foldersBeforeUnauthCreate, repository.folders.value.size)

        // Selecting a WRONG folder ID in Google Picker (even if named "FamilyTasks") must be rejected!
        val wrongPickerRes = repository.verifyAndConnectPickedSharedFolderSuspend(
            pickedDriveId = "unrelated_personal_familytasks_folder_999",
            pickedName = "FamilyTasks"
        )
        assertTrue("Selecting an unrelated folder ID in Google Picker must be rejected", wrongPickerRes.isFailure)
        assertTrue(
            "Mismatch error must explain in Hebrew that the selected folder ID does not match the family's registered driveRootFolderId",
            repository.drivePickerVerificationError.value.orEmpty().contains(driveConfig1.driveRootFolderId)
        )

        // Selecting the EXACT authoritative driveRootFolderId in Google Picker succeeds and connects!
        val validPickerRes = repository.verifyAndConnectPickedSharedFolderSuspend(
            pickedDriveId = driveConfig1.driveRootFolderId,
            pickedName = "FamilyTasks"
        )
        assertTrue("Selecting the exact registered driveRootFolderId in Google Picker must succeed", validPickerRes.isSuccess)
        assertEquals(
            com.example.model.DriveConnectionStatus.CONNECTED,
            repository.familyDriveConfig.value.connectionStatus
        )
        assertEquals(
            driveConfig1.driveRootFolderId,
            repository.familyDriveConfig.value.driveRootFolderId
        )

        // Now Child creates a subfolder physically inside the shared FamilyTasks hierarchy
        val childSubfolder = repository.createSharedFolderInCloud(
            nameHe = "שיעורי בית נועם",
            parentFolderId = "f_school"
        ).getOrThrow()
        assertEquals("f_school", childSubfolder.parentFolderId)
        assertEquals(
            "Created subfolder must physically use the shared FamilyTasks root folder ID as its Drive parent",
            driveConfig1.driveRootFolderId,
            childSubfolder.driveParentFolderId
        )

        // Child can rename any folder in the shared FamilyTasks workspace
        assertTrue("Child must be allowed to rename shared folders", repository.renameFolder(childSubfolder.id, "שיעורי בית - מעודכן"))
        assertEquals(
            "שיעורי בית - מעודכן",
            repository.folders.value.find { it.id == childSubfolder.id }?.nameHe
        )

        val childFile = repository.uploadFamilyFileInCloud(
            folderId = childSubfolder.id,
            rawFileName = "פתרון_מתמטיקה.jpg",
            rawExtension = "JPG",
            rawBytes = com.example.files.FamilyFileManagerHelper.generateSampleImageBytes("פתרון", "נועם")
        ).getOrThrow()
        assertEquals(
            "Uploaded file must physically reside inside the shared subfolder's Drive ID",
            childSubfolder.driveFolderId,
            childFile.driveParentFolderId
        )

        // Child can open/download bytes of Parent's shared file
        val downloadedParentBytes = repository.fetchDocumentBytes(parentDoc)
        assertNotNull("Child must be able to open/download Parent's shared file bytes", downloadedParentBytes)
        assertTrue(downloadedParentBytes!!.isNotEmpty())

        // Child can rename, move, and delete ANY shared file or folder in the FamilyTasks workspace
        assertTrue("Child must have equal permission to rename parent's shared file", repository.renameDocument(parentDoc.id, "תקנון_מעודכן.pdf"))
        assertEquals("תקנון_מעודכן.pdf", repository.documents.value.find { it.id == parentDoc.id }?.name)

        assertTrue("Child must have equal permission to move parent's shared file between folders", repository.moveDocumentToFolder(parentDoc.id, childSubfolder.id))
        assertEquals(childSubfolder.id, repository.documents.value.find { it.id == parentDoc.id }?.folderId)
        assertEquals(
            "Moved file must update driveParentFolderId to the target shared subfolder's Drive ID",
            childSubfolder.driveFolderId,
            repository.documents.value.find { it.id == parentDoc.id }?.driveParentFolderId
        )

        assertTrue("Child must be allowed to delete their own uploaded file", repository.deleteDocument(childFile.id))

        val tempFolderToDelete = repository.createSharedFolderInCloud(
            nameHe = "תיקייה זמנית למחיקה",
            parentFolderId = childSubfolder.id
        ).getOrThrow()
        assertTrue("Child must have equal permission to delete shared folders", repository.deleteFolder(tempFolderToDelete.id))
        assertFalse(repository.folders.value.any { it.id == tempFolderToDelete.id })

        // 5. Folder navigation state and Google Drive FamilyTasks config persist across repository restart
        repository.selectFolder(childSubfolder.id)
        assertEquals(childSubfolder.id, repository.selectedFolderId.value)

        val restartedRepository = FamilyRepository(context = context)
        assertEquals(
            "App restart must preserve selected folder navigation",
            childSubfolder.id,
            restartedRepository.selectedFolderId.value
        )
        assertEquals(
            "App restart must preserve FamilyTasks Google Drive root folder ID",
            driveConfig1.driveRootFolderId,
            restartedRepository.familyDriveConfig.value.driveRootFolderId
        )
        assertTrue(
            "App restart must preserve created folders",
            restartedRepository.folders.value.any { it.id == childSubfolder.id }
        )
        assertTrue(
            "App restart must preserve uploaded family files",
            restartedRepository.documents.value.any { it.id == parentDoc.id }
        )
    }

    @Test
    fun `test complete file manager UX refinement camera JPG document PDF empty folders and safe cleanup`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = FamilyRepository()
        repository.createFamilyAsParent(
            familyNameInput = "משפחת כהן החדשה",
            parentDisplayName = "אמא",
            creatorUidOverride = "uid_mother_clean_test",
            creatorEmailOverride = "mom.clean@example.com",
            familyIdentity = com.example.model.FamilyIdentity.MOTHER
        ).getOrThrow()

        // 1. File manager starts completely empty with zero default/predefined folders and zero sample files
        assertTrue(
            "New FamilyTasks file manager must start with zero predefined folders",
            FamilyRepository.defaultStarterFolders("fam_new_clean").isEmpty()
        )
        assertTrue(
            "New family repository folders must be empty",
            repository.folders.value.isEmpty()
        )
        assertTrue(
            "New family repository documents must be empty",
            repository.documents.value.isEmpty()
        )
        assertNull(
            "Initial selectedFolderId must be null (FamilyTasks root folder)",
            repository.selectedFolderId.value
        )

        // 2A. Take Photo (צילום תמונה) -> Always JPEG (.jpg, image/jpeg), never converted to PDF
        val tempCameraFile = java.io.File(context.cacheDir, "test_camera_capture.jpg")
        val sampleBitmap = android.graphics.Bitmap.createBitmap(120, 90, android.graphics.Bitmap.Config.ARGB_8888)
        sampleBitmap.eraseColor(android.graphics.Color.rgb(30, 136, 229))
        java.io.FileOutputStream(tempCameraFile).use { out ->
            sampleBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
        }
        val cameraPayload = com.example.files.FamilyFileManagerHelper
            .processCapturedCameraPhotoJpeg(tempCameraFile)
            .getOrThrow()

        assertTrue(
            "Camera photo filename must end with .jpg",
            cameraPayload.sanitizedName.lowercase().endsWith(".jpg")
        )
        assertEquals(
            "Camera photo extension must be JPG (never PDF)",
            "JPG",
            cameraPayload.extension
        )
        assertEquals(
            "Camera photo MIME type must be image/jpeg",
            "image/jpeg",
            cameraPayload.mimeType
        )
        assertEquals(
            "image/jpeg",
            com.example.model.FamilyDocument.defaultMimeTypeForExtension(cameraPayload.extension)
        )
        // Verify JPEG magic header (0xFF 0xD8)
        assertTrue(cameraPayload.bytes.size > 4)
        assertEquals(0xFF.toByte(), cameraPayload.bytes[0])
        assertEquals(0xD8.toByte(), cameraPayload.bytes[1])
        tempCameraFile.delete()

        // 2B. Scan Document (סריקת מסמך) -> Always multi-page PDF (.pdf, application/pdf), never JPG
        val page1Bytes = com.example.files.FamilyFileManagerHelper.generateSampleImageBytes("עמוד 1", "אמא")
        val page2Bytes = com.example.files.FamilyFileManagerHelper.generateSampleImageBytes("עמוד 2", "אמא")
        val scannedPdfBytes = com.example.files.FamilyFileManagerHelper.generateMultiPageScannedDocumentPdfBytes(
            pagesBytes = listOf(page1Bytes, page2Bytes),
            title = "אישור_לימודים"
        )
        assertTrue("Scanned multi-page PDF must be non-empty", scannedPdfBytes.size > 100)
        val pdfHeader = String(scannedPdfBytes.copyOfRange(0, 4), Charsets.US_ASCII)
        assertEquals("Scanned document must start with %PDF magic header", "%PDF", pdfHeader)
        assertEquals(
            "application/pdf",
            com.example.model.FamilyDocument.defaultMimeTypeForExtension("PDF")
        )

        // 2C. Upload Existing File -> Preserves original format, extension, and MIME type
        assertEquals(
            "PNG",
            com.example.files.FamilyFileManagerHelper.extractExtension("diagram.png", "PNG")
        )
        assertEquals(
            "DOCX",
            com.example.files.FamilyFileManagerHelper.extractExtension("homework_notes", "DOCX")
        )
        assertEquals(
            "image/png",
            com.example.model.FamilyDocument.defaultMimeTypeForExtension("PNG")
        )

        // 3. Root-level upload directly into shared FamilyTasks root (when folderId is blank)
        val driveConfig = repository.connectOrSetupFamilyDriveFolder().getOrThrow()
        val rootPhotoDoc = repository.uploadFamilyFileInCloud(
            folderId = "",
            rawFileName = cameraPayload.sanitizedName,
            rawExtension = cameraPayload.extension,
            rawBytes = cameraPayload.bytes
        ).getOrThrow()

        assertEquals("JPG", rootPhotoDoc.extension)
        assertEquals(
            "Root-level photo upload must use the shared FamilyTasks root folder ID as driveParentFolderId",
            driveConfig.driveRootFolderId,
            rootPhotoDoc.driveParentFolderId
        )

        val rootScannedPdfDoc = repository.uploadFamilyFileInCloud(
            folderId = "",
            rawFileName = "סריקה_רב_עמודית.pdf",
            rawExtension = "PDF",
            rawBytes = scannedPdfBytes
        ).getOrThrow()

        assertEquals("PDF", rootScannedPdfDoc.extension)
        assertEquals(driveConfig.driveRootFolderId, rootScannedPdfDoc.driveParentFolderId)

        // 4. Safe, idempotent cleanup of legacy auto-generated default folders:
        //    - Empty legacy auto-generated folder ("f_medical") IS deleted.
        //    - Non-empty legacy auto-generated folder ("f_school" with a file) is NOT deleted.
        //    - Manually created folder with a similar name ("בית ספר") is NOT deleted even when empty.
        val emptyLegacyFolder = com.example.model.DocumentFolder(
            id = "f_medical",
            nameHe = "רפואה ובריאות",
            nameEn = "Medical",
            iconEmoji = "🏥",
            colorHex = 0xFFDC2626
        )
        val nonEmptyLegacyFolder = com.example.model.DocumentFolder(
            id = "f_school",
            nameHe = "בית ספר ולימודים",
            nameEn = "School",
            iconEmoji = "🎒",
            colorHex = 0xFF2563EB
        )
        val manualSameNameFolder = repository.createSharedFolderInCloud(
            nameHe = "בית ספר",
            parentFolderId = null
        ).getOrThrow()

        repository.injectFolderForTest(emptyLegacyFolder)
        repository.injectFolderForTest(nonEmptyLegacyFolder)

        // Put a real family file inside nonEmptyLegacyFolder ("f_school")
        repository.uploadFamilyFileInCloud(
            folderId = "f_school",
            rawFileName = "תעודה.pdf",
            rawExtension = "PDF",
            rawBytes = scannedPdfBytes
        ).getOrThrow()

        val deletedCount = repository.cleanupEmptyAutoGeneratedDefaultFolders()
        assertEquals("Only the 1 empty legacy auto-generated folder must be removed", 1, deletedCount)
        assertFalse(
            "Empty auto-generated default folder f_medical must be removed",
            repository.folders.value.any { it.id == "f_medical" }
        )
        assertTrue(
            "Non-empty legacy folder f_school containing a family file must NOT be deleted",
            repository.folders.value.any { it.id == "f_school" }
        )
        assertTrue(
            "Manually created folder with similar name ('בית ספר') must NEVER be deleted by auto-cleanup",
            repository.folders.value.any { it.id == manualSameNameFolder.id }
        )
    }

    @Test
    fun `test family alarm clock local scheduling target device acknowledgement snooze and bounded thumbnails`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = FamilyRepository(context = context)

        // 1. Parent creates an alarm assigned to Child ("m_kid1"):
        //    While Parent ("m_dad") is the active profile on this device, the alarm is NOT claimed as scheduled
        //    on the child's device until the child's device actually receives and registers it locally!
        val parentMember = repository.members.value.first { it.id == "m_dad" }
        val childMember = repository.members.value.first { it.id == "m_kid1" }
        repository.switchCurrentMember(parentMember)

        val parentCreatedChildAlarm = com.example.model.FamilyAlarm(
            id = "alarm_for_kid1_morning",
            title = "השכמה לבית ספר",
            hour = 7,
            minute = 15,
            daysOfWeek = com.example.model.SchoolDay.entries.toList(), // Sunday - Friday
            assignedMemberId = childMember.id,
            assignedMemberName = childMember.name,
            isEnabled = true,
            createdByMemberId = parentMember.id,
            createdByMemberName = parentMember.name,
            soundType = "CUSTOM_URI",
            customSoundUri = "content://media/internal/audio/media/42",
            customSoundTitle = "ציפורים בבוקר.mp3",
            vibrateEnabled = true,
            snoozeDurationMinutes = 10,
            volumePercent = 90
        )
        assertTrue(repository.addOrUpdateFamilyAlarm(parentCreatedChildAlarm))

        val savedOnParentDevice = repository.familyAlarms.value.first { it.id == "alarm_for_kid1_morning" }
        assertFalse(
            "Parent-created alarm for child must NOT claim remote device scheduling before child device acknowledges it",
            savedOnParentDevice.isConfirmedOnTargetDevice
        )
        assertEquals(
            "PENDING_TARGET_DEVICE",
            savedOnParentDevice.scheduleStatus
        )
        assertEquals("CUSTOM_URI", savedOnParentDevice.soundType)
        assertEquals("ציפורים בבוקר.mp3", savedOnParentDevice.customSoundTitle)
        assertEquals(10, savedOnParentDevice.snoozeDurationMinutes)
        assertEquals(90, savedOnParentDevice.volumePercent)

        // 2. When Child's device syncs/activates as m_kid1, FamilyAlarmScheduler registers the alarm locally
        //    with Android AlarmManager and updates status to SCHEDULED_LOCALLY (or EXACT_PERMISSION_NEEDED).
        val shouldScheduleForChild = com.example.notifications.FamilyAlarmScheduler.shouldScheduleOnDevice(
            alarm = savedOnParentDevice,
            currentMemberId = childMember.id
        )
        assertTrue("Child device must recognize alarm assigned to childMember", shouldScheduleForChild)

        val syncResults = com.example.notifications.FamilyAlarmScheduler.syncAlarmsForCurrentDevice(
            context = context,
            alarms = repository.familyAlarms.value,
            currentMemberId = childMember.id
        )
        val localScheduleResult = syncResults[savedOnParentDevice.id]
        assertNotNull("Child device sync must schedule the assigned alarm", localScheduleResult)
        assertTrue(
            "Local scheduling on child device must produce SCHEDULED_LOCALLY or EXACT_PERMISSION_NEEDED",
            localScheduleResult!!.statusCode == "SCHEDULED_LOCALLY" ||
                localScheduleResult.statusCode == "EXACT_PERMISSION_NEEDED"
        )
        assertTrue("Next trigger timestamp must be in the future", localScheduleResult.nextTriggerAtMillis > System.currentTimeMillis())

        // Verify persisted for boot restoration
        val restoredCount = com.example.notifications.FamilyAlarmScheduler.restoreAlarmsOnBoot(context)
        assertTrue("Boot restoration must restore at least 1 persisted local alarm", restoredCount >= 1)

        // Verify snooze scheduling
        val snoozeTriggerAt = com.example.notifications.FamilyAlarmScheduler.scheduleSnoozeAlarm(
            context = context,
            alarmId = savedOnParentDevice.id,
            title = savedOnParentDevice.title,
            soundType = savedOnParentDevice.soundType,
            customSoundUri = savedOnParentDevice.customSoundUri,
            vibrateEnabled = savedOnParentDevice.vibrateEnabled,
            snoozeMinutes = savedOnParentDevice.snoozeDurationMinutes,
            volumePercent = savedOnParentDevice.volumePercent
        )
        assertTrue(snoozeTriggerAt > System.currentTimeMillis() + 9 * 60_000L)

        // Cancel local alarm
        com.example.notifications.FamilyAlarmScheduler.cancelLocalAlarm(context, savedOnParentDevice.id)

        // 3. Verify Friday is supported in SchoolDay.schoolDays (6-day school week) and Saturday in SchoolDay.allWeekDays (7-day alarms)
        assertEquals(6, com.example.model.SchoolDay.schoolDays.size)
        assertEquals(7, com.example.model.SchoolDay.allWeekDays.size)
        assertTrue(com.example.model.SchoolDay.schoolDays.contains(com.example.model.SchoolDay.FRIDAY))
        assertTrue(com.example.model.SchoolDay.allWeekDays.contains(com.example.model.SchoolDay.SATURDAY))

        // 4. Verify bounded Firestore thumbnail respects Firestore document size limits (< 150KB Base64)
        val sampleImgBytes = com.example.files.FamilyFileManagerHelper.generateSampleImageBytes("תמונה_משפחתית.jpg", "אבא")
        val boundedThumbnail = com.example.files.FamilyFileManagerHelper.createCompactThumbnailBase64(sampleImgBytes)
        assertNotNull(boundedThumbnail)
        assertTrue(
            "Firestore thumbnailBase64 must remain strictly bounded well below Firestore 1MB limit",
            boundedThumbnail!!.length < 180_000
        )
    }

    @Test
    fun `test family alarm ownership permissions audio preview and ringing pipeline`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = FamilyRepository(context = context)

        val parentMember = repository.members.value.first { it.id == "m_dad" }
        val childMember = repository.members.value.first { it.id == "m_kid1" }

        // 1. Parent creates an alarm for the child
        repository.switchCurrentMember(parentMember)
        val parentForChildAlarm = com.example.model.FamilyAlarm(
            id = "alarm_parent_for_child_test",
            title = "השכמה לבית הספר",
            hour = 6,
            minute = 45,
            assignedMemberId = childMember.id,
            assignedToMemberId = childMember.id,
            assignedMemberName = childMember.name,
            createdByMemberId = parentMember.id,
            createdByMemberName = parentMember.name,
            createdByRole = "PARENT",
            soundType = com.example.notifications.AlarmAudioController.SOUND_MELODY_ENERGETIC,
            volumePercent = 85,
            snoozeDurationMinutes = 5,
            isEnabled = true
        )
        assertTrue(repository.addOrUpdateFamilyAlarm(parentForChildAlarm))

        // Verify target-device routing: rings on child's device, NOT on parent's device
        assertFalse(
            "Parent-created child alarm must NOT be scheduled locally on parent's device",
            com.example.notifications.FamilyAlarmScheduler.shouldScheduleOnDevice(parentForChildAlarm, parentMember.id)
        )
        assertTrue(
            "Parent-created child alarm MUST be scheduled locally on child's device",
            com.example.notifications.FamilyAlarmScheduler.shouldScheduleOnDevice(parentForChildAlarm, childMember.id)
        )

        // Switch to Child role: Child can see the alarm, but CANNOT edit, toggle, or delete it
        repository.switchCurrentMember(childMember)
        val storedParentAlarm = repository.familyAlarms.value.first { it.id == "alarm_parent_for_child_test" }
        assertFalse("Child must not have modify permission on parent-created alarm", storedParentAlarm.canMemberModify(childMember))
        assertFalse("Child must not have delete permission on parent-created alarm", storedParentAlarm.canMemberDelete(childMember))
        assertFalse("Child toggle on parent-created alarm must be rejected", repository.toggleFamilyAlarm(storedParentAlarm.id))
        assertFalse("Child edit on parent-created alarm must be rejected", repository.addOrUpdateFamilyAlarm(storedParentAlarm.copy(hour = 9)))
        assertFalse("Child delete on parent-created alarm must be rejected", repository.deleteFamilyAlarm(storedParentAlarm.id))

        // 2. Child creates their OWN alarm -> Child can edit, toggle, and delete; Parent can also manage it
        val childOwnAlarm = com.example.model.FamilyAlarm(
            id = "alarm_child_own_test",
            title = "אימון כדורסל",
            hour = 16,
            minute = 30,
            assignedMemberId = childMember.id,
            assignedToMemberId = childMember.id,
            assignedMemberName = childMember.name,
            createdByMemberId = childMember.id,
            createdByMemberName = childMember.name,
            createdByRole = "CHILD",
            soundType = com.example.notifications.AlarmAudioController.SOUND_MELODY_GENTLE,
            volumePercent = 75,
            snoozeDurationMinutes = 10,
            isEnabled = true
        )
        assertTrue("Child must be able to create their own alarm", repository.addOrUpdateFamilyAlarm(childOwnAlarm))
        val storedChildAlarm = repository.familyAlarms.value.first { it.id == "alarm_child_own_test" }
        assertTrue("Child can modify their own alarm", storedChildAlarm.canMemberModify(childMember))
        assertTrue("Parent can also modify child's alarm", storedChildAlarm.canMemberModify(parentMember))
        assertTrue("Child can toggle their own alarm", repository.toggleFamilyAlarm(storedChildAlarm.id))
        assertFalse(repository.familyAlarms.value.first { it.id == "alarm_child_own_test" }.isEnabled)

        // Parent can re-enable and delete child's alarm
        repository.switchCurrentMember(parentMember)
        assertTrue("Parent can toggle child's alarm", repository.toggleFamilyAlarm(storedChildAlarm.id))
        assertTrue("Parent can delete child's alarm", repository.deleteFamilyAlarm(storedChildAlarm.id))

        // 3. Parent creates their OWN personal alarm -> rings only on parent's device; child cannot modify
        val parentOwnAlarm = com.example.model.FamilyAlarm(
            id = "alarm_parent_own_test",
            title = "פגישת בוקר",
            hour = 6,
            minute = 15,
            assignedMemberId = parentMember.id,
            assignedToMemberId = parentMember.id,
            assignedMemberName = parentMember.name,
            createdByMemberId = parentMember.id,
            createdByMemberName = parentMember.name,
            createdByRole = "PARENT",
            isPersonal = true,
            soundType = com.example.notifications.AlarmAudioController.SOUND_MELODY_CLASSIC
        )
        assertTrue(repository.addOrUpdateFamilyAlarm(parentOwnAlarm))
        assertTrue(com.example.notifications.FamilyAlarmScheduler.shouldScheduleOnDevice(parentOwnAlarm, parentMember.id))
        assertFalse(com.example.notifications.FamilyAlarmScheduler.shouldScheduleOnDevice(parentOwnAlarm, childMember.id))
        assertFalse(parentOwnAlarm.canMemberModify(childMember))

        // 4. Test Sound Preview & Volume Preview lifecycle
        assertEquals(6, com.example.notifications.AlarmAudioController.builtInSoundOptions.size)
        com.example.notifications.AlarmAudioController.startPreview(
            context = context,
            soundType = com.example.notifications.AlarmAudioController.SOUND_MELODY_ENERGETIC,
            customSoundUri = "",
            volumePercent = 70
        )
        assertTrue(com.example.notifications.AlarmAudioController.previewState.value.isPlaying)
        assertEquals(70, com.example.notifications.AlarmAudioController.previewState.value.activeVolumePercent)

        com.example.notifications.AlarmAudioController.updateActivePreviewVolume(context, 95)
        assertEquals(95, com.example.notifications.AlarmAudioController.previewState.value.activeVolumePercent)

        com.example.notifications.AlarmAudioController.stopPreview(context)
        assertFalse(com.example.notifications.AlarmAudioController.previewState.value.isPlaying)

        // 5. Test Alarm Trigger -> Foreground Ringing State -> Snooze -> Dismiss pipeline
        val triggerIntent = android.content.Intent(context, com.example.notifications.FamilyAlarmReceiver::class.java).apply {
            action = com.example.notifications.FamilyAlarmScheduler.ACTION_TRIGGER_FAMILY_ALARM
            putExtra(com.example.notifications.FamilyAlarmScheduler.EXTRA_ALARM_ID, parentOwnAlarm.id)
            putExtra(com.example.notifications.FamilyAlarmScheduler.EXTRA_ALARM_TITLE, parentOwnAlarm.title)
            putExtra(com.example.notifications.FamilyAlarmScheduler.EXTRA_ALARM_HOUR, parentOwnAlarm.hour)
            putExtra(com.example.notifications.FamilyAlarmScheduler.EXTRA_ALARM_MINUTE, parentOwnAlarm.minute)
            putExtra(com.example.notifications.FamilyAlarmScheduler.EXTRA_ALARM_SOUND_TYPE, parentOwnAlarm.soundType)
            putExtra(com.example.notifications.FamilyAlarmScheduler.EXTRA_ALARM_SNOOZE_MINUTES, 5)
            putExtra(com.example.notifications.FamilyAlarmScheduler.EXTRA_ALARM_VOLUME_PERCENT, 80)
        }
        com.example.notifications.FamilyAlarmReceiver().onReceive(context, triggerIntent)

        val activeRinging = com.example.notifications.FamilyAlarmRingingService.activeRingingAlarm.value
        assertNotNull("Alarm trigger must activate FamilyAlarmRingingService state", activeRinging)
        assertEquals(parentOwnAlarm.id, activeRinging?.alarmId)
        assertEquals("06:15", activeRinging?.timeFormatted)

        // Snooze active alarm
        val snoozedUntil = com.example.notifications.FamilyAlarmRingingService.snoozeActiveAlarm(context)
        assertTrue("Snooze must schedule future alarm trigger", snoozedUntil > System.currentTimeMillis())
        assertNull("Active ringing state must clear on snooze", com.example.notifications.FamilyAlarmRingingService.activeRingingAlarm.value)

        // Dismiss clean-up
        com.example.notifications.FamilyAlarmRingingService.dismissActiveAlarm(context, parentOwnAlarm.id)
        com.example.notifications.FamilyAlarmScheduler.cancelLocalAlarm(context, parentOwnAlarm.id)
        com.example.notifications.FamilyAlarmScheduler.cancelLocalAlarm(context, parentForChildAlarm.id)
    }

    @Test
    fun `test default viewmodel repository context attachment 15s diagnostic alarm and saturday alarm scheduling`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Instantiate FamilyRepository() with default no-arg constructor (context = null, matching FamilyViewModel())
        //    and attachApplicationContext(context). Verify local alarms actually register in Android AlarmManager!
        val repository = FamilyRepository()
        repository.attachApplicationContext(context)

        val childMember = repository.members.value.first { it.id == "m_kid1" }
        repository.switchCurrentMember(childMember)

        val childLocalAlarm = com.example.model.FamilyAlarm(
            id = "alarm_child_local_real_am",
            title = "שעון מקומי של הילד",
            hour = 8,
            minute = 15,
            assignedMemberId = childMember.id,
            assignedToMemberId = childMember.id,
            assignedMemberName = childMember.name,
            createdByMemberId = childMember.id,
            createdByMemberName = childMember.name,
            createdByRole = "CHILD",
            isPersonal = true,
            daysOfWeek = com.example.model.SchoolDay.allWeekDays
        )
        assertTrue(repository.addOrUpdateFamilyAlarm(childLocalAlarm))
        assertTrue(
            "Local alarm created via default FamilyRepository() MUST register a real PendingIntent in Android AlarmManager",
            com.example.notifications.FamilyAlarmScheduler.isAlarmLocallyScheduled(context, childLocalAlarm.id)
        )
        val savedChildAlarm = repository.familyAlarms.value.first { it.id == childLocalAlarm.id }
        assertFalse(
            "Personal child alarm must NEVER be treated as targeted to another member",
            savedChildAlarm.isTargetedToAnotherMember()
        )
        assertNotEquals(
            "Personal child alarm must NEVER show PENDING_TARGET_DEVICE",
            com.example.model.FamilyAlarm.STATUS_PENDING_TARGET_DEVICE,
            savedChildAlarm.scheduleStatus
        )

        // 2. Verify Saturday alarm scheduling calculates same-day trigger when scheduled on a Saturday
        val satCal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.SATURDAY)
            set(java.util.Calendar.HOUR_OF_DAY, 10)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val satAlarm = childLocalAlarm.copy(
            hour = 10,
            minute = 5,
            daysOfWeek = com.example.model.SchoolDay.allWeekDays
        )
        val nextSatTrigger = com.example.notifications.FamilyAlarmScheduler.computeNextTriggerAtMillis(
            alarm = satAlarm,
            nowMillis = satCal.timeInMillis
        )
        assertEquals(
            "Alarm created on Saturday at 10:00 for 10:05 must trigger 5 minutes later on Saturday, not roll over to Sunday",
            5 * 60_000L,
            nextSatTrigger - satCal.timeInMillis
        )

        // 3. Test Real 15-Second Developer Diagnostic Alarm Chain:
        //    AlarmManager -> PendingIntent -> FamilyAlarmReceiver -> FamilyAlarmRingingService -> AlarmAudioController
        com.example.notifications.FamilyAlarmScheduler.resetDiagnosticState(context)
        val diagScheduleRes = com.example.notifications.FamilyAlarmScheduler.scheduleDiagnostic15SecondAlarm(
            context = context,
            currentMemberName = childMember.name
        )
        assertTrue("15-second diagnostic alarm must be scheduled in AlarmManager", diagScheduleRes.isScheduled)
        assertTrue(
            "Diagnostic state must report isScheduled = true",
            com.example.notifications.FamilyAlarmScheduler.diagnosticState.value.isScheduled
        )
        assertTrue(
            "Diagnostic PendingIntent must exist in AlarmManager",
            com.example.notifications.FamilyAlarmScheduler.isAlarmLocallyScheduled(
                context,
                com.example.notifications.FamilyAlarmScheduler.DIAGNOSTIC_15S_ALARM_ID
            )
        )

        // Simulate AlarmManager firing the PendingIntent to FamilyAlarmReceiver
        val diagReceiverIntent = android.content.Intent(context, com.example.notifications.FamilyAlarmReceiver::class.java).apply {
            action = com.example.notifications.FamilyAlarmScheduler.ACTION_TRIGGER_FAMILY_ALARM
            putExtra(
                com.example.notifications.FamilyAlarmScheduler.EXTRA_ALARM_ID,
                com.example.notifications.FamilyAlarmScheduler.DIAGNOSTIC_15S_ALARM_ID
            )
            putExtra(com.example.notifications.FamilyAlarmScheduler.EXTRA_ALARM_TITLE, "בדיקת צלצול (15 שניות) 🔔")
            putExtra(com.example.notifications.FamilyAlarmScheduler.EXTRA_ALARM_HOUR, 10)
            putExtra(com.example.notifications.FamilyAlarmScheduler.EXTRA_ALARM_MINUTE, 5)
            putExtra(
                com.example.notifications.FamilyAlarmScheduler.EXTRA_ALARM_SOUND_TYPE,
                com.example.notifications.AlarmAudioController.SOUND_MELODY_ENERGETIC
            )
            putExtra(com.example.notifications.FamilyAlarmScheduler.EXTRA_ALARM_VOLUME_PERCENT, 90)
            putExtra(com.example.notifications.FamilyAlarmScheduler.EXTRA_IS_DIAGNOSTIC_TEST, true)
        }
        com.example.notifications.FamilyAlarmReceiver().onReceive(context, diagReceiverIntent)

        // Start service directly in Robolectric to verify onStartCommand execution and audio start
        val serviceIntent = android.content.Intent(context, com.example.notifications.FamilyAlarmRingingService::class.java).apply {
            action = com.example.notifications.FamilyAlarmRingingService.ACTION_START_RINGING
            putExtras(diagReceiverIntent)
        }
        org.robolectric.Robolectric.buildService(
            com.example.notifications.FamilyAlarmRingingService::class.java,
            serviceIntent
        ).create().startCommand(0, 1)

        val diagAfterTrigger = com.example.notifications.FamilyAlarmScheduler.diagnosticState.value
        assertTrue("Diagnostic state must record receiverTriggered = true", diagAfterTrigger.receiverTriggered)
        assertTrue("Diagnostic state must record ringingServiceStarted = true", diagAfterTrigger.ringingServiceStarted)
        assertTrue("Diagnostic state must record audioPlaybackStarted = true", diagAfterTrigger.audioPlaybackStarted)
        assertTrue("Diagnostic errorMessage must be empty", diagAfterTrigger.errorMessage.isBlank())

        com.example.notifications.FamilyAlarmReceiver.stopActiveRinging(context)
        com.example.notifications.FamilyAlarmScheduler.cancelLocalAlarm(context, childLocalAlarm.id)
    }

    @Test
    fun `test remote alarm deletion tombstone cancels child local alarm stops active ringing and tracks target confirmation`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = FamilyRepository(context)

        val parentMember = repository.members.value.first { it.id == "m_dad" }
        val childMember = repository.members.value.first { it.id == "m_kid1" }

        // 1. Parent creates an alarm assigned to the child
        repository.switchCurrentMember(parentMember)
        val childAssignedAlarm = com.example.model.FamilyAlarm(
            id = "alarm_remote_delete_sync_test",
            title = "השכמה לבית ספר",
            hour = 7,
            minute = 10,
            assignedMemberId = childMember.id,
            assignedToMemberId = childMember.id,
            assignedMemberName = childMember.name,
            createdByMemberId = parentMember.id,
            createdByMemberName = parentMember.name,
            createdByRole = "PARENT",
            isPersonal = false,
            isEnabled = true
        )
        assertTrue(repository.addOrUpdateFamilyAlarm(childAssignedAlarm))

        // 2. Simulate the child's device syncing and scheduling the alarm locally
        com.example.notifications.FamilyAlarmScheduler.syncAllDeviceAlarms(
            context = context,
            alarms = repository.familyAlarms.value,
            currentMemberId = childMember.id,
            currentAuthUid = childMember.id,
            currentMemberName = childMember.name,
            isChildDevice = true
        )
        assertTrue(
            "Assigned alarm must be scheduled in AlarmManager on child's device",
            com.example.notifications.FamilyAlarmScheduler.isAlarmLocallyScheduled(context, childAssignedAlarm.id)
        )
        assertTrue(
            "Assigned alarm must be persisted in local device alarm storage",
            com.example.notifications.FamilyAlarmScheduler.loadPersistedAlarms(context).any { it.id == childAssignedAlarm.id }
        )

        // Also simulate a snoozed PendingIntent and active ringing for this alarm on child's device
        com.example.notifications.FamilyAlarmScheduler.scheduleSnoozeAlarm(
            context = context,
            alarmId = childAssignedAlarm.id,
            title = childAssignedAlarm.title,
            soundType = childAssignedAlarm.soundType,
            customSoundUri = "",
            vibrateEnabled = true,
            snoozeMinutes = 5,
            volumePercent = 80
        )
        com.example.notifications.FamilyAlarmRingingService.startRingingService(
            context = context,
            alarmId = childAssignedAlarm.id,
            title = childAssignedAlarm.title,
            hour = childAssignedAlarm.hour,
            minute = childAssignedAlarm.minute,
            soundType = childAssignedAlarm.soundType,
            customSoundUri = "",
            vibrateEnabled = true,
            snoozeMinutes = 5,
            volumePercent = 80
        )
        assertEquals(
            childAssignedAlarm.id,
            com.example.notifications.FamilyAlarmRingingService.activeRingingAlarm.value?.alarmId
        )

        // 3. Parent deletes the alarm -> removed from active list, and since target is remote child,
        //    creates a pending cloud deletion notice until child device confirms cancellation
        assertTrue(repository.deleteFamilyAlarm(childAssignedAlarm.id))
        assertFalse(
            "Deleted alarm must be removed from active familyAlarms list",
            repository.familyAlarms.value.any { it.id == childAssignedAlarm.id }
        )
        val pendingNotice = repository.deletedAlarmNotices.value.find { it.alarmId == childAssignedAlarm.id }
        assertNotNull("Parent must see pending target cancellation notice when deleting remote child alarm", pendingNotice)
        assertFalse(
            "Remote child alarm deletion must not claim immediate target cancellation before child device confirms",
            pendingNotice!!.isConfirmedCancelledOnTarget
        )

        // 4. Child device receives the tombstone / updated alarm list -> cancels AlarmManager PendingIntent,
        //    cancels snooze, stops active ringing, and purges persisted local alarm state
        val purgedIds = com.example.notifications.FamilyAlarmScheduler.syncAllDeviceAlarms(
            context = context,
            alarms = repository.familyAlarms.value,
            currentMemberId = childMember.id,
            currentAuthUid = childMember.id,
            currentMemberName = childMember.name,
            isChildDevice = true,
            deletedAlarmIds = setOf(childAssignedAlarm.id)
        )
        assertTrue("Child device sync must report the deleted alarm ID as purged", childAssignedAlarm.id in purgedIds)
        assertFalse(
            "Deleted alarm PendingIntent must be cancelled in AlarmManager",
            com.example.notifications.FamilyAlarmScheduler.isAlarmLocallyScheduled(context, childAssignedAlarm.id)
        )
        assertNull(
            "Active ringing for the deleted alarm must stop immediately",
            com.example.notifications.FamilyAlarmRingingService.activeRingingAlarm.value
        )
        assertFalse(
            "Deleted alarm must be removed from persisted local alarm cache",
            com.example.notifications.FamilyAlarmScheduler.loadPersistedAlarms(context).any { it.id == childAssignedAlarm.id }
        )
        assertTrue(
            "Deleted alarm ID must be recorded in local tombstone set so startup never recreates it",
            com.example.notifications.FamilyAlarmScheduler.isAlarmTombstoned(context, childAssignedAlarm.id)
        )

        // 5. Verify full-screen alarm notification includes fullScreenIntent and Dismiss/Snooze actions
        val alarmNotif = com.example.notifications.FamilyAlarmRingingService.buildStaticAlarmNotification(
            context = context,
            alarmId = "test_fullscreen_notif",
            title = "בדיקת מסך מלא",
            timeFormatted = "07:30",
            hour = 7,
            minute = 30,
            soundType = com.example.notifications.AlarmAudioController.SOUND_MELODY_ENERGETIC,
            customUri = "",
            vibrateEnabled = true,
            snoozeMinutes = 5,
            volumePercent = 85
        )
        assertNotNull("Alarm notification must configure fullScreenIntent", alarmNotif.fullScreenIntent)
        assertEquals(android.app.Notification.CATEGORY_ALARM, alarmNotif.category)
        assertEquals("Alarm notification must include Snooze and Dismiss actions", 2, alarmNotif.actions.size)
    }

    @Test
    fun `test premium sunrise alarm ringing screen label resolution notification actions snooze dismiss and recurring preservation`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Verify alarm label resolution: custom labels preserved, blank/generic labels default to "בוקר טוב!"
        assertEquals(
            "בוקר טוב רועי!",
            com.example.notifications.FamilyAlarmRingingActivity.resolveAlarmDisplayLabel("בוקר טוב רועי!")
        )
        assertEquals(
            "בוקר טוב!",
            com.example.notifications.FamilyAlarmRingingActivity.resolveAlarmDisplayLabel("")
        )
        assertEquals(
            "בוקר טוב!",
            com.example.notifications.FamilyAlarmRingingActivity.resolveAlarmDisplayLabel("   ")
        )
        assertEquals(
            "בוקר טוב!",
            com.example.notifications.FamilyAlarmRingingActivity.resolveAlarmDisplayLabel("שעון מעורר")
        )
        assertEquals(
            "בוקר טוב!",
            com.example.notifications.FamilyAlarmRingingActivity.resolveAlarmDisplayLabel("שעון מעורר משפחתי")
        )
        assertEquals(
            "☀️ הגיע הזמן להתחיל את היום",
            com.example.notifications.FamilyAlarmRingingActivity.MORNING_PILL_MESSAGE_HE
        )

        // 2. Schedule a recurring alarm for 07:00 ("בוקר טוב רועי!") with 5-minute snooze
        val recurringAlarm = com.example.model.FamilyAlarm(
            id = "alarm_sunrise_roey_0700",
            title = "בוקר טוב רועי!",
            hour = 7,
            minute = 0,
            daysOfWeek = com.example.model.SchoolDay.allWeekDays,
            isEnabled = true,
            soundType = com.example.notifications.AlarmAudioController.SOUND_MELODY_ENERGETIC,
            vibrateEnabled = true,
            snoozeDurationMinutes = 5,
            volumePercent = 85,
            isPersonal = true
        )
        val scheduleRes = com.example.notifications.FamilyAlarmScheduler.scheduleLocalAlarm(context, recurringAlarm)
        assertTrue("Recurring alarm must be scheduled in AlarmManager", scheduleRes.isScheduled)
        assertTrue(
            "Recurring alarm PendingIntent must exist in AlarmManager",
            com.example.notifications.FamilyAlarmScheduler.isAlarmLocallyScheduled(context, recurringAlarm.id)
        )

        // 3. Trigger the alarm via FamilyAlarmReceiver -> starts ringing service & preserves next recurring occurrence
        val triggerIntent = com.example.notifications.FamilyAlarmScheduler.buildTriggerBroadcastIntent(
            context = context,
            alarm = recurringAlarm,
            isSnooze = false
        )
        com.example.notifications.FamilyAlarmReceiver().onReceive(context, triggerIntent)

        val activeRinging = com.example.notifications.FamilyAlarmRingingService.activeRingingAlarm.value
        assertNotNull("Triggering alarm must set activeRingingAlarm state", activeRinging)
        assertEquals("07:00", activeRinging?.timeFormatted)
        assertEquals("בוקר טוב רועי!", activeRinging?.title)
        assertEquals(5, activeRinging?.snoozeMinutes)
        assertTrue(
            "Next recurring occurrence must remain scheduled in AlarmManager when alarm triggers",
            com.example.notifications.FamilyAlarmScheduler.isAlarmLocallyScheduled(context, recurringAlarm.id)
        )

        // 4. Launch FamilyAlarmRingingActivity via createLaunchIntent and verify lock-screen activity creation
        val launchIntent = com.example.notifications.FamilyAlarmRingingActivity.createLaunchIntent(
            context = context,
            alarmId = recurringAlarm.id,
            title = recurringAlarm.title,
            hour = recurringAlarm.hour,
            minute = recurringAlarm.minute,
            snoozeMinutes = recurringAlarm.snoozeDurationMinutes
        )
        val activityController = org.robolectric.Robolectric.buildActivity(
            com.example.notifications.FamilyAlarmRingingActivity::class.java,
            launchIntent
        ).create().start().resume()
        val activity = activityController.get()
        assertNotNull("FamilyAlarmRingingActivity must instantiate and resume over lock screen", activity)
        assertEquals(
            "בוקר טוב רועי!",
            activity.intent.getStringExtra(com.example.notifications.FamilyAlarmScheduler.EXTRA_ALARM_TITLE)
        )
        assertEquals(
            7,
            activity.intent.getIntExtra(com.example.notifications.FamilyAlarmScheduler.EXTRA_ALARM_HOUR, -1)
        )
        assertEquals(
            0,
            activity.intent.getIntExtra(com.example.notifications.FamilyAlarmScheduler.EXTRA_ALARM_MINUTE, -1)
        )

        // 5. Verify full-screen alarm notification content & actions
        val notification = com.example.notifications.FamilyAlarmRingingService.buildStaticAlarmNotification(
            context = context,
            alarmId = recurringAlarm.id,
            title = recurringAlarm.title,
            timeFormatted = "07:00",
            hour = 7,
            minute = 0,
            soundType = recurringAlarm.soundType,
            customUri = "",
            vibrateEnabled = true,
            snoozeMinutes = 5,
            volumePercent = 85
        )
        assertNotNull("Full-screen intent must be configured on alarm notification", notification.fullScreenIntent)
        assertEquals(android.app.Notification.CATEGORY_ALARM, notification.category)
        assertEquals(android.app.Notification.VISIBILITY_PUBLIC, notification.visibility)
        val notifTitle = notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
        val notifText = notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertTrue("Notification title must include 07:00", notifTitle.contains("07:00"))
        assertTrue("Notification title must include alarm label", notifTitle.contains("בוקר טוב רועי!"))
        assertTrue("Notification text must include morning message", notifText.contains("הגיע הזמן להתחיל את היום"))
        assertEquals("Notification must provide Snooze and Dismiss actions", 2, notification.actions.size)
        assertTrue("First action must be Snooze (נודניק)", notification.actions[0].title.toString().contains("נודניק"))
        assertEquals("כיבוי", notification.actions[1].title.toString())

        // 6. Test Snooze via notification broadcast action (ACTION_SNOOZE_FAMILY_ALARM)
        val snoozeBroadcast = android.content.Intent(context, com.example.notifications.FamilyAlarmReceiver::class.java).apply {
            action = com.example.notifications.FamilyAlarmScheduler.ACTION_SNOOZE_FAMILY_ALARM
            putExtra(com.example.notifications.FamilyAlarmScheduler.EXTRA_ALARM_ID, recurringAlarm.id)
            putExtra(com.example.notifications.FamilyAlarmScheduler.EXTRA_ALARM_TITLE, recurringAlarm.title)
            putExtra(com.example.notifications.FamilyAlarmScheduler.EXTRA_ALARM_SNOOZE_MINUTES, 5)
        }
        com.example.notifications.FamilyAlarmReceiver().onReceive(context, snoozeBroadcast)

        assertNull(
            "Snooze must stop current active ringing state immediately",
            com.example.notifications.FamilyAlarmRingingService.activeRingingAlarm.value
        )
        assertTrue(
            "Snooze must schedule a snooze PendingIntent in AlarmManager",
            com.example.notifications.FamilyAlarmScheduler.isSnoozeLocallyScheduled(context, recurringAlarm.id)
        )
        assertTrue(
            "Snooze must preserve the original recurring alarm schedule",
            com.example.notifications.FamilyAlarmScheduler.isAlarmLocallyScheduled(context, recurringAlarm.id)
        )

        // 7. Re-trigger at snooze time and test Dismiss ("כיבוי")
        com.example.notifications.FamilyAlarmReceiver.clearRecentTrigger(recurringAlarm.id)
        val snoozeTriggerIntent = com.example.notifications.FamilyAlarmScheduler.buildTriggerBroadcastIntent(
            context = context,
            alarm = recurringAlarm,
            isSnooze = true
        )
        com.example.notifications.FamilyAlarmReceiver().onReceive(context, snoozeTriggerIntent)
        assertNotNull(
            "Snooze trigger must re-activate ringing state",
            com.example.notifications.FamilyAlarmRingingService.activeRingingAlarm.value
        )

        val dismissBroadcast = android.content.Intent(context, com.example.notifications.FamilyAlarmReceiver::class.java).apply {
            action = com.example.notifications.FamilyAlarmScheduler.ACTION_DISMISS_FAMILY_ALARM
            putExtra(com.example.notifications.FamilyAlarmScheduler.EXTRA_ALARM_ID, recurringAlarm.id)
        }
        com.example.notifications.FamilyAlarmReceiver().onReceive(context, dismissBroadcast)

        assertNull(
            "Dismiss must immediately stop active ringing state",
            com.example.notifications.FamilyAlarmRingingService.activeRingingAlarm.value
        )
        assertFalse(
            "Dismiss must cancel any pending snooze PendingIntent",
            com.example.notifications.FamilyAlarmScheduler.isSnoozeLocallyScheduled(context, recurringAlarm.id)
        )
        assertTrue(
            "Dismiss must record dismissal deduplication to prevent duplicate ringing",
            com.example.notifications.FamilyAlarmReceiver.wasRecentlyDismissed(recurringAlarm.id)
        )
        assertTrue(
            "Dismiss must preserve the next scheduled occurrence for recurring alarms",
            com.example.notifications.FamilyAlarmScheduler.isAlarmLocallyScheduled(context, recurringAlarm.id)
        )

        activityController.pause().stop().destroy()
        com.example.notifications.FamilyAlarmScheduler.cancelLocalAlarm(context, recurringAlarm.id)
    }

    @Test
    fun `test parent personal alarm privacy hidden from child and native android ringtone support`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = FamilyRepository()
        repository.attachApplicationContext(context)

        val parentMember = repository.members.value.first { it.isParent }
        val childMember = repository.members.value.first { !it.isParent }

        // 1. Parent creates a personal alarm for themselves
        repository.switchCurrentMember(parentMember)
        val parentPersonalAlarm = com.example.model.FamilyAlarm(
            id = "alarm_parent_private_test",
            title = "פגישת בוקר להורה",
            hour = 6,
            minute = 15,
            assignedMemberId = parentMember.id,
            assignedToMemberId = parentMember.id,
            assignedMemberName = parentMember.name,
            createdByMemberId = parentMember.id,
            createdByMemberName = parentMember.name,
            createdByRole = "PARENT",
            isPersonal = true
        )
        repository.addOrUpdateFamilyAlarm(parentPersonalAlarm)

        // 2. Parent creates an alarm assigned to the child
        val parentAssignedToChildAlarm = com.example.model.FamilyAlarm(
            id = "alarm_parent_for_child_privacy_test",
            title = "בוקר טוב לבית הספר",
            hour = 7,
            minute = 0,
            assignedMemberId = childMember.id,
            assignedToMemberId = childMember.id,
            assignedMemberName = childMember.name,
            createdByMemberId = parentMember.id,
            createdByMemberName = parentMember.name,
            createdByRole = "PARENT",
            isPersonal = false
        )
        repository.addOrUpdateFamilyAlarm(parentAssignedToChildAlarm)

        assertTrue(
            "Parent personal alarm must be recognized as a parent personal alarm",
            parentPersonalAlarm.isParentPersonalAlarm(repository.members.value)
        )
        assertTrue(
            "Parent must see their own personal alarm",
            repository.familyAlarms.value.any { it.id == parentPersonalAlarm.id }
        )
        assertTrue(
            "Parent must see the child-assigned alarm",
            repository.familyAlarms.value.any { it.id == parentAssignedToChildAlarm.id }
        )

        // 3. Switch to Child -> Parent's personal alarm MUST be hidden, while child-assigned alarm remains visible
        repository.switchCurrentMember(childMember)
        assertFalse(
            "Child MUST NOT see parent's personal alarm in familyAlarms list",
            repository.familyAlarms.value.any { it.id == parentPersonalAlarm.id }
        )
        assertFalse(
            "isVisibleToMember must return false for child viewing parent's personal alarm",
            parentPersonalAlarm.isVisibleToMember(childMember, members = repository.members.value)
        )
        assertTrue(
            "Child MUST see alarm assigned to them by parent",
            repository.familyAlarms.value.any { it.id == parentAssignedToChildAlarm.id }
        )

        // 4. Child creates their own personal alarm -> visible to both Child and Parent
        val childPersonalAlarm = com.example.model.FamilyAlarm(
            id = "alarm_child_own_privacy_test",
            title = "חוג כדורגל",
            hour = 16,
            minute = 30,
            assignedMemberId = childMember.id,
            assignedToMemberId = childMember.id,
            assignedMemberName = childMember.name,
            createdByMemberId = childMember.id,
            createdByMemberName = childMember.name,
            createdByRole = "CHILD",
            isPersonal = true
        )
        repository.addOrUpdateFamilyAlarm(childPersonalAlarm)
        assertTrue(
            "Child must see their own personal alarm",
            repository.familyAlarms.value.any { it.id == childPersonalAlarm.id }
        )

        // 5. Switch back to Parent -> Parent sees their own personal alarm, child-assigned alarm, and child's own alarm
        repository.switchCurrentMember(parentMember)
        assertTrue(
            "Parent must see their personal alarm again after switching back",
            repository.familyAlarms.value.any { it.id == parentPersonalAlarm.id }
        )
        assertTrue(
            "Parent must see child's personal alarm",
            repository.familyAlarms.value.any { it.id == childPersonalAlarm.id }
        )

        // 6. Verify default FamilyAlarm soundType is SYSTEM_ALARM and native ringtone picker intent is valid
        assertEquals(
            "New alarms must default to native Android SYSTEM_ALARM soundType",
            com.example.notifications.AlarmAudioController.SOUND_SYSTEM_ALARM,
            parentPersonalAlarm.soundType
        )
        val pickerIntent = com.example.notifications.AlarmAudioController.createSystemRingtonePickerIntent(
            context = context,
            currentSoundType = com.example.notifications.AlarmAudioController.SOUND_SYSTEM_ALARM,
            currentCustomUri = "",
            isHebrew = true
        )
        assertEquals(
            android.media.RingtoneManager.ACTION_RINGTONE_PICKER,
            pickerIntent.action
        )
        assertEquals(
            android.media.RingtoneManager.TYPE_ALARM or android.media.RingtoneManager.TYPE_RINGTONE,
            pickerIntent.getIntExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, -1)
        )
    }

    @Test
    fun `test final alarm ui redesign ringtone picker categories and 7 second success banner`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Verify RingtonePickerTab categories and exact Hebrew labels
        val tabs = com.example.ui.screens.RingtonePickerTab.entries
        assertEquals(3, tabs.size)
        assertEquals("צלילי מערכת", com.example.ui.screens.RingtonePickerTab.SYSTEM_ALARMS.labelHe)
        assertEquals("צלילים נוספים", com.example.ui.screens.RingtonePickerTab.ADDITIONAL_SOUNDS.labelHe)
        assertEquals("צליל מותאם אישית", com.example.ui.screens.RingtonePickerTab.CUSTOM_SOUND.labelHe)
        assertFalse(
            "Custom sound tab must never use the forbidden label קבצים אישיים",
            tabs.any { it.labelHe == "קבצים אישיים" }
        )

        // 2. Verify 7-second auto-dismissing green success banner duration & state
        assertEquals(
            "Success banner auto-dismiss duration must be exactly 7,000 ms (7 seconds)",
            7_000L,
            com.example.ui.screens.ALARM_SAVED_SUCCESS_BANNER_DURATION_MS
        )

        val testAlarm = com.example.model.FamilyAlarm(
            id = "alarm_banner_test",
            title = "בוקר טוב רועי!",
            hour = 7,
            minute = 0,
            daysOfWeek = com.example.model.SchoolDay.allWeekDays
        )
        val nextRingSummary = com.example.ui.screens.formatNextTriggerRelativeSummary(testAlarm, isHebrew = true)
        assertTrue(
            "Next ring summary in Hebrew must include 07:00",
            nextRingSummary.contains("07:00")
        )
        val banner1 = com.example.ui.screens.AlarmSavedBannerState(
            id = 101L,
            headlineText = "השעון המעורר נשמר בהצלחה",
            nextRingSubtitleText = "הצלצול הבא: $nextRingSummary"
        )
        val banner2 = com.example.ui.screens.AlarmSavedBannerState(
            id = 102L,
            headlineText = "השעון המעורר נשמר בהצלחה",
            nextRingSubtitleText = "הצלצול הבא: $nextRingSummary"
        )
        assertNotEquals("Each saved alarm banner must have a distinct ID so older timers never dismiss newer banners", banner1.id, banner2.id)

        // 3. Verify clean ringtone display name and audio preview controller state
        val cleanDefaultName = com.example.notifications.AlarmAudioController.cleanSoundDisplayName(
            soundType = com.example.notifications.AlarmAudioController.SOUND_SYSTEM_ALARM,
            customTitle = "",
            isHebrew = true
        )
        assertTrue(cleanDefaultName.isNotBlank())

        val cleanCustomName = com.example.notifications.AlarmAudioController.cleanSoundDisplayName(
            soundType = com.example.notifications.AlarmAudioController.SOUND_CUSTOM_URI,
            customTitle = "Morning Sunrise",
            isHebrew = true
        )
        assertEquals("Morning Sunrise", cleanCustomName)

        // Ensure ringtone category helpers execute safely without throwing
        val systemTones = com.example.notifications.AlarmAudioController.getSystemAlarmCategoryRingtones(context)
        val additionalTones = com.example.notifications.AlarmAudioController.getAdditionalCategoryRingtones(context)
        assertNotNull(systemTones)
        assertNotNull(additionalTones)
    }

    @Test
    fun `test soft glass design system light dark theme text scale and persistence`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Verify AppThemeMode options and Hebrew labels ("בהיר", "כהה", "לפי הגדרות המכשיר")
        assertEquals("בהיר", com.example.model.AppThemeMode.LIGHT.labelHe)
        assertEquals("כהה", com.example.model.AppThemeMode.DARK.labelHe)
        assertEquals("לפי הגדרות המכשיר", com.example.model.AppThemeMode.SYSTEM.labelHe)

        // 2. Verify AppTextScale 5 presets and exact scale factors ("קטן", "רגיל", "גדול", "גדול מאוד", "ענק")
        val scales = com.example.model.AppTextScale.entries
        assertEquals(5, scales.size)
        assertEquals("קטן", com.example.model.AppTextScale.SMALL.labelHe)
        assertEquals(0.90f, com.example.model.AppTextScale.SMALL.scaleFactor, 0.001f)
        assertEquals("רגיל", com.example.model.AppTextScale.NORMAL.labelHe)
        assertEquals(1.00f, com.example.model.AppTextScale.NORMAL.scaleFactor, 0.001f)
        assertEquals("גדול", com.example.model.AppTextScale.LARGE.labelHe)
        assertEquals(1.12f, com.example.model.AppTextScale.LARGE.scaleFactor, 0.001f)
        assertEquals("גדול מאוד", com.example.model.AppTextScale.EXTRA_LARGE.labelHe)
        assertEquals(1.24f, com.example.model.AppTextScale.EXTRA_LARGE.scaleFactor, 0.001f)
        assertEquals("ענק", com.example.model.AppTextScale.HUGE.labelHe)
        assertEquals(1.36f, com.example.model.AppTextScale.HUGE.scaleFactor, 0.001f)

        // 3. Verify centralized scaledTypography scales font sizes and line heights proportionally
        val normalTypo = com.example.ui.theme.scaledTypography(com.example.model.AppTextScale.NORMAL.scaleFactor)
        val hugeTypo = com.example.ui.theme.scaledTypography(com.example.model.AppTextScale.HUGE.scaleFactor)
        val smallTypo = com.example.ui.theme.scaledTypography(com.example.model.AppTextScale.SMALL.scaleFactor)

        assertTrue(
            "HUGE text scale must produce larger bodyLarge fontSize than NORMAL",
            hugeTypo.bodyLarge.fontSize.value > normalTypo.bodyLarge.fontSize.value
        )
        assertTrue(
            "SMALL text scale must produce smaller bodyLarge fontSize than NORMAL",
            smallTypo.bodyLarge.fontSize.value < normalTypo.bodyLarge.fontSize.value
        )
        assertEquals(
            normalTypo.bodyLarge.fontSize.value * 1.36f,
            hugeTypo.bodyLarge.fontSize.value,
            0.05f
        )

        // 4. Verify Soft Glass Light and Dark color tokens
        assertFalse(com.example.ui.theme.LightSoftGlassColors.isDark)
        assertTrue(com.example.ui.theme.DarkSoftGlassColors.isDark)
        assertEquals(
            androidx.compose.ui.graphics.Color(0xFF0B1120),
            com.example.ui.theme.DarkSoftGlassColors.screenBackgroundTop
        )
        assertEquals(
            androidx.compose.ui.graphics.Color(0xFF1E293B),
            com.example.ui.theme.DarkSoftGlassColors.cardElevated
        )
        assertEquals(
            androidx.compose.ui.graphics.Color(0xFF60A5FA),
            com.example.ui.theme.DarkSoftGlassColors.primaryAccent
        )

        // 5. Verify fresh-install default is Velvet Light (AppThemeMode.LIGHT) and SharedPreferences persistence
        context.getSharedPreferences("family_tasks_ui_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val freshRepo = FamilyRepository(context = context)
        assertEquals(
            "Default themeMode on fresh install MUST be Velvet Light (AppThemeMode.LIGHT)",
            com.example.model.AppThemeMode.LIGHT,
            freshRepo.themeMode.value
        )

        val repo1 = FamilyRepository(context = context)
        repo1.setThemeMode(com.example.model.AppThemeMode.DARK)
        repo1.setAppTextScale(com.example.model.AppTextScale.EXTRA_LARGE)
        assertEquals(com.example.model.AppThemeMode.DARK, repo1.themeMode.value)
        assertEquals(com.example.model.AppTextScale.EXTRA_LARGE, repo1.appTextScale.value)

        // Creating a new FamilyRepository attached to the same context restores the persisted preferences
        val repo2 = FamilyRepository(context = context)
        assertEquals(
            "Persisted DARK themeMode must be restored on app launch",
            com.example.model.AppThemeMode.DARK,
            repo2.themeMode.value
        )
        assertEquals(
            "Persisted EXTRA_LARGE appTextScale must be restored on app launch",
            com.example.model.AppTextScale.EXTRA_LARGE,
            repo2.appTextScale.value
        )

        // Reset back to Velvet Light default for cleanliness
        repo2.setThemeMode(com.example.model.AppThemeMode.LIGHT)
        repo2.setAppTextScale(com.example.model.AppTextScale.NORMAL)
        assertEquals(com.example.model.AppThemeMode.LIGHT, repo2.themeMode.value)
        assertEquals(com.example.model.AppTextScale.NORMAL, repo2.appTextScale.value)

        // 6. Verify Israeli School Timetable strictly uses Sunday through Friday (no Saturday)
        val schoolDays = com.example.model.SchoolDay.schoolDays
        assertEquals(6, schoolDays.size)
        assertFalse("School timetable days must never include Saturday", schoolDays.contains(com.example.model.SchoolDay.SATURDAY))
        assertEquals(com.example.model.SchoolDay.SUNDAY, schoolDays.first())
        assertEquals(com.example.model.SchoolDay.FRIDAY, schoolDays.last())
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun `test soft glass visual redesign rendered screens and components`() = androidx.compose.ui.test.runComposeUiTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = FamilyRepository(context = context)
        val members = repository.members.value
        val currentMember = repository.currentMember.value
        val tasks = repository.tasks.value
        val periods = repository.schedulePeriods.value
        val alarms = repository.familyAlarms.value

        val activeScreenState = androidx.compose.runtime.mutableStateOf(com.example.model.AppScreen.HOME)

        setContent {
            val activeScreen = activeScreenState.value
            com.example.ui.theme.MyApplicationTheme(
                themeMode = com.example.model.AppThemeMode.LIGHT,
                textScale = com.example.model.AppTextScale.NORMAL
            ) {
                androidx.compose.foundation.layout.Column {
                    com.example.ui.components.TopFamilyBar(
                        currentMember = currentMember,
                        unreadNotificationsCount = 2,
                        isDriveSyncActive = true,
                        currentLanguage = com.example.model.AppLanguage.HEBREW,
                        familyTitle = repository.familyName.value,
                        onLanguageToggle = {},
                        onOpenGemini = {},
                        onOpenNotifications = {},
                        onOpenProfile = {},
                        onOpenSettings = {}
                    )

                    androidx.compose.foundation.layout.Box(
                        modifier = androidx.compose.ui.Modifier.weight(1f)
                    ) {
                        when (activeScreen) {
                            com.example.model.AppScreen.HOME -> {
                                com.example.ui.screens.TasksScreen(
                                    tasks = tasks,
                                    members = members,
                                    currentMember = currentMember,
                                    selectedMemberFilter = null,
                                    isHebrew = true,
                                    onFilterChange = {},
                                    onToggleTask = {},
                                    onEditTask = {},
                                    onDeleteTask = {},
                                    onSendNudge = {},
                                    onAddNewTask = {},
                                    periods = periods,
                                    alarms = alarms
                                )
                            }
                            com.example.model.AppScreen.CALENDAR -> {
                                com.example.ui.screens.CalendarScreen(
                                    tasks = tasks,
                                    periods = periods,
                                    members = members,
                                    currentMember = currentMember,
                                    calendarMode = com.example.viewmodel.CalendarViewMode.MONTH,
                                    selectedDate = LocalDate.now(),
                                    firstDayOfWeek = com.example.model.FirstDayOfWeek.SUNDAY,
                                    isHebrew = true,
                                    onModeChange = {},
                                    onDateSelect = {},
                                    onToggleTask = {},
                                    onEditTask = {},
                                    onDeleteTask = {},
                                    onSendNudge = {},
                                    onAddNewTask = {}
                                )
                            }
                            com.example.model.AppScreen.TIMETABLE -> {
                                com.example.ui.screens.TimetableScreen(
                                    periods = periods,
                                    members = members,
                                    currentMember = currentMember,
                                    selectedChildId = members.firstOrNull { !it.isParent }?.id ?: currentMember.id,
                                    timetableViewMode = com.example.model.TimetableViewMode.DAILY_LIST,
                                    isHebrew = true,
                                    onSelectChild = {},
                                    onViewModeChange = {},
                                    onAddPeriod = {},
                                    onEditPeriod = {},
                                    onDeletePeriod = {},
                                    onTestAlert = { _, _ -> }
                                )
                            }
                            com.example.model.AppScreen.SETTINGS -> {
                                com.example.ui.screens.SettingsScreen(
                                    familyName = repository.familyName.value,
                                    currentMember = currentMember,
                                    isGoogleSignedIn = true,
                                    googleEmail = "parent@family.com",
                                    cloudFamilyGroup = repository.cloudFamilyGroup.value,
                                    autoSyncCalendar = true,
                                    autoSyncDrive = true,
                                    appLanguage = com.example.model.AppLanguage.HEBREW,
                                    themeMode = com.example.model.AppThemeMode.LIGHT,
                                    firstDayOfWeek = com.example.model.FirstDayOfWeek.SUNDAY,
                                    biometricLockEnabled = false,
                                    tenMinuteClassAlertsEnabled = true,
                                    onEditFamilyName = {},
                                    onOpenAdminDashboard = {},
                                    onOpenGoogleSignIn = {},
                                    onSignOutGoogle = {},
                                    onAutoSyncCalendarChange = {},
                                    onAutoSyncDriveChange = {},
                                    onLanguageChange = {},
                                    onThemeModeChange = {},
                                    onFirstDayOfWeekChange = {},
                                    onBiometricLockChange = {},
                                    onLockNow = {},
                                    onTenMinuteAlertsChange = {},
                                    appTextScale = com.example.model.AppTextScale.NORMAL,
                                    onTextScaleChange = {}
                                )
                            }
                            else -> {}
                        }
                    }

                    com.example.ui.components.FamilyBottomNavigation(
                        currentScreen = activeScreen,
                        isHebrew = true,
                        isParent = currentMember.isParent,
                        onNavigate = { activeScreenState.value = it }
                    )
                }
            }
        }

        waitForIdle()

        // 1. Verify TopFamilyBar & FamilyBottomNavigation Soft Glass bars are rendered
        onNodeWithTag("top_family_bar").assertExists()
        onNodeWithTag("bottom_navigation_bar").assertExists()
        onNodeWithTag("nav_home").assertExists()
        onNodeWithTag("nav_calendar").assertExists()
        onNodeWithTag("nav_timetable").assertExists()
        onNodeWithTag("nav_alarms").assertExists()
        onNodeWithTag("nav_files").assertExists()

        // 2. Verify Home / TasksScreen Velvet Edition hero banner, Bento stat cards, and single primary button (no duplicate FAB)
        onNodeWithTag("tasks_screen").assertExists()
        onNodeWithTag("our_family_banner_card").assertExists()
        onNodeWithTag("stat_card_points").assertExists()
        onNodeWithTag("stat_card_completed").assertExists()
        onNodeWithTag("add_task_fab").assertDoesNotExist()
        onNodeWithTag("tasks_screen").performScrollToNode(hasTestTag("add_task_primary_button"))
        onNodeWithTag("add_task_primary_button").assertExists()

        // 3. Navigate to CalendarScreen and verify Velvet Edition month grid and header
        activeScreenState.value = com.example.model.AppScreen.CALENDAR
        waitForIdle()
        onNodeWithTag("calendar_screen").assertExists()
        onNodeWithTag("calendar_month_grid").assertExists()
        onNodeWithTag("calendar_selected_date_header").assertExists()

        // 4. Navigate to TimetableScreen and verify Velvet Edition timetable view, single Add Period button, and day selector
        activeScreenState.value = com.example.model.AppScreen.TIMETABLE
        waitForIdle()
        onNodeWithTag("timetable_screen").assertExists()
        onNodeWithTag("add_period_fab").assertExists()
        onNodeWithTag("day_tab_SUNDAY").assertExists()
        onNodeWithTag("day_tab_FRIDAY").assertExists()
        onNodeWithTag("day_tab_SATURDAY").assertDoesNotExist()

        // 5. Navigate to SettingsScreen and verify Soft Glass theme mode and text scale live preview
        activeScreenState.value = com.example.model.AppScreen.SETTINGS
        waitForIdle()
        onNodeWithTag("settings_screen").assertExists()
        onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("theme_mode_LIGHT"))
        onNodeWithTag("theme_mode_LIGHT").assertExists()
        onNodeWithTag("theme_mode_DARK").assertExists()
        onNodeWithTag("theme_mode_SYSTEM").assertExists()
        onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("settings_text_scale_preview"))
        onNodeWithTag("settings_text_scale_preview").assertExists()
        onNodeWithTag("text_scale_LARGE").assertExists()
    }

    @Test
    fun `test normal local alarm background survival intent filter scheme matching and permission restore`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Verify FamilyAlarmReceiver intent-filter matches trigger broadcast with familytasks:// data URI
        //    (Required by Android 15/16 targetSdk=36 Safer Intents ENFORCE_INTENTS_TO_MATCH_INTENT_FILTERS)
        val localAlarm = com.example.model.FamilyAlarm(
            id = "alarm_local_closed_app_test",
            title = "השכמה לבית ספר",
            hour = 7,
            minute = 15,
            daysOfWeek = com.example.model.SchoolDay.allWeekDays,
            isEnabled = true
        )
        val triggerIntent = com.example.notifications.FamilyAlarmScheduler.buildTriggerBroadcastIntent(
            context = context,
            alarm = localAlarm,
            isSnooze = false
        )
        val matchedReceivers = context.packageManager.queryBroadcastReceivers(triggerIntent, 0)
        assertTrue(
            "FamilyAlarmReceiver intent-filter MUST match trigger intent with familytasks:// data URI under targetSdk=36 Safer Intents",
            matchedReceivers.any { it.activityInfo?.name == com.example.notifications.FamilyAlarmReceiver::class.java.name }
        )

        // 2. Create normal local alarm via FamilyRepository and verify it is scheduled in AlarmManager & persisted on disk
        val repo1 = FamilyRepository(context = null)
        repo1.attachApplicationContext(context)
        assertTrue(repo1.addOrUpdateFamilyAlarm(localAlarm))
        assertTrue(
            "Normal local alarm must be scheduled in AlarmManager",
            com.example.notifications.FamilyAlarmScheduler.isAlarmLocallyScheduled(context, localAlarm.id)
        )
        assertTrue(
            "Normal local alarm must be persisted synchronously for boot/background restore",
            com.example.notifications.FamilyAlarmScheduler.loadPersistedAlarms(context).any { it.id == localAlarm.id }
        )

        // 3. Simulate app process restart: a brand-new FamilyRepository instance starts with starter demo alarms
        //    and attaches application context. Verify it does NOT wipe or cancel the user's persisted local alarm!
        val repo2 = FamilyRepository(context = null)
        repo2.attachApplicationContext(context)
        assertTrue(
            "User-created local alarm must survive app restart and starter demo sync in AlarmManager",
            com.example.notifications.FamilyAlarmScheduler.isAlarmLocallyScheduled(context, localAlarm.id)
        )
        assertTrue(
            "User-created local alarm must remain visible in repository after app restart",
            repo2.familyAlarms.value.any { it.id == localAlarm.id }
        )

        // 4. Simulate Android granting SCHEDULE_EXACT_ALARM or rebooting while app is closed
        val bootReceiver = com.example.notifications.FamilyAlarmBootReceiver()
        bootReceiver.onReceive(
            context,
            android.content.Intent(android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
        )
        assertTrue(
            "Normal local alarm must be rescheduled when SCHEDULE_EXACT_ALARM permission is granted",
            com.example.notifications.FamilyAlarmScheduler.isAlarmLocallyScheduled(context, localAlarm.id)
        )
    }

    @Test
    fun `test full screen alarm presentation intent uniqueness and fcm background alarm synchronization`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // PART A: Full-screen alarm presentation verification
        val launchIntentA = com.example.notifications.FamilyAlarmRingingActivity.createLaunchIntent(
            context = context,
            alarmId = "alarm_child_1",
            title = "בוקר טוב!",
            hour = 7,
            minute = 0,
            snoozeMinutes = 5
        )
        val launchIntentB = com.example.notifications.FamilyAlarmRingingActivity.createLaunchIntent(
            context = context,
            alarmId = "alarm_child_2",
            title = "בית ספר",
            hour = 7,
            minute = 30,
            snoozeMinutes = 10
        )
        assertNotEquals(
            "Each alarm full-screen activity launch intent must have a distinct action so PendingIntents never collide",
            launchIntentA.action,
            launchIntentB.action
        )
        assertNotEquals(
            "Each alarm full-screen activity launch intent must have a distinct data URI",
            launchIntentA.data,
            launchIntentB.data
        )
        assertEquals("familytasks", launchIntentA.data?.scheme)

        val notification = com.example.notifications.FamilyAlarmRingingService.buildStaticAlarmNotification(
            context = context,
            alarmId = "alarm_child_1",
            title = "בוקר טוב!",
            timeFormatted = "07:00",
            hour = 7,
            minute = 0,
            soundType = com.example.notifications.AlarmAudioController.SOUND_SYSTEM_ALARM,
            customUri = "",
            vibrateEnabled = true,
            snoozeMinutes = 5,
            volumePercent = 85
        )
        assertNotNull("Full-screen alarm notification MUST include a non-null fullScreenIntent", notification.fullScreenIntent)
        assertEquals(android.app.Notification.CATEGORY_ALARM, notification.category)
        assertEquals(android.app.Notification.VISIBILITY_PUBLIC, notification.visibility)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channel = nm.getNotificationChannel(com.example.notifications.FamilyAlarmRingingService.ALARM_CHANNEL_ID)
        assertNotNull("High-importance full-screen alarm notification channel must be created", channel)
        assertEquals(
            "Alarm notification channel must have IMPORTANCE_HIGH for full-screen presentation",
            android.app.NotificationManager.IMPORTANCE_HIGH,
            channel?.importance
        )
        assertNull(
            "Alarm notification channel sound must be null so AlarmAudioController controls alarm ringtone without conflict",
            channel?.sound
        )

        // PART B: FCM data-only background alarm synchronization verification
        com.example.notifications.FcmTokenManager.clearAllDispatchedEventsForTesting()
        com.example.notifications.FamilyAlarmCloudSyncWorker.clearCompletedSyncEventsForTesting(context)
        com.example.notifications.FcmTokenManager.cacheTokenLocally(
            context = context,
            token = "test_fcm_child_token_12345",
            uid = "uid_child_roey",
            familyId = "fam_test_99",
            role = com.example.model.UserRole.CHILD
        )
        assertEquals("uid_child_roey", com.example.notifications.FcmTokenManager.getCachedUid(context))
        assertEquals("fam_test_99", com.example.notifications.FcmTokenManager.getCachedFamilyId(context))
        assertEquals("CHILD", com.example.notifications.FcmTokenManager.getCachedRole(context))

        // Verify that enqueuing via WorkManager does NOT mark eventKey as completed before the worker finishes
        val pendingWorkerEventKey = "alarm_create:alarm_699:uid_child_roey:1700000000"
        val pendingPayload = mapOf(
            "eventKey" to pendingWorkerEventKey,
            "eventType" to com.example.notifications.FcmTokenManager.EVENT_ALARM_SYNC_REQUIRED,
            "action" to "CREATE",
            "familyId" to "fam_test_99",
            "alarmId" to "alarm_699",
            "targetUid" to "uid_child_roey",
            "actorUid" to "uid_parent_dad",
            "updatedAt" to "1700000000"
        )
        val enqueuedPending = com.example.notifications.FamilyMessagingService.handleRemoteMessagePayload(
            context = context,
            data = pendingPayload,
            signedInUidOverride = "uid_child_roey"
        )
        assertTrue("ALARM_SYNC_REQUIRED must enqueue persistent WorkManager work", enqueuedPending)
        assertFalse(
            "ALARM_SYNC_REQUIRED eventKey must NOT be marked completed before reconciliation finishes",
            com.example.notifications.FamilyAlarmCloudSyncWorker.isSyncEventCompleted(context, pendingWorkerEventKey)
        )
        com.example.notifications.FamilyAlarmCloudSyncWorker.markSyncEventCompleted(context, pendingWorkerEventKey)
        assertTrue(
            "ALARM_SYNC_REQUIRED eventKey must be marked completed after reconciliation succeeds",
            com.example.notifications.FamilyAlarmCloudSyncWorker.isSyncEventCompleted(context, pendingWorkerEventKey)
        )

        var reconciledFamilyId = ""
        var reconciledAlarmId = ""
        var reconciledAction = ""
        var reconcileCallCount = 0

        val fcmDataPayload = mapOf(
            "eventKey" to "alarm_create:alarm_700:uid_child_roey:1700000001",
            "eventType" to com.example.notifications.FcmTokenManager.EVENT_ALARM_SYNC_REQUIRED,
            "action" to "CREATE",
            "familyId" to "fam_test_99",
            "alarmId" to "alarm_700",
            "targetUid" to "uid_child_roey",
            "actorUid" to "uid_parent_dad",
            "updatedAt" to "1700000001"
        )

        val processedFirst = com.example.notifications.FamilyMessagingService.handleRemoteMessagePayload(
            context = context,
            data = fcmDataPayload,
            signedInUidOverride = "uid_child_roey",
            onAlarmReconcileRequested = { famId, alId, act ->
                reconciledFamilyId = famId
                reconciledAlarmId = alId
                reconciledAction = act
                reconcileCallCount++
            }
        )
        assertTrue("Valid ALARM_SYNC_REQUIRED data message for target child must be processed", processedFirst)
        assertEquals(1, reconcileCallCount)
        assertEquals("fam_test_99", reconciledFamilyId)
        assertEquals("alarm_700", reconciledAlarmId)
        assertEquals("CREATE", reconciledAction)

        // Duplicate delivery with the same eventKey must be ignored idempotently after completion
        val processedDuplicate = com.example.notifications.FamilyMessagingService.handleRemoteMessagePayload(
            context = context,
            data = fcmDataPayload,
            signedInUidOverride = "uid_child_roey",
            onAlarmReconcileRequested = { _, _, _ ->
                reconcileCallCount++
            }
        )
        assertFalse("Duplicate ALARM_SYNC_REQUIRED message with same completed eventKey must be ignored", processedDuplicate)
        assertEquals(1, reconcileCallCount)

        // Message targeted at a different user or sent by self must be ignored
        val selfPayload = fcmDataPayload.toMutableMap().apply {
            put("eventKey", "alarm_create:alarm_701:uid_parent_dad:1700000002")
            put("targetUid", "uid_parent_dad")
            put("actorUid", "uid_parent_dad")
        }
        val processedSelf = com.example.notifications.FamilyMessagingService.handleRemoteMessagePayload(
            context = context,
            data = selfPayload,
            signedInUidOverride = "uid_parent_dad",
            onAlarmReconcileRequested = { _, _, _ ->
                reconcileCallCount++
            }
        )
        assertFalse("Actor must not process self-sent ALARM_SYNC_REQUIRED push", processedSelf)
        assertEquals(1, reconcileCallCount)

        // Logout clears cached FCM token and session metadata
        com.example.notifications.FcmTokenManager.unregisterCurrentDeviceTokenAsync(context = context)
        assertNull(com.example.notifications.FcmTokenManager.getCachedToken(context))
        assertNull(com.example.notifications.FcmTokenManager.getCachedUid(context))
        assertNull(com.example.notifications.FcmTokenManager.getCachedFamilyId(context))
    }

    @Test
    fun `test family points management add deduct reset history child protection persistence task approval and concurrency`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("family_tasks_ui_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val repository = FamilyRepository(context = context)
        val parentDad = repository.members.value.first { it.isParent }
        val kidNoam = repository.members.value.first { it.id == "m_kid1" }
        val kidMaya = repository.members.value.first { it.id == "m_kid2" }

        repository.switchCurrentMember(parentDad)
        val initialNoamPoints = kidNoam.earnedPoints
        val initialMayaPoints = kidMaya.earnedPoints

        // Scenario 1: Parent adds points to a user and balance updates immediately
        val addRes = repository.addMemberPointsAsync(
            memberId = kidNoam.id,
            amount = 35,
            reason = "עזרה בבית",
            operationId = "op_test_add_1"
        )
        assertTrue("Parent addMemberPointsAsync must succeed", addRes.isSuccess)
        val afterAddNoam = repository.members.value.first { it.id == kidNoam.id }.earnedPoints
        assertEquals(initialNoamPoints + 35, afterAddNoam)

        // Scenario 2: Parent deducts points from a user and balance updates (and blocks negative balance)
        val deductRes = repository.deductMemberPointsAsync(
            memberId = kidNoam.id,
            amount = 15,
            reason = "הפחתה מבוקרת",
            operationId = "op_test_deduct_1"
        )
        assertTrue("Parent deductMemberPointsAsync must succeed", deductRes.isSuccess)
        val afterDeductNoam = repository.members.value.first { it.id == kidNoam.id }.earnedPoints
        assertEquals(initialNoamPoints + 20, afterDeductNoam)

        // Negative balance attempt must be rejected and preserve current balance
        val negativeAttempt = repository.deductMemberPointsAsync(
            memberId = kidNoam.id,
            amount = afterDeductNoam + 500,
            reason = "ניסיון יתרה שלילית",
            operationId = "op_test_deduct_negative"
        )
        assertTrue("Deduction exceeding current balance must fail by default", negativeAttempt.isFailure)
        assertEquals(
            "Balance must remain unchanged after rejected negative deduction",
            afterDeductNoam,
            repository.members.value.first { it.id == kidNoam.id }.earnedPoints
        )

        // Scenario 3: Parent resets a user's points and ONLY that user's balance becomes 0
        val resetRes = repository.resetMemberPointsAsync(
            memberId = kidNoam.id,
            reason = "איפוס נקודות תקופתי",
            operationId = "op_test_reset_1"
        )
        assertTrue("Parent resetMemberPointsAsync must succeed", resetRes.isSuccess)
        assertEquals(0, repository.members.value.first { it.id == kidNoam.id }.earnedPoints)
        assertEquals(
            "Other family members' points must NOT be affected by resetting one member",
            initialMayaPoints,
            repository.members.value.first { it.id == kidMaya.id }.earnedPoints
        )
        assertTrue("Task list must NOT be deleted when resetting member points", repository.tasks.value.isNotEmpty())

        // Scenario 4: Points history updates on every action with full metadata and filtering
        val noamHistory = repository.getPointsHistoryForMember(memberId = kidNoam.id)
        assertEquals("History must contain 3 entries (ADD, DEDUCT, RESET)", 3, noamHistory.size)
        assertTrue(noamHistory.any { it.actionType == com.example.model.PointsActionType.ADD && it.pointsDelta == 35 && it.reason == "עזרה בבית" })
        assertTrue(noamHistory.any { it.actionType == com.example.model.PointsActionType.DEDUCT && it.pointsDelta == -15 && it.previousBalance == initialNoamPoints + 35 && it.newBalance == initialNoamPoints + 20 })
        assertTrue(noamHistory.any { it.actionType == com.example.model.PointsActionType.RESET && it.previousBalance == initialNoamPoints + 20 && it.newBalance == 0 && it.performedByName == parentDad.name })
        assertEquals(
            1,
            repository.getPointsHistoryForMember(
                memberId = kidNoam.id,
                actionFilter = com.example.model.PointsActionType.RESET
            ).size
        )

        // Scenario 5: Child cannot access points management or modify points
        repository.switchCurrentMember(kidNoam)
        assertFalse("Child member must not have parent privileges", repository.currentMember.value.isParent)
        assertFalse("Child addMemberPoints must be blocked", repository.addMemberPoints(kidNoam.id, 100, "Hack"))
        assertFalse("Child deductMemberPoints must be blocked", repository.deductMemberPoints(kidMaya.id, 50, "Hack"))
        assertFalse("Child resetMemberPoints must be blocked", repository.resetMemberPoints(kidMaya.id, "Hack"))
        assertEquals(0, repository.members.value.first { it.id == kidNoam.id }.earnedPoints)

        // Scenario 7: Completing a task and parent approval continues to award points on top of current balance
        repository.switchCurrentMember(parentDad)
        repository.addMemberPoints(kidNoam.id, 50, "בונוס התחלתי", "op_test_bonus_50")
        assertEquals(50, repository.members.value.first { it.id == kidNoam.id }.earnedPoints)

        val customTask = com.example.model.FamilyTask(
            id = "task_pts_compat_test",
            title = "סידור חדר שינה",
            assignedToMemberId = kidNoam.id,
            dateEpochDay = LocalDate.now().toEpochDay(),
            points = 25,
            isCompleted = false,
            approvedByParent = false
        )
        assertTrue(repository.addTask(customTask))

        // Child marks task completed -> pending approval (points still 50)
        repository.switchCurrentMember(kidNoam)
        assertTrue(repository.toggleTaskCompletion(customTask.id))
        assertEquals(50, repository.members.value.first { it.id == kidNoam.id }.earnedPoints)

        // Parent approves task -> points become 50 + 25 = 75 and TASK_EARNED entry is logged
        repository.switchCurrentMember(parentDad)
        assertTrue(repository.approveTaskCompletion(customTask.id))
        assertEquals(75, repository.members.value.first { it.id == kidNoam.id }.earnedPoints)
        assertTrue(
            "Task approval must record a TASK_EARNED entry in pointsHistory",
            repository.pointsHistory.value.any {
                it.memberId == kidNoam.id &&
                    it.actionType == com.example.model.PointsActionType.TASK_EARNED &&
                    it.pointsDelta == 25 &&
                    it.newBalance == 75
            }
        )

        // Scenario 8: Two concurrent point changes do NOT overwrite each other (serialized via pointsMutex),
        // and duplicate operationId is idempotent
        var res1: Result<com.example.model.PointsHistoryEntry>? = null
        var res2: Result<com.example.model.PointsHistoryEntry>? = null
        val t1 = Thread {
            res1 = kotlinx.coroutines.runBlocking {
                repository.addMemberPointsAsync(kidNoam.id, 10, "בונוס מקבילי 1", "op_concurrent_1")
            }
        }
        val t2 = Thread {
            res2 = kotlinx.coroutines.runBlocking {
                repository.addMemberPointsAsync(kidNoam.id, 20, "בונוס מקבילי 2", "op_concurrent_2")
            }
        }
        t1.start()
        t2.start()
        t1.join()
        t2.join()
        assertTrue(res1?.isSuccess == true)
        assertTrue(res2?.isSuccess == true)
        assertEquals(
            "Both concurrent additions (+10 and +20) must be preserved without race condition (75 + 30 = 105)",
            105,
            repository.members.value.first { it.id == kidNoam.id }.earnedPoints
        )

        // Duplicate operationId must not apply a second time
        val duplicateRes = repository.addMemberPointsAsync(kidNoam.id, 20, "בונוס מקבילי 2", "op_concurrent_2")
        assertTrue(duplicateRes.isSuccess)
        assertEquals(
            "Duplicate operationId must be idempotent and not double-add points",
            105,
            repository.members.value.first { it.id == kidNoam.id }.earnedPoints
        )

        // Scenario 6: Closing and reopening the app (new FamilyRepository with same Context) preserves balances & history
        val restartedRepository = FamilyRepository(context = context)
        assertEquals(
            "Member points balance must survive app restart without being overwritten",
            105,
            restartedRepository.members.value.first { it.id == kidNoam.id }.earnedPoints
        )
        assertTrue(
            "Points history must survive app restart",
            restartedRepository.pointsHistory.value.count { it.memberId == kidNoam.id } >= 6
        )
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun `test family points management ui dialogs and full screen alarm permission banner`() = androidx.compose.ui.test.runComposeUiTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("family_tasks_ui_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val repository = FamilyRepository(context = context)
        val parentMember = repository.members.value.first { it.isParent }
        repository.switchCurrentMember(parentMember)

        // Scenario 9: Test Full-Screen Alarm Permission check when missing (false) and when granted (true)
        com.example.notifications.FamilyAlarmScheduler.fullScreenPermissionOverrideForTesting = false
        assertFalse(com.example.notifications.FamilyAlarmScheduler.canUseFullScreenIntent(context))

        val showAlarmScreenState = androidx.compose.runtime.mutableStateOf(true)
        setContent {
            val members = repository.members.value
            val currentMember = repository.currentMember.value
            val pointsHistory = repository.pointsHistory.value
            val isProcessing = repository.isProcessingPointsOperation.value
            val pointsError = repository.pointsOperationError.value
            val alarms = repository.familyAlarms.value

            com.example.ui.theme.MyApplicationTheme(
                themeMode = com.example.model.AppThemeMode.LIGHT,
                textScale = com.example.model.AppTextScale.NORMAL
            ) {
                if (showAlarmScreenState.value) {
                    com.example.ui.screens.FamilyAlarmScreen(
                        alarms = alarms,
                        members = members,
                        currentMember = currentMember,
                        isHebrew = true,
                        onSaveAlarm = { repository.addOrUpdateFamilyAlarm(it) },
                        onToggleAlarm = { repository.toggleFamilyAlarm(it) },
                        onDeleteAlarm = { repository.deleteFamilyAlarm(it) },
                        onShowToast = {}
                    )
                } else {
                    com.example.ui.screens.FamilyPointsManagementScreen(
                        members = members,
                        currentMember = currentMember,
                        pointsHistory = pointsHistory,
                        isProcessingPointsOperation = isProcessing,
                        pointsOperationError = pointsError,
                        isHebrew = true,
                        onAddPoints = { mId, amt, rsn, opId -> repository.addMemberPoints(mId, amt, rsn, opId) },
                        onDeductPoints = { mId, amt, rsn, opId -> repository.deductMemberPoints(mId, amt, rsn, false, opId) },
                        onResetPoints = { mId, rsn, opId -> repository.resetMemberPoints(mId, rsn, opId) },
                        onClearError = { repository.clearPointsOperationError() },
                        onNavigateBack = {}
                    )
                }
            }
        }

        waitForIdle()

        // Verify Full-Screen Alarm Permission banner and button appear when permission is missing
        onNodeWithTag("alarm_screen_fullscreen_permission_banner").assertExists()
        onNodeWithTag("alarm_screen_fullscreen_permission_text").assertExists()
        onNodeWithTag("alarm_screen_grant_fullscreen_permission_btn").assertExists()

        // Switch to FamilyPointsManagementScreen and verify member cards, Add, Deduct, and Reset buttons
        showAlarmScreenState.value = false
        waitForIdle()

        onNodeWithTag("family_points_management_screen").assertExists()
        onNodeWithTag("points_management_title").assertExists()
        onNodeWithTag("points_member_card_m_kid1").assertExists()
        onNodeWithTag("points_member_balance_m_kid1").assertExists()
        onNodeWithTag("manage_points_btn_m_kid1").assertExists()
        onNodeWithTag("add_points_btn_m_kid1").assertExists()
        onNodeWithTag("deduct_points_btn_m_kid1").assertExists()
        onNodeWithTag("reset_points_btn_m_kid1").assertExists()

        // Verify when permission is granted (true), canUseFullScreenIntent returns true
        com.example.notifications.FamilyAlarmScheduler.fullScreenPermissionOverrideForTesting = true
        assertTrue(com.example.notifications.FamilyAlarmScheduler.canUseFullScreenIntent(context))

        // Restore testing override
        com.example.notifications.FamilyAlarmScheduler.fullScreenPermissionOverrideForTesting = null
    }

    @Test
    fun `test security audit fix prevents duplicate task points and enforces history consistency`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("family_tasks_ui_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val repository = FamilyRepository(context = context)
        val dadParent = repository.members.value.first { it.id == "m_dad" }
        val momParent = repository.members.value.first { it.id == "m_mom" }
        val kidNoam = repository.members.value.first { it.id == "m_kid1" }

        repository.switchCurrentMember(dadParent)
        // Reset Noam's balance to 0 as a clean baseline
        assertTrue(repository.resetMemberPointsAsync(kidNoam.id, "איפוס התחלתי לבדיקה", "op_audit_init_rst").isSuccess)
        assertEquals(0, repository.members.value.first { it.id == kidNoam.id }.earnedPoints)

        // 1. Create a task worth 35 points assigned to Noam, child marks completed, parent approves once
        val taskSingle = FamilyTask(
            id = "task_audit_single",
            title = "שטיפת כלים",
            assignedToMemberId = kidNoam.id,
            dateEpochDay = LocalDate.now().toEpochDay(),
            points = 35,
            isCompleted = false,
            approvedByParent = false
        )
        assertTrue(repository.addTask(taskSingle))

        repository.switchCurrentMember(kidNoam)
        assertTrue(repository.toggleTaskCompletionAsync(taskSingle.id).isSuccess)
        assertEquals(0, repository.members.value.first { it.id == kidNoam.id }.earnedPoints)

        repository.switchCurrentMember(dadParent)
        val firstApprove = repository.approveTaskCompletionAsync(taskSingle.id)
        assertTrue(firstApprove.isSuccess)
        assertEquals(35, repository.members.value.first { it.id == kidNoam.id }.earnedPoints)

        // Verify deterministic operationId pts_task_{taskId} in points_history
        val singleTaskEntriesAfterFirst = repository.pointsHistory.value.filter { it.taskId == taskSingle.id }
        assertEquals(1, singleTaskEntriesAfterFirst.size)
        assertEquals("pts_task_${taskSingle.id}", singleTaskEntriesAfterFirst.first().operationId)
        assertEquals(0, singleTaskEntriesAfterFirst.first().previousBalance)
        assertEquals(35, singleTaskEntriesAfterFirst.first().pointsDelta)
        assertEquals(35, singleTaskEntriesAfterFirst.first().newBalance)

        // 2. Duplicate approval attempt on the same task -> MUST NOT award additional points or duplicate history
        val duplicateApprove1 = repository.approveTaskCompletionAsync(taskSingle.id)
        assertTrue(duplicateApprove1.isSuccess)
        assertEquals(
            "Duplicate approval must NOT award points a second time",
            35,
            repository.members.value.first { it.id == kidNoam.id }.earnedPoints
        )
        assertEquals(
            "Duplicate approval must NOT create a second TASK_EARNED history entry",
            1,
            repository.pointsHistory.value.count { it.taskId == taskSingle.id }
        )

        // Even if parent uncompletes and re-completes/re-approves the already-rewarded task, no extra points are awarded
        assertTrue(repository.toggleTaskCompletionAsync(taskSingle.id).isSuccess) // uncomplete
        assertEquals(35, repository.members.value.first { it.id == kidNoam.id }.earnedPoints)
        assertTrue(repository.toggleTaskCompletionAsync(taskSingle.id).isSuccess) // re-complete by parent
        assertEquals(
            "Re-completing an already-rewarded task must NOT award duplicate points",
            35,
            repository.members.value.first { it.id == kidNoam.id }.earnedPoints
        )
        assertEquals(1, repository.pointsHistory.value.count { it.taskId == taskSingle.id })

        // 3. Concurrent approval by two parents (Dad and Mom) on a new task worth 40 points -> credited ONCE only
        val taskConcurrent = FamilyTask(
            id = "task_audit_concurrent",
            title = "סידור הסלון",
            assignedToMemberId = kidNoam.id,
            dateEpochDay = LocalDate.now().toEpochDay(),
            points = 40,
            isCompleted = true,
            approvedByParent = false
        )
        assertTrue(repository.addTask(taskConcurrent))

        val startLatch = java.util.concurrent.CountDownLatch(1)
        var dadResult: Result<FamilyTask>? = null
        var momResult: Result<FamilyTask>? = null
        val tDad = Thread {
            startLatch.await()
            dadResult = kotlinx.coroutines.runBlocking {
                repository.approveTaskCompletionAsync(taskConcurrent.id)
            }
        }
        val tMom = Thread {
            startLatch.await()
            momResult = kotlinx.coroutines.runBlocking {
                repository.switchCurrentMember(momParent)
                repository.approveTaskCompletionAsync(taskConcurrent.id)
            }
        }
        tDad.start()
        tMom.start()
        startLatch.countDown()
        tDad.join()
        tMom.join()

        assertTrue(dadResult?.isSuccess == true)
        assertTrue(momResult?.isSuccess == true)
        assertEquals(
            "Concurrent approval by two parents must credit the 40-point task exactly ONCE (35 + 40 = 75)",
            75,
            repository.members.value.first { it.id == kidNoam.id }.earnedPoints
        )
        assertEquals(
            "Concurrent approval must produce exactly 1 points_history entry for task_audit_concurrent",
            1,
            repository.pointsHistory.value.count { it.taskId == taskConcurrent.id }
        )

        // 4. Add (+25 -> 100), Deduct (-30 -> 70), Reset (-70 -> 0), Add (+50 -> 50) & verify ledger consistency
        repository.switchCurrentMember(dadParent)
        assertTrue(repository.addMemberPointsAsync(kidNoam.id, 25, "עזרה בגינה", "op_audit_add").isSuccess)
        assertEquals(100, repository.members.value.first { it.id == kidNoam.id }.earnedPoints)

        assertTrue(repository.deductMemberPointsAsync(kidNoam.id, 30, "אי סדר", false, "op_audit_ded").isSuccess)
        assertEquals(70, repository.members.value.first { it.id == kidNoam.id }.earnedPoints)

        assertTrue(repository.resetMemberPointsAsync(kidNoam.id, "איפוס תקופתי", "op_audit_rst").isSuccess)
        assertEquals(0, repository.members.value.first { it.id == kidNoam.id }.earnedPoints)

        assertTrue(repository.addMemberPointsAsync(kidNoam.id, 50, "פתיחת תקופה חדשה", "op_audit_add_after_rst").isSuccess)
        assertEquals(50, repository.members.value.first { it.id == kidNoam.id }.earnedPoints)

        val noamHistory = repository.getPointsHistoryForMember(kidNoam.id)
        for (entry in noamHistory) {
            assertEquals(
                "Every history entry must satisfy previousBalance + pointsDelta == newBalance (${entry.operationId})",
                entry.previousBalance + entry.pointsDelta,
                entry.newBalance
            )
        }
        assertEquals(
            "Latest history entry newBalance must equal member's current earnedPoints",
            repository.members.value.first { it.id == kidNoam.id }.earnedPoints,
            noamHistory.first().newBalance
        )

        // 5. Child attempt to modify points or approve task -> blocked
        repository.switchCurrentMember(kidNoam)
        assertTrue(repository.addMemberPointsAsync(kidNoam.id, 100, "ניסיון ילד").isFailure)
        assertTrue(repository.deductMemberPointsAsync(kidNoam.id, 10, "ניסיון ילד").isFailure)
        assertTrue(repository.resetMemberPointsAsync(kidNoam.id, "ניסיון ילד").isFailure)
        assertTrue(repository.approveTaskCompletionAsync(taskSingle.id).isFailure)
        assertEquals(50, repository.members.value.first { it.id == kidNoam.id }.earnedPoints)

        // 6. Task deletion regression check (Parent can delete task without corrupting points)
        repository.switchCurrentMember(dadParent)
        assertTrue(repository.deleteTask(taskSingle.id))
        assertFalse(repository.tasks.value.any { it.id == taskSingle.id })
        assertEquals(50, repository.members.value.first { it.id == kidNoam.id }.earnedPoints)

        // 7. Reopen app (new FamilyRepository with same Context) -> balance (50) and history are preserved
        val reopenedRepo = FamilyRepository(context = context)
        assertEquals(50, reopenedRepo.members.value.first { it.id == kidNoam.id }.earnedPoints)
        assertTrue(reopenedRepo.pointsHistory.value.any { it.operationId == "pts_task_${taskSingle.id}" })
        assertTrue(reopenedRepo.pointsHistory.value.any { it.operationId == "pts_task_${taskConcurrent.id}" })
    }

    @Test
    fun `test parent create child acknowledge parent delete background fcm cancel prevents ringing and handles duplicate delete`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.example.notifications.FcmTokenManager.clearAllDispatchedEventsForTesting()
        com.example.notifications.FamilyAlarmCloudSyncWorker.clearCompletedSyncEventsForTesting(context)

        val repository = FamilyRepository(context = context)
        val parentDad = repository.members.value.first { it.id == "m_dad" }
        val childRoey = repository.members.value.first { it.id == "m_kid1" }

        // 1. Parent creates an alarm assigned to the child
        repository.switchCurrentMember(parentDad)
        val alarmId = "alarm_bg_cancel_roey_0700"
        val childAlarm = com.example.model.FamilyAlarm(
            id = alarmId,
            familyId = "fam_local_test",
            title = "השכמה לרועי",
            hour = 7,
            minute = 0,
            assignedMemberId = childRoey.id,
            assignedToMemberId = childRoey.id,
            assignedMemberName = childRoey.name,
            createdByMemberId = parentDad.id,
            createdByMemberName = parentDad.name,
            createdByRole = "PARENT",
            isPersonal = false,
            isEnabled = true
        )
        assertTrue(repository.addOrUpdateFamilyAlarm(childAlarm))

        // 2. Child device receives CREATE sync in background, schedules alarm in AlarmManager, and acknowledges
        com.example.notifications.FamilyAlarmScheduler.syncAlarmsForCurrentDevice(
            context = context,
            alarms = repository.familyAlarms.value,
            currentMemberId = childRoey.id,
            currentAuthUid = childRoey.id,
            currentMemberName = childRoey.name,
            isChildDevice = true,
            authoritativeRemoteSync = true
        )
        assertTrue(
            "Child device must have the alarm scheduled in AlarmManager after CREATE sync",
            com.example.notifications.FamilyAlarmScheduler.isAlarmLocallyScheduled(context, alarmId)
        )

        // 3. Parent deletes the alarm -> Parent sees pending target cancellation notice ("ממתין לביטול...")
        assertTrue(repository.deleteFamilyAlarm(alarmId))
        val pendingNotice = repository.deletedAlarmNotices.value.find { it.alarmId == alarmId }
        assertNotNull("Parent must see pending cancellation notice", pendingNotice)
        assertFalse("Cancellation notice must initially be unconfirmed", pendingNotice!!.isConfirmedCancelledOnTarget)

        // 4. Child device (while app is closed) receives ALARM_SYNC_REQUIRED (action=DELETE) via FamilyMessagingService
        val deleteEventKey = "alarm_del:$alarmId:${childRoey.id}:1700020000"
        val deleteFcmPayload = mapOf(
            "eventKey" to deleteEventKey,
            "eventType" to com.example.notifications.FcmTokenManager.EVENT_ALARM_SYNC_REQUIRED,
            "action" to "DELETE",
            "familyId" to "fam_local_test",
            "alarmId" to alarmId,
            "targetUid" to childRoey.id,
            "actorUid" to parentDad.id,
            "updatedAt" to "1700020000"
        )

        var backgroundReconcileCount = 0
        val handledFirstDelete = com.example.notifications.FamilyMessagingService.handleRemoteMessagePayload(
            context = context,
            data = deleteFcmPayload,
            signedInUidOverride = childRoey.id,
            onAlarmReconcileRequested = { famId, delAlarmId, action ->
                backgroundReconcileCount++
                assertEquals("DELETE", action)
                assertEquals(alarmId, delAlarmId)
                // Execute the background cancellation logic
                com.example.notifications.FamilyAlarmScheduler.markAlarmDeletedAndCancel(context, delAlarmId)
                com.example.notifications.FamilyAlarmScheduler.syncAllDeviceAlarms(
                    context = context,
                    alarms = emptyList(),
                    currentMemberId = childRoey.id,
                    currentAuthUid = childRoey.id,
                    currentMemberName = childRoey.name,
                    isChildDevice = true,
                    deletedAlarmIds = setOf(delAlarmId)
                )
            }
        )
        assertTrue("Child FamilyMessagingService must accept and process ALARM_SYNC_REQUIRED (DELETE)", handledFirstDelete)
        assertEquals(1, backgroundReconcileCount)

        // 5. Verify PendingIntent is cancelled in AlarmManager and tombstoned on disk
        assertFalse(
            "Cancelled alarm PendingIntent must be removed from AlarmManager in background",
            com.example.notifications.FamilyAlarmScheduler.isAlarmLocallyScheduled(context, alarmId)
        )
        assertTrue(
            "Cancelled alarm ID must be persisted in deleted alarm tombstones on disk",
            com.example.notifications.FamilyAlarmScheduler.isAlarmTombstoned(context, alarmId)
        )

        // 6. Verify that even if an OS alarm broadcast fires at the scheduled time, FamilyAlarmReceiver blocks ringing
        com.example.notifications.FamilyAlarmRingingService.dismissActiveAlarm(context)
        val staleTriggerIntent = com.example.notifications.FamilyAlarmScheduler.buildTriggerBroadcastIntent(
            context = context,
            alarm = childAlarm,
            isSnooze = false
        )
        com.example.notifications.FamilyAlarmReceiver().onReceive(context, staleTriggerIntent)
        assertNull(
            "Cancelled alarm must NEVER start ringing if a stale broadcast arrives at scheduled time",
            com.example.notifications.FamilyAlarmRingingService.activeRingingAlarm.value
        )

        // 7. Verify duplicate DELETE FCM event and duplicate local deletion do not cause errors
        val handledDuplicateDelete = com.example.notifications.FamilyMessagingService.handleRemoteMessagePayload(
            context = context,
            data = deleteFcmPayload,
            signedInUidOverride = childRoey.id,
            onAlarmReconcileRequested = { _, _, _ ->
                backgroundReconcileCount++
            }
        )
        assertFalse("Duplicate completed DELETE eventKey must be ignored idempotently", handledDuplicateDelete)
        assertEquals(1, backgroundReconcileCount)

        // Calling markAlarmDeletedAndCancel a second time must be a safe no-op
        com.example.notifications.FamilyAlarmScheduler.markAlarmDeletedAndCancel(context, alarmId)
        assertFalse(com.example.notifications.FamilyAlarmScheduler.isAlarmLocallyScheduled(context, alarmId))
    }

    @Test
    fun `test task reminder scheduler schedules cancels on time change complete delete reassignment and deduplicates by versioned signature`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.example.notifications.TaskReminderScheduler.clearAllForTesting(context)

        val childId = "m_kid1"
        val siblingId = "m_kid2"
        val futureDate = java.time.LocalDate.now().plusDays(1)
        val taskId = "task_reminder_e2e_1"

        val taskV1 = com.example.model.FamilyTask(
            id = taskId,
            title = "להכין תיק לבית הספר",
            description = "ספרים ומחברות",
            category = com.example.model.TaskCategory.HOMEWORK,
            assignedToMemberId = childId,
            dateEpochDay = futureDate.toEpochDay(),
            dueTimeFormatted = "18:00",
            hasDueTime = true,
            reminderOffset = com.example.model.TaskReminderOffset.MINUTES_10_BEFORE,
            points = 15,
            isCompleted = false
        )

        // 1. Schedule V1 reminder for assigned child
        val scheduledV1 = com.example.notifications.TaskReminderScheduler.scheduleOrUpdateTaskReminder(
            context = context,
            task = taskV1,
            assigneeName = "רועי"
        )
        assertTrue("Task V1 with future execution time & reminder must be scheduled", scheduledV1)
        assertTrue(
            "PendingIntent for task must exist in AlarmManager",
            com.example.notifications.TaskReminderScheduler.isTaskReminderPendingInAlarmManager(context, taskId)
        )
        assertEquals(
            taskV1.reminderScheduleSignature(),
            com.example.notifications.TaskReminderScheduler.getActiveScheduledSignature(context, taskId)
        )

        // 2. Simulate V1 reminder firing via TaskReminderReceiver -> records fired signature & prevents duplicate firing of same V1 signature
        val fireIntentV1 = android.content.Intent(context, com.example.notifications.TaskReminderReceiver::class.java).apply {
            action = com.example.notifications.TaskReminderScheduler.ACTION_TRIGGER_TASK_REMINDER
            putExtra(com.example.notifications.TaskReminderScheduler.EXTRA_TASK_ID, taskId)
            putExtra(com.example.notifications.TaskReminderScheduler.EXTRA_TASK_TITLE, taskV1.title)
            putExtra(com.example.notifications.TaskReminderScheduler.EXTRA_TASK_DUE_TIME, taskV1.dueTimeFormatted)
            putExtra(com.example.notifications.TaskReminderScheduler.EXTRA_ASSIGNEE_ID, childId)
            putExtra(com.example.notifications.TaskReminderScheduler.EXTRA_ASSIGNEE_NAME, "רועי")
            putExtra(com.example.notifications.TaskReminderScheduler.EXTRA_TASK_REMINDER_OFFSET, taskV1.reminderOffset.name)
            putExtra(com.example.notifications.TaskReminderScheduler.EXTRA_SCHEDULE_SIGNATURE, taskV1.reminderScheduleSignature())
        }
        com.example.notifications.TaskReminderReceiver().onReceive(context, fireIntentV1)
        assertTrue(
            "V1 signature must be marked as fired after broadcast",
            com.example.notifications.TaskReminderScheduler.isSignatureAlreadyFired(context, taskV1.reminderScheduleSignature())
        )

        // Attempting to re-schedule identical V1 signature must be skipped (no duplicate reminder for same version)
        val rescheduledSameV1 = com.example.notifications.TaskReminderScheduler.scheduleOrUpdateTaskReminder(
            context = context,
            task = taskV1,
            assigneeName = "רועי"
        )
        assertFalse("Already-fired identical V1 signature must not be re-scheduled", rescheduledSameV1)

        // 3. Parent changes execution time from 18:00 to 19:30 (V2) -> new signature MUST cancel old PendingIntent and schedule freshly!
        val taskV2 = taskV1.copy(dueTimeFormatted = "19:30")
        assertNotEquals(taskV1.reminderScheduleSignature(), taskV2.reminderScheduleSignature())
        assertFalse(
            "New V2 signature must NOT be blocked by V1 having fired",
            com.example.notifications.TaskReminderScheduler.isSignatureAlreadyFired(context, taskV2.reminderScheduleSignature())
        )
        val scheduledV2 = com.example.notifications.TaskReminderScheduler.scheduleOrUpdateTaskReminder(
            context = context,
            task = taskV2,
            assigneeName = "רועי"
        )
        assertTrue("Task V2 with updated execution time must be scheduled", scheduledV2)
        assertEquals(
            taskV2.reminderScheduleSignature(),
            com.example.notifications.TaskReminderScheduler.getActiveScheduledSignature(context, taskId)
        )

        // 4. Reassigning task to sibling (m_kid2) -> syncTaskRemindersForCurrentDevice on m_kid1 cancels local reminder!
        val taskReassigned = taskV2.copy(assignedToMemberId = siblingId)
        com.example.notifications.TaskReminderScheduler.syncTaskRemindersForCurrentDevice(
            context = context,
            tasks = listOf(taskReassigned),
            currentMemberId = childId,
            currentAuthUid = childId,
            currentMemberName = "רועי"
        )
        assertFalse(
            "Reassigning task to another member must cancel the local PendingIntent on previous assignee's device",
            com.example.notifications.TaskReminderScheduler.isTaskReminderPendingInAlarmManager(context, taskId)
        )

        // 5. Re-assign back to childId, then mark task completed -> cancels local reminder
        com.example.notifications.TaskReminderScheduler.syncTaskRemindersForCurrentDevice(
            context = context,
            tasks = listOf(taskV2),
            currentMemberId = childId,
            currentAuthUid = childId,
            currentMemberName = "רועי"
        )
        assertTrue(com.example.notifications.TaskReminderScheduler.isTaskReminderPendingInAlarmManager(context, taskId))

        val taskCompleted = taskV2.copy(isCompleted = true)
        com.example.notifications.TaskReminderScheduler.scheduleOrUpdateTaskReminder(
            context = context,
            task = taskCompleted,
            assigneeName = "רועי"
        )
        assertFalse(
            "Completing task must cancel the scheduled reminder PendingIntent",
            com.example.notifications.TaskReminderScheduler.isTaskReminderPendingInAlarmManager(context, taskId)
        )
    }

    @Test
    fun `test closed app silent TASK_SYNC_REQUIRED fcm reconciles task reminder without duplicate push notification`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.example.notifications.FcmTokenManager.clearAllDispatchedEventsForTesting()
        com.example.notifications.TaskReminderScheduler.clearAllForTesting(context)

        val childId = "m_kid1"
        val parentId = "m_dad"
        val taskId = "task_closed_app_fcm_1"
        val futureEpochDay = java.time.LocalDate.now().plusDays(1).toEpochDay()

        // 1. Closed-app FCM arrives with TASK_SYNC_REQUIRED (UPSERT)
        val upsertPayload = mapOf(
            "eventKey" to "task_sync:$taskId:UPSERT:$childId:1700050000",
            "eventType" to com.example.notifications.FcmTokenManager.EVENT_TASK_SYNC_REQUIRED,
            "action" to "UPSERT",
            "familyId" to "fam_local_test",
            "taskId" to taskId,
            "targetUid" to childId,
            "actorUid" to parentId,
            "taskTitle" to "לסדר את השולחן",
            "assignedToMemberId" to childId,
            "dateEpochDay" to futureEpochDay.toString(),
            "dueTimeFormatted" to "17:00",
            "hasDueTime" to "true",
            "reminderOffset" to "MINUTES_30_BEFORE",
            "isCompleted" to "false",
            "updatedAt" to "1700050000"
        )

        val syncCallbackActions = mutableListOf<String>()
        val handledUpsert = com.example.notifications.FamilyMessagingService.handleRemoteMessagePayload(
            context = context,
            data = upsertPayload,
            signedInUidOverride = childId,
            onTaskReminderReconcileRequested = { _, syncedTaskId, operation ->
                syncCallbackActions.add("$syncedTaskId:$operation")
            }
        )
        assertTrue("FamilyMessagingService must handle silent TASK_SYNC_REQUIRED (UPSERT)", handledUpsert)
        assertEquals(listOf("$taskId:UPSERT"), syncCallbackActions)
        assertTrue(
            "Immediate payload handling must schedule the reminder in AlarmManager while app is closed",
            com.example.notifications.TaskReminderScheduler.isTaskReminderPendingInAlarmManager(context, taskId)
        )

        // 2. Closed-app FCM arrives with TASK_SYNC_REQUIRED (DELETE) -> cancels scheduled reminder immediately
        val deletePayload = mapOf(
            "eventKey" to "task_sync:$taskId:DELETE:$childId:1700050099",
            "eventType" to com.example.notifications.FcmTokenManager.EVENT_TASK_SYNC_REQUIRED,
            "action" to "DELETE",
            "familyId" to "fam_local_test",
            "taskId" to taskId,
            "targetUid" to childId,
            "actorUid" to parentId,
            "updatedAt" to "1700050099"
        )
        val handledDelete = com.example.notifications.FamilyMessagingService.handleRemoteMessagePayload(
            context = context,
            data = deletePayload,
            signedInUidOverride = childId,
            onTaskReminderReconcileRequested = { _, syncedTaskId, operation ->
                syncCallbackActions.add("$syncedTaskId:$operation")
            }
        )
        assertTrue("FamilyMessagingService must handle silent TASK_SYNC_REQUIRED (DELETE)", handledDelete)
        assertFalse(
            "DELETE sync event must immediately cancel the scheduled task reminder in AlarmManager",
            com.example.notifications.TaskReminderScheduler.isTaskReminderPendingInAlarmManager(context, taskId)
        )
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun `test compact AddTaskDialog fixed save button expandable options 6 row calendar and Fluent 3D WebP icons`() = androidx.compose.ui.test.runComposeUiTest {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Verify all 12 Fluent Premium 3D WebP icons decode with alpha transparency at 256x256
        val iconResIds = listOf(
            R.drawable.ic_velvet_tasks,
            R.drawable.ic_velvet_calendar,
            R.drawable.ic_velvet_timetable,
            R.drawable.ic_velvet_alarms,
            R.drawable.ic_velvet_files,
            R.drawable.ic_velvet_family,
            R.drawable.ic_velvet_reminder_bell,
            R.drawable.ic_velvet_due_clock,
            R.drawable.ic_velvet_points_star,
            R.drawable.ic_velvet_add_plus,
            R.drawable.ic_velvet_filter,
            R.drawable.ic_velvet_check_badge
        )
        for (resId in iconResIds) {
            val drawable = androidx.core.content.ContextCompat.getDrawable(context, resId)
            assertNotNull("Drawable resource $resId must load cleanly", drawable)
            val bytes = context.resources.openRawResource(resId).use { it.readBytes() }
            assertTrue("Fluent 3D WebP resource $resId must be non-empty", bytes.size > 100)
            val header = String(bytes, 0, 4) + String(bytes, 8, 4)
            assertEquals("RIFFWEBP", header)
        }

        val repository = FamilyRepository(context = context)
        val members = repository.members.value
        val parentMember = members.first { it.isParent }
        var savedTask: FamilyTask? = null
        val showAddDialogState = androidx.compose.runtime.mutableStateOf(true)
        // May 2026 starts on Friday (index 5 when Sunday=0) and has 31 days -> 36 cells -> 6 week rows (0..5)
        val selectedDateState = androidx.compose.runtime.mutableStateOf(LocalDate.of(2026, 5, 1))

        setContent {
            com.example.ui.theme.MyApplicationTheme(
                themeMode = com.example.model.AppThemeMode.LIGHT,
                textScale = com.example.model.AppTextScale.NORMAL
            ) {
                if (showAddDialogState.value) {
                    com.example.ui.dialogs.AddTaskDialog(
                        members = members,
                        isHebrew = true,
                        initialDate = selectedDateState.value,
                        onDismiss = { showAddDialogState.value = false },
                        onSaveTask = { task ->
                            savedTask = task
                            showAddDialogState.value = false
                        }
                    )
                } else {
                    com.example.ui.screens.CalendarScreen(
                        tasks = repository.tasks.value,
                        periods = emptyList(),
                        members = members,
                        currentMember = parentMember,
                        calendarMode = com.example.viewmodel.CalendarViewMode.MONTH,
                        selectedDate = selectedDateState.value,
                        firstDayOfWeek = com.example.model.FirstDayOfWeek.SUNDAY,
                        isHebrew = true,
                        onModeChange = {},
                        onDateSelect = { selectedDateState.value = it },
                        onToggleTask = {},
                        onEditTask = {},
                        onDeleteTask = {},
                        onSendNudge = {},
                        onAddNewTask = {}
                    )
                }
            }
        }

        waitForIdle()

        // 2. Verify compact AddTaskDialog primary rows & fixed bottom Save button exist without scrolling
        onNodeWithTag("add_task_dialog").assertExists()
        onNodeWithTag("task_title_input").assertExists()
        onNodeWithTag("assign_member_row").assertExists()
        onNodeWithTag("task_execution_time_section").assertExists()
        onNodeWithTag("task_reminder_offset_row").assertExists()
        onNodeWithTag("expand_more_options_button").assertExists()
        onNodeWithTag("save_task_btn").assertExists()

        // Enter title, select 10-minute reminder (which auto-enables due time), expand More Options, pick +20 pts & WEEKLY recurrence
        onNodeWithTag("task_title_input").performTextInput("אימון כדורסל")
        onNodeWithTag("task_reminder_chip_MINUTES_10_BEFORE").performClick()
        onNodeWithTag("expand_more_options_button").performClick()
        waitForIdle()

        onNodeWithTag("more_options_container").assertExists()
        onNodeWithTag("points_chip_20").performScrollTo().performClick()
        onNodeWithTag("task_recurrence_selector").performScrollTo()
        onNodeWithTag("task_recurrence_selector").performScrollToNode(hasTestTag("task_recurrence_chip_WEEKLY"))
        onNodeWithTag("task_recurrence_chip_WEEKLY").performClick()
        onNodeWithTag("save_task_btn").performClick()
        waitForIdle()

        assertNotNull("Saving from compact AddTaskDialog must produce a FamilyTask", savedTask)
        assertEquals("אימון כדורסל", savedTask!!.title)
        assertTrue("Selecting a reminder chip must auto-enable hasDueTime", savedTask!!.hasDueTime)
        assertEquals(com.example.model.TaskReminderOffset.MINUTES_10_BEFORE, savedTask!!.reminderOffset)
        assertEquals(20, savedTask!!.points)
        assertEquals(com.example.model.TaskRecurrence.WEEKLY, savedTask!!.recurrence)

        // 3. Verify 6-row month (May 2026) renders all 6 rows (calendar_week_row_0 .. calendar_week_row_5) and day 31 is scrollable & clickable
        onNodeWithTag("calendar_screen").assertExists()
        onNodeWithTag("calendar_week_row_0").assertExists()
        onNodeWithTag("calendar_week_row_5").assertExists()
        onNodeWithTag("calendar_day_cell_2026-05-31").assertExists()
        onNodeWithTag("calendar_month_scroll_container").performScrollToNode(hasTestTag("calendar_day_cell_2026-05-31"))
        onNodeWithTag("calendar_day_cell_2026-05-31").performClick()
        waitForIdle()
        assertEquals(LocalDate.of(2026, 5, 31), selectedDateState.value)
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun `test full task reminder pipeline create save reopen edit modify cancel cross device sync and legacy compatibility`() = androidx.compose.ui.test.runComposeUiTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) {
            com.google.firebase.FirebaseApp.initializeApp(
                context,
                com.google.firebase.FirebaseOptions.Builder()
                    .setProjectId("test-project")
                    .setApplicationId("1:1234567890:android:abcdef")
                    .setApiKey("fake-api-key-for-test")
                    .build()
            )
        }
        com.example.notifications.TaskReminderScheduler.clearAllForTesting(context)

        val parentRepository = FamilyRepository(
            context = context,
            firestoreProvider = { null },
            authProvider = { null }
        )
        val viewModel = com.example.viewmodel.FamilyViewModel(repository = parentRepository)
        viewModel.attachApplicationContext(context)

        val childMember = parentRepository.members.value.first { !it.isParent }
        val tomorrowEpochDay = LocalDate.now().plusDays(1).toEpochDay()
        val showDialogState = androidx.compose.runtime.mutableStateOf(false)
        val taskToEditState = androidx.compose.runtime.mutableStateOf<com.example.model.FamilyTask?>(null)

        setContent {
            com.example.ui.theme.MyApplicationTheme(
                themeMode = com.example.model.AppThemeMode.LIGHT,
                textScale = com.example.model.AppTextScale.NORMAL
            ) {
                if (showDialogState.value) {
                    com.example.ui.dialogs.AddTaskDialog(
                        members = parentRepository.members.value,
                        isHebrew = true,
                        taskToEdit = taskToEditState.value,
                        initialDate = LocalDate.ofEpochDay(tomorrowEpochDay),
                        onDismiss = {
                            viewModel.closeAddTaskDialog()
                            showDialogState.value = false
                            taskToEditState.value = null
                        },
                        onSaveTask = { taskResult ->
                            val currentEditing = taskToEditState.value
                            viewModel.saveTask(
                                id = if (currentEditing != null) taskResult.id else null,
                                title = taskResult.title,
                                description = taskResult.description,
                                category = taskResult.category,
                                assignedToMemberId = taskResult.assignedToMemberId,
                                dateEpochDay = taskResult.dateEpochDay,
                                dueTimeFormatted = taskResult.dueTimeFormatted,
                                priority = taskResult.priority,
                                recurrence = taskResult.recurrence,
                                points = taskResult.points,
                                hasDueTime = taskResult.hasDueTime,
                                reminderOffset = taskResult.reminderOffset
                            )
                            showDialogState.value = false
                            taskToEditState.value = null
                        }
                    )
                }
            }
        }

        // 1. Create a task with a reminder at execution time (AT_TIME) assigned to a family member
        viewModel.openAddTaskDialog(null)
        taskToEditState.value = viewModel.taskToEdit.value
        showDialogState.value = viewModel.showAddTaskDialog.value
        waitForIdle()

        onNodeWithTag("task_title_input").performTextInput("הכנת שיעורי בית במדעים")
        onNodeWithTag("assign_member_chip_${childMember.id}").performClick()
        onNodeWithTag("task_reminder_chip_AT_TIME").performClick()
        onNodeWithTag("save_task_btn").performClick()
        waitForIdle()

        val createdTask = viewModel.tasks.value.find { it.title == "הכנת שיעורי בית במדעים" }
        assertNotNull("Created task must exist in ViewModel/Repository", createdTask)
        assertEquals(childMember.id, createdTask!!.assignedToMemberId)
        assertTrue("Created task must persist hasDueTime = true", createdTask.hasDueTime)
        assertEquals(
            "Created task must persist reminderOffset = AT_TIME and NOT reset to NONE",
            com.example.model.TaskReminderOffset.AT_TIME,
            createdTask.reminderOffset
        )
        assertTrue("Created task must have hasActiveReminder = true", createdTask.hasActiveReminder)

        // 2 & 3. Reopen the saved task for editing and verify reminderOffset does NOT reset to NONE
        viewModel.openAddTaskDialog(createdTask)
        taskToEditState.value = viewModel.taskToEdit.value
        showDialogState.value = viewModel.showAddTaskDialog.value
        waitForIdle()

        assertNotNull("taskToEdit in ViewModel must be populated", viewModel.taskToEdit.value)
        assertTrue("taskToEdit.hasDueTime must remain true when reopened", viewModel.taskToEdit.value!!.hasDueTime)
        assertEquals(
            "taskToEdit.reminderOffset must remain AT_TIME when reopened (never NONE)",
            com.example.model.TaskReminderOffset.AT_TIME,
            viewModel.taskToEdit.value!!.reminderOffset
        )

        // 4. Modify the existing reminder in the Edit dialog (AT_TIME -> MINUTES_30_BEFORE) and save
        onNodeWithTag("task_reminder_chip_MINUTES_30_BEFORE").performClick()
        onNodeWithTag("save_task_btn").performClick()
        waitForIdle()

        val updatedTask = viewModel.tasks.value.find { it.id == createdTask.id }
        assertNotNull(updatedTask)
        assertTrue("Updated task must preserve hasDueTime = true", updatedTask!!.hasDueTime)
        assertEquals(
            "Updated task must persist modified reminderOffset = MINUTES_30_BEFORE",
            com.example.model.TaskReminderOffset.MINUTES_30_BEFORE,
            updatedTask.reminderOffset
        )

        // 6 & 7. Sync task with reminder between two devices (Parent -> Firestore map / Room -> Child device)
        // and verify scheduling on the assigned family member's device (including background FCM when app is closed)
        val firestoreDataMap = mapOf<String, Any?>(
            "id" to updatedTask.id,
            "familyId" to updatedTask.familyId.ifBlank { "fam_sync_test" },
            "title" to updatedTask.title,
            "description" to updatedTask.description,
            "assignedUid" to updatedTask.assignedToMemberId,
            "assignedToMemberId" to updatedTask.assignedToMemberId,
            "dateEpochDay" to updatedTask.dateEpochDay,
            "dueTimeFormatted" to updatedTask.dueTimeFormatted,
            "hasDueTime" to updatedTask.hasDueTime,
            "reminderOffset" to updatedTask.reminderOffset.name,
            "isCompleted" to false,
            "points" to updatedTask.points
        )
        val syncedTaskOnChildDevice = com.example.notifications.TaskReminderSyncWorker.parseFirestoreTaskForReminderSync(
            docId = updatedTask.id,
            data = firestoreDataMap,
            fallbackFamilyId = "fam_sync_test"
        )
        assertNotNull("Child device must parse synced task from Firestore", syncedTaskOnChildDevice)
        assertTrue("Synced task on child device must preserve hasDueTime = true", syncedTaskOnChildDevice!!.hasDueTime)
        assertEquals(
            "Synced task on child device must preserve reminderOffset = MINUTES_30_BEFORE",
            com.example.model.TaskReminderOffset.MINUTES_30_BEFORE,
            syncedTaskOnChildDevice.reminderOffset
        )

        // Verify Room round-trip on child device also preserves hasDueTime and reminderOffset
        val roomEntity = syncedTaskOnChildDevice.toCoreTask("fam_sync_test")
        val restoredFromRoom = roomEntity.toFamilyTask()
        assertTrue("Room round-trip must preserve hasDueTime = true", restoredFromRoom.hasDueTime)
        assertEquals(
            "Room round-trip must preserve reminderOffset = MINUTES_30_BEFORE",
            com.example.model.TaskReminderOffset.MINUTES_30_BEFORE,
            restoredFromRoom.reminderOffset
        )

        // Schedule on child's device via syncTaskRemindersForCurrentDevice
        com.example.notifications.TaskReminderScheduler.syncTaskRemindersForCurrentDevice(
            context = context,
            tasks = listOf(syncedTaskOnChildDevice),
            currentMemberId = childMember.id,
            currentAuthUid = childMember.id,
            currentMemberName = childMember.name
        )
        assertTrue(
            "Reminder MUST be scheduled in AlarmManager on the assigned child's device",
            com.example.notifications.TaskReminderScheduler.isTaskReminderPendingInAlarmManager(context, updatedTask.id)
        )
        assertEquals(
            updatedTask.reminderScheduleSignature(),
            com.example.notifications.TaskReminderScheduler.getActiveScheduledSignature(context, updatedTask.id)
        )

        // 5. Cancel the existing reminder in Edit dialog (select NONE "ללא תזכורת") and save
        viewModel.openAddTaskDialog(updatedTask)
        taskToEditState.value = viewModel.taskToEdit.value
        showDialogState.value = viewModel.showAddTaskDialog.value
        waitForIdle()
        onNodeWithTag("task_reminder_chip_NONE").performClick()
        onNodeWithTag("save_task_btn").performClick()
        waitForIdle()

        val cancelledTask = viewModel.tasks.value.find { it.id == createdTask.id }
        assertNotNull(cancelledTask)
        assertEquals(
            "Selecting 'ללא תזכורת' must save reminderOffset = NONE",
            com.example.model.TaskReminderOffset.NONE,
            cancelledTask!!.reminderOffset
        )
        assertFalse("Cancelled reminder task must have hasActiveReminder = false", cancelledTask.hasActiveReminder)

        // Sync the cancellation to the child's device and verify AlarmManager PendingIntent is cancelled
        com.example.notifications.TaskReminderScheduler.syncTaskRemindersForCurrentDevice(
            context = context,
            tasks = listOf(cancelledTask),
            currentMemberId = childMember.id,
            currentAuthUid = childMember.id,
            currentMemberName = childMember.name
        )
        assertFalse(
            "Cancelling reminder must remove the scheduled PendingIntent on the child's device",
            com.example.notifications.TaskReminderScheduler.isTaskReminderPendingInAlarmManager(context, updatedTask.id)
        )

        // 8. Verify backward compatibility for legacy tasks without reminders (missing hasDueTime / reminderOffset fields)
        val legacyFirestoreMap = mapOf<String, Any?>(
            "id" to "legacy_task_001",
            "title" to "משימה ישנה ללא שדות תזכורת",
            "assignedToMemberId" to childMember.id,
            "dateEpochDay" to tomorrowEpochDay,
            "dueTimeFormatted" to "17:00",
            "isCompleted" to false,
            "points" to 10
        )
        val parsedLegacyTask = com.example.notifications.TaskReminderSyncWorker.parseFirestoreTaskForReminderSync(
            docId = "legacy_task_001",
            data = legacyFirestoreMap,
            fallbackFamilyId = "fam_sync_test"
        )
        assertNotNull("Legacy task without reminder fields must parse cleanly", parsedLegacyTask)
        assertFalse("Legacy task must default to hasDueTime = false", parsedLegacyTask!!.hasDueTime)
        assertEquals(
            "Legacy task must default to reminderOffset = NONE",
            com.example.model.TaskReminderOffset.NONE,
            parsedLegacyTask.reminderOffset
        )
        assertFalse("Legacy task must not schedule an active reminder", parsedLegacyTask.hasActiveReminder)

        com.example.notifications.TaskReminderScheduler.clearAllForTesting(context)
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun `test additional sounds crash fix 48 velvet library sounds unified sound system channel rotation and custom sound fallback`() = androidx.compose.ui.test.runComposeUiTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.example.notifications.FamilySoundManager.clearAllSoundPrefsForTesting(context)

        // 1. Verify all 49 real .wav audio files (1 Signature Pulse + 48 Velvet sounds) across all 8 categories exist in res/raw
        val allVerifiedSounds = com.example.notifications.FamilySoundManager.getVerifiedLibrarySounds(context)
        assertEquals(
            "All 49 real .wav sounds across the 8 categories must exist in res/raw and pass verification",
            49,
            allVerifiedSounds.size
        )
        com.example.notifications.SoundLibraryCategory.entries.forEach { category ->
            val catSounds = com.example.notifications.FamilySoundManager.getVerifiedLibrarySounds(context, category)
            val minExpected = if (category == com.example.notifications.SoundLibraryCategory.FAMILYTASKS_SIGNATURE) 1 else 6
            assertTrue(
                "Category ${category.name} must have at least $minExpected verified real audio file(s)",
                catSounds.size >= minExpected
            )
            catSounds.forEach { item ->
                assertTrue(
                    "Sound ${item.id} must have a valid raw resource",
                    com.example.notifications.FamilySoundManager.isRawSoundResourceValid(context, item.rawResId)
                )
            }
        }

        // 2. Verify getAdditionalCategoryRingtones produces strictly unique idKeys (root cause of LazyColumn duplicate key crash)
        val additionalRingtones = com.example.notifications.AlarmAudioController.getAdditionalCategoryRingtones(context)
        val uniqueIdKeys = additionalRingtones.map { it.idKey }.toSet()
        assertEquals(
            "Every item returned by getAdditionalCategoryRingtones must have a strictly unique idKey",
            additionalRingtones.size,
            uniqueIdKeys.size
        )

        // 3. Verify createSystemRingtonePickerIntent NEVER exposes a file:// URI in EXTRA_RINGTONE_EXISTING_URI
        val fileCustomUri = "file:///data/user/0/com.example/files/custom_alarm_tones/custom_test.wav"
        val pickerIntentWithFileUri = com.example.notifications.AlarmAudioController.createSystemRingtonePickerIntent(
            context = context,
            currentSoundType = com.example.notifications.AlarmAudioController.SOUND_CUSTOM_URI,
            currentCustomUri = fileCustomUri,
            isHebrew = true
        )
        val existingExtraUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pickerIntentWithFileUri.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, android.net.Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            pickerIntentWithFileUri.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI)
        }
        assertFalse(
            "EXTRA_RINGTONE_EXISTING_URI must never expose a file:// scheme URI (prevents FileUriExposedException)",
            existingExtraUri?.scheme.equals("file", ignoreCase = true)
        )

        // 4. Test FamilyAlarmRingtonePickerScreen UI:
        //    - Open "צלילים נוספים" (ringtone_tab_additional_sounds) without crashing
        //    - Browse all 7 Velvet Sound Library categories and select a sound from each category
        //    - Preview playback starts and stops previous sound automatically when switching sounds
        //    - Save selection and reopen picker to verify selection persists
        var savedSoundType = com.example.notifications.AlarmAudioController.SOUND_SYSTEM_ALARM
        var savedCustomUri = ""
        var savedCustomTitle = ""
        var savedVolume = 85
        val pickerSessionState = androidx.compose.runtime.mutableIntStateOf(0)

        setContent {
            val session = pickerSessionState.intValue
            androidx.compose.runtime.key(session) {
                com.example.ui.screens.FamilyAlarmRingtonePickerScreen(
                    initialSoundType = savedSoundType,
                    initialCustomSoundUri = savedCustomUri,
                    initialCustomSoundTitle = savedCustomTitle,
                    initialVolumePercent = savedVolume,
                    isTargetRemoteChild = false,
                    isHebrew = true,
                    onCancel = {},
                    onConfirmSound = { sType, cUri, cTitle, vol ->
                        savedSoundType = sType
                        savedCustomUri = cUri
                        savedCustomTitle = cTitle
                        savedVolume = vol
                    }
                )
            }
        }
        waitForIdle()

        // Tap "צלילים נוספים" tab -> MUST NOT CRASH!
        onNodeWithTag("ringtone_tab_additional_sounds").assertExists().performClick()
        waitForIdle()
        onNodeWithTag("open_android_ringtone_picker_in_additional").assertExists()
        onNodeWithTag("velvet_sound_category_bar").assertExists()

        // Select a sound from each of the 7 categories and verify preview state updates & stops previous preview
        val categorySampleSounds = com.example.notifications.SoundLibraryCategory.entries.map { cat ->
            cat to com.example.notifications.FamilySoundManager.getVerifiedLibrarySounds(context, cat).first()
        }
        categorySampleSounds.forEach { (cat, sampleSound) ->
            onNodeWithTag("velvet_sound_category_bar").performScrollToNode(hasTestTag("sound_category_chip_${cat.name}"))
            onNodeWithTag("sound_category_chip_${cat.name}").performClick()
            waitForIdle()

            onNodeWithTag("ringtone_picker_list").performScrollToNode(hasTestTag("ringtone_row_${sampleSound.id}"))
            onNodeWithTag("ringtone_row_${sampleSound.id}").performClick()
            waitForIdle()

            // Verify preview started for this sound and replaced any previous preview
            assertEquals(
                "Preview activeSoundType must switch to the newly selected sound ${sampleSound.id}",
                sampleSound.id,
                com.example.notifications.AlarmAudioController.previewState.value.activeSoundType
            )
        }

        // Confirm & save the last selected sound (from SOFT_NOTIFICATIONS)
        val lastPickedLibrarySound = categorySampleSounds.last().second
        onNodeWithTag("confirm_ringtone_picker_button").performClick()
        waitForIdle()

        assertEquals(lastPickedLibrarySound.id, savedSoundType)

        // Reopen the picker (increment session) and verify the saved sound is preserved
        pickerSessionState.intValue++
        waitForIdle()
        onNodeWithTag("ringtone_picker_selected_summary").assertTextContains(lastPickedLibrarySound.titleHe, substring = true)

        // Tap "צליל מותאם אישית" tab and verify custom file & system picker buttons exist
        onNodeWithTag("ringtone_tab_custom_sound").performClick()
        waitForIdle()
        onNodeWithTag("alarm_custom_sound_chip").assertExists()
        onNodeWithTag("alarm_system_ringtone_picker_button").assertExists()

        // 5. Test Android System Ringtone Picker result resolution & cancellation
        val pickedSysIntent = android.content.Intent().apply {
            putExtra(
                android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
            )
        }
        val resolvedSysTone = com.example.notifications.AlarmAudioController.resolvePickedSystemRingtone(
            context = context,
            resultData = pickedSysIntent,
            isHebrew = true
        )
        assertNotNull("Picking a valid system ringtone URI must resolve non-null pair", resolvedSysTone)
        assertTrue(resolvedSysTone!!.first.isNotBlank())

        val cancelledSysTone = com.example.notifications.AlarmAudioController.resolvePickedSystemRingtone(
            context = context,
            resultData = null,
            isHebrew = true
        )
        assertNull("Cancelling system ringtone picker (null data) must return null cleanly without crashing", cancelledSysTone)

        // 6. Test importing a personal audio file from device & fallback when custom file is deleted
        val tempAudioFile = java.io.File(context.cacheDir, "personal_melody_test.wav")
        context.resources.openRawResource(com.example.R.raw.sound_velvet_aurora).use { input ->
            tempAudioFile.outputStream().use { output -> input.copyTo(output) }
        }
        val tempFileUri = android.net.Uri.fromFile(tempAudioFile)
        val importResult = com.example.notifications.AlarmAudioController.importAndPersistCustomSoundUri(context, tempFileUri)
        assertTrue("Importing valid personal audio file must succeed", importResult.isSuccess)
        val (mirroredUriStr, mirroredTitle) = importResult.getOrThrow()
        assertTrue(mirroredUriStr.isNotBlank())
        assertTrue(mirroredTitle.isNotBlank())

        // Save custom sound for TASK_REMINDER and verify it resolves to the custom file while it exists
        com.example.notifications.FamilySoundManager.saveSoundConfigForUsage(
            context = context,
            usageType = com.example.notifications.SoundUsageType.TASK_REMINDER,
            config = com.example.notifications.SelectedSoundConfig(
                soundId = com.example.notifications.AlarmAudioController.SOUND_CUSTOM_URI,
                customUri = mirroredUriStr,
                customTitle = mirroredTitle,
                volumePercent = 90
            )
        )
        val persistedReminderCfg = com.example.notifications.FamilySoundManager.getSoundConfigForUsage(
            context,
            com.example.notifications.SoundUsageType.TASK_REMINDER
        )
        assertEquals(com.example.notifications.AlarmAudioController.SOUND_CUSTOM_URI, persistedReminderCfg.soundId)
        assertEquals(mirroredTitle, persistedReminderCfg.customTitle)

        // First delete only the primary file -> device-protected mirror must recover it seamlessly!
        val mirroredFile = java.io.File(android.net.Uri.parse(mirroredUriStr).path ?: "")
        if (mirroredFile.exists()) {
            mirroredFile.delete()
        }
        val recoveredMirrorUri = com.example.notifications.FamilySoundManager.resolvePlayableUriOrFallback(
            context = context,
            usageType = com.example.notifications.SoundUsageType.TASK_REMINDER
        )
        assertNotNull("Device-protected mirror must recover when primary custom sound file is deleted", recoveredMirrorUri)
        assertEquals("file", recoveredMirrorUri.scheme)

        // Now delete the device-protected mirror copy as well -> must automatically fall back to default android.resource sound!
        val dpFile = java.io.File(recoveredMirrorUri.path ?: "")
        if (dpFile.exists()) {
            dpFile.delete()
        }
        val fallbackUri = com.example.notifications.FamilySoundManager.resolvePlayableUriOrFallback(
            context = context,
            usageType = com.example.notifications.SoundUsageType.TASK_REMINDER
        )
        assertNotNull("When custom sound file is completely deleted, resolvePlayableUriOrFallback must return a valid fallback URI", fallbackUri)
        assertEquals(
            "Fallback URI must use android.resource scheme from the verified default library sound",
            "android.resource",
            fallbackUri.scheme
        )

        // Also verify startPreview and startAlarmRinging fall back gracefully without crashing when custom file is missing
        com.example.notifications.AlarmAudioController.startPreview(
            context = context,
            soundType = com.example.notifications.AlarmAudioController.SOUND_CUSTOM_URI,
            customSoundUri = "file:///non_existent_deleted_audio_file.mp3",
            volumePercent = 80
        )
        assertTrue(
            "startPreview must fall back and play when custom file is missing",
            com.example.notifications.AlarmAudioController.previewState.value.isPlaying
        )
        com.example.notifications.AlarmAudioController.stopPreview(context)

        val alarmFallbackPlayed = com.example.notifications.AlarmAudioController.startAlarmRinging(
            context = context,
            soundType = com.example.notifications.AlarmAudioController.SOUND_CUSTOM_URI,
            customSoundUri = "file:///non_existent_deleted_audio_file.mp3",
            vibrateEnabled = false,
            volumePercent = 85
        )
        assertTrue("startAlarmRinging must fall back to default alarm sound when custom file is deleted", alarmFallbackPlayed)
        com.example.notifications.AlarmAudioController.stopAlarmRinging(context)

        // 7. Test Android 8+ NotificationChannel sound rotation without channel explosion
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        // Configure distinct sounds for TASK_REMINDER, TASK_CREATED, TASK_UPDATED, TASK_COMPLETED, TASK_DELETED, FAMILY_GENERAL
        val usageSoundMappings = mapOf(
            com.example.notifications.SoundUsageType.ALARM_DEFAULT to "SOUND:sound_velvet_sapphire",
            com.example.notifications.SoundUsageType.TASK_REMINDER to "SOUND:sound_piano_morning_sonata",
            com.example.notifications.SoundUsageType.TASK_CREATED to "SOUND:sound_soft_task_created",
            com.example.notifications.SoundUsageType.TASK_UPDATED to "SOUND:sound_soft_task_updated",
            com.example.notifications.SoundUsageType.TASK_COMPLETED to "SOUND:sound_soft_task_completed",
            com.example.notifications.SoundUsageType.TASK_DELETED to "SOUND:sound_soft_task_deleted",
            com.example.notifications.SoundUsageType.FAMILY_GENERAL to "SOUND:sound_nature_wind_chimes"
        )
        usageSoundMappings.forEach { (usage, soundId) ->
            com.example.notifications.FamilySoundManager.saveSoundConfigForUsage(
                context = context,
                usageType = usage,
                config = com.example.notifications.SelectedSoundConfig(soundId = soundId, volumePercent = 85)
            )
            val saved = com.example.notifications.FamilySoundManager.getSoundConfigForUsage(context, usage)
            assertTrue(
                "Saved sound for ${usage.name} must persist (${saved.soundId})",
                saved.soundId.contains(soundId.removePrefix("SOUND:"))
            )
        }

        // Verify channel creation & rotation for TASK_REMINDER on Android 8+
        val firstReminderChannelId = com.example.util.NotificationHelper.ensureChannelForSoundUsage(
            context,
            com.example.notifications.SoundUsageType.TASK_REMINDER
        )
        // Calling ensureChannelForSoundUsage again with the SAME sound must return the exact same channel ID
        val repeatedReminderChannelId = com.example.util.NotificationHelper.ensureChannelForSoundUsage(
            context,
            com.example.notifications.SoundUsageType.TASK_REMINDER
        )
        assertEquals(
            "Calling ensureChannelForSoundUsage when sound has not changed must reuse the existing channel ID",
            firstReminderChannelId,
            repeatedReminderChannelId
        )

        // Change TASK_REMINDER sound to sound_digital_starlight -> must rotate to a new channel ID and delete the old one
        com.example.notifications.FamilySoundManager.saveSoundConfigForUsage(
            context = context,
            usageType = com.example.notifications.SoundUsageType.TASK_REMINDER,
            config = com.example.notifications.SelectedSoundConfig(soundId = "sound_digital_starlight", volumePercent = 85)
        )
        val rotatedReminderChannelId = com.example.util.NotificationHelper.ensureChannelForSoundUsage(
            context,
            com.example.notifications.SoundUsageType.TASK_REMINDER
        )
        assertNotEquals(
            "Changing the sound for TASK_REMINDER must rotate the Android 8+ NotificationChannel ID",
            firstReminderChannelId,
            rotatedReminderChannelId
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            assertNull(
                "Previous channel ID for TASK_REMINDER must be deleted upon rotation to prevent channel buildup",
                notificationManager.getNotificationChannel(firstReminderChannelId)
            )
            val activeChannel = notificationManager.getNotificationChannel(rotatedReminderChannelId)
            assertNotNull("New rotated channel for TASK_REMINDER must exist", activeChannel)
            assertNotNull("New rotated channel for TASK_REMINDER must have a valid sound URI", activeChannel!!.sound)
        }

        // 8. Test Background Playback & Notifications when app is closed:
        //    - Alarm ringing via FamilyAlarmReceiver -> FamilyAlarmRingingService with Velvet sound
        //    - Task scheduled reminder via TaskReminderReceiver -> NotificationHelper with rotated sound channel
        //    - Push/FCM notifications for task_created, task_completed, task_deleted, family_general
        val velvetAlarm = com.example.model.FamilyAlarm(
            id = "alarm_velvet_closed_app_test",
            title = "השכמה עם צליל קטיפה",
            hour = 7,
            minute = 0,
            soundType = "sound_velvet_aurora",
            volumePercent = 90
        )
        val alarmServiceIntent = android.content.Intent(context, com.example.notifications.FamilyAlarmRingingService::class.java).apply {
            action = com.example.notifications.FamilyAlarmRingingService.ACTION_START_RINGING
            putExtra(com.example.notifications.FamilyAlarmScheduler.EXTRA_ALARM_ID, velvetAlarm.id)
            putExtra(com.example.notifications.FamilyAlarmScheduler.EXTRA_ALARM_TITLE, velvetAlarm.title)
            putExtra(com.example.notifications.FamilyAlarmScheduler.EXTRA_ALARM_HOUR, velvetAlarm.hour)
            putExtra(com.example.notifications.FamilyAlarmScheduler.EXTRA_ALARM_MINUTE, velvetAlarm.minute)
            putExtra(com.example.notifications.FamilyAlarmScheduler.EXTRA_ALARM_SOUND_TYPE, velvetAlarm.soundType)
            putExtra(com.example.notifications.FamilyAlarmScheduler.EXTRA_ALARM_VOLUME_PERCENT, velvetAlarm.volumePercent)
        }
        org.robolectric.Robolectric.buildService(
            com.example.notifications.FamilyAlarmRingingService::class.java,
            alarmServiceIntent
        ).create().startCommand(0, 1)
        assertTrue(
            "Alarm must start audio playback in background with selected Velvet sound",
            com.example.notifications.FamilyAlarmScheduler.diagnosticState.value.audioPlaybackStarted
        )
        com.example.notifications.FamilyAlarmReceiver.stopActiveRinging(context)

        // Grant POST_NOTIFICATIONS in Robolectric so Android 13+ notification posting succeeds
        org.robolectric.Shadows.shadowOf(context as android.app.Application).grantPermissions(
            android.Manifest.permission.POST_NOTIFICATIONS
        )

        // Trigger Task Reminder Notification when app is closed
        com.example.util.NotificationHelper.showTaskScheduledReminderNotification(
            context = context,
            taskId = "task_rem_sound_test",
            taskTitle = "להכין תיק לבית ספר",
            assigneeName = "רועי",
            dueTimeFormatted = "07:45",
            points = 15,
            reminderOffsetLabelHe = "10 דקות לפני",
            notificationId = 88811
        )
        // Trigger regular notifications (TASK_CREATED, TASK_COMPLETED, TASK_DELETED, FAMILY_GENERAL)
        listOf("task_created", "task_completed", "task_deleted", "family_general").forEach { eventType ->
            com.example.util.NotificationHelper.showIncomingFcmNotification(
                context = context,
                title = "עדכון משימה ($eventType)",
                message = "בדיקת צליל התראה מותאם",
                eventKey = "event_key_$eventType",
                taskId = "task_$eventType",
                eventType = eventType
            )
        }
        val shadowNotificationManager = org.robolectric.Shadows.shadowOf(notificationManager)
        assertTrue(
            "Task reminder and regular notifications must post to NotificationManager",
            shadowNotificationManager.allNotifications.size >= 5
        )
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun `test familytasks signature pulse integration default task_created sound preference preservation channel settings and deduplication`() = androidx.compose.ui.test.runComposeUiTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val shadowNotificationManager = org.robolectric.Shadows.shadowOf(notificationManager)

        // Grant POST_NOTIFICATIONS on Android 13+
        org.robolectric.Shadows.shadowOf(context as android.app.Application).grantPermissions(
            android.Manifest.permission.POST_NOTIFICATIONS
        )

        // Clean state to simulate a fresh installation / profile with no prior sound preference
        com.example.notifications.FamilySoundManager.clearAllSoundPrefsForTesting(context)
        com.example.util.NotificationHelper.clearChannelPrefsForTesting(context)
        com.example.notifications.FcmTokenManager.clearAllDispatchedEventsForTesting()
        notificationManager.cancelAll()

        // =========================================================================
        // 1. Verify the attached WAV file exists in res/raw/sound_familytasks_signature_pulse.wav and is valid
        // =========================================================================
        val sigResId = com.example.R.raw.sound_familytasks_signature_pulse
        assertTrue(
            "R.raw.sound_familytasks_signature_pulse must exist and be a valid non-empty WAV resource",
            com.example.notifications.FamilySoundManager.isRawSoundResourceValid(context, sigResId)
        )
        val rawBytes = context.resources.openRawResource(sigResId).use { it.readBytes() }
        assertEquals(
            "Attached Signature Pulse WAV file must match exact original byte size (63984 bytes)",
            63984,
            rawBytes.size
        )
        assertEquals("RIFF", String(rawBytes.sliceArray(0 until 4), Charsets.US_ASCII))
        assertEquals("WAVE", String(rawBytes.sliceArray(8 until 12), Charsets.US_ASCII))

        // =========================================================================
        // 2. Verify Signature Pulse is registered in FamilyTasks Signature category with 🔷 and exact ID/title
        // =========================================================================
        val sigCategorySounds = com.example.notifications.FamilySoundManager.getVerifiedLibrarySounds(
            context,
            com.example.notifications.SoundLibraryCategory.FAMILYTASKS_SIGNATURE
        )
        assertEquals(1, sigCategorySounds.size)
        val sigItem = sigCategorySounds.first()
        assertEquals("familytasks_signature_pulse", sigItem.id)
        assertEquals("FamilyTasks Signature Pulse", sigItem.titleHe)
        assertEquals("FamilyTasks Signature Pulse", sigItem.titleEn)
        assertEquals("🔷", sigItem.emoji)
        assertEquals(
            "FamilyTasks Signature",
            com.example.notifications.SoundLibraryCategory.FAMILYTASKS_SIGNATURE.titleHe
        )

        // Verify the existing 48 Velvet sounds remain intact (total 49 verified sounds)
        val allVerified = com.example.notifications.FamilySoundManager.getVerifiedLibrarySounds(context)
        assertEquals(49, allVerified.size)

        // =========================================================================
        // 3. Verify Signature Pulse is the default sound ONLY for TASK_CREATED on a fresh profile,
        //    while ALARM_DEFAULT, TASK_REMINDER, and other notification types keep their existing defaults
        // =========================================================================
        val defaultTaskCreatedCfg = com.example.notifications.FamilySoundManager.getSoundConfigForUsage(
            context,
            com.example.notifications.SoundUsageType.TASK_CREATED
        )
        assertEquals(
            "LIBRARY:familytasks_signature_pulse",
            defaultTaskCreatedCfg.soundId
        )
        assertEquals(
            "FamilyTasks Signature Pulse",
            com.example.notifications.FamilySoundManager.formatSelectedSoundDisplayName(
                context,
                com.example.notifications.SoundUsageType.TASK_CREATED,
                isHebrew = true
            )
        )
        val taskCreatedPlayableUri = com.example.notifications.FamilySoundManager.resolvePlayableUriOrFallback(
            context,
            com.example.notifications.SoundUsageType.TASK_CREATED
        )
        assertEquals(sigItem.buildResourceUri(context), taskCreatedPlayableUri.toString())

        // Verify other sound usage types were NOT altered
        assertEquals(
            "sound_wakeup_sunrise_glow",
            com.example.notifications.SoundUsageType.ALARM_DEFAULT.defaultSoundId
        )
        assertEquals(
            "sound_soft_reminder_dew",
            com.example.notifications.SoundUsageType.TASK_REMINDER.defaultSoundId
        )
        assertEquals(
            "sound_soft_task_updated",
            com.example.notifications.SoundUsageType.TASK_UPDATED.defaultSoundId
        )
        assertEquals(
            "sound_soft_task_completed",
            com.example.notifications.SoundUsageType.TASK_COMPLETED.defaultSoundId
        )
        assertEquals(
            "sound_soft_task_deleted",
            com.example.notifications.SoundUsageType.TASK_DELETED.defaultSoundId
        )
        assertEquals(
            "sound_soft_family_message",
            com.example.notifications.SoundUsageType.FAMILY_GENERAL.defaultSoundId
        )

        // =========================================================================
        // 4. Verify UI display, preview playback, replacing Signature Pulse with another sound,
        //    and persistence across app closure
        // =========================================================================
        var currentSavedType = defaultTaskCreatedCfg.soundId
        var currentSavedUri = defaultTaskCreatedCfg.customUri
        var currentSavedTitle = defaultTaskCreatedCfg.customTitle
        val pickerSession = androidx.compose.runtime.mutableIntStateOf(0)

        setContent {
            val session = pickerSession.intValue
            androidx.compose.runtime.key(session) {
                com.example.ui.screens.FamilyAlarmRingtonePickerScreen(
                    initialSoundType = currentSavedType,
                    initialCustomSoundUri = currentSavedUri,
                    initialCustomSoundTitle = currentSavedTitle,
                    initialVolumePercent = 85,
                    isTargetRemoteChild = false,
                    isHebrew = true,
                    headerTitleOverride = "צליל: יצירה והקצאת משימה",
                    showVolumeSlider = false,
                    onCancel = {},
                    onConfirmSound = { sType, cUri, cTitle, vol ->
                        val savedCfg = com.example.notifications.FamilySoundManager.saveSoundConfigForUsage(
                            context = context,
                            usage = com.example.notifications.SoundUsageType.TASK_CREATED,
                            soundType = sType,
                            customSoundUri = cUri,
                            customSoundTitle = cTitle,
                            volumePercent = vol
                        )
                        currentSavedType = savedCfg.soundId
                        currentSavedUri = savedCfg.customUri
                        currentSavedTitle = savedCfg.customTitle
                    }
                )
            }
        }
        waitForIdle()

        // Verify the header shows "FamilyTasks Signature Pulse" as currently selected
        onNodeWithTag("ringtone_picker_selected_summary").assertTextContains("FamilyTasks Signature Pulse", substring = true)
        onNodeWithTag("sound_category_chip_FAMILYTASKS_SIGNATURE").assertExists()
        onNodeWithTag("ringtone_row_familytasks_signature_pulse").assertExists()

        // Click on FamilyTasks Signature Pulse row to preview it
        onNodeWithTag("ringtone_row_familytasks_signature_pulse").performClick()
        waitForIdle()
        assertTrue(com.example.notifications.AlarmAudioController.previewState.value.isPlaying)
        assertEquals(
            "familytasks_signature_pulse",
            com.example.notifications.AlarmAudioController.previewState.value.activeSoundType
        )

        // Replace Signature Pulse with another sound (e.g. from PIANO_MELODY) and save
        onNodeWithTag("velvet_sound_category_bar").performScrollToNode(hasTestTag("sound_category_chip_PIANO_MELODY"))
        onNodeWithTag("sound_category_chip_PIANO_MELODY").performClick()
        waitForIdle()

        onNodeWithTag("ringtone_picker_list").performScrollToNode(hasTestTag("ringtone_row_sound_piano_morning_sonata"))
        onNodeWithTag("ringtone_row_sound_piano_morning_sonata").performClick()
        waitForIdle()
        assertEquals(
            "sound_piano_morning_sonata",
            com.example.notifications.AlarmAudioController.previewState.value.activeSoundType
        )

        onNodeWithTag("confirm_ringtone_picker_button").performClick()
        waitForIdle()

        // =========================================================================
        // 5. Verify existing user sound choice is preserved and NEVER overwritten by default Signature Pulse,
        //    including after closing/reopening the app
        // =========================================================================
        val userChosenCfg = com.example.notifications.FamilySoundManager.getSoundConfigForUsage(
            context,
            com.example.notifications.SoundUsageType.TASK_CREATED
        )
        assertEquals("LIBRARY:sound_piano_morning_sonata", userChosenCfg.soundId)
        com.example.notifications.FamilySoundManager.refreshUsageConfigsState(context)
        val afterRestartCfg = com.example.notifications.FamilySoundManager.getSoundConfigForUsage(
            context,
            com.example.notifications.SoundUsageType.TASK_CREATED
        )
        assertEquals(
            "Existing user preference for TASK_CREATED must never be overwritten by default Signature Pulse",
            "LIBRARY:sound_piano_morning_sonata",
            afterRestartCfg.soundId
        )

        // Reopen picker -> must show user's chosen sound, not reset to Signature Pulse
        pickerSession.intValue++
        waitForIdle()
        onNodeWithTag("ringtone_picker_selected_summary").assertTextContains("סונטת בוקר", substring = true)

        // =========================================================================
        // 6. Verify Android 8+ NotificationChannel behavior & preservation of user OS channel settings
        //    when switching back to Signature Pulse (or on fresh profile)
        // =========================================================================
        com.example.notifications.FamilySoundManager.clearAllSoundPrefsForTesting(context)
        com.example.util.NotificationHelper.clearChannelPrefsForTesting(context)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            notificationManager.notificationChannels.map { it.id }.forEach { id ->
                notificationManager.deleteNotificationChannel(id)
            }
        }

        // Fresh profile: ensureChannelForSoundUsage(TASK_CREATED) configures channel with Signature Pulse URI
        val defaultTaskChannelId = com.example.util.NotificationHelper.ensureChannelForSoundUsage(
            context,
            com.example.notifications.SoundUsageType.TASK_CREATED
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val ch = notificationManager.getNotificationChannel(defaultTaskChannelId)
            assertNotNull(ch)
            assertEquals(sigItem.buildResourceUri(context), ch!!.sound?.toString())

            // Simulate user customizing OS channel settings (e.g. disabling vibration on the channel)
            ch.enableVibration(false)
            notificationManager.createNotificationChannel(ch)

            // User changes sound in app -> new rotated channel must preserve user's OS vibration setting (false)
            com.example.notifications.FamilySoundManager.saveSoundConfigForUsage(
                context = context,
                usage = com.example.notifications.SoundUsageType.TASK_CREATED,
                soundType = "sound_velvet_aurora",
                customSoundUri = "",
                customSoundTitle = "",
                volumePercent = 85
            )
            val rotatedChannelId = com.example.util.NotificationHelper.ensureChannelForSoundUsage(
                context,
                com.example.notifications.SoundUsageType.TASK_CREATED
            )
            val rotatedCh = notificationManager.getNotificationChannel(rotatedChannelId)
            assertNotNull(rotatedCh)
            assertFalse(
                "Rotated NotificationChannel must preserve user's OS-level vibration setting",
                rotatedCh!!.shouldVibrate()
            )
        }

        // Reset back to default Signature Pulse for closed-app FCM & deduplication tests
        com.example.notifications.FamilySoundManager.clearAllSoundPrefsForTesting(context)
        com.example.util.NotificationHelper.clearChannelPrefsForTesting(context)
        notificationManager.cancelAll()
        com.example.notifications.FcmTokenManager.clearAllDispatchedEventsForTesting()

        // =========================================================================
        // 7. Verify closed-app ("סגור הכל") new task notification plays Signature Pulse
        //    and NEVER creates duplicate notifications or double sound playback
        // =========================================================================
        val childUid = "child_sig_pulse_uid"
        val parentUid = "parent_sig_pulse_uid"
        val newTaskId = "task_sig_pulse_101"

        // Simulate incoming FCM push for a newly created & assigned task when app is closed
        val handledFirst = com.example.notifications.FamilyMessagingService.handleRemoteMessagePayload(
            context = context,
            data = mapOf(
                "eventType" to com.example.notifications.FcmTokenManager.EVENT_TASK_ASSIGNED,
                "eventKey" to "assign:$newTaskId:$childUid:$childUid",
                "familyId" to "fam_sig_1",
                "taskId" to newTaskId,
                "targetUid" to childUid,
                "actorUid" to parentUid,
                "title" to "משימה חדשה שובצה עבורך 📋",
                "body" to "שובצה לך משימה חדשה: \"לסדר את החדר\""
            ),
            signedInUidOverride = childUid
        )
        assertTrue("First TASK_ASSIGNED FCM delivery must be handled", handledFirst)
        assertEquals(
            "Exactly 1 OS notification must be posted for the new task",
            1,
            shadowNotificationManager.allNotifications.size
        )
        val postedNotification = shadowNotificationManager.allNotifications.first()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val activeChannel = notificationManager.getNotificationChannel(postedNotification.channelId)
            assertNotNull(activeChannel)
            assertEquals(
                "TASK_CREATED notification channel must use FamilyTasks Signature Pulse sound",
                sigItem.buildResourceUri(context),
                activeChannel!!.sound?.toString()
            )
        }

        // Simulate duplicate delivery (e.g. Firestore snapshot listener or duplicate FCM with base key "assign:taskId:uid")
        val canDispatchDuplicateBaseKey = com.example.notifications.FcmTokenManager.markEventDispatchedIfNew(
            "assign:$newTaskId:$childUid"
        )
        assertFalse(
            "Canonical eventKey deduplication must block duplicate notification from Firestore snapshot / FCM",
            canDispatchDuplicateBaseKey
        )
        val handledDuplicate = com.example.notifications.FamilyMessagingService.handleRemoteMessagePayload(
            context = context,
            data = mapOf(
                "eventType" to com.example.notifications.FcmTokenManager.EVENT_TASK_ASSIGNED,
                "eventKey" to "assign:$newTaskId:$childUid",
                "familyId" to "fam_sig_1",
                "taskId" to newTaskId,
                "targetUid" to childUid,
                "actorUid" to parentUid,
                "title" to "משימה חדשה שובצה עבורך 📋",
                "body" to "שובצה לך משימה חדשה: \"לסדר את החדר\""
            ),
            signedInUidOverride = childUid
        )
        assertFalse("Duplicate TASK_ASSIGNED event must be ignored", handledDuplicate)
        assertEquals(
            "Notification count must remain 1 (no duplicate notification or double sound)",
            1,
            shadowNotificationManager.allNotifications.size
        )

        // =========================================================================
        // 8. Verify Family Alarm and Task Reminder mechanisms continue working without change
        // =========================================================================
        val futureDueTask = com.example.model.FamilyTask(
            id = "task_reminder_still_intact",
            title = "בדיקת תזכורת משימה",
            assignedToMemberId = childUid,
            dateEpochDay = java.time.LocalDate.now().plusDays(1).toEpochDay(),
            dueTimeFormatted = "18:30",
            hasDueTime = true,
            reminderOffset = com.example.model.TaskReminderOffset.MINUTES_10_BEFORE,
            reminderEnabled = true,
            isCompleted = false
        )
        val scheduledReminder = com.example.notifications.TaskReminderScheduler.scheduleOrUpdateTaskReminder(
            context = context,
            task = futureDueTask
        )
        assertTrue("TaskReminderScheduler must continue scheduling task reminders normally", scheduledReminder)
        com.example.notifications.TaskReminderScheduler.cancelReminderForTask(context, futureDueTask.id)
    }
}




