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

  // 1. Parent creates an alarm assigned to the Child (BOB_UID) with updatedByUid = ALICE_UID -> SUCCEEDS
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
      updatedByUid: ALICE_UID,
    })
  );

  // Child can read the alarm
  const childViewOfParentAlarm = childDb.collection("families").doc("fam_alarms").collection("alarms").doc("alarm_parent_for_child");
  await assertSucceeds(childViewOfParentAlarm.get());

  // Child CANNOT edit time, disable, change volume/sound, overwrite updatedByUid, or delete the parent-created alarm -> MUST FAIL
  await assertFails(childViewOfParentAlarm.update({ isEnabled: false }));
  await assertFails(childViewOfParentAlarm.update({ hour: 9, minute: 30 }));
  await assertFails(childViewOfParentAlarm.update({ volumePercent: 10 }));
  await assertFails(
    childViewOfParentAlarm.update({
      scheduleStatus: "SCHEDULED_LOCALLY",
      acknowledgedByMemberId: BOB_UID,
      updatedByUid: BOB_UID,
    })
  );
  await assertFails(childViewOfParentAlarm.delete());

  // Child's device CAN write local scheduling acknowledgement on the parent-created alarm assigned to that child
  // WITHOUT overwriting updatedByUid -> SUCCEEDS and preserves updatedByUid == ALICE_UID
  await assertSucceeds(
    childViewOfParentAlarm.update({
      scheduleStatus: "SCHEDULED_LOCALLY",
      acknowledgedByMemberId: BOB_UID,
      acknowledgedByDeviceName: "Pixel 8",
      acknowledgedAtMillis: 1700000100000,
      nextTriggerAtMillis: 1700050000000,
      lastScheduleError: "",
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

test("Alarm Cancellation Sync Root-Cause Fix: Create -> Child Ack (preserves updatedByUid) -> Parent Delete -> Cloud Function DELETE & Child Confirmation", async () => {
  const assert = require("node:assert");
  const functionsIndex = require("./functions/index.js");
  const functionsPkg = require("./functions/package.json");
  const { classifyAlarmSyncEvent, resolveVerifiedRecipientUids } = functionsIndex;

  // Verify Cloud Function export alignment with deployed Firebase environment:
  // 1. Exported name is onFamilyAlarmFirestoreWritten (v2 onDocumentWritten, region europe-west2)
  // 2. No duplicate export under onFamilyAlarmWritten
  // 3. onFamilyTaskWritten remains intact
  // 4. Runtime configured for Node.js 22
  assert.strictEqual(typeof functionsIndex.onFamilyAlarmFirestoreWritten, "function");
  assert.strictEqual(functionsIndex.onFamilyAlarmWritten, undefined);
  assert.strictEqual(typeof functionsIndex.onFamilyTaskWritten, "function");
  assert.strictEqual(functionsPkg.engines && functionsPkg.engines.node, "22");
  if (functionsIndex.onFamilyAlarmFirestoreWritten.__triggerOptions) {
    assert.strictEqual(functionsIndex.onFamilyAlarmFirestoreWritten.__triggerOptions.region, "europe-west2");
    assert.strictEqual(
      functionsIndex.onFamilyAlarmFirestoreWritten.__triggerOptions.document,
      "families/{familyId}/alarms/{alarmId}"
    );
    assert.strictEqual(
      functionsIndex.onFamilyAlarmFirestoreWritten.__triggerOptions.database,
      "ai-studio-android-familyta-a054dbd1-9c2d-4a83-ad6d-dcfe6fb0d2ca"
    );
  }

  const parentDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const childDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const famId = "fam_alarm_cancel_sync";
  const alarmId = "alarm_roey_wakeup_700";

  await testEnv.withSecurityRulesDisabled(async (context) => {
    const adminDb = context.firestore();
    await adminDb.collection("families").doc(famId).set({
      id: famId,
      familyId: famId,
      familyName: "משפחת כהן",
      ownerUid: ALICE_UID,
      createdById: ALICE_UID,
      adminUids: [ALICE_UID],
      memberUids: [ALICE_UID, BOB_UID],
      memberIds: [ALICE_UID, BOB_UID],
    });
    await adminDb.collection("families").doc(famId).collection("members").doc(ALICE_UID).set({
      id: ALICE_UID,
      uid: ALICE_UID,
      familyId: famId,
      name: "אבא",
      role: "PARENT",
      earnedPoints: 0,
      spentPoints: 0,
    });
    await adminDb.collection("families").doc(famId).collection("members").doc(BOB_UID).set({
      id: BOB_UID,
      uid: BOB_UID,
      familyId: famId,
      name: "רועי",
      role: "CHILD",
      earnedPoints: 0,
      spentPoints: 0,
    });
  });

  // Step 1: Parent creates alarm for Child (Roey = BOB_UID)
  const parentAlarmRef = parentDb.collection("families").doc(famId).collection("alarms").doc(alarmId);
  const initialAlarmData = {
    id: alarmId,
    familyId: famId,
    title: "השכמה לרועי",
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
    acknowledgedByMemberId: "",
    updatedByUid: ALICE_UID,
    updatedAt: 1700010000000,
  };
  await assertSucceeds(parentAlarmRef.set(initialAlarmData));

  const createEventSpec = classifyAlarmSyncEvent(null, initialAlarmData, alarmId);
  assert.ok(createEventSpec, "Parent creating child alarm must produce CREATE sync event");
  assert.strictEqual(createEventSpec.action, "CREATE");
  assert.strictEqual(createEventSpec.targetUid, BOB_UID);
  assert.strictEqual(createEventSpec.actorUid, ALICE_UID);

  // Step 2: Child device acknowledges local scheduling WITHOUT overwriting updatedByUid
  const childAlarmRef = childDb.collection("families").doc(famId).collection("alarms").doc(alarmId);
  await assertSucceeds(
    childAlarmRef.update({
      scheduleStatus: "SCHEDULED_LOCALLY",
      acknowledgedByMemberId: BOB_UID,
      acknowledgedByDeviceName: "Galaxy S24",
      acknowledgedAtMillis: 1700010005000,
      nextTriggerAtMillis: 1700050000000,
      lastScheduleError: "",
      updatedAt: 1700010005000,
    })
  );

  const snapAfterAck = await childAlarmRef.get();
  const dataAfterAck = snapAfterAck.data();
  assert.strictEqual(dataAfterAck.acknowledgedByMemberId, BOB_UID);
  assert.strictEqual(
    dataAfterAck.updatedByUid,
    ALICE_UID,
    "Child acknowledgement must preserve parent's updatedByUid unchanged"
  );

  // Ack-only update must NOT trigger another FCM sync loop
  const ackEventSpec = classifyAlarmSyncEvent(initialAlarmData, dataAfterAck, alarmId);
  assert.strictEqual(ackEventSpec, null, "Ack-only write must not trigger redundant FCM sync push");

  // Step 3: Parent deletes the alarm after Child acknowledged it
  await assertSucceeds(parentAlarmRef.delete());
  await assertSucceeds(
    parentDb.collection("families").doc(famId).update({
      deletedAlarmIds: [alarmId],
      updatedAt: 1700010010000,
    })
  );

  // Cloud Function classifies hard delete (beforeData = dataAfterAck, afterData = null)
  const deleteEventSpec = classifyAlarmSyncEvent(dataAfterAck, null, alarmId);
  assert.ok(deleteEventSpec, "Parent deleting acknowledged child alarm must produce DELETE sync event");
  assert.strictEqual(deleteEventSpec.eventType, "ALARM_SYNC_REQUIRED");
  assert.strictEqual(deleteEventSpec.action, "DELETE");
  assert.strictEqual(deleteEventSpec.targetUid, BOB_UID);
  assert.strictEqual(
    deleteEventSpec.actorUid,
    ALICE_UID,
    "DELETE event actorUid must be the parent (ALICE_UID), never the acknowledging child (BOB_UID)"
  );

  // Even if legacy data had updatedByUid == BOB_UID, classifyAlarmSyncEvent & resolveVerifiedRecipientUids must still target BOB_UID
  const legacyBeforeData = { ...dataAfterAck, updatedByUid: BOB_UID };
  const legacyDeleteSpec = classifyAlarmSyncEvent(legacyBeforeData, null, alarmId);
  assert.ok(legacyDeleteSpec);
  assert.strictEqual(legacyDeleteSpec.action, "DELETE");
  assert.strictEqual(legacyDeleteSpec.targetUid, BOB_UID);
  assert.notStrictEqual(legacyDeleteSpec.actorUid, BOB_UID);

  await testEnv.withSecurityRulesDisabled(async (context) => {
    const adminDb = context.firestore();
    const recipientsNormal = await resolveVerifiedRecipientUids(adminDb, famId, deleteEventSpec);
    assert.deepStrictEqual(recipientsNormal, [BOB_UID]);
    const recipientsLegacy = await resolveVerifiedRecipientUids(adminDb, famId, legacyDeleteSpec);
    assert.deepStrictEqual(recipientsLegacy, [BOB_UID]);
  });

  // Step 4: Child background worker cancels local alarm and writes cancelledAlarmIds confirmation to member doc
  const childMemberRef = childDb.collection("families").doc(famId).collection("members").doc(BOB_UID);
  await assertSucceeds(
    childMemberRef.update({
      cancelledAlarmIds: [alarmId],
      lastAlarmSyncAtMillis: 1700010012000,
    })
  );

  // Step 5: Duplicate deletion / duplicate confirmation is idempotent and does not fail
  await assertSucceeds(
    childMemberRef.update({
      cancelledAlarmIds: [alarmId],
      lastAlarmSyncAtMillis: 1700010015000,
    })
  );
});

test("Task Execution Time & Reminders: Preserves 4 notification events, dispatches silent TASK_SYNC_REQUIRED for create/time-change/complete/delete, and cancels previous assignee on reassignment", async () => {
  const assert = require("node:assert");
  const functionsIndex = require("./functions/index.js");
  const {
    classifyTaskNotificationEvent,
    classifyTaskReminderSyncEvents,
  } = functionsIndex;

  assert.strictEqual(typeof classifyTaskNotificationEvent, "function");
  assert.strictEqual(typeof classifyTaskReminderSyncEvents, "function");

  const PARENT_UID = ALICE_UID;
  const CHILD_1_UID = BOB_UID;
  const CHILD_2_UID = "child_maya_789";
  const taskId = "task_math_homework_1";

  // 1. Create task with execution time & reminder (10 minutes before)
  const createdTask = {
    id: taskId,
    familyId: "fam_velvet_1",
    title: "שיעורי בית בחשבון",
    assignedToMemberId: CHILD_1_UID,
    assignedMemberId: CHILD_1_UID,
    assignedUid: CHILD_1_UID,
    dateEpochDay: 20500,
    dueTimeFormatted: "17:30",
    hasDueTime: true,
    reminderOffset: "MINUTES_10_BEFORE",
    isCompleted: false,
    approvedByParent: false,
    points: 20,
    updatedByUid: PARENT_UID,
  };

  // Existing notification event #1 (TASK_ASSIGNED) is preserved
  const assignNotif = classifyTaskNotificationEvent(null, createdTask, taskId);
  assert.ok(assignNotif);
  assert.strictEqual(assignNotif.eventType, "TASK_ASSIGNED");
  assert.strictEqual(assignNotif.targetUid, CHILD_1_UID);

  // Silent TASK_SYNC_REQUIRED event is generated for background reminder scheduling
  const createSyncSpecs = classifyTaskReminderSyncEvents(null, createdTask, taskId);
  assert.strictEqual(createSyncSpecs.length, 1);
  assert.strictEqual(createSyncSpecs[0].eventType, "TASK_SYNC_REQUIRED");
  assert.strictEqual(createSyncSpecs[0].action, "UPSERT");
  assert.strictEqual(createSyncSpecs[0].targetUid, CHILD_1_UID);
  assert.strictEqual(createSyncSpecs[0].hasDueTime, true);
  assert.strictEqual(createSyncSpecs[0].reminderOffset, "MINUTES_10_BEFORE");

  // 2. Changing execution time (17:30 -> 18:45) produces silent UPSERT sync WITHOUT duplicate TASK_ASSIGNED notification
  const timeChangedTask = {
    ...createdTask,
    dueTimeFormatted: "18:45",
    updatedByUid: PARENT_UID,
  };
  const timeChangeNotif = classifyTaskNotificationEvent(createdTask, timeChangedTask, taskId);
  assert.strictEqual(timeChangeNotif, null, "Time change alone must not emit duplicate visible notification");

  const timeChangeSyncSpecs = classifyTaskReminderSyncEvents(createdTask, timeChangedTask, taskId);
  assert.strictEqual(timeChangeSyncSpecs.length, 1);
  assert.strictEqual(timeChangeSyncSpecs[0].eventType, "TASK_SYNC_REQUIRED");
  assert.strictEqual(timeChangeSyncSpecs[0].action, "UPSERT");
  assert.strictEqual(timeChangeSyncSpecs[0].targetUid, CHILD_1_UID);
  assert.strictEqual(timeChangeSyncSpecs[0].dueTimeFormatted, "18:45");

  // 3. Reassigning task from CHILD_1_UID to CHILD_2_UID:
  //    - Preserves TASK_REASSIGNED notification (#2)
  //    - Sends CANCEL to previous assignee (CHILD_1_UID) so old reminder is cancelled on the previous device!
  //    - Sends UPSERT to new assignee (CHILD_2_UID)
  const reassignedTask = {
    ...timeChangedTask,
    assignedToMemberId: CHILD_2_UID,
    assignedMemberId: CHILD_2_UID,
    assignedUid: CHILD_2_UID,
    updatedByUid: PARENT_UID,
  };
  const reassignNotif = classifyTaskNotificationEvent(timeChangedTask, reassignedTask, taskId);
  assert.ok(reassignNotif);
  assert.strictEqual(reassignNotif.eventType, "TASK_REASSIGNED");
  assert.strictEqual(reassignNotif.targetUid, CHILD_2_UID);

  const reassignSyncSpecs = classifyTaskReminderSyncEvents(timeChangedTask, reassignedTask, taskId);
  assert.strictEqual(reassignSyncSpecs.length, 2);
  const cancelPrevSpec = reassignSyncSpecs.find((s) => s.targetUid === CHILD_1_UID);
  const upsertNewSpec = reassignSyncSpecs.find((s) => s.targetUid === CHILD_2_UID);
  assert.ok(cancelPrevSpec, "Must emit CANCEL sync event for previous assignee");
  assert.strictEqual(cancelPrevSpec.action, "CANCEL");
  assert.ok(upsertNewSpec, "Must emit UPSERT sync event for new assignee");
  assert.strictEqual(upsertNewSpec.action, "UPSERT");

  // 4. Child completes task -> Preserves TASK_SUBMITTED_FOR_APPROVAL (#3) + emits COMPLETE sync to cancel local reminder
  const completedTask = {
    ...reassignedTask,
    isCompleted: true,
    completedByMemberId: CHILD_2_UID,
    updatedByUid: CHILD_2_UID,
  };
  const submitNotif = classifyTaskNotificationEvent(reassignedTask, completedTask, taskId);
  assert.ok(submitNotif);
  assert.strictEqual(submitNotif.eventType, "TASK_SUBMITTED_FOR_APPROVAL");

  const completeSyncSpecs = classifyTaskReminderSyncEvents(reassignedTask, completedTask, taskId);
  assert.strictEqual(completeSyncSpecs.length, 1);
  assert.strictEqual(completeSyncSpecs[0].action, "COMPLETE");
  assert.strictEqual(completeSyncSpecs[0].targetUid, CHILD_2_UID);

  // 5. Parent approves task -> Preserves TASK_APPROVED (#4)
  const approvedTask = {
    ...completedTask,
    approvedByParent: true,
    updatedByUid: PARENT_UID,
  };
  const approveNotif = classifyTaskNotificationEvent(completedTask, approvedTask, taskId);
  assert.ok(approveNotif);
  assert.strictEqual(approveNotif.eventType, "TASK_APPROVED");
  assert.strictEqual(approveNotif.targetUid, CHILD_2_UID);

  // 6. Parent deletes task -> Emits DELETE sync event to cancel local reminder on assignee's device
  const deleteSyncSpecs = classifyTaskReminderSyncEvents(reassignedTask, null, taskId);
  assert.strictEqual(deleteSyncSpecs.length, 1);
  assert.strictEqual(deleteSyncSpecs[0].eventType, "TASK_SYNC_REQUIRED");
  assert.strictEqual(deleteSyncSpecs[0].action, "DELETE");
  assert.strictEqual(deleteSyncSpecs[0].targetUid, CHILD_2_UID);
});

test("Task Collaboration Proposals (Volunteer & Swap): Two-Stage Child + Parent Approval and Direct Child Reassignment Protection", async () => {
  const parentDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const child1Db = testEnv.authenticatedContext(BOB_UID).firestore();
  const child2Uid = "child_maya_789";
  const child2Db = testEnv.authenticatedContext(child2Uid).firestore();
  const famId = "fam_collab_rules";

  await testEnv.withSecurityRulesDisabled(async (context) => {
    const adminDb = context.firestore();
    await adminDb.collection("families").doc(famId).set({
      id: famId,
      familyId: famId,
      familyName: "משפחת שיתוף",
      ownerUid: ALICE_UID,
      createdById: ALICE_UID,
      adminUids: [ALICE_UID],
      memberIds: [ALICE_UID, BOB_UID, child2Uid],
      memberUids: [ALICE_UID, BOB_UID, child2Uid],
    });
    await adminDb.collection("families").doc(famId).collection("members").doc(ALICE_UID).set({
      id: ALICE_UID,
      uid: ALICE_UID,
      familyId: famId,
      name: "אמא",
      role: "PARENT",
      earnedPoints: 0,
      spentPoints: 0,
    });
    await adminDb.collection("families").doc(famId).collection("members").doc(BOB_UID).set({
      id: BOB_UID,
      uid: BOB_UID,
      familyId: famId,
      name: "רועי",
      role: "CHILD",
      earnedPoints: 40,
      spentPoints: 0,
    });
    await adminDb.collection("families").doc(famId).collection("members").doc(child2Uid).set({
      id: child2Uid,
      uid: child2Uid,
      familyId: famId,
      name: "מאיה",
      role: "CHILD",
      earnedPoints: 30,
      spentPoints: 0,
    });
    await adminDb.collection("families").doc(famId).collection("tasks").doc("task_roey_dishes").set({
      id: "task_roey_dishes",
      familyId: famId,
      title: "פינוי מדיח",
      assignedToMemberId: BOB_UID,
      assignedUid: BOB_UID,
      points: 20,
      isCompleted: false,
      approvedByParent: false,
    });
    await adminDb.collection("families").doc(famId).collection("tasks").doc("task_maya_room").set({
      id: "task_maya_room",
      familyId: famId,
      title: "סידור חדר",
      assignedToMemberId: child2Uid,
      assignedUid: child2Uid,
      points: 15,
      isCompleted: false,
      approvedByParent: false,
    });
  });

  // 1. Child CANNOT directly reassign a task or alter assignedToMemberId -> MUST FAIL
  await assertFails(
    child2Db.collection("families").doc(famId).collection("tasks").doc("task_roey_dishes").update({
      assignedToMemberId: child2Uid,
      updatedAt: 1700020000000,
    })
  );

  // 2. Child CANNOT create a proposal pre-approved as PENDING_PARENT_APPROVAL or APPROVED_BY_PARENT -> MUST FAIL
  await assertFails(
    child2Db.collection("families").doc(famId).collection("task_proposals").doc("prop_fake_approved").set({
      id: "prop_fake_approved",
      operationId: "prop_fake_approved",
      familyId: famId,
      type: "VOLUNTEER_FOR_TASK",
      requesterChildId: child2Uid,
      requesterChildName: "מאיה",
      targetChildId: BOB_UID,
      targetChildName: "רועי",
      primaryTaskId: "task_roey_dishes",
      primaryTaskTitle: "פינוי מדיח",
      primaryTaskPoints: 20,
      status: "APPROVED_BY_PARENT",
      createdAtMillis: 1700020001000,
      updatedAtMillis: 1700020001000,
    })
  );

  // 3. Stage 1: Child 2 (Maya) proposes to volunteer for Child 1's (Roey's) active task in an atomic batch -> SUCCEEDS
  const createPropBatch = child2Db.batch();
  createPropBatch.set(
    child2Db.collection("families").doc(famId).collection("task_proposals").doc("prop_vol_1"),
    {
      id: "prop_vol_1",
      operationId: "prop_vol_1",
      familyId: famId,
      type: "VOLUNTEER_FOR_TASK",
      requesterChildId: child2Uid,
      requesterChildName: "מאיה",
      targetChildId: BOB_UID,
      targetChildName: "רועי",
      primaryTaskId: "task_roey_dishes",
      primaryTaskTitle: "פינוי מדיח",
      primaryTaskPoints: 20,
      status: "PENDING_CHILD_APPROVAL",
      note: "אני אעזור לרועי היום",
      createdAtMillis: 1700020002000,
      updatedAtMillis: 1700020002000,
    }
  );
  createPropBatch.update(
    child2Db.collection("families").doc(famId).collection("tasks").doc("task_roey_dishes"),
    {
      pendingProposalId: "prop_vol_1",
      updatedAt: 1700020002000,
    }
  );
  await assertSucceeds(createPropBatch.commit());

  // 4. Requester child (Maya) CANNOT approve the proposal on behalf of target child (Roey) -> MUST FAIL
  await assertFails(
    child2Db.collection("families").doc(famId).collection("task_proposals").doc("prop_vol_1").update({
      status: "PENDING_PARENT_APPROVAL",
      childRespondedAtMillis: 1700020003000,
      updatedAtMillis: 1700020003000,
    })
  );

  // 5. Parent CANNOT approve the proposal before the target child approves it -> MUST FAIL
  await assertFails(
    parentDb.collection("families").doc(famId).collection("task_proposals").doc("prop_vol_1").update({
      status: "APPROVED_BY_PARENT",
      parentDecidedByUid: ALICE_UID,
      parentDecidedAtMillis: 1700020003500,
      updatedAtMillis: 1700020003500,
    })
  );

  // 6. Stage 2: Target child (Roey) approves the proposal -> transitions to PENDING_PARENT_APPROVAL -> SUCCEEDS
  await assertSucceeds(
    child1Db.collection("families").doc(famId).collection("task_proposals").doc("prop_vol_1").update({
      status: "PENDING_PARENT_APPROVAL",
      childRespondedAtMillis: 1700020004000,
      updatedAtMillis: 1700020004000,
    })
  );

  // 7. Target child CANNOT skip parent approval and set APPROVED_BY_PARENT -> MUST FAIL
  await assertFails(
    child1Db.collection("families").doc(famId).collection("task_proposals").doc("prop_vol_1").update({
      status: "APPROVED_BY_PARENT",
      updatedAtMillis: 1700020004500,
    })
  );

  // 8. Stage 3: Parent approves the proposal and atomically reassigns the task while preserving originalAssignedToMemberId -> SUCCEEDS
  const parentApprovePropBatch = parentDb.batch();
  parentApprovePropBatch.update(
    parentDb.collection("families").doc(famId).collection("task_proposals").doc("prop_vol_1"),
    {
      status: "APPROVED_BY_PARENT",
      parentDecidedByUid: ALICE_UID,
      parentDecidedByName: "אמא",
      parentDecidedAtMillis: 1700020005000,
      updatedAtMillis: 1700020005000,
    }
  );
  parentApprovePropBatch.update(
    parentDb.collection("families").doc(famId).collection("tasks").doc("task_roey_dishes"),
    {
      assignedToMemberId: child2Uid,
      assignedUid: child2Uid,
      originalAssignedToMemberId: BOB_UID,
      originalAssignedMemberName: "רועי",
      transferProposalId: "prop_vol_1",
      pendingProposalId: null,
      updatedAt: 1700020005000,
    }
  );
  await assertSucceeds(parentApprovePropBatch.commit());
});

test("Flexible Points Splitting, Parent Points Transfers, Helping Bonus, Reward Catalog CRUD, and Two-Stage Redemptions", async () => {
  const parentDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const child1Db = testEnv.authenticatedContext(BOB_UID).firestore();
  const child2Uid = "child_maya_789";
  const child2Db = testEnv.authenticatedContext(child2Uid).firestore();
  const famId = "fam_split_and_rewards";

  await testEnv.withSecurityRulesDisabled(async (context) => {
    const adminDb = context.firestore();
    await adminDb.collection("families").doc(famId).set({
      id: famId,
      familyId: famId,
      familyName: "משפחת נקודות ופרסים",
      ownerUid: ALICE_UID,
      createdById: ALICE_UID,
      adminUids: [ALICE_UID],
      memberIds: [ALICE_UID, BOB_UID, child2Uid],
      memberUids: [ALICE_UID, BOB_UID, child2Uid],
    });
    await adminDb.collection("families").doc(famId).collection("members").doc(ALICE_UID).set({
      id: ALICE_UID,
      uid: ALICE_UID,
      familyId: famId,
      name: "אבא",
      role: "PARENT",
      earnedPoints: 0,
      spentPoints: 0,
    });
    await adminDb.collection("families").doc(famId).collection("members").doc(BOB_UID).set({
      id: BOB_UID,
      uid: BOB_UID,
      familyId: famId,
      name: "רועי",
      role: "CHILD",
      earnedPoints: 40,
      spentPoints: 0,
    });
    await adminDb.collection("families").doc(famId).collection("members").doc(child2Uid).set({
      id: child2Uid,
      uid: child2Uid,
      familyId: famId,
      name: "מאיה",
      role: "CHILD",
      earnedPoints: 30,
      spentPoints: 0,
    });
    await adminDb.collection("families").doc(famId).collection("tasks").doc("task_split_20").set({
      id: "task_split_20",
      familyId: famId,
      title: "פינוי מדיח",
      assignedToMemberId: child2Uid,
      assignedUid: child2Uid,
      originalAssignedToMemberId: BOB_UID,
      originalAssignedMemberName: "רועי",
      points: 20,
      isCompleted: true,
      approvedByParent: false,
    });
  });

  // 1. Flexible Points Split exceeding task points (e.g. 15 + 10 = 25 > 20) -> MUST FAIL
  const invalidSplitBatch = parentDb.batch();
  invalidSplitBatch.update(
    parentDb.collection("families").doc(famId).collection("tasks").doc("task_split_20"),
    {
      isCompleted: true,
      approvedByParent: true,
      rewardedOperationId: "pts_task_task_split_20",
      updatedAt: 1700030001000,
    }
  );
  invalidSplitBatch.update(
    parentDb.collection("families").doc(famId).collection("members").doc(child2Uid),
    {
      earnedPoints: 45,
      lastPointsOperationId: "pts_task_task_split_20",
      updatedAt: 1700030001000,
    }
  );
  invalidSplitBatch.update(
    parentDb.collection("families").doc(famId).collection("members").doc(BOB_UID),
    {
      earnedPoints: 50,
      lastPointsOperationId: "pts_task_task_split_20_orig",
      updatedAt: 1700030001000,
    }
  );
  invalidSplitBatch.set(
    parentDb.collection("families").doc(famId).collection("points_history").doc("pts_task_task_split_20"),
    {
      id: "pts_task_task_split_20",
      operationId: "pts_task_task_split_20",
      familyId: famId,
      memberId: child2Uid,
      memberName: "מאיה",
      actionType: "TASK_SPLIT_EARNED",
      pointsDelta: 15,
      previousBalance: 30,
      newBalance: 45,
      reason: "חלוקת נקודות לא תקינה",
      performedByUid: ALICE_UID,
      performedByName: "אבא",
      timestampMillis: 1700030001000,
      taskId: "task_split_20",
    }
  );
  invalidSplitBatch.set(
    parentDb.collection("families").doc(famId).collection("points_history").doc("pts_task_task_split_20_orig"),
    {
      id: "pts_task_task_split_20_orig",
      operationId: "pts_task_task_split_20_orig",
      familyId: famId,
      memberId: BOB_UID,
      memberName: "רועי",
      actionType: "TASK_SPLIT_EARNED",
      pointsDelta: 10,
      previousBalance: 40,
      newBalance: 50,
      reason: "חלוקת נקודות לא תקינה",
      performedByUid: ALICE_UID,
      performedByName: "אבא",
      timestampMillis: 1700030001001,
      taskId: "task_split_20",
    }
  );
  await assertFails(invalidSplitBatch.commit());

  // 2. Valid Flexible Points Split (15 to Maya + 5 to Roey = 20) + Chained Helping Bonus (+5 to Maya) -> SUCCEEDS
  const validSplitBatch = parentDb.batch();
  validSplitBatch.update(
    parentDb.collection("families").doc(famId).collection("tasks").doc("task_split_20"),
    {
      isCompleted: true,
      approvedByParent: true,
      rewardedOperationId: "pts_task_task_split_20",
      updatedAt: 1700030002000,
    }
  );
  validSplitBatch.update(
    parentDb.collection("families").doc(famId).collection("members").doc(child2Uid),
    {
      earnedPoints: 50, // 30 + 15 split + 5 bonus
      lastPointsOperationId: "pts_task_task_split_20_bonus",
      updatedAt: 1700030002000,
    }
  );
  validSplitBatch.update(
    parentDb.collection("families").doc(famId).collection("members").doc(BOB_UID),
    {
      earnedPoints: 45, // 40 + 5 split
      lastPointsOperationId: "pts_task_task_split_20_orig",
      updatedAt: 1700030002000,
    }
  );
  validSplitBatch.set(
    parentDb.collection("families").doc(famId).collection("points_history").doc("pts_task_task_split_20"),
    {
      id: "pts_task_task_split_20",
      operationId: "pts_task_task_split_20",
      familyId: famId,
      memberId: child2Uid,
      memberName: "מאיה",
      actionType: "TASK_SPLIT_EARNED",
      pointsDelta: 15,
      previousBalance: 30,
      newBalance: 45,
      reason: "חלוקת נקודות ממשימה (15/20 למבצעת)",
      performedByUid: ALICE_UID,
      performedByName: "אבא",
      timestampMillis: 1700030002000,
      taskId: "task_split_20",
      relatedMemberId: BOB_UID,
    }
  );
  validSplitBatch.set(
    parentDb.collection("families").doc(famId).collection("points_history").doc("pts_task_task_split_20_orig"),
    {
      id: "pts_task_task_split_20_orig",
      operationId: "pts_task_task_split_20_orig",
      familyId: famId,
      memberId: BOB_UID,
      memberName: "רועי",
      actionType: "TASK_SPLIT_EARNED",
      pointsDelta: 5,
      previousBalance: 40,
      newBalance: 45,
      reason: "חלוקת נקודות ממשימה (5/20 לבעלים המקורי)",
      performedByUid: ALICE_UID,
      performedByName: "אבא",
      timestampMillis: 1700030002001,
      taskId: "task_split_20",
      relatedMemberId: child2Uid,
    }
  );
  validSplitBatch.set(
    parentDb.collection("families").doc(famId).collection("points_history").doc("pts_task_task_split_20_bonus"),
    {
      id: "pts_task_task_split_20_bonus",
      operationId: "pts_task_task_split_20_bonus",
      familyId: famId,
      memberId: child2Uid,
      memberName: "מאיה",
      actionType: "HELPING_BONUS",
      pointsDelta: 5,
      previousBalance: 45,
      newBalance: 50,
      reason: "בונוס מיוחד מהורה על עזרה",
      performedByUid: ALICE_UID,
      performedByName: "אבא",
      timestampMillis: 1700030002002,
      taskId: "task_split_20",
      relatedMemberId: BOB_UID,
    }
  );
  await assertSucceeds(validSplitBatch.commit());

  // 3. Parent Points Transfer between children (Roey 45 -> 35, Maya 50 -> 60) -> SUCCEEDS, while Child transfer FAILS
  const childTransferBatch = child1Db.batch();
  childTransferBatch.update(child1Db.collection("families").doc(famId).collection("members").doc(BOB_UID), {
    earnedPoints: 35,
    lastPointsOperationId: "pts_tx_child_out",
    updatedAt: 1700030003000,
  });
  childTransferBatch.set(
    child1Db.collection("families").doc(famId).collection("points_history").doc("pts_tx_child_out"),
    {
      id: "pts_tx_child_out",
      operationId: "pts_tx_child_out",
      familyId: famId,
      memberId: BOB_UID,
      actionType: "TRANSFER_OUT",
      pointsDelta: -10,
      previousBalance: 45,
      newBalance: 35,
      reason: "העברה ע״י ילד",
      performedByUid: BOB_UID,
      relatedMemberId: child2Uid,
    }
  );
  await assertFails(childTransferBatch.commit());

  const parentTransferBatch = parentDb.batch();
  parentTransferBatch.update(parentDb.collection("families").doc(famId).collection("members").doc(BOB_UID), {
    earnedPoints: 35,
    lastPointsOperationId: "pts_tx_valid_out",
    updatedAt: 1700030004000,
  });
  parentTransferBatch.update(parentDb.collection("families").doc(famId).collection("members").doc(child2Uid), {
    earnedPoints: 60,
    lastPointsOperationId: "pts_tx_valid_in",
    updatedAt: 1700030004000,
  });
  parentTransferBatch.set(
    parentDb.collection("families").doc(famId).collection("points_history").doc("pts_tx_valid_out"),
    {
      id: "pts_tx_valid_out",
      operationId: "pts_tx_valid_out",
      familyId: famId,
      memberId: BOB_UID,
      memberName: "רועי",
      actionType: "TRANSFER_OUT",
      pointsDelta: -10,
      previousBalance: 45,
      newBalance: 35,
      reason: "העברה אל מאיה: עזרה בשיעורים",
      performedByUid: ALICE_UID,
      performedByName: "אבא",
      timestampMillis: 1700030004000,
      relatedMemberId: child2Uid,
    }
  );
  parentTransferBatch.set(
    parentDb.collection("families").doc(famId).collection("points_history").doc("pts_tx_valid_in"),
    {
      id: "pts_tx_valid_in",
      operationId: "pts_tx_valid_in",
      familyId: famId,
      memberId: child2Uid,
      memberName: "מאיה",
      actionType: "TRANSFER_IN",
      pointsDelta: 10,
      previousBalance: 50,
      newBalance: 60,
      reason: "קבלה מ-רועי: עזרה בשיעורים",
      performedByUid: ALICE_UID,
      performedByName: "אבא",
      timestampMillis: 1700030004001,
      relatedMemberId: BOB_UID,
    }
  );
  await assertSucceeds(parentTransferBatch.commit());

  // 4. Reward Catalog CRUD: Parent creates valid reward (cost 50), Child cannot create or edit rewards -> SUCCEEDS / FAILS
  await assertFails(
    parentDb.collection("families").doc(famId).collection("rewards").doc("rew_zero_cost").set({
      id: "rew_zero_cost",
      familyId: famId,
      title: "פרס חינם",
      pointsCost: 0,
      isActive: true,
      isDeleted: false,
    })
  );
  await assertFails(
    child2Db.collection("families").doc(famId).collection("rewards").doc("rew_child_created").set({
      id: "rew_child_created",
      familyId: famId,
      title: "פרס של ילד",
      pointsCost: 10,
      isActive: true,
      isDeleted: false,
    })
  );
  await assertSucceeds(
    parentDb.collection("families").doc(famId).collection("rewards").doc("rew_cinema_50").set({
      id: "rew_cinema_50",
      familyId: famId,
      title: "יציאה לסרט",
      pointsCost: 50,
      iconEmoji: "🍿",
      description: "כרטיס קולנוע ופופקורן",
      isActive: true,
      isDeleted: false,
    })
  );

  // 5. Two-Stage Reward Redemption:
  //    - Roey (35 pts) CANNOT request rew_cinema_50 (50 pts) -> MUST FAIL
  await assertFails(
    child1Db.collection("families").doc(famId).collection("redemptions").doc("red_roey_insufficient").set({
      id: "red_roey_insufficient",
      operationId: "red_roey_insufficient",
      familyId: famId,
      rewardId: "rew_cinema_50",
      rewardTitle: "יציאה לסרט",
      pointsCost: 50,
      childUid: BOB_UID,
      childName: "רועי",
      status: "PENDING_PARENT_APPROVAL",
      requestedAtMillis: 1700030005000,
    })
  );

  //    - Maya (60 pts) CANNOT create a pre-approved redemption (status: APPROVED) -> MUST FAIL
  await assertFails(
    child2Db.collection("families").doc(famId).collection("redemptions").doc("red_maya_self_approved").set({
      id: "red_maya_self_approved",
      operationId: "red_maya_self_approved",
      familyId: famId,
      rewardId: "rew_cinema_50",
      rewardTitle: "יציאה לסרט",
      pointsCost: 50,
      childUid: child2Uid,
      childName: "מאיה",
      status: "APPROVED",
      requestedAtMillis: 1700030005500,
    })
  );

  //    - Maya (60 pts) creates a valid PENDING_PARENT_APPROVAL redemption request -> SUCCEEDS
  await assertSucceeds(
    child2Db.collection("families").doc(famId).collection("redemptions").doc("red_maya_valid_1").set({
      id: "red_maya_valid_1",
      operationId: "red_maya_valid_1",
      familyId: famId,
      rewardId: "rew_cinema_50",
      rewardTitle: "יציאה לסרט",
      pointsCost: 50,
      childUid: child2Uid,
      childName: "מאיה",
      status: "PENDING_PARENT_APPROVAL",
      requestedAtMillis: 1700030006000,
    })
  );

  //    - Parent atomically approves redemption: marks APPROVED, deducts 50 from earnedPoints (60 -> 10),
  //      increments spentPoints (0 -> 50), and writes REWARD_REDEEMED history entry -> SUCCEEDS
  const approveRedemptionBatch = parentDb.batch();
  approveRedemptionBatch.update(
    parentDb.collection("families").doc(famId).collection("redemptions").doc("red_maya_valid_1"),
    {
      status: "APPROVED",
      decidedByParentUid: ALICE_UID,
      decidedByParentName: "אבא",
      decidedAtMillis: 1700030007000,
    }
  );
  approveRedemptionBatch.update(
    parentDb.collection("families").doc(famId).collection("members").doc(child2Uid),
    {
      earnedPoints: 10,
      spentPoints: 50,
      lastPointsOperationId: "pts_red_red_maya_valid_1",
      updatedAt: 1700030007000,
    }
  );
  approveRedemptionBatch.set(
    parentDb.collection("families").doc(famId).collection("points_history").doc("pts_red_red_maya_valid_1"),
    {
      id: "pts_red_red_maya_valid_1",
      operationId: "pts_red_red_maya_valid_1",
      familyId: famId,
      memberId: child2Uid,
      memberName: "מאיה",
      actionType: "REWARD_REDEEMED",
      pointsDelta: -50,
      previousBalance: 60,
      newBalance: 10,
      reason: "מימוש פרס באישור הורה: יציאה לסרט",
      performedByUid: ALICE_UID,
      performedByName: "אבא",
      timestampMillis: 1700030007000,
      rewardId: "rew_cinema_50",
      redemptionId: "red_maya_valid_1",
    }
  );
  await assertSucceeds(approveRedemptionBatch.commit());
});

test("Personal Reward Goal & Open Tasks for Volunteering: Firestore rules enforce parent-only open task creation, child volunteering, and atomic volunteer selection", async () => {
  const famId = "fam_open_tasks_vol";
  const child2Uid = "maya_child_2";
  const parentDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const child1Db = testEnv.authenticatedContext(BOB_UID).firestore();
  const child2Db = testEnv.authenticatedContext(child2Uid).firestore();

  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection("families").doc(famId).set({
      id: famId,
      familyName: "משפחת הראל",
      createdById: ALICE_UID,
      memberIds: [ALICE_UID, BOB_UID, child2Uid],
      createdAt: new Date(),
    });
    await db.collection("families").doc(famId).collection("members").doc(ALICE_UID).set({
      id: ALICE_UID,
      name: "אבא",
      role: "PARENT",
      earnedPoints: 0,
      spentPoints: 0,
    });
    await db.collection("families").doc(famId).collection("members").doc(BOB_UID).set({
      id: BOB_UID,
      name: "רועי",
      role: "CHILD",
      earnedPoints: 40,
      spentPoints: 0,
    });
    await db.collection("families").doc(famId).collection("members").doc(child2Uid).set({
      id: child2Uid,
      name: "מאיה",
      role: "CHILD",
      earnedPoints: 55,
      spentPoints: 0,
    });
  });

  // 1. Personal Reward Goal:
  //    - Child 1 can set their own targetRewardId (without changing earnedPoints/role) -> SUCCEEDS
  await assertSucceeds(
    child1Db.collection("families").doc(famId).collection("members").doc(BOB_UID).update({
      targetRewardId: "r_movie",
    })
  );
  //    - Child 1 CANNOT change Child 2's targetRewardId -> FAILS
  await assertFails(
    child1Db.collection("families").doc(famId).collection("members").doc(child2Uid).update({
      targetRewardId: "r_icecream",
    })
  );
  //    - Parent CAN change Child 2's targetRewardId -> SUCCEEDS
  await assertSucceeds(
    parentDb.collection("families").doc(famId).collection("members").doc(child2Uid).update({
      targetRewardId: "r_game",
    })
  );

  // 2. Open Tasks Creation:
  //    - Child CANNOT create an open task -> FAILS
  await assertFails(
    child1Db.collection("families").doc(famId).collection("tasks").doc("task_open_illegal").set({
      id: "task_open_illegal",
      familyId: famId,
      title: "משימה פתוחה של ילד",
      assignedToMemberId: "",
      isOpenForVolunteering: true,
      points: 20,
      isCompleted: false,
      approvedByParent: false,
    })
  );
  //    - Parent CAN create an open task -> SUCCEEDS
  await assertSucceeds(
    parentDb.collection("families").doc(famId).collection("tasks").doc("task_open_yard").set({
      id: "task_open_yard",
      familyId: famId,
      title: "סידור החצר",
      description: "איסוף צעצועים וניקוי השולחן",
      assignedToMemberId: "",
      isOpenForVolunteering: true,
      points: 25,
      isCompleted: false,
      approvedByParent: false,
    })
  );

  //    - Child CANNOT directly assign the open task to themselves -> FAILS
  await assertFails(
    child1Db.collection("families").doc(famId).collection("tasks").doc("task_open_yard").update({
      assignedToMemberId: BOB_UID,
      isOpenForVolunteering: false,
    })
  );

  // 3. Multiple Children Volunteering for the Open Task:
  await assertSucceeds(
    child1Db.collection("families").doc(famId).collection("task_proposals").doc(`vol_open_task_open_yard_${BOB_UID}`).set({
      id: `vol_open_task_open_yard_${BOB_UID}`,
      familyId: famId,
      type: "VOLUNTEER_FOR_TASK",
      primaryTaskId: "task_open_yard",
      primaryTaskTitle: "סידור החצר",
      primaryTaskPoints: 25,
      requesterChildId: BOB_UID,
      requesterChildName: "רועי",
      targetChildId: "",
      targetChildName: "משימה פתוחה",
      isOpenTaskVolunteer: true,
      status: "PENDING_PARENT_APPROVAL",
      createdAtMillis: 1700040001000,
      updatedAtMillis: 1700040001000,
    })
  );

  await assertSucceeds(
    child2Db.collection("families").doc(famId).collection("task_proposals").doc(`vol_open_task_open_yard_${child2Uid}`).set({
      id: `vol_open_task_open_yard_${child2Uid}`,
      familyId: famId,
      type: "VOLUNTEER_FOR_TASK",
      primaryTaskId: "task_open_yard",
      primaryTaskTitle: "סידור החצר",
      primaryTaskPoints: 25,
      requesterChildId: child2Uid,
      requesterChildName: "מאיה",
      targetChildId: "",
      targetChildName: "משימה פתוחה",
      isOpenTaskVolunteer: true,
      status: "PENDING_PARENT_APPROVAL",
      createdAtMillis: 1700040002000,
      updatedAtMillis: 1700040002000,
    })
  );

  // 4. Parent selects Child 2 (Maya) as the volunteer via a Firestore Transaction with conflict detection:
  //    Atomically verifies task is still open and unassigned, assigns task to Maya, sets isOpenForVolunteering = false,
  //    marks Maya's proposal APPROVED_BY_PARENT, and closes Roey's proposal as CLOSED_NOT_SELECTED
  const taskRef = parentDb.collection("families").doc(famId).collection("tasks").doc("task_open_yard");
  const mayaProposalRef = parentDb.collection("families").doc(famId).collection("task_proposals").doc(`vol_open_task_open_yard_${child2Uid}`);
  const roeyProposalRef = parentDb.collection("families").doc(famId).collection("task_proposals").doc(`vol_open_task_open_yard_${BOB_UID}`);

  await assertSucceeds(
    parentDb.runTransaction(async (tx) => {
      const taskSnap = await tx.get(taskRef);
      const mayaPropSnap = await tx.get(mayaProposalRef);
      const roeyPropSnap = await tx.get(roeyProposalRef);

      const taskData = taskSnap.data() || {};
      if (!taskData.isOpenForVolunteering || (taskData.assignedToMemberId && taskData.assignedToMemberId.trim() !== "")) {
        throw new Error("CONFLICT: Open task was already assigned to another volunteer");
      }
      if ((mayaPropSnap.data() || {}).status !== "PENDING_PARENT_APPROVAL") {
        throw new Error("CONFLICT: Proposal is no longer pending parent approval");
      }

      tx.update(taskRef, {
        assignedToMemberId: child2Uid,
        assignedUid: child2Uid,
        assignedMemberId: child2Uid,
        isOpenForVolunteering: false,
        transferProposalId: `vol_open_task_open_yard_${child2Uid}`,
        activeCollaborationProposalId: `vol_open_task_open_yard_${child2Uid}`,
        updatedAt: 1700040005000,
      });
      tx.update(mayaProposalRef, {
        status: "APPROVED_BY_PARENT",
        parentDecidedByUid: ALICE_UID,
        parentDecidedByName: "אבא",
        parentDecidedAtMillis: 1700040005000,
        updatedAtMillis: 1700040005000,
      });
      if (roeyPropSnap.exists && (roeyPropSnap.data() || {}).status === "PENDING_PARENT_APPROVAL") {
        tx.update(roeyProposalRef, {
          status: "CLOSED_NOT_SELECTED",
          parentDecidedByUid: ALICE_UID,
          parentDecidedByName: "אבא",
          parentDecidedAtMillis: 1700040005000,
          updatedAtMillis: 1700040005000,
        });
      }
    })
  );

  // 4B. Second Parent (or concurrent parent approval) tries to approve Roey (`BOB_UID`) for the same task via Transaction ->
  //     Transaction detects `isOpenForVolunteering == false` / `assignedToMemberId == child2Uid` and FAILS with conflict!
  const assert = require("node:assert");
  await assert.rejects(
    async () => {
      await parentDb.runTransaction(async (tx) => {
        const taskSnap = await tx.get(taskRef);
        const roeyPropSnap = await tx.get(roeyProposalRef);
        const taskData = taskSnap.data() || {};
        if (!taskData.isOpenForVolunteering || (taskData.assignedToMemberId && taskData.assignedToMemberId.trim() !== "")) {
          throw new Error("המשימה הפתוחה כבר שויכה למתנדב אחר על ידי הורה נוסף");
        }
        if ((roeyPropSnap.data() || {}).status !== "PENDING_PARENT_APPROVAL") {
          throw new Error("המשימה הפתוחה כבר שויכה למתנדב אחר על ידי הורה נוסף");
        }
        tx.update(taskRef, {
          assignedToMemberId: BOB_UID,
          isOpenForVolunteering: false,
        });
      });
    },
    /כבר שויכה למתנדב אחר/
  );

  // Verify Maya remains the sole assignee and Roey's proposal remains CLOSED_NOT_SELECTED
  const finalTaskSnap = await taskRef.get();
  assert.strictEqual(finalTaskSnap.data().assignedToMemberId, child2Uid);
  assert.strictEqual(finalTaskSnap.data().isOpenForVolunteering, false);
  const finalRoeySnap = await roeyProposalRef.get();
  assert.strictEqual(finalRoeySnap.data().status, "CLOSED_NOT_SELECTED");

  // 5. Volunteering for an already-assigned task MUST FAIL
  await assertFails(
    child1Db.collection("families").doc(famId).collection("task_proposals").doc("vol_open_after_assigned").set({
      id: "vol_open_after_assigned",
      familyId: famId,
      type: "VOLUNTEER_FOR_TASK",
      primaryTaskId: "task_open_yard",
      primaryTaskTitle: "סידור החצר",
      primaryTaskPoints: 25,
      requesterChildId: BOB_UID,
      requesterChildName: "רועי",
      targetChildId: "",
      targetChildName: "משימה פתוחה",
      isOpenTaskVolunteer: true,
      status: "PENDING_PARENT_APPROVAL",
      createdAtMillis: 1700040009000,
      updatedAtMillis: 1700040009000,
    })
  );

  // 6. Verify Cloud Function OPEN_TASK_CREATED classification produces canonical eventKey "open_task:{taskId}"
  const { classifyTaskNotificationEvent } = require("./functions/index.js");
  const openTaskEvent = classifyTaskNotificationEvent(
    null,
    {
      id: "task_open_yard",
      familyId: famId,
      title: "סידור החצר",
      assignedToMemberId: "",
      isOpenForVolunteering: true,
      points: 25,
      dueTimeFormatted: "17:00",
      userId: ALICE_UID,
    },
    "task_open_yard"
  );
  assert.ok(openTaskEvent);
  assert.strictEqual(openTaskEvent.eventType, "OPEN_TASK_CREATED");
  assert.strictEqual(openTaskEvent.eventKey, "open_task:task_open_yard");
  assert.strictEqual(openTaskEvent.targetMode, "FAMILY_CHILDREN");
});



