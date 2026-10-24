const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: cannot read tasks", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("tasks").get());
});

test("Unauthenticated user: cannot write task", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    unauthDb.collection("tasks").doc("task_1").set({
      id: "task_1",
      userId: ALICE_UID,
      title: "Clean kitchen",
      category: "CLEANING",
      assignedToMemberId: "user_maya",
      dateEpochDay: 20000,
      priority: "HIGH",
      points: 20,
      recurrence: "DAILY",
      isCompleted: false,
      createdAt: new Date(),
    })
  );
});

test("Authenticated user (Alice): can create valid task", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb.collection("tasks").doc("task_1").set({
      id: "task_1",
      userId: ALICE_UID,
      title: "Clean kitchen",
      category: "CLEANING",
      assignedToMemberId: "user_maya",
      dateEpochDay: 20000,
      priority: "HIGH",
      points: 20,
      recurrence: "DAILY",
      isCompleted: false,
      createdAt: new Date(),
    })
  );
});

test("Authenticated user (Alice): can read her own task", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await aliceDb.collection("tasks").doc("task_1").set({
    id: "task_1",
    userId: ALICE_UID,
    title: "Clean kitchen",
    category: "CLEANING",
    assignedToMemberId: "user_maya",
    dateEpochDay: 20000,
    priority: "HIGH",
    points: 20,
    recurrence: "DAILY",
    isCompleted: false,
    createdAt: new Date(),
  });
  await assertSucceeds(aliceDb.collection("tasks").doc("task_1").get());
});

test("Cross-user isolation: Bob cannot read Alice's task", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await aliceDb.collection("tasks").doc("task_1").set({
    id: "task_1",
    userId: ALICE_UID,
    title: "Clean kitchen",
    category: "CLEANING",
    assignedToMemberId: "user_maya",
    dateEpochDay: 20000,
    priority: "HIGH",
    points: 20,
    recurrence: "DAILY",
    isCompleted: false,
    createdAt: new Date(),
  });
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("tasks").doc("task_1").get());
});

test("Authenticated user (Alice): can create and read document metadata", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb.collection("documents").doc("doc_1").set({
      id: "doc_1",
      userId: ALICE_UID,
      folderId: "folder_school",
      name: "Timetable.pdf",
      extension: "PDF",
      sizeFormatted: "1.2 MB",
      uploadedByName: "Moran",
      uploadDateFormatted: "17/10/2026",
      isDriveSynced: true,
      driveFileUrl: "https://drive.google.com/test",
      createdAt: new Date(),
    })
  );
});

test("Cross-user isolation: Bob cannot read Alice's document metadata", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await aliceDb.collection("documents").doc("doc_1").set({
    id: "doc_1",
    userId: ALICE_UID,
    folderId: "folder_school",
    name: "Timetable.pdf",
    extension: "PDF",
    sizeFormatted: "1.2 MB",
    uploadedByName: "Moran",
    uploadDateFormatted: "17/10/2026",
    isDriveSynced: true,
    driveFileUrl: "https://drive.google.com/test",
    createdAt: new Date(),
  });
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("documents").doc("doc_1").get());
});

test("Family Group: Parent can create family with only creator in memberIds, and cannot insert arbitrary UIDs on creation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb.collection("families").doc("fam_wexler_invalid").set({
      id: "fam_wexler_invalid",
      familyName: "Wexler Family",
      createdById: ALICE_UID,
      inviteCode: "FAM-WEXLER-7749",
      memberIds: [ALICE_UID, BOB_UID],
      createdAt: new Date(),
    })
  );
  await assertSucceeds(
    aliceDb.collection("families").doc("fam_wexler").set({
      id: "fam_wexler",
      familyName: "Wexler Family",
      createdById: ALICE_UID,
      inviteCode: "FAM-WEXLER-7749",
      memberIds: [ALICE_UID],
      createdAt: new Date(),
    })
  );
});

test("Family Tasks: Authorized family member (Bob) can read family tasks, but stranger cannot", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await aliceDb.collection("families").doc("fam_wexler").set({
    id: "fam_wexler",
    familyName: "Wexler Family",
    createdById: ALICE_UID,
    inviteCode: "FAM-WEXLER-7749",
    memberIds: [ALICE_UID],
    createdAt: new Date(),
  });
  await aliceDb.collection("families").doc("fam_wexler").update({
    memberIds: [ALICE_UID, BOB_UID],
  });

  await aliceDb.collection("families").doc("fam_wexler").collection("tasks").doc("task_clean").set({
    id: "task_clean",
    familyId: "fam_wexler",
    title: "Clean bedroom",
    assignedMemberId: BOB_UID,
    points: 25,
    isCompleted: false,
    approvedByParent: false,
    createdAt: new Date(),
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertSucceeds(
    bobDb.collection("families").doc("fam_wexler").collection("tasks").doc("task_clean").get()
  );

  const strangerDb = testEnv.authenticatedContext("stranger_999").firestore();
  await assertFails(
    strangerDb.collection("families").doc("fam_wexler").collection("tasks").doc("task_clean").get()
  );
});

test("Parent-Child Permissions: Child can mark task completed, but CANNOT modify points", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await aliceDb.collection("families").doc("fam_wexler").set({
    id: "fam_wexler",
    familyName: "Wexler Family",
    createdById: ALICE_UID,
    inviteCode: "FAM-WEXLER-7749",
    memberIds: [ALICE_UID],
    createdAt: new Date(),
  });
  await aliceDb.collection("families").doc("fam_wexler").update({
    memberIds: [ALICE_UID, BOB_UID],
  });

  await aliceDb.collection("families").doc("fam_wexler").collection("tasks").doc("task_clean").set({
    id: "task_clean",
    familyId: "fam_wexler",
    title: "Clean bedroom",
    assignedMemberId: BOB_UID,
    points: 25,
    isCompleted: false,
    approvedByParent: false,
    createdAt: new Date(),
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  // Child marks task completed
  await assertSucceeds(
    bobDb.collection("families").doc("fam_wexler").collection("tasks").doc("task_clean").update({
      isCompleted: true,
      completedAtMillis: Date.now(),
      completedByMemberId: BOB_UID,
    })
  );

  // Child tries to give themselves 100 points - MUST FAIL
  await assertFails(
    bobDb.collection("families").doc("fam_wexler").collection("tasks").doc("task_clean").update({
      points: 100,
    })
  );
});

test("Security: Stranger cannot create invite for another family", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await aliceDb.collection("families").doc("fam_wexler").set({
    id: "fam_wexler",
    familyName: "Wexler Family",
    createdById: ALICE_UID,
    inviteCode: "FAM-WEXLER-7749",
    memberIds: [ALICE_UID],
    createdAt: new Date(),
  });

  const strangerDb = testEnv.authenticatedContext("stranger_999").firestore();
  await assertFails(
    strangerDb.collection("family_invites").doc("HACK_INVITE").set({
      inviteCode: "HACK_INVITE",
      familyId: "fam_wexler",
      createdById: "stranger_999",
      updatedAt: new Date(),
    })
  );
});

test("Security: Stranger cannot overwrite or modify existing invite", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await aliceDb.collection("families").doc("fam_wexler").set({
    id: "fam_wexler",
    familyName: "Wexler Family",
    createdById: ALICE_UID,
    inviteCode: "FAM-WEXLER-7749",
    memberIds: [ALICE_UID],
    createdAt: new Date(),
  });

  await aliceDb.collection("family_invites").doc("FAM-WEXLER-7749").set({
    inviteCode: "FAM-WEXLER-7749",
    familyId: "fam_wexler",
    createdById: ALICE_UID,
    updatedAt: new Date(),
  });

  const strangerDb = testEnv.authenticatedContext("stranger_999").firestore();
  await assertFails(
    strangerDb.collection("family_invites").doc("FAM-WEXLER-7749").update({
      familyId: "fam_stranger_household",
    })
  );
});

