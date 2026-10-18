package com.example.data

import com.example.base.FirestoreEmulatorTestBase
import com.example.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class FamilyFirestoreRuleTest : FirestoreEmulatorTestBase() {

    private val testEmailAlice = "alice@example.com"
    private val testEmailBob = "bob@example.com"
    private val timeoutMs = 15000L

    @Test
    fun testCreateAndGetTask_authenticatedUser_success() = runBlocking {
        signInTestUser(testEmailAlice)
        val repository = FamilyRepository(firestore, auth)

        val task = FamilyTask(
            id = "task_rule_1",
            title = "Test Chores",
            category = TaskCategory.CLEANING,
            assignedToMemberId = "user_maya",
            dateEpochDay = LocalDate.now().toEpochDay(),
            priority = TaskPriority.HIGH,
            points = 25,
            recurrence = TaskRecurrence.DAILY,
            isCompleted = false
        )

        val createResult = withTimeout(timeoutMs) { repository.createTaskInCloud(task) }
        assertTrue("Task creation in cloud should succeed", createResult.isSuccess)
        assertEquals("task_rule_1", createResult.getOrThrow())

        val getResult = withTimeout(timeoutMs) { repository.getTasksFromCloud() }
        assertTrue("Getting tasks should succeed", getResult.isSuccess)
        val tasks = getResult.getOrThrow()
        assertTrue("Returned tasks should contain created task", tasks.any { it.id == "task_rule_1" })
    }

    @Test
    fun testCrossUserIsolation_bobCannotReadAliceTasks() = runBlocking {
        // Alice creates a task
        signInTestUser(testEmailAlice)
        val aliceRepo = FamilyRepository(firestore, auth)
        val task = FamilyTask(
            id = "task_alice_secret",
            title = "Alice Secret Chore",
            category = TaskCategory.HOMEWORK,
            assignedToMemberId = "user_maya",
            dateEpochDay = LocalDate.now().toEpochDay(),
            priority = TaskPriority.LOW,
            points = 10,
            recurrence = TaskRecurrence.ONCE,
            isCompleted = false
        )
        val aliceCreate = withTimeout(timeoutMs) { aliceRepo.createTaskInCloud(task) }
        assertTrue(aliceCreate.isSuccess)

        // Bob signs in and attempts to read
        signInTestUser(testEmailBob)
        val bobRepo = FamilyRepository(firestore, auth)
        val bobTasks = withTimeout(timeoutMs) { bobRepo.getTasksFromCloud() }.getOrThrow()
        assertFalse("Bob should not see Alice's tasks", bobTasks.any { it.id == "task_alice_secret" })
    }

    @Test
    fun testCreateAndGetDocument_authenticatedUser_success() = runBlocking {
        signInTestUser(testEmailAlice)
        val repository = FamilyRepository(firestore, auth)

        val doc = FamilyDocument(
            id = "doc_test_1",
            folderId = "f_school",
            name = "ScienceProject.pdf",
            extension = "PDF",
            sizeFormatted = "2.4 MB",
            uploadedByName = "Alice",
            uploadDateFormatted = "Today",
            isDriveSynced = true,
            driveFileUrl = "https://drive.google.com/test-doc"
        )

        val createResult = withTimeout(timeoutMs) { repository.createDocumentInCloud(doc) }
        assertTrue("Document creation should succeed", createResult.isSuccess)

        val getResult = withTimeout(timeoutMs) { repository.getDocumentsFromCloud() }
        assertTrue("Getting documents should succeed", getResult.isSuccess)
        val docs = getResult.getOrThrow()
        assertTrue("Returned documents should contain created document", docs.any { it.id == "doc_test_1" })
    }

    @Test
    fun testUnauthenticatedUser_accessFails() = runBlocking {
        auth.signOut()
        val repository = FamilyRepository(firestore, auth)

        val task = FamilyTask(
            id = "task_unauth",
            title = "Unauthorized Task",
            category = TaskCategory.OTHER,
            assignedToMemberId = "user_test",
            dateEpochDay = LocalDate.now().toEpochDay()
        )
        val createResult = repository.createTaskInCloud(task)
        assertTrue("Unauthenticated task creation should fail", createResult.isFailure)
    }

    @Test
    fun testUnauthorizedInviteCreation_unauthenticatedFails() = runBlocking {
        auth.signOut()
        val repository = FamilyRepository(firestore, auth)
        val result = repository.createInviteInCloud("FAM-UNAUTH-1", "fam_test")
        assertTrue("Unauthenticated invite creation must fail", result.isFailure)
    }

    @Test
    fun testUnauthorizedInviteCreation_strangerFails() = runBlocking {
        signInTestUser(testEmailBob)
        val bobRepo = FamilyRepository(firestore, auth)
        val result = withTimeout(timeoutMs) {
            bobRepo.createInviteInCloud("FAM-STRANGER-1", "fam_unauthorized_123")
        }
        assertTrue("Stranger creating invite for unauthorized family must fail", result.isFailure)
    }

    @Test
    fun testDocumentOwnershipEnforced_cannotSpoofUserId() = runBlocking {
        val aliceUid = signInTestUser(testEmailAlice)
        val bobUid = signInTestUser(testEmailBob)

        val spoofDocId = "doc_spoofed_${System.currentTimeMillis()}"
        // Bob attempts to create a document claiming Alice's userId
        val spoofedPayload = hashMapOf(
            "id" to spoofDocId,
            "userId" to aliceUid,
            "name" to "Spoofed Document.pdf"
        )
        val result = runCatching {
            withTimeout(5000L) {
                firestore.collection("documents").document(spoofDocId).set(spoofedPayload).awaitTask()
            }
        }
        assertTrue("Creating document with spoofed userId must fail with permission denied", result.isFailure)
    }

    @Test
    fun testStrangerCannotReadOrDeleteAliceDocument() = runBlocking {
        signInTestUser(testEmailAlice)
        val aliceRepo = FamilyRepository(firestore, auth)
        val docId = "doc_alice_priv_${System.currentTimeMillis()}"
        val doc = FamilyDocument(
            id = docId,
            folderId = "f_confidential",
            name = "Confidential.pdf",
            extension = "PDF",
            sizeFormatted = "1.0 MB",
            uploadedByName = "Alice",
            uploadDateFormatted = "Today",
            isDriveSynced = true
        )
        val createResult = withTimeout(timeoutMs) { aliceRepo.createDocumentInCloud(doc) }
        assertTrue(createResult.isSuccess)

        // Bob signs in
        signInTestUser(testEmailBob)

        // Bob cannot read Alice's document
        val readResult = runCatching {
            withTimeout(5000L) {
                firestore.collection("documents").document(docId).get().awaitTask()
            }
        }
        assertTrue("Bob cannot read Alice's private document", readResult.isFailure)

        // Bob cannot delete Alice's document
        val deleteResult = runCatching {
            withTimeout(5000L) {
                firestore.collection("documents").document(docId).delete().awaitTask()
            }
        }
        assertTrue("Bob cannot delete Alice's private document", deleteResult.isFailure)
    }

    @Test
    fun testRevokedInvite_cannotJoinFamily() = runBlocking {
        val bobUid = signInTestUser(testEmailBob)
        val revokedInviteCode = "FAM-REVOKED-9999"
        val famId = "fam_test_revoked_${System.currentTimeMillis()}"

        val joinResult = runCatching {
            withTimeout(5000L) {
                firestore.collection("families").document(famId).collection("members").document(bobUid).set(
                    hashMapOf(
                        "id" to bobUid,
                        "name" to "Bob",
                        "role" to "MEMBER_KID",
                        "earnedPoints" to 0,
                        "spentPoints" to 0,
                        "inviteCode" to revokedInviteCode
                    )
                ).awaitTask()
            }
        }
        assertTrue("Joining family with revoked or invalid invite code must fail", joinResult.isFailure)
    }

    @Test
    fun testUnauthorizedMembershipCreation_notInMemberIdsFails() = runBlocking {
        val aliceUid = signInTestUser(testEmailAlice)
        val famId = "fam_no_outsider_${System.currentTimeMillis()}"

        // Alice creates family with only Alice in memberIds
        withTimeout(timeoutMs) {
            firestore.collection("families").document(famId).set(
                hashMapOf(
                    "id" to famId,
                    "familyName" to "Private Family",
                    "createdById" to aliceUid,
                    "memberIds" to listOf(aliceUid)
                )
            ).awaitTask()
            firestore.collection("families").document(famId).collection("members").document(aliceUid).set(
                hashMapOf(
                    "id" to aliceUid,
                    "name" to "Alice",
                    "role" to "PRIMARY_ADMIN",
                    "earnedPoints" to 0
                )
            ).awaitTask()
        }

        // Bob signs in. Bob is NOT in memberIds and has no invite code
        val bobUid = signInTestUser(testEmailBob)
        val joinResult = runCatching {
            withTimeout(5000L) {
                firestore.collection("families").document(famId).collection("members").document(bobUid).set(
                    hashMapOf(
                        "id" to bobUid,
                        "name" to "Bob",
                        "role" to "MEMBER_KID",
                        "earnedPoints" to 0
                    )
                ).awaitTask()
            }
        }
        assertTrue("Stranger not in memberIds cannot create member profile", joinResult.isFailure)
    }

    @Test
    fun testValidExistingMemberOnboarding_succeeds() = runBlocking {
        val bobUid = signInTestUser(testEmailBob)
        val aliceUid = signInTestUser(testEmailAlice)
        val famId = "fam_onboard_${System.currentTimeMillis()}"

        withTimeout(timeoutMs) {
            firestore.collection("families").document(famId).set(
                hashMapOf(
                    "id" to famId,
                    "familyName" to "Onboarding Family",
                    "createdById" to aliceUid,
                    "memberIds" to listOf(aliceUid)
                )
            ).awaitTask()
            firestore.collection("families").document(famId).collection("members").document(aliceUid).set(
                hashMapOf(
                    "id" to aliceUid,
                    "name" to "Alice",
                    "role" to "PRIMARY_ADMIN",
                    "earnedPoints" to 0
                )
            ).awaitTask()
            firestore.collection("families").document(famId).update(
                "memberIds", listOf(aliceUid, bobUid)
            ).awaitTask()
        }

        signInTestUser(testEmailBob)
        // Attempting to pass an invalid inviteCode via compatibility path must fail
        val spoofedInviteAttempt = runCatching {
            withTimeout(5000L) {
                firestore.collection("families").document(famId).collection("members").document(bobUid).set(
                    hashMapOf(
                        "id" to bobUid,
                        "name" to "Bob",
                        "role" to "MEMBER_KID",
                        "earnedPoints" to 0,
                        "spentPoints" to 0,
                        "inviteCode" to "FAM-FAKE-BYPASS"
                    )
                ).awaitTask()
            }
        }
        assertTrue("Compatibility path must not allow arbitrary inviteCode", spoofedInviteAttempt.isFailure)

        // Legitimate existing member in memberIds onboarding without inviteCode succeeds
        val bobProfileResult = runCatching {
            withTimeout(timeoutMs) {
                firestore.collection("families").document(famId).collection("members").document(bobUid).set(
                    hashMapOf(
                        "id" to bobUid,
                        "name" to "Bob",
                        "role" to "MEMBER_KID",
                        "earnedPoints" to 0,
                        "spentPoints" to 0
                    )
                ).awaitTask()
            }
        }
        assertTrue("Legitimate member in memberIds must be allowed to onboard", bobProfileResult.isSuccess)
    }

    @Test
    fun testFamilyTaskCreationAndVisibilityAcrossTwoAccounts() = runBlocking {
        val bobUid = signInTestUser(testEmailBob)
        val aliceUid = signInTestUser(testEmailAlice)
        val famId = "fam_shared_tasks_${System.currentTimeMillis()}"

        // Setup family with Alice and Bob as members
        withTimeout(timeoutMs) {
            firestore.collection("families").document(famId).set(
                hashMapOf(
                    "id" to famId,
                    "familyName" to "Shared Tasks Family",
                    "createdById" to aliceUid,
                    "memberIds" to listOf(aliceUid)
                )
            ).awaitTask()
            firestore.collection("families").document(famId).collection("members").document(aliceUid).set(
                hashMapOf("id" to aliceUid, "name" to "Alice", "role" to "PRIMARY_ADMIN", "earnedPoints" to 0)
            ).awaitTask()
            firestore.collection("families").document(famId).update(
                "memberIds", listOf(aliceUid, bobUid)
            ).awaitTask()
            firestore.collection("families").document(famId).collection("members").document(bobUid).set(
                hashMapOf("id" to bobUid, "name" to "Bob", "role" to "MEMBER_KID", "earnedPoints" to 0, "spentPoints" to 0)
            ).awaitTask()
        }

        // Alice creates a family task
        val aliceRepo = repo()
        val sharedTaskId = "task_shared_${System.currentTimeMillis()}"
        val familyTask = FamilyTask(
            id = sharedTaskId,
            familyId = famId,
            title = "Clean Shared Living Room",
            category = TaskCategory.CLEANING,
            assignedToMemberId = bobUid,
            dateEpochDay = LocalDate.now().toEpochDay(),
            priority = TaskPriority.HIGH,
            points = 15,
            isCompleted = false
        )
        val createResult = withTimeout(timeoutMs) { aliceRepo.createTaskInCloud(familyTask) }
        assertTrue("Alice creating family task in cloud should succeed", createResult.isSuccess)

        // Bob signs in and reads family tasks
        signInTestUser(testEmailBob)
        val bobRepo = repo()
        val bobFetchResult = withTimeout(timeoutMs) { bobRepo.getTasksFromCloud(famId) }
        assertTrue("Bob should successfully get family tasks", bobFetchResult.isSuccess)
        val bobTasks = bobFetchResult.getOrThrow()
        assertTrue("Bob must see the task created by Alice", bobTasks.any { it.id == sharedTaskId })
    }

    @Test
    fun testCrossFamilyIsolation_memberCannotAccessOtherFamilyTasks() = runBlocking {
        val aliceUid = signInTestUser(testEmailAlice)
        val famAlphaId = "fam_alpha_${System.currentTimeMillis()}"

        // Alice creates Family Alpha
        withTimeout(timeoutMs) {
            firestore.collection("families").document(famAlphaId).set(
                hashMapOf(
                    "id" to famAlphaId,
                    "familyName" to "Family Alpha",
                    "createdById" to aliceUid,
                    "memberIds" to listOf(aliceUid)
                )
            ).awaitTask()
            firestore.collection("families").document(famAlphaId).collection("members").document(aliceUid).set(
                hashMapOf("id" to aliceUid, "name" to "Alice", "role" to "PRIMARY_ADMIN", "earnedPoints" to 0)
            ).awaitTask()
        }

        // Alice creates a task in Family Alpha
        val aliceRepo = repo()
        val alphaTaskId = "task_alpha_${System.currentTimeMillis()}"
        val alphaTask = FamilyTask(
            id = alphaTaskId,
            familyId = famAlphaId,
            title = "Alpha Family Secret Chore",
            category = TaskCategory.HOMEWORK,
            assignedToMemberId = aliceUid,
            dateEpochDay = LocalDate.now().toEpochDay(),
            points = 20
        )
        val createAlpha = withTimeout(timeoutMs) { aliceRepo.createTaskInCloud(alphaTask) }
        assertTrue(createAlpha.isSuccess)

        // Bob signs in. Bob has no membership in Family Alpha
        signInTestUser(testEmailBob)
        val bobRepo = repo()

        // Bob attempting to read Family Alpha tasks must fail
        val bobReadResult = withTimeout(timeoutMs) { bobRepo.getTasksFromCloud(famAlphaId) }
        assertTrue("Bob reading Family Alpha tasks must fail with permission denied", bobReadResult.isFailure)

        // Bob attempting to write a task into Family Alpha must fail
        val bobIntruderTask = FamilyTask(
            id = "task_intruder_${System.currentTimeMillis()}",
            familyId = famAlphaId,
            title = "Intruder Task",
            category = TaskCategory.OTHER,
            assignedToMemberId = "user_bob",
            dateEpochDay = LocalDate.now().toEpochDay()
        )
        val bobWriteResult = withTimeout(timeoutMs) { bobRepo.createTaskInCloud(bobIntruderTask) }
        assertTrue("Bob writing into Family Alpha tasks must fail with permission denied", bobWriteResult.isFailure)
    }

    @Test
    fun testTaskUpdatesAndCompletionSynchronization() = runBlocking {
        val bobUid = signInTestUser(testEmailBob)
        val aliceUid = signInTestUser(testEmailAlice)
        val famId = "fam_sync_flow_${System.currentTimeMillis()}"

        // Setup family with Alice and Bob
        withTimeout(timeoutMs) {
            firestore.collection("families").document(famId).set(
                hashMapOf(
                    "id" to famId,
                    "familyName" to "Sync Flow Family",
                    "createdById" to aliceUid,
                    "memberIds" to listOf(aliceUid)
                )
            ).awaitTask()
            firestore.collection("families").document(famId).collection("members").document(aliceUid).set(
                hashMapOf("id" to aliceUid, "name" to "Alice", "role" to "PRIMARY_ADMIN", "earnedPoints" to 0)
            ).awaitTask()
            firestore.collection("families").document(famId).update(
                "memberIds", listOf(aliceUid, bobUid)
            ).awaitTask()
            firestore.collection("families").document(famId).collection("members").document(bobUid).set(
                hashMapOf("id" to bobUid, "name" to "Bob", "role" to "MEMBER_KID", "earnedPoints" to 0, "spentPoints" to 0)
            ).awaitTask()
        }

        // Alice creates task assigned to Bob
        val aliceRepo = repo()
        val syncTaskId = "task_sync_${System.currentTimeMillis()}"
        val initialTask = FamilyTask(
            id = syncTaskId,
            familyId = famId,
            title = "Wash The Dishes",
            category = TaskCategory.DISHES,
            assignedToMemberId = bobUid,
            dateEpochDay = LocalDate.now().toEpochDay(),
            points = 20,
            isCompleted = false
        )
        val createResult = withTimeout(timeoutMs) { aliceRepo.createTaskInCloud(initialTask) }
        assertTrue(createResult.isSuccess)

        // Bob signs in, completes the task
        signInTestUser(testEmailBob)
        val bobRepo = repo()
        val completedTask = initialTask.copy(
            isCompleted = true,
            completedAtMillis = System.currentTimeMillis(),
            completedByMemberId = bobUid
        )
        val bobUpdateResult = withTimeout(timeoutMs) { bobRepo.updateTaskInCloud(completedTask) }
        assertTrue("Bob completing assigned task should succeed", bobUpdateResult.isSuccess)

        // Alice signs in and verifies synchronization
        signInTestUser(testEmailAlice)
        val aliceRepo2 = repo()
        val aliceFetch = withTimeout(timeoutMs) { aliceRepo2.getTasksFromCloud(famId) }.getOrThrow()
        val updatedTaskOnCloud = aliceFetch.find { it.id == syncTaskId }
        assertNotNull("Task must exist in cloud", updatedTaskOnCloud)
        assertTrue("Task must be marked completed in cloud", updatedTaskOnCloud!!.isCompleted)
        assertEquals("CompletedByMemberId must match Bob", bobUid, updatedTaskOnCloud.completedByMemberId)
    }

    @Test
    fun testNewUserJoiningWithValidInvitation_succeedsAndUpdatesMemberIds() = runBlocking {
        val aliceUid = signInTestUser(testEmailAlice)
        val ts = System.currentTimeMillis()
        val famId = "fam_valid_join_$ts"
        val inviteCode = "FAM-VALID-$ts"

        withTimeout(timeoutMs) {
            firestore.collection("families").document(famId).set(
                hashMapOf(
                    "id" to famId,
                    "familyName" to "Valid Join Family",
                    "createdById" to aliceUid,
                    "memberIds" to listOf(aliceUid)
                )
            ).awaitTask()
            firestore.collection("families").document(famId).collection("members").document(aliceUid).set(
                hashMapOf("id" to aliceUid, "name" to "Alice", "role" to "PRIMARY_ADMIN", "earnedPoints" to 0)
            ).awaitTask()
        }

        val aliceRepo = repo()
        val createInviteRes = withTimeout(timeoutMs) { aliceRepo.createInviteInCloud(inviteCode, famId) }
        assertTrue("Alice should create invite in cloud", createInviteRes.isSuccess)

        // Bob signs in as a new user and joins using the valid invitation
        val bobUid = signInTestUser(testEmailBob)
        val bobRepo = repo()
        val joinRes = withTimeout(timeoutMs) {
            bobRepo.joinFamilyWithInviteInCloud(inviteCode, famId, "Bob")
        }
        assertTrue("New user joining with valid invitation must succeed: ${joinRes.exceptionOrNull()}", joinRes.isSuccess)
        assertEquals(famId, joinRes.getOrThrow())

        // Verify Bob's member document exists and Bob's UID is in family.memberIds
        val memDoc = withTimeout(timeoutMs) {
            firestore.collection("families").document(famId).collection("members").document(bobUid).get().awaitTask()
        }
        assertTrue("Bob member document must exist after joining", memDoc.exists())
        assertEquals("CHILD", memDoc.getString("role"))

        val famDoc = withTimeout(timeoutMs) {
            firestore.collection("families").document(famId).get().awaitTask()
        }
        val memberIds = (famDoc.get("memberIds") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        assertTrue("Family memberIds must contain Bob's UID after atomic redemption", memberIds.contains(bobUid))
    }

    @Test
    fun testExistingMemberReconnecting_succeeds() = runBlocking {
        val aliceUid = signInTestUser(testEmailAlice)
        val ts = System.currentTimeMillis()
        val famId = "fam_reconnect_$ts"
        val inviteCode = "FAM-RECON-$ts"

        withTimeout(timeoutMs) {
            firestore.collection("families").document(famId).set(
                hashMapOf(
                    "id" to famId,
                    "familyName" to "Reconnect Family",
                    "createdById" to aliceUid,
                    "memberIds" to listOf(aliceUid)
                )
            ).awaitTask()
            firestore.collection("families").document(famId).collection("members").document(aliceUid).set(
                hashMapOf("id" to aliceUid, "name" to "Alice", "role" to "PRIMARY_ADMIN", "earnedPoints" to 0)
            ).awaitTask()
        }

        val aliceRepo = repo()
        assertTrue(withTimeout(timeoutMs) { aliceRepo.createInviteInCloud(inviteCode, famId) }.isSuccess)

        // Bob joins initially
        val bobUid = signInTestUser(testEmailBob)
        val bobRepo1 = repo()
        assertTrue(withTimeout(timeoutMs) { bobRepo1.joinFamilyWithInviteInCloud(inviteCode, famId, "Bob") }.isSuccess)

        // Now Bob reconnects in a new repository session (invite is already consumed isUsed = true)
        val bobRepo2 = repo()
        val reconnectRes = withTimeout(timeoutMs) {
            bobRepo2.reconnectExistingMemberInCloud(famId, "Bob")
        }
        assertTrue("Existing member reconnecting must succeed without re-consuming invite", reconnectRes.isSuccess)
        assertEquals(bobUid, reconnectRes.getOrThrow().id)
    }

    @Test
    fun testInvalidOrExpiredInvitation_cannotJoin() = runBlocking {
        val aliceUid = signInTestUser(testEmailAlice)
        val ts = System.currentTimeMillis()
        val famId = "fam_inv_exp_$ts"
        val expiredCode = "FAM-EXP-$ts"

        withTimeout(timeoutMs) {
            firestore.collection("families").document(famId).set(
                hashMapOf(
                    "id" to famId,
                    "familyName" to "Expired Invite Family",
                    "createdById" to aliceUid,
                    "memberIds" to listOf(aliceUid)
                )
            ).awaitTask()
            firestore.collection("families").document(famId).collection("members").document(aliceUid).set(
                hashMapOf("id" to aliceUid, "name" to "Alice", "role" to "PRIMARY_ADMIN", "earnedPoints" to 0)
            ).awaitTask()
            // Create an invite that is valid at creation, then mark revoked/expired by parent
            firestore.collection("family_invites").document(expiredCode).set(
                hashMapOf(
                    "inviteCode" to expiredCode,
                    "familyId" to famId,
                    "createdById" to aliceUid,
                    "expiresAtMillis" to (System.currentTimeMillis() + 60000L)
                )
            ).awaitTask()
            firestore.collection("family_invites").document(expiredCode).update(
                "expiresAtMillis", System.currentTimeMillis() - 60000L
            ).awaitTask()
        }

        // Bob signs in and tries both a non-existent code and the expired code
        val bobUid = signInTestUser(testEmailBob)
        val bobRepo = repo()

        val invalidRes = withTimeout(timeoutMs) {
            bobRepo.joinFamilyWithInviteInCloud("FAM-NONEXISTENT-$ts", famId, "Bob")
        }
        assertTrue("Joining with non-existent invite code must fail", invalidRes.isFailure)

        val expiredRes = withTimeout(timeoutMs) {
            bobRepo.joinFamilyWithInviteInCloud(expiredCode, famId, "Bob")
        }
        assertTrue("Joining with expired invite code must fail", expiredRes.isFailure)

        // Verify Bob was NOT added to memberIds
        signInTestUser(testEmailAlice)
        val famDoc = withTimeout(timeoutMs) {
            firestore.collection("families").document(famId).get().awaitTask()
        }
        val memberIds = (famDoc.get("memberIds") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        assertFalse("Bob must not be in memberIds after failed invite attempts", memberIds.contains(bobUid))
    }

    @Test
    fun testInvitationReplay_cannotReuseConsumedInvitation() = runBlocking {
        val aliceUid = signInTestUser(testEmailAlice)
        val ts = System.currentTimeMillis()
        val famId = "fam_replay_$ts"
        val inviteCode = "FAM-REPLAY-$ts"

        withTimeout(timeoutMs) {
            firestore.collection("families").document(famId).set(
                hashMapOf(
                    "id" to famId,
                    "familyName" to "Replay Test Family",
                    "createdById" to aliceUid,
                    "memberIds" to listOf(aliceUid)
                )
            ).awaitTask()
            firestore.collection("families").document(famId).collection("members").document(aliceUid).set(
                hashMapOf("id" to aliceUid, "name" to "Alice", "role" to "PRIMARY_ADMIN", "earnedPoints" to 0)
            ).awaitTask()
        }

        val aliceRepo = repo()
        assertTrue(withTimeout(timeoutMs) { aliceRepo.createInviteInCloud(inviteCode, famId) }.isSuccess)

        // Bob redeems the invitation once -> succeeds
        signInTestUser(testEmailBob)
        val bobRepo = repo()
        val firstJoin = withTimeout(timeoutMs) { bobRepo.joinFamilyWithInviteInCloud(inviteCode, famId, "Bob") }
        assertTrue("First redemption of valid invite must succeed", firstJoin.isSuccess)

        // Replaying the same consumed invitation again -> MUST FAIL
        val replayJoin = withTimeout(timeoutMs) { bobRepo.joinFamilyWithInviteInCloud(inviteCode, famId, "Bob") }
        assertTrue("Replaying an already-consumed invitation must fail", replayJoin.isFailure)
    }

    @Test
    fun testUnauthorizedMemberIdsModification_fails() = runBlocking {
        val aliceUid = signInTestUser(testEmailAlice)
        val ts = System.currentTimeMillis()
        val famId = "fam_unauth_memids_$ts"

        withTimeout(timeoutMs) {
            firestore.collection("families").document(famId).set(
                hashMapOf(
                    "id" to famId,
                    "familyName" to "Protected MemberIds Family",
                    "createdById" to aliceUid,
                    "memberIds" to listOf(aliceUid)
                )
            ).awaitTask()
            firestore.collection("families").document(famId).collection("members").document(aliceUid).set(
                hashMapOf("id" to aliceUid, "name" to "Alice", "role" to "PRIMARY_ADMIN", "earnedPoints" to 0)
            ).awaitTask()
        }

        // Bob (not a member, no member doc) attempts to add his UID directly to family.memberIds -> MUST FAIL
        val bobUid = signInTestUser(testEmailBob)
        val directAddSelf = runCatching {
            withTimeout(5000L) {
                firestore.collection("families").document(famId)
                    .update("memberIds", com.google.firebase.firestore.FieldValue.arrayUnion(bobUid))
                    .awaitTask()
            }
        }
        assertTrue("Outsider without member doc cannot add self to memberIds", directAddSelf.isFailure)
    }

    @Test
    fun testTwoUsersAttemptingToRedeemSameInvitation_secondUserFails() = runBlocking {
        val aliceUid = signInTestUser(testEmailAlice)
        val ts = System.currentTimeMillis()
        val famId = "fam_two_users_$ts"
        val inviteCode = "FAM-SINGLE-USE-$ts"

        withTimeout(timeoutMs) {
            firestore.collection("families").document(famId).set(
                hashMapOf(
                    "id" to famId,
                    "familyName" to "Single Use Invite Family",
                    "createdById" to aliceUid,
                    "memberIds" to listOf(aliceUid)
                )
            ).awaitTask()
            firestore.collection("families").document(famId).collection("members").document(aliceUid).set(
                hashMapOf("id" to aliceUid, "name" to "Alice", "role" to "PRIMARY_ADMIN", "earnedPoints" to 0)
            ).awaitTask()
        }

        val aliceRepo = repo()
        assertTrue(withTimeout(timeoutMs) { aliceRepo.createInviteInCloud(inviteCode, famId) }.isSuccess)

        // First user (Bob) redeems the invitation -> SUCCEEDS
        val bobUid = signInTestUser(testEmailBob)
        val bobRepo = repo()
        val bobJoin = withTimeout(timeoutMs) {
            bobRepo.joinFamilyWithInviteInCloud(inviteCode, famId, "Bob")
        }
        assertTrue("First user (Bob) redeeming invitation should succeed", bobJoin.isSuccess)

        // Second user (Charlie) attempts to redeem the SAME invitation -> MUST FAIL
        val charlieUid = signInTestUser("charlie@example.com")
        val charlieRepo = repo()
        val charlieJoin = withTimeout(timeoutMs) {
            charlieRepo.joinFamilyWithInviteInCloud(inviteCode, famId, "Charlie")
        }
        assertTrue("Second user (Charlie) attempting to redeem already-used invitation must fail", charlieJoin.isFailure)

        // Verify Bob is in memberIds, and Charlie is NOT in memberIds and has no member doc
        signInTestUser(testEmailAlice)
        val famDoc = withTimeout(timeoutMs) {
            firestore.collection("families").document(famId).get().awaitTask()
        }
        val memberIds = (famDoc.get("memberIds") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        assertTrue("Bob must be in memberIds", memberIds.contains(bobUid))
        assertFalse("Charlie must NOT be in memberIds", memberIds.contains(charlieUid))

        val charlieMemDoc = withTimeout(timeoutMs) {
            firestore.collection("families").document(famId).collection("members").document(charlieUid).get().awaitTask()
        }
        assertFalse("Charlie member doc must NOT exist", charlieMemDoc.exists())
    }

    @Test
    fun testInitialFamilyCreation_unauthorizedUidsInMemberIdsFails() = runBlocking {
        val bobUid = signInTestUser(testEmailBob)
        val aliceUid = signInTestUser(testEmailAlice)
        val ts = System.currentTimeMillis()

        // 1. Alice attempting to create family with both Alice and Bob in initial memberIds -> MUST FAIL
        val extraUidAttempt = runCatching {
            withTimeout(5000L) {
                firestore.collection("families").document("fam_extra_uid_$ts").set(
                    hashMapOf(
                        "id" to "fam_extra_uid_$ts",
                        "familyName" to "Unauthorized Extra UID Family",
                        "createdById" to aliceUid,
                        "memberIds" to listOf(aliceUid, bobUid)
                    )
                ).awaitTask()
            }
        }
        assertTrue("Initial family creation with arbitrary extra UID in memberIds must fail", extraUidAttempt.isFailure)

        // 2. Alice attempting to create family with only Bob's UID in memberIds -> MUST FAIL
        val wrongUidAttempt = runCatching {
            withTimeout(5000L) {
                firestore.collection("families").document("fam_wrong_uid_$ts").set(
                    hashMapOf(
                        "id" to "fam_wrong_uid_$ts",
                        "familyName" to "Wrong UID Family",
                        "createdById" to aliceUid,
                        "memberIds" to listOf(bobUid)
                    )
                ).awaitTask()
            }
        }
        assertTrue("Initial family creation with non-creator UID in memberIds must fail", wrongUidAttempt.isFailure)

        // 3. Alice attempting to create family with empty memberIds -> MUST FAIL
        val emptyMemberIdsAttempt = runCatching {
            withTimeout(5000L) {
                firestore.collection("families").document("fam_empty_ids_$ts").set(
                    hashMapOf(
                        "id" to "fam_empty_ids_$ts",
                        "familyName" to "Empty MemberIds Family",
                        "createdById" to aliceUid,
                        "memberIds" to emptyList<String>()
                    )
                ).awaitTask()
            }
        }
        assertTrue("Initial family creation with empty memberIds must fail", emptyMemberIdsAttempt.isFailure)
    }

    @Test
    fun testValidFamilyCreation_onlyAuthenticatedCreatorSucceeds() = runBlocking {
        val aliceUid = signInTestUser(testEmailAlice)
        val famId = "fam_valid_create_${System.currentTimeMillis()}"

        val createRes = runCatching {
            withTimeout(timeoutMs) {
                firestore.collection("families").document(famId).set(
                    hashMapOf(
                        "id" to famId,
                        "familyName" to "Valid Creator Family",
                        "createdById" to aliceUid,
                        "memberIds" to listOf(aliceUid)
                    )
                ).awaitTask()
            }
        }
        assertTrue("Valid family creation with only authenticated creator in memberIds must succeed", createRes.isSuccess)

        val snap = withTimeout(timeoutMs) {
            firestore.collection("families").document(famId).get().awaitTask()
        }
        assertTrue(snap.exists())
        assertEquals(listOf(aliceUid), snap.get("memberIds"))
    }

    @Test
    fun testChildTaskCompletion_sendsOnlyPermittedFieldsAndSucceeds() = runBlocking {
        val bobUid = signInTestUser(testEmailBob)
        val aliceUid = signInTestUser(testEmailAlice)
        val ts = System.currentTimeMillis()
        val famId = "fam_child_comp_$ts"

        withTimeout(timeoutMs) {
            firestore.collection("families").document(famId).set(
                hashMapOf(
                    "id" to famId,
                    "familyName" to "Child Completion Family",
                    "createdById" to aliceUid,
                    "memberIds" to listOf(aliceUid)
                )
            ).awaitTask()
            firestore.collection("families").document(famId).collection("members").document(aliceUid).set(
                hashMapOf("id" to aliceUid, "name" to "Alice", "role" to "PRIMARY_ADMIN", "earnedPoints" to 0)
            ).awaitTask()
            firestore.collection("families").document(famId).update(
                "memberIds", listOf(aliceUid, bobUid)
            ).awaitTask()
            firestore.collection("families").document(famId).collection("members").document(bobUid).set(
                hashMapOf("id" to bobUid, "name" to "Bob", "role" to "MEMBER_KID", "earnedPoints" to 0, "spentPoints" to 0)
            ).awaitTask()
        }

        val aliceRepo = repo()
        val taskId = "task_child_comp_$ts"
        val task = FamilyTask(
            id = taskId,
            familyId = famId,
            title = "Feed the Dog",
            description = "Morning bowl",
            category = TaskCategory.PETS,
            assignedToMemberId = bobUid,
            dateEpochDay = LocalDate.now().toEpochDay(),
            points = 15,
            isCompleted = false
        )
        assertTrue(withTimeout(timeoutMs) { aliceRepo.createTaskInCloud(task) }.isSuccess)

        // Bob (child) completes the task via completeTaskInCloud (sends only completion fields)
        signInTestUser(testEmailBob)
        val bobRepo = repo()
        val completeRes = withTimeout(timeoutMs) {
            bobRepo.completeTaskInCloud(
                taskId = taskId,
                familyId = famId,
                isCompleted = true,
                completedByMemberId = bobUid
            )
        }
        assertTrue("Child completing assigned task via completeTaskInCloud must succeed", completeRes.isSuccess)

        val tasksOnCloud = withTimeout(timeoutMs) { bobRepo.getTasksFromCloud(famId) }.getOrThrow()
        val completed = tasksOnCloud.find { it.id == taskId }
        assertNotNull(completed)
        assertTrue(completed!!.isCompleted)
        assertEquals(bobUid, completed.completedByMemberId)
    }

    @Test
    fun testUnauthorizedChildTaskEdits_fails() = runBlocking {
        val bobUid = signInTestUser(testEmailBob)
        val aliceUid = signInTestUser(testEmailAlice)
        val ts = System.currentTimeMillis()
        val famId = "fam_child_unauth_edit_$ts"

        withTimeout(timeoutMs) {
            firestore.collection("families").document(famId).set(
                hashMapOf(
                    "id" to famId,
                    "familyName" to "Protected Task Edit Family",
                    "createdById" to aliceUid,
                    "memberIds" to listOf(aliceUid)
                )
            ).awaitTask()
            firestore.collection("families").document(famId).collection("members").document(aliceUid).set(
                hashMapOf("id" to aliceUid, "name" to "Alice", "role" to "PRIMARY_ADMIN", "earnedPoints" to 0)
            ).awaitTask()
            firestore.collection("families").document(famId).update(
                "memberIds", listOf(aliceUid, bobUid)
            ).awaitTask()
            firestore.collection("families").document(famId).collection("members").document(bobUid).set(
                hashMapOf("id" to bobUid, "name" to "Bob", "role" to "MEMBER_KID", "earnedPoints" to 0, "spentPoints" to 0)
            ).awaitTask()
        }

        val aliceRepo = repo()
        val taskId = "task_protected_$ts"
        val task = FamilyTask(
            id = taskId,
            familyId = famId,
            title = "Math Homework",
            description = "Chapter 4 exercises",
            category = TaskCategory.HOMEWORK,
            assignedToMemberId = bobUid,
            dateEpochDay = LocalDate.now().toEpochDay(),
            points = 20,
            isCompleted = false,
            approvedByParent = false
        )
        assertTrue(withTimeout(timeoutMs) { aliceRepo.createTaskInCloud(task) }.isSuccess)

        // Bob (child) attempts direct Firestore edits to non-completion fields (title, points, approvedByParent) -> MUST FAIL
        signInTestUser(testEmailBob)
        val bobRepo = repo()
        val taskDocRef = firestore.collection("families").document(famId).collection("tasks").document(taskId)

        val editTitleAttempt = runCatching {
            withTimeout(5000L) {
                taskDocRef.update(mapOf("title" to "Play Games Instead", "isCompleted" to true)).awaitTask()
            }
        }
        assertTrue("Child editing task title must fail", editTitleAttempt.isFailure)

        val editPointsAttempt = runCatching {
            withTimeout(5000L) {
                taskDocRef.update(mapOf("points" to 500, "isCompleted" to true)).awaitTask()
            }
        }
        assertTrue("Child editing task points must fail", editPointsAttempt.isFailure)

        val editApprovalAttempt = runCatching {
            withTimeout(5000L) {
                taskDocRef.update(mapOf("approvedByParent" to true, "isCompleted" to true)).awaitTask()
            }
        }
        assertTrue("Child self-approving task must fail", editApprovalAttempt.isFailure)

        // Verify original task remains unmodified in cloud
        val cloudTask = withTimeout(timeoutMs) { bobRepo.getTasksFromCloud(famId) }.getOrThrow().find { it.id == taskId }
        assertNotNull(cloudTask)
        assertEquals("Math Homework", cloudTask!!.title)
        assertEquals(20, cloudTask.points)
        assertFalse(cloudTask.approvedByParent)
        assertFalse(cloudTask.isCompleted)
    }

    @Test
    fun testParentEditsTaskWhileChildCompletesWithStaleLocalData_preservesParentEditsAndCompletes() = runBlocking {
        val bobUid = signInTestUser(testEmailBob)
        val aliceUid = signInTestUser(testEmailAlice)
        val ts = System.currentTimeMillis()
        val famId = "fam_stale_child_complete_$ts"

        withTimeout(timeoutMs) {
            firestore.collection("families").document(famId).set(
                hashMapOf(
                    "id" to famId,
                    "familyName" to "Concurrent Edit & Complete Family",
                    "createdById" to aliceUid,
                    "memberIds" to listOf(aliceUid)
                )
            ).awaitTask()
            firestore.collection("families").document(famId).collection("members").document(aliceUid).set(
                hashMapOf("id" to aliceUid, "name" to "Alice", "role" to "PRIMARY_ADMIN", "earnedPoints" to 0)
            ).awaitTask()
            firestore.collection("families").document(famId).update(
                "memberIds", listOf(aliceUid, bobUid)
            ).awaitTask()
            firestore.collection("families").document(famId).collection("members").document(bobUid).set(
                hashMapOf("id" to bobUid, "name" to "Bob", "role" to "MEMBER_KID", "earnedPoints" to 0, "spentPoints" to 0)
            ).awaitTask()
        }

        // 1. Alice (parent) creates the initial task assigned to Bob
        val aliceRepo = repo()
        val taskId = "task_concurrent_$ts"
        val initialTaskOnChildDevice = FamilyTask(
            id = taskId,
            familyId = famId,
            title = "Clean Room",
            description = "Put away toys",
            category = TaskCategory.BEDROOM,
            assignedToMemberId = bobUid,
            dateEpochDay = LocalDate.now().toEpochDay(),
            priority = TaskPriority.LOW,
            points = 10,
            isCompleted = false,
            approvedByParent = false
        )
        assertTrue(withTimeout(timeoutMs) { aliceRepo.createTaskInCloud(initialTaskOnChildDevice) }.isSuccess)

        // 2. Alice (parent) edits the task on her device (changing title, description, priority, and points)
        val parentEditedTask = initialTaskOnChildDevice.copy(
            title = "Clean Room & Vacuum Rug",
            description = "Put away toys and vacuum the bedroom rug",
            priority = TaskPriority.HIGH,
            points = 35
        )
        val parentUpdateRes = withTimeout(timeoutMs) { aliceRepo.updateTaskInCloud(parentEditedTask) }
        assertTrue("Parent full task edit should succeed", parentUpdateRes.isSuccess)

        // 3. Bob (child) completes the task on his device using his STALE local copy of initialTaskOnChildDevice
        signInTestUser(testEmailBob)
        val bobRepo = repo()
        val staleChildCompletion = initialTaskOnChildDevice.copy(
            isCompleted = true,
            completedAtMillis = System.currentTimeMillis(),
            completedByMemberId = bobUid
        )
        val childCompleteRes = withTimeout(timeoutMs) { bobRepo.updateTaskInCloud(staleChildCompletion) }
        assertTrue(
            "Child completing task using stale local data must succeed via completion-only update path: ${childCompleteRes.exceptionOrNull()}",
            childCompleteRes.isSuccess
        )

        // 4. Verify that the task is completed AND none of the parent's updated fields were overwritten
        signInTestUser(testEmailAlice)
        val finalTask = withTimeout(timeoutMs) { aliceRepo.getTasksFromCloud(famId) }.getOrThrow().find { it.id == taskId }
        assertNotNull("Task must exist in cloud", finalTask)
        assertTrue("Task must be marked completed", finalTask!!.isCompleted)
        assertEquals("completedByMemberId must be Bob", bobUid, finalTask.completedByMemberId)
        assertEquals("Parent's updated title must be preserved", "Clean Room & Vacuum Rug", finalTask.title)
        assertEquals("Parent's updated description must be preserved", "Put away toys and vacuum the bedroom rug", finalTask.description)
        assertEquals("Parent's updated priority must be preserved", TaskPriority.HIGH, finalTask.priority)
        assertEquals("Parent's updated points must be preserved", 35, finalTask.points)
    }

    @Test
    fun testV16FirstTimeLoginWithoutMembership_returnsNoMembershipAndDoesNotAutoCreateFamily() = runBlocking {
        val freshEmail = "fresh_user_${System.currentTimeMillis()}@example.com"
        val freshUid = signInTestUser(freshEmail)
        val freshRepo = repo()

        val state = withTimeout(timeoutMs) {
            freshRepo.checkAuthenticatedUserMembership(
                uid = freshUid,
                email = freshEmail,
                displayName = "Fresh User"
            )
        }
        assertTrue(
            "First-time login without family membership must return NoMembership state, not auto-create family",
            state is FamilyMembershipState.NoMembership
        )

        // Verify user document was not given a familyId
        val userDoc = withTimeout(timeoutMs) {
            firestore.collection("users").document(freshUid).get().awaitTask()
        }
        assertFalse("Fresh user without family must not have auto-created user doc with familyId", userDoc.exists() && !userDoc.getString("familyId").isNullOrBlank())
    }

    @Test
    fun testV16CreateFamilyAndRoleSpecificInvitations_parentAndChildFlows() = runBlocking {
        val parentEmail = "parent_v16_${System.currentTimeMillis()}@example.com"
        val childEmail = "child_v16_${System.currentTimeMillis()}@example.com"
        val secondParentEmail = "parent2_v16_${System.currentTimeMillis()}@example.com"

        // 1. First parent signs in and explicitly creates a family -> receives PARENT role
        val parentUid = signInTestUser(parentEmail)
        val parentRepo = repo()
        val createFamRes = withTimeout(timeoutMs) {
            parentRepo.createFamilyInCloud(
                familyName = "משפחת כהן",
                creatorName = "יעל כהן",
                creatorEmail = parentEmail,
                photoUrl = "https://example.com/yael.jpg"
            )
        }
        assertTrue("Explicit family creation must succeed: ${createFamRes.exceptionOrNull()}", createFamRes.isSuccess)
        val createdMembership = createFamRes.getOrThrow()
        assertEquals("Creator must be assigned PARENT role", UserRole.PARENT, createdMembership.role)
        val familyId = createdMembership.familyId

        // 2. Parent generates a CHILD invitation -> stores CHILD
        val childInviteRes = withTimeout(timeoutMs) {
            parentRepo.createRoleInvitationInCloud(
                invitedRole = UserRole.CHILD,
                inviteeNameHint = "נועה",
                familyIdOverride = familyId
            )
        }
        assertTrue("Parent creating CHILD invitation must succeed: ${childInviteRes.exceptionOrNull()}", childInviteRes.isSuccess)
        val childInvite = childInviteRes.getOrThrow()
        assertEquals(FamilyRole.CHILD, childInvite.invitedRole)
        assertEquals(InvitationStatus.PENDING, childInvite.status)

        // 3. Parent generates a PARENT invitation -> stores PARENT
        val parentInviteRes = withTimeout(timeoutMs) {
            parentRepo.createRoleInvitationInCloud(
                invitedRole = UserRole.PARENT,
                inviteeNameHint = "דוד",
                familyIdOverride = familyId
            )
        }
        assertTrue("Parent creating PARENT invitation must succeed", parentInviteRes.isSuccess)
        val parentInvite = parentInviteRes.getOrThrow()
        assertEquals(FamilyRole.PARENT, parentInvite.invitedRole)

        // 4. Child signs in, validates the invitation, sees family name & CHILD role, and redeems it
        val childUid = signInTestUser(childEmail)
        val childRepo = repo()
        val validation = withTimeout(timeoutMs) {
            childRepo.validateInvitationCodeInCloud(childInvite.inviteCode)
        }
        assertTrue("Child invitation validation must succeed", validation is InvitationValidationResult.Valid)
        val validPreview = validation as InvitationValidationResult.Valid
        assertEquals("משפחת כהן", validPreview.familyName)
        assertEquals(UserRole.CHILD, validPreview.invitedRole)

        // Child attempts to spoof role = PARENT while redeeming a CHILD invitation -> MUST FAIL security rules!
        val spoofParentAttempt = runCatching {
            withTimeout(5000L) {
                firestore.collection("families").document(familyId).collection("members").document(childUid).set(
                    hashMapOf(
                        "id" to childUid,
                        "uid" to childUid,
                        "name" to "Spoofed Child",
                        "role" to "PARENT",
                        "inviteCode" to childInvite.inviteCode
                    )
                ).awaitTask()
            }
        }
        assertTrue("Recipient of CHILD invitation must not be able to elevate role to PARENT", spoofParentAttempt.isFailure)

        // Legitimate redemption of CHILD invitation -> assigns CHILD and never PARENT
        val redeemChildRes = withTimeout(timeoutMs) {
            childRepo.redeemInvitationAndJoinFamilyInCloud(
                inviteCode = childInvite.inviteCode,
                memberDisplayName = "נועה כהן",
                photoUrl = "https://example.com/noa.jpg"
            )
        }
        assertTrue("Redeeming valid CHILD invitation must succeed: ${redeemChildRes.exceptionOrNull()}", redeemChildRes.isSuccess)
        val childMemberState = redeemChildRes.getOrThrow()
        assertEquals("Redeemed CHILD invitation must assign CHILD role", UserRole.CHILD, childMemberState.role)

        // Verify in Firestore that child is in memberIds/memberUids, NOT in adminUids, and has role = CHILD
        val famDocAfterChild = withTimeout(timeoutMs) {
            firestore.collection("families").document(familyId).get().awaitTask()
        }
        val adminUidsAfterChild = (famDocAfterChild.get("adminUids") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        assertFalse("Child UID must never be added to adminUids", adminUidsAfterChild.contains(childUid))

        // 5. Child cannot create tasks, delete tasks, invite members, or promote self to PARENT in Firestore
        val illegalChildTask = FamilyTask(
            id = "task_illegal_by_child_${System.currentTimeMillis()}",
            familyId = familyId,
            title = "Child Created Task",
            category = TaskCategory.OTHER,
            assignedToMemberId = parentUid,
            dateEpochDay = LocalDate.now().toEpochDay()
        )
        val childCreateTaskRes = withTimeout(timeoutMs) { childRepo.createTaskInCloud(illegalChildTask) }
        assertTrue("CHILD creating a task in Firestore must fail", childCreateTaskRes.isFailure)

        val childCreateInviteRes = withTimeout(timeoutMs) {
            childRepo.createRoleInvitationInCloud(UserRole.CHILD, "Friend", familyId)
        }
        assertTrue("CHILD creating an invitation in Firestore must fail", childCreateInviteRes.isFailure)

        val childSelfPromoteAttempt = runCatching {
            withTimeout(5000L) {
                firestore.collection("families").document(familyId).collection("members").document(childUid)
                    .update("role", "PARENT").awaitTask()
            }
        }
        assertTrue("CHILD promoting self to PARENT in Firestore must fail", childSelfPromoteAttempt.isFailure)

        // 6. Second parent redeems the PARENT invitation -> receives PARENT role and is added to adminUids
        val secondParentUid = signInTestUser(secondParentEmail)
        val secondParentRepo = repo()
        val redeemParentRes = withTimeout(timeoutMs) {
            secondParentRepo.redeemInvitationAndJoinFamilyInCloud(
                inviteCode = parentInvite.inviteCode,
                memberDisplayName = "דוד כהן",
                familyIdentity = FamilyIdentity.FATHER
            )
        }
        assertTrue("Redeeming valid PARENT invitation must succeed: ${redeemParentRes.exceptionOrNull()}", redeemParentRes.isSuccess)
        assertEquals(UserRole.PARENT, redeemParentRes.getOrThrow().role)
        assertEquals(FamilyIdentity.FATHER, redeemParentRes.getOrThrow().familyIdentity)
    }

    @Test
    fun testV16FatherAndMotherProfilePersistenceAcrossAppRestartsAndAntiOverwrite() = runBlocking {
        val fatherEmail = "father_persist_${System.currentTimeMillis()}@example.com"
        val motherEmail = "mother_persist_${System.currentTimeMillis()}@example.com"

        // 1. Father signs in, configures family name "משפחת אלון" and personal profile as FATHER
        val fatherUid = signInTestUser(fatherEmail)
        val firstLaunchRepo = repo()

        val createdState = withTimeout(timeoutMs) {
            firstLaunchRepo.createFamilyAsParent(
                familyNameInput = "משפחת אלון",
                parentDisplayName = "דני (אבא)",
                creatorUidOverride = fatherUid,
                creatorEmailOverride = fatherEmail,
                familyIdentity = FamilyIdentity.FATHER
            )
        }.getOrThrow()

        val familyId = createdState.familyId
        assertEquals("משפחת אלון", createdState.familyName)
        assertEquals(UserRole.PARENT, createdState.role)
        assertEquals(FamilyIdentity.FATHER, createdState.familyIdentity)

        // Verify Firestore /users/{uid} and /families/{familyId}/members/{uid} both persisted familyIdentity = FATHER
        val userDocSnap = withTimeout(timeoutMs) {
            firestore.collection("users").document(fatherUid).get().awaitTask()
        }
        assertEquals("FATHER", userDocSnap.getString("familyIdentity"))
        assertEquals("PARENT", userDocSnap.getString("role"))
        assertEquals("משפחת אלון", userDocSnap.getString("familyName"))
        assertEquals(familyId, userDocSnap.getString("familyId"))

        val memberDocSnap = withTimeout(timeoutMs) {
            firestore.collection("families").document(familyId).collection("members").document(fatherUid).get().awaitTask()
        }
        assertEquals("FATHER", memberDocSnap.getString("familyIdentity"))
        assertEquals("PARENT", memberDocSnap.getString("role"))
        assertEquals("דני (אבא)", memberDocSnap.getString("name"))

        // 2. Simulate closing and reopening FamilyTasks with the existing FirebaseAuth session
        firstLaunchRepo.detachAll()
        val reopenedRepo = repo()

        val restoredMembership = withTimeout(timeoutMs) {
            reopenedRepo.checkAuthenticatedUserMembership(
                uid = fatherUid,
                email = fatherEmail,
                displayName = "Dani Alon"
            )
        }

        assertTrue("Reopened app must restore ActiveMember state", restoredMembership is FamilyMembershipState.ActiveMember)
        val activeFather = restoredMembership as FamilyMembershipState.ActiveMember
        assertEquals("Family ID must persist across restart", familyId, activeFather.familyId)
        assertEquals("Family name must persist across restart", "משפחת אלון", activeFather.familyName)
        assertEquals("Father must remain FATHER after restart", FamilyIdentity.FATHER, activeFather.familyIdentity)
        assertEquals("Father must retain PARENT authorization role", UserRole.PARENT, activeFather.role)
        assertEquals("Current member familyIdentity must be FATHER", FamilyIdentity.FATHER, reopenedRepo.currentMember.value.familyIdentity)
        assertEquals("Current member display name must be preserved", "דני (אבא)", reopenedRepo.currentMember.value.name)
        assertTrue("StartupState must transition to ReadyToEnterDashboard", reopenedRepo.startupState.value is StartupState.ReadyToEnterDashboard)

        // 3. Ensure createFamilyInCloud / createFamilyAsParent has create-if-absent semantics and does NOT overwrite existing profile
        val accidentalReinit = withTimeout(timeoutMs) {
            reopenedRepo.createFamilyInCloud(
                familyName = "משפחת ישראלי",
                creatorName = "אמא",
                creatorEmail = fatherEmail,
                familyIdentity = FamilyIdentity.MOTHER
            )
        }.getOrThrow()

        assertEquals("Must not create duplicate replacement family", familyId, accidentalReinit.familyId)
        assertEquals("Must not overwrite existing family name", "משפחת אלון", accidentalReinit.familyName)
        assertEquals("Must not overwrite existing FATHER identity to MOTHER", FamilyIdentity.FATHER, accidentalReinit.familyIdentity)

        // 4. Mother joins via PARENT invitation with FamilyIdentity.MOTHER and persists across restart
        val motherInvite = withTimeout(timeoutMs) {
            reopenedRepo.createRoleInvitationInCloud(
                invitedRole = UserRole.PARENT,
                inviteeNameHint = "רונית (אמא)",
                familyIdOverride = familyId
            )
        }.getOrThrow()

        val motherUid = signInTestUser(motherEmail)
        val motherRepo = repo()
        val motherJoined = withTimeout(timeoutMs) {
            motherRepo.redeemInvitationAndJoinFamily(
                rawCode = motherInvite.inviteCode,
                memberDisplayName = "רונית (אמא)",
                joiningUidOverride = motherUid,
                joiningEmailOverride = motherEmail,
                familyIdentity = FamilyIdentity.MOTHER
            )
        }.getOrThrow()

        assertEquals(UserRole.PARENT, motherJoined.role)
        assertEquals(FamilyIdentity.MOTHER, motherJoined.familyIdentity)

        // Restart Mother's app session -> remains MOTHER with PARENT role
        motherRepo.detachAll()
        val motherReopenedRepo = repo()
        val motherRestored = withTimeout(timeoutMs) {
            motherReopenedRepo.checkAuthenticatedUserMembership(
                uid = motherUid,
                email = motherEmail,
                displayName = "Ronit Alon"
            )
        } as FamilyMembershipState.ActiveMember

        assertEquals(FamilyIdentity.MOTHER, motherRestored.familyIdentity)
        assertEquals(UserRole.PARENT, motherRestored.role)
        assertEquals("משפחת אלון", motherRestored.familyName)
    }

    private val activeRepos = mutableListOf<FamilyRepository>()

    private fun repo(): FamilyRepository {
        val r = FamilyRepository(firestore, auth)
        activeRepos.add(r)
        return r
    }

    @org.junit.After
    override fun tearDownFirebase() {
        activeRepos.forEach { it.detachAll() }
        activeRepos.clear()
        super.tearDownFirebase()
    }

    private val directExecutor = java.util.concurrent.Executor { it.run() }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T {
        val callSite = RuntimeException("awaitTask call-site")
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            addOnSuccessListener(directExecutor) { if (cont.isActive) cont.resume(it) }
            addOnFailureListener(directExecutor) {
                if (cont.isActive) {
                    it.addSuppressed(callSite)
                    cont.resumeWithException(it)
                }
            }
        }
    }
}


