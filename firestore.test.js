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

  // Child proposing a task (points = 0, approvedByParent = false) -> SUCCEEDS
  await assertSucceeds(
    bobDb.collection("families").doc("fam_wexler").collection("tasks").doc("task_proposal").set({
      id: "task_proposal",
      familyId: "fam_wexler",
      title: "Proposed chore",
      points: 0,
      approvedByParent: false,
      isCompleted: false,
    })
  );

  // Child attempting to approve proposal and give self points -> MUST FAIL
  await assertFails(
    bobDb.collection("families").doc("fam_wexler").collection("tasks").doc("task_proposal").update({
      approvedByParent: true,
      points: 25,
    })
  );

  // Parent authorizing proposal and assigning points -> SUCCEEDS
  await assertSucceeds(
    aliceDb.collection("families").doc("fam_wexler").collection("tasks").doc("task_proposal").update({
      approvedByParent: true,
      points: 25,
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