test("Security: Member joining family cannot self-escalate to PRIMARY_ADMIN", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await aliceDb.collection("families").doc("fam_wexler").set({
    id: "fam_wexler",
    familyName: "Wexler Family",
    createdById: ALICE_UID,
    inviteCode: "FAM-WEXLER-7749",
    memberIds: [ALICE_UID],
    createdAt: new Date(),
  });
  await aliceDb.collection("families").doc("fam_wexler").update({
    memberIds: [ALICE_UID, BOB_UID],
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  // Bob tries to create his own member profile with PRIMARY_ADMIN role
  await assertFails(
    bobDb.collection("families").doc("fam_wexler").collection("members").doc(BOB_UID).set({
      id: BOB_UID,
      name: "Bob",
      role: "PRIMARY_ADMIN",
      earnedPoints: 9999,
    })
  );

  // Bob creates a valid child member profile
  await assertSucceeds(
    bobDb.collection("families").doc("fam_wexler").collection("members").doc(BOB_UID).set({
      id: BOB_UID,
      name: "Bob",
      role: "MEMBER_KID",
      earnedPoints: 0,
    })
  );
});

test("Security: Child cannot create task with unauthorized self-approval or excessive points", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await aliceDb.collection("families").doc("fam_wexler").set({
    id: "fam_wexler",
    familyName: "Wexler Family",
    createdById: ALICE_UID,
    inviteCode: "FAM-WEXLER-7749",
    memberIds: [ALICE_UID],
    createdAt: new Date(),
  });
  await aliceDb.collection("families").doc("fam_wexler").update({
    memberIds: [ALICE_UID, BOB_UID],
  });

  await aliceDb.collection("families").doc("fam_wexler").collection("members").doc(BOB_UID).set({
    id: BOB_UID,
    name: "Bob",
    role: "MEMBER_KID",
    earnedPoints: 0,
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  // Child tries to create a pre-approved task -> MUST FAIL
  await assertFails(
    bobDb.collection("families").doc("fam_wexler").collection("tasks").doc("task_exploit_1").set({
      id: "task_exploit_1",
      familyId: "fam_wexler",
      title: "Self rewarded task",
      points: 20,
      approvedByParent: true,
      isCompleted: true,
    })
  );

  // Child tries to create task with 500 points -> MUST FAIL
  await assertFails(
    bobDb.collection("families").doc("fam_wexler").collection("tasks").doc("task_exploit_2").set({
      id: "task_exploit_2",
      familyId: "fam_wexler",
      title: "Excessive points task",
      points: 500,
      approvedByParent: false,
      isCompleted: false,
    })
  );

  // Child creating task with points without parent authorization -> MUST FAIL
  await assertFails(
    bobDb.collection("families").doc("fam_wexler").collection("tasks").doc("task_unauth_points").set({
      id: "task_unauth_points",
      familyId: "fam_wexler",
      title: "Help sister with math",
      points: 20,
      approvedByParent: false,
      isCompleted: false,
    })
  );

  // V1.6 Requirement 5: Child creating any task (even with 0 points) -> MUST FAIL
  await assertFails(
    bobDb.collection("families").doc("fam_wexler").collection("tasks").doc("task_proposal").set({
      id: "task_proposal",
      familyId: "fam_wexler",
      title: "Proposed chore",
      points: 0,
      approvedByParent: false,
      isCompleted: false,
    })
  );

  // Parent creating a task assigned to Bob -> SUCCEEDS
  await assertSucceeds(
    aliceDb.collection("families").doc("fam_wexler").collection("tasks").doc("task_proposal").set({
      id: "task_proposal",
      familyId: "fam_wexler",
      title: "Assigned chore",
      assignedUid: BOB_UID,
      assignedToMemberId: BOB_UID,
      points: 25,
      approvedByParent: false,
      isCompleted: false,
    })
  );

  // Child attempting to approve task or alter points -> MUST FAIL
  await assertFails(
    bobDb.collection("families").doc("fam_wexler").collection("tasks").doc("task_proposal").update({
      approvedByParent: true,
      points: 25,
    })
  );

  // Child completing assigned task (without self-approving) -> SUCCEEDS
  await assertSucceeds(
    bobDb.collection("families").doc("fam_wexler").collection("tasks").doc("task_proposal").update({
      isCompleted: true,
      completedAtMillis: 1700000000000,
      completedByMemberId: BOB_UID,
      updatedAt: 1700000000000,
    })
  );

  // Parent approving the completed task -> SUCCEEDS
  await assertSucceeds(
    aliceDb.collection("families").doc("fam_wexler").collection("tasks").doc("task_proposal").update({
      approvedByParent: true,
      updatedAt: 1700000005000,
    })
  );
});

test("Security: Child cannot self-approve rewards or alter reward pointsCost", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await aliceDb.collection("families").doc("fam_wexler").set({
    id: "fam_wexler",
    familyName: "Wexler Family",
    createdById: ALICE_UID,
    inviteCode: "FAM-WEXLER-7749",
    memberIds: [ALICE_UID],
    createdAt: new Date(),
  });
  await aliceDb.collection("families").doc("fam_wexler").update({
    memberIds: [ALICE_UID, BOB_UID],
  });

  await aliceDb.collection("families").doc("fam_wexler").collection("members").doc(BOB_UID).set({
    id: BOB_UID,
    name: "Bob",
    role: "MEMBER_KID",
    earnedPoints: 100,
  });

  await aliceDb.collection("families").doc("fam_wexler").collection("rewards").doc("reward_ice_cream").set({
    id: "reward_ice_cream",
    title: "Ice Cream Cone",
    pointsCost: 50,
    approvedByParent: false,
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  // Bob tries to alter pointsCost to 0 -> MUST FAIL
  await assertFails(
    bobDb.collection("families").doc("fam_wexler").collection("rewards").doc("reward_ice_cream").update({
      pointsCost: 0,
    })
  );

  // Bob tries to mark approvedByParent: true -> MUST FAIL
  await assertFails(
    bobDb.collection("families").doc("fam_wexler").collection("rewards").doc("reward_ice_cream").update({
      approvedByParent: true,
    })
  );
});

test("Security: User cannot join arbitrary family without verified invite code", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await aliceDb.collection("families").doc("fam_wexler").set({
    id: "fam_wexler",
    familyName: "Wexler Family",
    createdById: ALICE_UID,
    inviteCode: "FAM-WEXLER-7749",
    memberIds: [ALICE_UID],
    createdAt: new Date(),
  });

  const malloryDb = testEnv.authenticatedContext("mallory_666").firestore();
  // Mallory tries to inject herself into fam_wexler without an invite
  await assertFails(
    malloryDb.collection("families").doc("fam_wexler").collection("members").doc("mallory_666").set({
      id: "mallory_666",
      name: "Mallory Hacker",
      role: "MEMBER_KID",
      earnedPoints: 0,
    })
  );

  // Mallory tries with an invalid/fake invite code
  await assertFails(
    malloryDb.collection("families").doc("fam_wexler").collection("members").doc("mallory_666").set({
      id: "mallory_666",
      name: "Mallory Hacker",
      role: "MEMBER_KID",
      earnedPoints: 0,
      inviteCode: "FAKE-CODE-9999",
    })
  );
});

test("Security: User with verified invite code can join family with role MEMBER_KID and 0 points", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await aliceDb.collection("families").doc("fam_wexler").set({
    id: "fam_wexler",
    familyName: "Wexler Family",
    createdById: ALICE_UID,
    inviteCode: "FAM-WEXLER-7749",
    memberIds: [ALICE_UID],
    createdAt: new Date(),
  });

  await aliceDb.collection("family_invites").doc("FAM-WEXLER-7749").set({
    inviteCode: "FAM-WEXLER-7749",
    familyId: "fam_wexler",
    createdById: ALICE_UID,
    updatedAt: new Date(),
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  // 1. Missing consumption: Bob attempts to create member WITHOUT consuming invite in atomic operation -> MUST FAIL
  await assertFails(
    bobDb.collection("families").doc("fam_wexler").collection("members").doc(BOB_UID).set({
      id: BOB_UID,
      name: "Bob",
      role: "MEMBER_KID",
      earnedPoints: 0,
      spentPoints: 0,
      inviteCode: "FAM-WEXLER-7749",
    })
  );

  // 2. Atomic consumption: Bob creates member AND marks invite as used in same atomic batch -> SUCCEEDS
  const batch = bobDb.batch();
  batch.set(bobDb.collection("families").doc("fam_wexler").collection("members").doc(BOB_UID), {
    id: BOB_UID,
    name: "Bob",
    role: "MEMBER_KID",
    earnedPoints: 0,
    spentPoints: 0,
    inviteCode: "FAM-WEXLER-7749",
  });
  batch.update(bobDb.collection("family_invites").doc("FAM-WEXLER-7749"), {
    isUsed: true,
    usedByUid: BOB_UID,
    usedAtMillis: Date.now(),
    updatedAt: new Date(),
  });
  await assertSucceeds(batch.commit());

  // 3. Concurrent redemption / Replay: Charlie attempts to reuse or concurrently redeem consumed invite -> MUST FAIL
  const charlieDb = testEnv.authenticatedContext("charlie_789").firestore();
  const charlieBatch = charlieDb.batch();
  charlieBatch.set(charlieDb.collection("families").doc("fam_wexler").collection("members").doc("charlie_789"), {
    id: "charlie_789",
    name: "Charlie",
    role: "MEMBER_KID",
    earnedPoints: 0,
    spentPoints: 0,
    inviteCode: "FAM-WEXLER-7749",
  });
  charlieBatch.update(charlieDb.collection("family_invites").doc("FAM-WEXLER-7749"), {
    isUsed: true,
    usedByUid: "charlie_789",
    usedAtMillis: Date.now(),
    updatedAt: new Date(),
  });
  await assertFails(charlieBatch.commit());
});

test("Security: Child cannot update task assigned to another family member", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await aliceDb.collection("families").doc("fam_wexler").set({
    id: "fam_wexler",
    familyName: "Wexler Family",
    createdById: ALICE_UID,
    inviteCode: "FAM-WEXLER-7749",
    memberIds: [ALICE_UID],
    createdAt: new Date(),
  });
  await aliceDb.collection("families").doc("fam_wexler").update({
    memberIds: [ALICE_UID, BOB_UID, "charlie_789"],
  });

  await aliceDb.collection("families").doc("fam_wexler").collection("members").doc(BOB_UID).set({
    id: BOB_UID,
    name: "Bob",
    role: "MEMBER_KID",
    earnedPoints: 0,
  });

  // Task assigned to Charlie, NOT Bob
  await aliceDb.collection("families").doc("fam_wexler").collection("tasks").doc("task_charlie").set({
    id: "task_charlie",
    familyId: "fam_wexler",
    title: "Charlie's chore",
    assignedToMemberId: "charlie_789",
    points: 15,
    isCompleted: false,
    approvedByParent: false,
    createdAt: new Date(),
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  // Bob tries to update Charlie's task -> MUST FAIL
  await assertFails(
    bobDb.collection("families").doc("fam_wexler").collection("tasks").doc("task_charlie").update({
      isCompleted: true,
      completedAtMillis: Date.now(),
      completedByMemberId: BOB_UID,
    })
  );
});

test("Security: Child updating assigned task with forbidden fields MUST FAIL", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await aliceDb.collection("families").doc("fam_wexler").set({
    id: "fam_wexler",
    familyName: "Wexler Family",
    createdById: ALICE_UID,
    inviteCode: "FAM-WEXLER-7749",
    memberIds: [ALICE_UID],
    createdAt: new Date(),
  });
  await aliceDb.collection("families").doc("fam_wexler").update({
    memberIds: [ALICE_UID, BOB_UID],
  });

  await aliceDb.collection("families").doc("fam_wexler").collection("tasks").doc("task_bob").set({
    id: "task_bob",
    familyId: "fam_wexler",
    title: "Do homework",
    assignedToMemberId: BOB_UID,
    points: 10,
    isCompleted: false,
    approvedByParent: false,
    createdAt: new Date(),
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  // Bob tries to change task title -> MUST FAIL
  await assertFails(
    bobDb.collection("families").doc("fam_wexler").collection("tasks").doc("task_bob").update({
      title: "Play video games",
    })
  );

  // Bob tries to mark approvedByParent: true -> MUST FAIL
  await assertFails(
    bobDb.collection("families").doc("fam_wexler").collection("tasks").doc("task_bob").update({
      approvedByParent: true,
    })
  );
});

test("Security: Stranger cannot read private user profile /users/{userId}", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await aliceDb.collection("users").doc(ALICE_UID).set({
    id: ALICE_UID,
    email: "alice@example.com",
    familyName: "Wexler",
    familyId: "fam_wexler",
    createdAt: new Date(),
  });

  const strangerDb = testEnv.authenticatedContext("stranger_999").firestore();
  // Stranger cannot read Alice's profile
  await assertFails(strangerDb.collection("users").doc(ALICE_UID).get());

  // Alice can read her own profile
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).get());
});

test("Security: Legacy top-level tasks: Child cannot bypass family parent approval or modify points", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await aliceDb.collection("families").doc("fam_wexler").set({
    id: "fam_wexler",
    familyName: "Wexler Family",
    createdById: ALICE_UID,
    inviteCode: "FAM-WEXLER-7749",
    memberIds: [ALICE_UID],
    createdAt: new Date(),
  });
  await aliceDb.collection("families").doc("fam_wexler").update({
    memberIds: [ALICE_UID, BOB_UID],
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  // Bob tries to create top-level task with familyId and approvedByParent: true -> MUST FAIL
  await assertFails(
    bobDb.collection("tasks").doc("legacy_task_exploit").set({
      id: "legacy_task_exploit",
      userId: BOB_UID,
      familyId: "fam_wexler",
      title: "Exploit Task",
      points: 200,
      approvedByParent: true,
      isCompleted: false,
    })
  );

  // Alice (parent) creates legacy task
  await aliceDb.collection("tasks").doc("legacy_task_valid").set({
    id: "legacy_task_valid",
    userId: ALICE_UID,
    familyId: "fam_wexler",
    title: "Walk dog",
    assignedToMemberId: BOB_UID,
    points: 15,
    approvedByParent: true,
    isCompleted: false,
  });

  // Bob tries to change points on legacy task -> MUST FAIL
  await assertFails(
    bobDb.collection("tasks").doc("legacy_task_valid").update({
      points: 99,
    })
  );

  // Bob can complete his assigned legacy task
  await assertSucceeds(
    bobDb.collection("tasks").doc("legacy_task_valid").update({
      isCompleted: true,
      completedAtMillis: Date.now(),
      completedByMemberId: BOB_UID,
    })
  );
});

test("Security: Expired invitation cannot be used to join family", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection("families").doc("fam_wexler").set({
      id: "fam_wexler",
      familyName: "Wexler Family",
      createdById: ALICE_UID,
      inviteCode: "FAM-EXPIRED-9999",
      memberIds: [ALICE_UID],
      createdAt: new Date(),
    });
    // Expired 1 hour ago
    await db.collection("family_invites").doc("FAM-EXPIRED-9999").set({
      inviteCode: "FAM-EXPIRED-9999",
      familyId: "fam_wexler",
      createdById: ALICE_UID,
      expiresAtMillis: Date.now() - 3600000,
      updatedAt: new Date(),
    });
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(
    bobDb.collection("families").doc("fam_wexler").collection("members").doc(BOB_UID).set({
      id: BOB_UID,
      name: "Bob",
      role: "MEMBER_KID",
      earnedPoints: 0,
      spentPoints: 0,
      inviteCode: "FAM-EXPIRED-9999",
    })
  );
});

test("Security: Revoked invitation cannot be read by outsider or used to join", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection("families").doc("fam_wexler").set({
      id: "fam_wexler",
      familyName: "Wexler Family",
      createdById: ALICE_UID,
      inviteCode: "FAM-REVOKED-1111",
      memberIds: [ALICE_UID],
      createdAt: new Date(),
    });
    await db.collection("family_invites").doc("FAM-REVOKED-1111").set({
      inviteCode: "FAM-REVOKED-1111",
      familyId: "fam_wexler",
      createdById: ALICE_UID,
      isRevoked: true,
      updatedAt: new Date(),
    });
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  // Outsider reading revoked invite -> FAILS
  await assertFails(bobDb.collection("family_invites").doc("FAM-REVOKED-1111").get());

  // Outsider joining using revoked invite -> FAILS
  await assertFails(
    bobDb.collection("families").doc("fam_wexler").collection("members").doc(BOB_UID).set({
      id: BOB_UID,
      name: "Bob",
      role: "MEMBER_KID",
      earnedPoints: 0,
      spentPoints: 0,
      inviteCode: "FAM-REVOKED-1111",
    })
  );
});

test("Security: Stranger cannot enumerate or list all family_invites", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection("families").doc("fam_wexler").set({
      id: "fam_wexler",
      familyName: "Wexler Family",
      createdById: ALICE_UID,
      memberIds: [ALICE_UID],
      createdAt: new Date(),
    });
    await db.collection("family_invites").doc("INV-SECRET-1").set({
      inviteCode: "INV-SECRET-1",
      familyId: "fam_wexler",
      createdById: ALICE_UID,
      updatedAt: new Date(),
    });
  });

  const malloryDb = testEnv.authenticatedContext("mallory_666").firestore();
  // Enumeration of collection -> MUST FAIL
  await assertFails(malloryDb.collection("family_invites").get());
});

