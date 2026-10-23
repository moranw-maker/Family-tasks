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
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
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
}



