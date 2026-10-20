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

        // 3. Verify Friday is supported in SchoolDay for the 6-day Israeli school week (Sunday-Friday)
        assertEquals(6, com.example.model.SchoolDay.entries.size)
        assertTrue(com.example.model.SchoolDay.entries.contains(com.example.model.SchoolDay.FRIDAY))

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
}