test("Security: Documents collection - Strict separation and ownership validation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  // Alice creates valid document with her own userId -> SUCCEEDS
  await assertSucceeds(
    aliceDb.collection("documents").doc("doc_alice_1").set({
      id: "doc_alice_1",
      userId: ALICE_UID,
      name: "AliceNotes.txt",
    })
  );

  // Bob tries to create document spoofing Alice's userId -> MUST FAIL
  await assertFails(
    bobDb.collection("documents").doc("doc_spoofed").set({
      id: "doc_spoofed",
      userId: ALICE_UID,
      name: "Spoofed.txt",
    })
  );

  // Bob tries to read Alice's document -> MUST FAIL
  await assertFails(bobDb.collection("documents").doc("doc_alice_1").get());

  // Bob tries to overwrite/update Alice's document -> MUST FAIL
  await assertFails(
    bobDb.collection("documents").doc("doc_alice_1").update({
      userId: BOB_UID,
    })
  );

  // Alice tries to change document ownership to Bob -> MUST FAIL
  await assertFails(
    aliceDb.collection("documents").doc("doc_alice_1").update({
      userId: BOB_UID,
    })
  );

  // Bob tries to delete Alice's document -> MUST FAIL
  await assertFails(bobDb.collection("documents").doc("doc_alice_1").delete());

  // Alice can delete her own document -> SUCCEEDS
  await assertSucceeds(aliceDb.collection("documents").doc("doc_alice_1").delete());
});

test("Security: Used invitation cannot be reused to join family", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection("families").doc("fam_wexler").set({
      id: "fam_wexler",
      familyName: "Wexler Family",
      createdById: ALICE_UID,
      inviteCode: "FAM-USED-1111",
      memberIds: [ALICE_UID],
      createdAt: new Date(),
    });
    await db.collection("family_invites").doc("FAM-USED-1111").set({
      inviteCode: "FAM-USED-1111",
      familyId: "fam_wexler",
      createdById: ALICE_UID,
      isUsed: true,
      usedByUid: "previous_member",
      updatedAt: new Date(),
    });
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  // Outsider reading already used single-use invite -> FAILS
  await assertFails(bobDb.collection("family_invites").doc("FAM-USED-1111").get());

  // Outsider joining using already used invite -> FAILS
  await assertFails(
    bobDb.collection("families").doc("fam_wexler").collection("members").doc(BOB_UID).set({
      id: BOB_UID,
      name: "Bob",
      role: "MEMBER_KID",
      earnedPoints: 0,
      spentPoints: 0,
      inviteCode: "FAM-USED-1111",
    })
  );
});

test("Security: Invitation with intendedEmail cannot be used by different account", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection("families").doc("fam_wexler").set({
      id: "fam_wexler",
      familyName: "Wexler Family",
      createdById: ALICE_UID,
      inviteCode: "FAM-EMAIL-ONLY",
      memberIds: [ALICE_UID],
      createdAt: new Date(),
    });
    await db.collection("family_invites").doc("FAM-EMAIL-ONLY").set({
      inviteCode: "FAM-EMAIL-ONLY",
      familyId: "fam_wexler",
      createdById: ALICE_UID,
      intendedEmail: "target@example.com",
      updatedAt: new Date(),
    });
  });

  // Bob with bob@example.com tries to join -> FAILS
  const bobDb = testEnv.authenticatedContext(BOB_UID, { email: "bob@example.com" }).firestore();
  await assertFails(
    bobDb.collection("families").doc("fam_wexler").collection("members").doc(BOB_UID).set({
      id: BOB_UID,
      name: "Bob",
      role: "MEMBER_KID",
      earnedPoints: 0,
      spentPoints: 0,
      inviteCode: "FAM-EMAIL-ONLY",
    })
  );

  // Target user with target@example.com joins via atomic batch consumption -> SUCCEEDS
  const targetDb = testEnv.authenticatedContext("target_uid", { email: "target@example.com" }).firestore();
  const targetBatch = targetDb.batch();
  targetBatch.set(targetDb.collection("families").doc("fam_wexler").collection("members").doc("target_uid"), {
    id: "target_uid",
    name: "Target User",
    role: "MEMBER_KID",
    earnedPoints: 0,
    spentPoints: 0,
    inviteCode: "FAM-EMAIL-ONLY",
  });
  targetBatch.update(targetDb.collection("family_invites").doc("FAM-EMAIL-ONLY"), {
    isUsed: true,
    usedByUid: "target_uid",
    usedAtMillis: Date.now(),
    updatedAt: new Date(),
  });
  await assertSucceeds(targetBatch.commit());
});

test("Regression: New user joining with valid invitation must redeem invite atomically before adding UID to memberIds", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await aliceDb.collection("families").doc("fam_join_seq").set({
    id: "fam_join_seq",
    familyName: "Join Sequence Family",
    createdById: ALICE_UID,
    inviteCode: "FAM-JOIN-SEQ-1",
    memberIds: [ALICE_UID],
    createdAt: new Date(),
  });
  await aliceDb.collection("family_invites").doc("FAM-JOIN-SEQ-1").set({
    inviteCode: "FAM-JOIN-SEQ-1",
    familyId: "fam_join_seq",
    createdById: ALICE_UID,
    updatedAt: new Date(),
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  // 1. Bob CANNOT add himself to memberIds BEFORE redeeming the invitation
  await assertFails(
    bobDb.collection("families").doc("fam_join_seq").update({
      memberIds: [ALICE_UID, BOB_UID],
    })
  );

  // 2. Bob atomically redeems the invitation and creates his member document
  const batch = bobDb.batch();
  batch.set(bobDb.collection("families").doc("fam_join_seq").collection("members").doc(BOB_UID), {
    id: BOB_UID,
    name: "Bob",
    role: "MEMBER_KID",
    earnedPoints: 0,
    spentPoints: 0,
    inviteCode: "FAM-JOIN-SEQ-1",
  });
  batch.update(bobDb.collection("family_invites").doc("FAM-JOIN-SEQ-1"), {
    isUsed: true,
    usedByUid: BOB_UID,
    usedAtMillis: Date.now(),
    updatedAt: new Date(),
  });
  await assertSucceeds(batch.commit());

  // 3. After atomic invitation redemption succeeds, Bob can add only his own UID to memberIds
  await assertSucceeds(
    bobDb.collection("families").doc("fam_join_seq").update({
      memberIds: [ALICE_UID, BOB_UID],
    })
  );
});

test("Regression: Existing member reconnecting and compatibility path cannot bypass invite validation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await aliceDb.collection("families").doc("fam_reconnect").set({
    id: "fam_reconnect",
    familyName: "Reconnect Family",
    createdById: ALICE_UID,
    inviteCode: "FAM-RECON-1",
    memberIds: [ALICE_UID],
    createdAt: new Date(),
  });
  await aliceDb.collection("families").doc("fam_reconnect").update({
    memberIds: [ALICE_UID, BOB_UID],
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  // Pre-registered member in memberIds attempting to pass an invalid inviteCode MUST NOT bypass invite validation
  await assertFails(
    bobDb.collection("families").doc("fam_reconnect").collection("members").doc(BOB_UID).set({
      id: BOB_UID,
      name: "Bob",
      role: "MEMBER_KID",
      earnedPoints: 0,
      spentPoints: 0,
      inviteCode: "INVALID-CODE-BYPASS",
    })
  );

  // Legitimate existing member in memberIds onboarding without inviteCode succeeds
  await assertSucceeds(
    bobDb.collection("families").doc("fam_reconnect").collection("members").doc(BOB_UID).set({
      id: BOB_UID,
      name: "Bob",
      role: "MEMBER_KID",
      earnedPoints: 0,
      spentPoints: 0,
    })
  );

  // Reconnecting member can read their own member doc and family doc
  await assertSucceeds(
    bobDb.collection("families").doc("fam_reconnect").collection("members").doc(BOB_UID).get()
  );
  await assertSucceeds(
    bobDb.collection("families").doc("fam_reconnect").get()
  );
});

test("Regression: Unauthorized memberIds modification is blocked for outsiders and non-parent members", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await aliceDb.collection("families").doc("fam_lock").set({
    id: "fam_lock",
    familyName: "Locked Family",
    createdById: ALICE_UID,
    inviteCode: "FAM-LOCK-1",
    memberIds: [ALICE_UID],
    createdAt: new Date(),
  });
  await aliceDb.collection("family_invites").doc("FAM-LOCK-1").set({
    inviteCode: "FAM-LOCK-1",
    familyId: "fam_lock",
    createdById: ALICE_UID,
    updatedAt: new Date(),
  });

  // Outsider Mallory cannot add herself to memberIds
  const malloryDb = testEnv.authenticatedContext("mallory_999").firestore();
  await assertFails(
    malloryDb.collection("families").doc("fam_lock").update({
      memberIds: [ALICE_UID, "mallory_999"],
    })
  );

  // Bob legitimately joins via invite
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const batch = bobDb.batch();
  batch.set(bobDb.collection("families").doc("fam_lock").collection("members").doc(BOB_UID), {
    id: BOB_UID,
    name: "Bob",
    role: "MEMBER_KID",
    earnedPoints: 0,
    spentPoints: 0,
    inviteCode: "FAM-LOCK-1",
  });
  batch.update(bobDb.collection("family_invites").doc("FAM-LOCK-1"), {
    isUsed: true,
    usedByUid: BOB_UID,
    usedAtMillis: Date.now(),
    updatedAt: new Date(),
  });
  await assertSucceeds(batch.commit());
  await assertSucceeds(
    bobDb.collection("families").doc("fam_lock").update({
      memberIds: [ALICE_UID, BOB_UID],
    })
  );

  // Bob (non-parent member) CANNOT add another user (Mallory) to memberIds
  await assertFails(
    bobDb.collection("families").doc("fam_lock").update({
      memberIds: [ALICE_UID, BOB_UID, "mallory_999"],
    })
  );

  // Bob CANNOT remove Alice from memberIds
  await assertFails(
    bobDb.collection("families").doc("fam_lock").update({
      memberIds: [BOB_UID],
    })
  );
});

test("Regression: Initial family creation rejects unauthorized UIDs in memberIds and allows valid creator-only creation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();

  // 1. Inserting arbitrary other UID alongside creator during creation -> MUST FAIL
  await assertFails(
    aliceDb.collection("families").doc("fam_bad_init_1").set({
      id: "fam_bad_init_1",
      familyName: "Bad Init Family 1",
      createdById: ALICE_UID,
      memberIds: [ALICE_UID, BOB_UID],
      createdAt: new Date(),
    })
  );

  // 2. Inserting only another user's UID in memberIds -> MUST FAIL
  await assertFails(
    aliceDb.collection("families").doc("fam_bad_init_2").set({
      id: "fam_bad_init_2",
      familyName: "Bad Init Family 2",
      createdById: ALICE_UID,
      memberIds: [BOB_UID],
      createdAt: new Date(),
    })
  );

  // 3. Empty memberIds array on creation -> MUST FAIL
  await assertFails(
    aliceDb.collection("families").doc("fam_bad_init_3").set({
      id: "fam_bad_init_3",
      familyName: "Bad Init Family 3",
      createdById: ALICE_UID,
      memberIds: [],
      createdAt: new Date(),
    })
  );

  // 4. Valid family creation with only the authenticated creator in memberIds -> SUCCEEDS
  await assertSucceeds(
    aliceDb.collection("families").doc("fam_valid_init").set({
      id: "fam_valid_init",
      familyName: "Valid Init Family",
      createdById: ALICE_UID,
      memberIds: [ALICE_UID],
      createdAt: new Date(),
    })
  );
});

test("Regression: Child task completion sends only permitted completion fields and unauthorized child edits fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await aliceDb.collection("families").doc("fam_child_comp").set({
    id: "fam_child_comp",
    familyName: "Child Completion Family",
    createdById: ALICE_UID,
    memberIds: [ALICE_UID],
    createdAt: new Date(),
  });
  await aliceDb.collection("families").doc("fam_child_comp").collection("members").doc(ALICE_UID).set({
    id: ALICE_UID,
    name: "Alice",
    role: "PRIMARY_ADMIN",
    earnedPoints: 0,
  });
  await aliceDb.collection("families").doc("fam_child_comp").update({
    memberIds: [ALICE_UID, BOB_UID],
  });
  await aliceDb.collection("families").doc("fam_child_comp").collection("members").doc(BOB_UID).set({
    id: BOB_UID,
    name: "Bob",
    role: "MEMBER_KID",
    earnedPoints: 0,
    spentPoints: 0,
  });

  await aliceDb.collection("families").doc("fam_child_comp").collection("tasks").doc("task_1").set({
    id: "task_1",
    familyId: "fam_child_comp",
    title: "Practice Piano",
    description: "30 minutes of scales",
    category: "HOMEWORK",
    assignedToMemberId: BOB_UID,
    points: 20,
    isCompleted: false,
    approvedByParent: false,
    createdAt: new Date(),
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  // 1. Child completing assigned task with ONLY permitted completion fields -> SUCCEEDS
  await assertSucceeds(
    bobDb.collection("families").doc("fam_child_comp").collection("tasks").doc("task_1").update({
      isCompleted: true,
      completedAtMillis: Date.now(),
      completedByMemberId: BOB_UID,
      updatedAt: new Date(),
    })
  );

  // 2. Child attempting full task edit or modifying non-completion fields (title, description, points, approvedByParent) -> MUST FAIL
  await assertFails(
    bobDb.collection("families").doc("fam_child_comp").collection("tasks").doc("task_1").update({
      isCompleted: true,
      title: "Modified Title",
      updatedAt: new Date(),
    })
  );
  await assertFails(
    bobDb.collection("families").doc("fam_child_comp").collection("tasks").doc("task_1").update({
      description: "Only 5 minutes",
      updatedAt: new Date(),
    })
  );
  await assertFails(
    bobDb.collection("families").doc("fam_child_comp").collection("tasks").doc("task_1").update({
      points: 100,
      updatedAt: new Date(),
    })
  );
  await assertFails(
    bobDb.collection("families").doc("fam_child_comp").collection("tasks").doc("task_1").update({
      approvedByParent: true,
      updatedAt: new Date(),
    })
  );
});

test("Regression V1.6: User can get own non-existent member doc and V1.5 legacy creator can migrate to PARENT without allowing child self-promotion", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  // 1. Authenticated user reading their own non-existent /families/{familyId}/members/{uid} succeeds (returns exists=false instead of PERMISSION_DENIED)
  await assertSucceeds(
    aliceDb.collection("families").doc("fam_nonexistent_check").collection("members").doc(ALICE_UID).get()
  );

  // 2. Seed a legacy V1.5 family where Alice is createdBy and Bob is a MEMBER_KID
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection("families").doc("fam_v15_legacy").set({
      familyId: "fam_v15_legacy",
      name: "משפחת לוי",
      createdBy: ALICE_UID,
      memberIds: [ALICE_UID, BOB_UID],
    });
    await db.collection("users").doc(ALICE_UID).set({
      uid: ALICE_UID,
      familyId: "fam_v15_legacy",
      role: "ADMIN",
      familyIdentity: "FATHER",
    });
    await db.collection("families").doc("fam_v15_legacy").collection("members").doc(BOB_UID).set({
      id: BOB_UID,
      uid: BOB_UID,
      name: "Bob",
      role: "MEMBER_KID",
      earnedPoints: 20,
      spentPoints: 0,
    });
  });

  // 3. Alice (verified V1.5 family creator) can migrate/create her missing /members/{uid} doc as PARENT with FATHER identity
  await assertSucceeds(
    aliceDb.collection("families").doc("fam_v15_legacy").collection("members").doc(ALICE_UID).set({
      id: ALICE_UID,
      uid: ALICE_UID,
      familyId: "fam_v15_legacy",
      name: "אבא לוי",
      role: "PARENT",
      familyIdentity: "FATHER",
      earnedPoints: 0,
      spentPoints: 0,
    })
  );

  // 4. Alice can normalize her /users/{uid} role from legacy 'ADMIN' to canonical 'PARENT'
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      role: "PARENT",
      familyName: "משפחת לוי",
    })
  );

  // 5. Bob (legacy MEMBER_KID) can normalize his own member role to canonical 'CHILD', but CANNOT escalate to 'PARENT'
  await assertFails(
    bobDb.collection("families").doc("fam_v15_legacy").collection("members").doc(BOB_UID).update({
      role: "PARENT",
    })
  );
  await assertSucceeds(
    bobDb.collection("families").doc("fam_v15_legacy").collection("members").doc(BOB_UID).update({
      role: "CHILD",
    })
  );
});

test("Regression V1.6: CHILD can complete assigned task (pending approval), cannot tamper with protected fields or sibling tasks, and PARENT can approve", async () => {
  const parentDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const roiDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const siblingDb = testEnv.authenticatedContext("noa_child_789").firestore();

  await parentDb.collection("families").doc("fam_v16_tasks").set({
    id: "fam_v16_tasks",
    familyId: "fam_v16_tasks",
    familyName: "משפחת משימות",
    createdById: ALICE_UID,
    createdBy: ALICE_UID,
    ownerUid: ALICE_UID,
    memberIds: [ALICE_UID],
    memberUids: [ALICE_UID],
    adminUids: [ALICE_UID],
  });
  await parentDb.collection("families").doc("fam_v16_tasks").collection("members").doc(ALICE_UID).set({
    id: ALICE_UID,
    uid: ALICE_UID,
    familyId: "fam_v16_tasks",
    name: "אבא",
    role: "PARENT",
    familyIdentity: "FATHER",
    earnedPoints: 0,
    spentPoints: 0,
  });
  await parentDb.collection("families").doc("fam_v16_tasks").update({
    memberIds: [ALICE_UID, BOB_UID, "noa_child_789"],
    memberUids: [ALICE_UID, BOB_UID, "noa_child_789"],
  });
  await parentDb.collection("families").doc("fam_v16_tasks").collection("members").doc(BOB_UID).set({
    id: BOB_UID,
    uid: BOB_UID,
    familyId: "fam_v16_tasks",
    name: "רועי",
    role: "CHILD",
    familyIdentity: "SON",
    earnedPoints: 0,
    spentPoints: 0,
  });
  await parentDb.collection("families").doc("fam_v16_tasks").collection("members").doc("noa_child_789").set({
    id: "noa_child_789",
    uid: "noa_child_789",
    familyId: "fam_v16_tasks",
    name: "נועה",
    role: "CHILD",
    familyIdentity: "DAUGHTER",
    earnedPoints: 0,
    spentPoints: 0,
  });

  // Parent creates task assigned to Roi and task assigned to Noa
  await assertSucceeds(
    parentDb.collection("families").doc("fam_v16_tasks").collection("tasks").doc("task_roi").set({
      id: "task_roi",
      familyId: "fam_v16_tasks",
      title: "סידור החדר של רועי",
      assignedUid: BOB_UID,
      assignedToMemberId: BOB_UID,
      assignedMemberId: BOB_UID,
      points: 20,
      pointsValue: 20,
      isCompleted: false,
      approvedByParent: false,
      updatedAt: 1700000000000,
    })
  );
  await assertSucceeds(
    parentDb.collection("families").doc("fam_v16_tasks").collection("tasks").doc("task_noa").set({
      id: "task_noa",
      familyId: "fam_v16_tasks",
      title: "פינוי מדיח",
      assignedUid: "noa_child_789",
      assignedToMemberId: "noa_child_789",
      assignedMemberId: "noa_child_789",
      points: 15,
      pointsValue: 15,
      isCompleted: false,
      approvedByParent: false,
      updatedAt: 1700000000000,
    })
  );

  // 1. Roi (CHILD) completes his assigned task -> SUCCEEDS
  await assertSucceeds(
    roiDb.collection("families").doc("fam_v16_tasks").collection("tasks").doc("task_roi").update({
      isCompleted: true,
      completedAtMillis: 1700000005000,
      completedByMemberId: BOB_UID,
      updatedAt: 1700000005000,
    })
  );

  // 2. Roi is denied completing Noa's task -> MUST FAIL
  await assertFails(
    roiDb.collection("families").doc("fam_v16_tasks").collection("tasks").doc("task_noa").update({
      isCompleted: true,
      completedAtMillis: 1700000006000,
      completedByMemberId: BOB_UID,
      updatedAt: 1700000006000,
    })
  );

  // 3. Roi is denied changing title, assignee, points, or self-approving (approvedByParent: true) -> MUST FAIL
  await assertFails(
    roiDb.collection("families").doc("fam_v16_tasks").collection("tasks").doc("task_roi").update({
      title: "משימה קלה",
      updatedAt: 1700000007000,
    })
  );
  await assertFails(
    roiDb.collection("families").doc("fam_v16_tasks").collection("tasks").doc("task_roi").update({
      assignedToMemberId: "noa_child_789",
      updatedAt: 1700000007000,
    })
  );
  await assertFails(
    roiDb.collection("families").doc("fam_v16_tasks").collection("tasks").doc("task_roi").update({
      points: 500,
      updatedAt: 1700000007000,
    })
  );
  await assertFails(
    roiDb.collection("families").doc("fam_v16_tasks").collection("tasks").doc("task_roi").update({
      isCompleted: true,
      approvedByParent: true,
      updatedAt: 1700000007000,
    })
  );

  // 4. Roi is denied creating or deleting tasks -> MUST FAIL
  await assertFails(
    roiDb.collection("families").doc("fam_v16_tasks").collection("tasks").doc("task_illegal").set({
      id: "task_illegal",
      familyId: "fam_v16_tasks",
      title: "Illegal",
      assignedUid: BOB_UID,
      points: 0,
      isCompleted: false,
      approvedByParent: false,
    })
  );
  await assertFails(
    roiDb.collection("families").doc("fam_v16_tasks").collection("tasks").doc("task_roi").delete()
  );

  // 5. Parent cannot change earnedPoints without an atomic points_history entry -> MUST FAIL
  await assertFails(
    parentDb.collection("families").doc("fam_v16_tasks").collection("members").doc(BOB_UID).update({
      earnedPoints: 20,
      updatedAt: 1700000010000,
    })
  );

  // 6. Parent atomically approves Roi's completed task, credits points, and writes points_history/pts_task_task_roi -> SUCCEEDS
  const approveBatch = parentDb.batch();
  approveBatch.update(
    parentDb.collection("families").doc("fam_v16_tasks").collection("tasks").doc("task_roi"),
    {
      isCompleted: true,
      approvedByParent: true,
      rewardedOperationId: "pts_task_task_roi",
      updatedAt: 1700000010000,
    }
  );
  approveBatch.update(
    parentDb.collection("families").doc("fam_v16_tasks").collection("members").doc(BOB_UID),
    {
      earnedPoints: 20,
      lastPointsOperationId: "pts_task_task_roi",
      updatedAt: 1700000010000,
    }
  );
  approveBatch.set(
    parentDb.collection("families").doc("fam_v16_tasks").collection("points_history").doc("pts_task_task_roi"),
    {
      id: "pts_task_task_roi",
      operationId: "pts_task_task_roi",
      familyId: "fam_v16_tasks",
      memberId: BOB_UID,
      memberName: "רועי",
      actionType: "TASK_EARNED",
      pointsDelta: 20,
      previousBalance: 0,
      newBalance: 20,
      reason: "השלמת משימה: סידור החדר של רועי",
      performedByUid: ALICE_UID,
      performedByName: "אבא",
      timestampMillis: 1700000010000,
      taskId: "task_roi",
    }
  );
  await assertSucceeds(approveBatch.commit());
});

test("V1.0.0 FCM Push Security: Authenticated user can register/refresh/delete own multi-device FCM tokens, cannot access another user's tokens, and notification_dispatches is server-only", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const unauthDb = testEnv.unauthenticatedContext().firestore();

  // 1. Unauthenticated user cannot read or register FCM tokens
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("fcm_tokens").doc("tok_device_1").set({
      uid: ALICE_UID,
      tokenId: "tok_device_1",
      token: "fcm_token_string_device_1_valid_length",
      platform: "ANDROID",
    })
  );

  // 2. Alice can register multiple devices under her own /users/{uid}/fcm_tokens/{tokenId}
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("fcm_tokens").doc("tok_phone_1").set({
      uid: ALICE_UID,
      tokenId: "tok_phone_1",
      token: "fcm_token_string_phone_1_valid_length",
      familyId: "fam_v16_tasks",
      role: "PARENT",
      platform: "ANDROID",
      appVersion: "1.0.0",
      updatedAt: 1700000020000,
    })
  );
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("fcm_tokens").doc("tok_tablet_2").set({
      uid: ALICE_UID,
      tokenId: "tok_tablet_2",
      token: "fcm_token_string_tablet_2_valid_length",
      familyId: "fam_v16_tasks",
      role: "PARENT",
      platform: "ANDROID",
      appVersion: "1.0.0",
      updatedAt: 1700000021000,
    })
  );

  // 3. Alice can refresh and delete her own token on logout
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("fcm_tokens").doc("tok_phone_1").update({
      token: "fcm_token_string_phone_1_refreshed_valid",
      updatedAt: 1700000025000,
    })
  );
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("fcm_tokens").doc("tok_tablet_2").delete()
  );

  // 4. Bob cannot read, overwrite, or delete Alice's FCM tokens
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("fcm_tokens").doc("tok_phone_1").get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("fcm_tokens").doc("tok_phone_1").update({
      token: "hijacked_token_string_valid_length",
    })
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("fcm_tokens").doc("tok_phone_1").delete()
  );

  // 5. Client cannot spoof uid or write to server-only /families/{familyId}/notification_dispatches
  await assertFails(
    bobDb.collection("users").doc(BOB_UID).collection("fcm_tokens").doc("tok_spoof").set({
      uid: ALICE_UID,
      tokenId: "tok_spoof",
      token: "fcm_token_string_spoof_valid_length",
    })
  );
  await assertFails(
    aliceDb.collection("families").doc("fam_v16_tasks").collection("notification_dispatches").doc("disp_1").set({
      dispatchedAt: 1700000030000,
    })
  );
});

test("Family Alarm Clock Ownership & Editing Permissions: Parent-for-Child, Child-for-Self, and Parent-for-Self", async () => {
  const parentDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const childDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await testEnv.withSecurityRulesDisabled(async (context) => {
    const adminDb = context.firestore();
    await adminDb.collection("families").doc("fam_alarms").set({
      familyId: "fam_alarms",
      familyName: "משפחת אלון",
      ownerUid: ALICE_UID,
      createdById: ALICE_UID,
      adminUids: [ALICE_UID],
      memberUids: [ALICE_UID, BOB_UID],
      memberIds: [ALICE_UID, BOB_UID],
    });
    await adminDb.collection("families").doc("fam_alarms").collection("members").doc(ALICE_UID).set({
      id: ALICE_UID,
      uid: ALICE_UID,
      familyId: "fam_alarms",
      name: "אבא",
      role: "PARENT",
      earnedPoints: 0,
    });
    await adminDb.collection("families").doc("fam_alarms").collection("members").doc(BOB_UID).set({
      id: BOB_UID,
      uid: BOB_UID,
      familyId: "fam_alarms",
      name: "נועם",
      role: "CHILD",
      earnedPoints: 0,
    });
  });

  // 1. Parent creates an alarm assigned to the Child (BOB_UID) -> SUCCEEDS
  const parentForChildRef = parentDb.collection("families").doc("fam_alarms").collection("alarms").doc("alarm_parent_for_child");
  await assertSucceeds(
    parentForChildRef.set({
      id: "alarm_parent_for_child",
      familyId: "fam_alarms",
      title: "השכמה לבית ספר",
      hour: 7,
      minute: 0,
      createdByMemberId: ALICE_UID,
      createdByRole: "PARENT",
      assignedToMemberId: BOB_UID,
      assignedMemberId: BOB_UID,
      isEnabled: true,
      soundType: "SYSTEM_ALARM",
      volumePercent: 85,
      snoozeDurationMinutes: 5,
      scheduleStatus: "PENDING_TARGET_DEVICE",
    })
  );

  // Child can read the alarm
  const childViewOfParentAlarm = childDb.collection("families").doc("fam_alarms").collection("alarms").doc("alarm_parent_for_child");
  await assertSucceeds(childViewOfParentAlarm.get());

  // Child CANNOT edit time, disable, change volume/sound, or delete the parent-created alarm -> MUST FAIL
  await assertFails(childViewOfParentAlarm.update({ isEnabled: false }));
  await assertFails(childViewOfParentAlarm.update({ hour: 9, minute: 30 }));
  await assertFails(childViewOfParentAlarm.update({ volumePercent: 10 }));
  await assertFails(childViewOfParentAlarm.delete());

  // Child's device CAN write local scheduling acknowledgement on the parent-created alarm assigned to that child -> SUCCEEDS
  await assertSucceeds(
    childViewOfParentAlarm.update({
      scheduleStatus: "SCHEDULED_LOCALLY",
      acknowledgedByMemberId: BOB_UID,
      acknowledgedByDeviceName: "Pixel 8",
      acknowledgedAtMillis: 1700000100000,
      nextTriggerAtMillis: 1700050000000,
      lastScheduleError: "",
      updatedByUid: BOB_UID,
      updatedAt: 1700000100000,
    })
  );

  // Parent CAN edit, disable, and delete the parent-created child alarm -> SUCCEEDS
  await assertSucceeds(parentForChildRef.update({ isEnabled: false, hour: 7, minute: 15 }));

  // 2. Child creates their OWN alarm -> SUCCEEDS
  const childOwnAlarmRef = childDb.collection("families").doc("fam_alarms").collection("alarms").doc("alarm_child_own");
  await assertSucceeds(
    childOwnAlarmRef.set({
      id: "alarm_child_own",
      familyId: "fam_alarms",
      title: "אימון כדורסל",
      hour: 16,
      minute: 30,
      createdByMemberId: BOB_UID,
      createdByRole: "CHILD",
      assignedToMemberId: BOB_UID,
      assignedMemberId: BOB_UID,
      isEnabled: true,
      soundType: "SYSTEM_ALARM",
      volumePercent: 80,
      snoozeDurationMinutes: 5,
      scheduleStatus: "SCHEDULED_LOCALLY",
    })
  );

  // Child CAN edit, disable, and delete their own alarm -> SUCCEEDS
  await assertSucceeds(childOwnAlarmRef.update({ minute: 45, isEnabled: false }));
  // Parent CAN also edit, enable, and delete the child's alarm -> SUCCEEDS
  await assertSucceeds(
    parentDb.collection("families").doc("fam_alarms").collection("alarms").doc("alarm_child_own").update({
      isEnabled: true,
    })
  );
  await assertSucceeds(childOwnAlarmRef.delete());

  // Child CANNOT create an alarm assigned to the Parent -> MUST FAIL
  await assertFails(
    childDb.collection("families").doc("fam_alarms").collection("alarms").doc("alarm_illegal_for_parent").set({
      id: "alarm_illegal_for_parent",
      familyId: "fam_alarms",
      title: "השכמה לאבא",
      hour: 5,
      minute: 0,
      createdByMemberId: BOB_UID,
      createdByRole: "CHILD",
      assignedToMemberId: ALICE_UID,
      assignedMemberId: ALICE_UID,
      isEnabled: true,
    })
  );

  // 3. Parent creates their OWN personal alarm -> SUCCEEDS
  const parentOwnAlarmRef = parentDb.collection("families").doc("fam_alarms").collection("alarms").doc("alarm_parent_own");
  await assertSucceeds(
    parentOwnAlarmRef.set({
      id: "alarm_parent_own",
      familyId: "fam_alarms",
      title: "פגישת בוקר",
      hour: 6,
      minute: 30,
      createdByMemberId: ALICE_UID,
      createdByRole: "PARENT",
      assignedToMemberId: ALICE_UID,
      assignedMemberId: ALICE_UID,
      isEnabled: true,
      scheduleStatus: "SCHEDULED_LOCALLY",
    })
  );

  // Child CANNOT read (get), modify, disable, delete, or acknowledge Parent's personal alarm -> MUST FAIL
  const childViewOfParentOwn = childDb.collection("families").doc("fam_alarms").collection("alarms").doc("alarm_parent_own");
  await assertFails(childViewOfParentOwn.get());
  await assertSucceeds(parentOwnAlarmRef.get());
  await assertFails(childViewOfParentOwn.update({ isEnabled: false }));
  await assertFails(
    childViewOfParentOwn.update({
      scheduleStatus: "SCHEDULED_LOCALLY",
      acknowledgedByMemberId: BOB_UID,
    })
  );
  await assertFails(childViewOfParentOwn.delete());
});

test("Security Audit Fix: Atomic Points Ledger, Duplicate TASK_EARNED Prevention, Child Points Protection, and Sensitive Field Immutability", async () => {
  const parent1Db = testEnv.authenticatedContext(ALICE_UID).firestore();
  const parent2Db = testEnv.authenticatedContext("mom_parent_2").firestore();
  const childDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const famId = "fam_points_audit";

  await testEnv.withSecurityRulesDisabled(async (context) => {
    const adminDb = context.firestore();
    await adminDb.collection("families").doc(famId).set({
      id: famId,
      familyId: famId,
      familyName: "משפחת כץ",
      createdById: ALICE_UID,
      createdBy: ALICE_UID,
      ownerUid: ALICE_UID,
      adminUids: [ALICE_UID, "mom_parent_2"],
      memberIds: [ALICE_UID, "mom_parent_2", BOB_UID],
      memberUids: [ALICE_UID, "mom_parent_2", BOB_UID],
    });
    await adminDb.collection("families").doc(famId).collection("members").doc(ALICE_UID).set({
      id: ALICE_UID,
      uid: ALICE_UID,
      familyId: famId,
      name: "אבא כץ",
      email: "dad@example.com",
      googleEmail: "dad@example.com",
      role: "PARENT",
      familyIdentity: "FATHER",
      earnedPoints: 0,
      spentPoints: 0,
    });
    await adminDb.collection("families").doc(famId).collection("members").doc("mom_parent_2").set({
      id: "mom_parent_2",
      uid: "mom_parent_2",
      familyId: famId,
      name: "אמא כץ",
      email: "mom@example.com",
      googleEmail: "mom@example.com",
      role: "PARENT",
      familyIdentity: "MOTHER",
      earnedPoints: 0,
      spentPoints: 0,
    });
    await adminDb.collection("families").doc(famId).collection("members").doc(BOB_UID).set({
      id: BOB_UID,
      uid: BOB_UID,
      familyId: famId,
      name: "נועם כץ",
      email: "noam@example.com",
      googleEmail: "noam@example.com",
      role: "CHILD",
      familyIdentity: "SON",
      earnedPoints: 50,
      spentPoints: 0,
    });
    await adminDb.collection("families").doc(famId).collection("tasks").doc("task_audit_1").set({
      id: "task_audit_1",
      familyId: famId,
      title: "ניקיון חדר",
      assignedToMemberId: BOB_UID,
      assignedUid: BOB_UID,
      points: 30,
      isCompleted: true,
      approvedByParent: false,
    });
  });

  // 1. Child CANNOT modify earnedPoints on their own member doc or create points_history -> MUST FAIL
  await assertFails(
    childDb.collection("families").doc(famId).collection("members").doc(BOB_UID).update({
      earnedPoints: 500,
    })
  );
  const childFakeBatch = childDb.batch();
  childFakeBatch.update(
    childDb.collection("families").doc(famId).collection("members").doc(BOB_UID),
    {
      earnedPoints: 100,
      lastPointsOperationId: "pts_add_child_hack",
      updatedAt: 1700001000000,
    }
  );
  childFakeBatch.set(
    childDb.collection("families").doc(famId).collection("points_history").doc("pts_add_child_hack"),
    {
      id: "pts_add_child_hack",
      operationId: "pts_add_child_hack",
      familyId: famId,
      memberId: BOB_UID,
      memberName: "נועם כץ",
      actionType: "ADD",
      pointsDelta: 50,
      previousBalance: 50,
      newBalance: 100,
      reason: "Hack",
      performedByUid: BOB_UID,
      performedByName: "נועם כץ",
      timestampMillis: 1700001000000,
    }
  );
  await assertFails(childFakeBatch.commit());

  // 2. Parent CANNOT update earnedPoints without creating a matching points_history entry in the same transaction -> MUST FAIL
  await assertFails(
    parent1Db.collection("families").doc(famId).collection("members").doc(BOB_UID).update({
      earnedPoints: 80,
      lastPointsOperationId: "pts_missing_history",
      updatedAt: 1700001000000,
    })
  );

  // 3. Parent CANNOT create a fake TASK_EARNED entry for a non-existent task, wrong points value, or without approving the task -> MUST FAIL
  const fakeTaskEarnedBatch1 = parent1Db.batch();
  fakeTaskEarnedBatch1.update(
    parent1Db.collection("families").doc(famId).collection("members").doc(BOB_UID),
    {
      earnedPoints: 150,
      lastPointsOperationId: "pts_task_task_audit_1",
      updatedAt: 1700001001000,
    }
  );
  fakeTaskEarnedBatch1.set(
    parent1Db.collection("families").doc(famId).collection("points_history").doc("pts_task_task_audit_1"),
    {
      id: "pts_task_task_audit_1",
      operationId: "pts_task_task_audit_1",
      familyId: famId,
      memberId: BOB_UID,
      memberName: "נועם כץ",
      actionType: "TASK_EARNED",
      pointsDelta: 100, // Mismatched points (task is worth 30, not 100!)
      previousBalance: 50,
      newBalance: 150,
      reason: "Fake task points",
      performedByUid: ALICE_UID,
      performedByName: "אבא כץ",
      timestampMillis: 1700001001000,
      taskId: "task_audit_1",
    }
  );
  await assertFails(fakeTaskEarnedBatch1.commit());

  // 4. Legitimate single-transaction task approval + points credit + points_history/pts_task_task_audit_1 -> SUCCEEDS
  const validApproveBatch = parent1Db.batch();
  validApproveBatch.update(
    parent1Db.collection("families").doc(famId).collection("tasks").doc("task_audit_1"),
    {
      isCompleted: true,
      approvedByParent: true,
      rewardedOperationId: "pts_task_task_audit_1",
      updatedAt: 1700001002000,
    }
  );
  validApproveBatch.update(
    parent1Db.collection("families").doc(famId).collection("members").doc(BOB_UID),
    {
      earnedPoints: 80,
      lastPointsOperationId: "pts_task_task_audit_1",
      updatedAt: 1700001002000,
    }
  );
  validApproveBatch.set(
    parent1Db.collection("families").doc(famId).collection("points_history").doc("pts_task_task_audit_1"),
    {
      id: "pts_task_task_audit_1",
      operationId: "pts_task_task_audit_1",
      familyId: famId,
      memberId: BOB_UID,
      memberName: "נועם כץ",
      actionType: "TASK_EARNED",
      pointsDelta: 30,
      previousBalance: 50,
      newBalance: 80,
      reason: "השלמת משימה: ניקיון חדר",
      performedByUid: ALICE_UID,
      performedByName: "אבא כץ",
      timestampMillis: 1700001002000,
      taskId: "task_audit_1",
    }
  );
  await assertSucceeds(validApproveBatch.commit());

  // 5. Second parent (or same parent) attempting to approve/credit the same task a second time -> MUST FAIL
  const duplicateApproveBatch = parent2Db.batch();
  duplicateApproveBatch.update(
    parent2Db.collection("families").doc(famId).collection("tasks").doc("task_audit_1"),
    {
      isCompleted: true,
      approvedByParent: true,
      rewardedOperationId: "pts_task_task_audit_1",
      updatedAt: 1700001003000,
    }
  );
  duplicateApproveBatch.update(
    parent2Db.collection("families").doc(famId).collection("members").doc(BOB_UID),
    {
      earnedPoints: 110,
      lastPointsOperationId: "pts_task_task_audit_1",
      updatedAt: 1700001003000,
    }
  );
  duplicateApproveBatch.set(
    parent2Db.collection("families").doc(famId).collection("points_history").doc("pts_task_task_audit_1"),
    {
      id: "pts_task_task_audit_1",
      operationId: "pts_task_task_audit_1",
      familyId: famId,
      memberId: BOB_UID,
      memberName: "נועם כץ",
      actionType: "TASK_EARNED",
      pointsDelta: 30,
      previousBalance: 80,
      newBalance: 110,
      reason: "השלמת משימה כפולה",
      performedByUid: "mom_parent_2",
      performedByName: "אמא כץ",
      timestampMillis: 1700001003000,
      taskId: "task_audit_1",
    }
  );
  await assertFails(duplicateApproveBatch.commit());

  // 6. Atomic ADD (+20 -> 100), DEDUCT (-40 -> 60), and RESET (-60 -> 0) succeed and preserve history
  const addBatch = parent1Db.batch();
  addBatch.update(parent1Db.collection("families").doc(famId).collection("members").doc(BOB_UID), {
    earnedPoints: 100,
    lastPointsOperationId: "pts_add_valid_1",
    updatedAt: 1700001004000,
  });
  addBatch.set(parent1Db.collection("families").doc(famId).collection("points_history").doc("pts_add_valid_1"), {
    id: "pts_add_valid_1",
    operationId: "pts_add_valid_1",
    familyId: famId,
    memberId: BOB_UID,
    memberName: "נועם כץ",
    actionType: "ADD",
    pointsDelta: 20,
    previousBalance: 80,
    newBalance: 100,
    reason: "עזרה בבית",
    performedByUid: ALICE_UID,
    performedByName: "אבא כץ",
    timestampMillis: 1700001004000,
  });
  await assertSucceeds(addBatch.commit());

  const deductBatch = parent1Db.batch();
  deductBatch.update(parent1Db.collection("families").doc(famId).collection("members").doc(BOB_UID), {
    earnedPoints: 60,
    lastPointsOperationId: "pts_ded_valid_1",
    updatedAt: 1700001005000,
  });
  deductBatch.set(parent1Db.collection("families").doc(famId).collection("points_history").doc("pts_ded_valid_1"), {
    id: "pts_ded_valid_1",
    operationId: "pts_ded_valid_1",
    familyId: famId,
    memberId: BOB_UID,
    memberName: "נועם כץ",
    actionType: "DEDUCT",
    pointsDelta: -40,
    previousBalance: 100,
    newBalance: 60,
    reason: "אי הכנת שיעורים",
    performedByUid: ALICE_UID,
    performedByName: "אבא כץ",
    timestampMillis: 1700001005000,
  });
  await assertSucceeds(deductBatch.commit());

  const resetBatch = parent1Db.batch();
  resetBatch.update(parent1Db.collection("families").doc(famId).collection("members").doc(BOB_UID), {
    earnedPoints: 0,
    lastPointsOperationId: "pts_rst_valid_1",
    updatedAt: 1700001006000,
  });
  resetBatch.set(parent1Db.collection("families").doc(famId).collection("points_history").doc("pts_rst_valid_1"), {
    id: "pts_rst_valid_1",
    operationId: "pts_rst_valid_1",
    familyId: famId,
    memberId: BOB_UID,
    memberName: "נועם כץ",
    actionType: "RESET",
    pointsDelta: -60,
    previousBalance: 60,
    newBalance: 0,
    reason: "איפוס חודשי",
    performedByUid: ALICE_UID,
    performedByName: "אבא כץ",
    timestampMillis: 1700001006000,
  });
  await assertSucceeds(resetBatch.commit());

  // 7. Sensitive fields (uid, id, familyId, email, googleEmail, familyIdentity, standalone role) CANNOT be tampered with
  const bobMemberRef = parent1Db.collection("families").doc(famId).collection("members").doc(BOB_UID);
  await assertFails(bobMemberRef.update({ uid: "spoofed_uid" }));
  await assertFails(bobMemberRef.update({ id: "spoofed_id" }));
  await assertFails(bobMemberRef.update({ familyId: "other_family" }));
  await assertFails(bobMemberRef.update({ email: "hijacked@example.com" }));
  await assertFails(bobMemberRef.update({ googleEmail: "hijacked@example.com" }));
  await assertFails(bobMemberRef.update({ familyIdentity: "DAUGHTER" }));
  await assertFails(bobMemberRef.update({ role: "PARENT", familyIdentity: "FATHER" })); // fails without atomic adminUids sync

  // 8. Authorized parent role change synchronized with /families/{famId}.adminUids SUCCEEDS, while demoting owner FAILS
  const promoteBatch = parent1Db.batch();
  promoteBatch.update(bobMemberRef, {
    role: "PARENT",
    familyIdentity: "FATHER",
    updatedAt: 1700001007000,
  });
  promoteBatch.update(parent1Db.collection("families").doc(famId), {
    adminUids: [ALICE_UID, "mom_parent_2", BOB_UID],
  });
  await assertSucceeds(promoteBatch.commit());

  // Second parent CANNOT demote the primary family creator/owner (ALICE_UID)
  const illegalDemoteOwnerBatch = parent2Db.batch();
  illegalDemoteOwnerBatch.update(
    parent2Db.collection("families").doc(famId).collection("members").doc(ALICE_UID),
    {
      role: "CHILD",
      familyIdentity: "CHILD",
      updatedAt: 1700001008000,
    }
  );
  illegalDemoteOwnerBatch.update(parent2Db.collection("families").doc(famId), {
    adminUids: ["mom_parent_2", BOB_UID],
  });
  await assertFails(illegalDemoteOwnerBatch.commit());
});

