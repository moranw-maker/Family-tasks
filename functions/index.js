/**
 * FamilyTasks Server-Side FCM Push Notification Dispatcher (Cloud Functions v2)
 *
 * Configured for:
 * - Firebase Project: gen-lang-client-0640538286
 * - Named Firestore Database: ai-studio-android-familyta-a054dbd1-9c2d-4a83-ad6d-dcfe6fb0d2ca
 *
 * Dispatches verified cross-device FCM notifications ONLY after Firestore writes succeed for:
 * A. Parent assigns a task to a child or other family member -> Notify the assigned member.
 * B. Child submits a task for parent approval -> Notify eligible parents in the same family.
 * C. Parent approves a completed task -> Notify the child whose task was approved.
 * D. A task is reassigned -> Notify the new assignee.
 *
 * Security & Reliability:
 * - Never sends FCM from the Android client using server credentials.
 * - Validates family membership and recipient roles using authoritative server-side Firestore data.
 * - Deduplicates events via /families/{familyId}/notification_dispatches/{eventKey}.
 * - Supports multiple devices per user (/users/{uid}/fcm_tokens/{tokenId}) and automatically cleans up
 *   invalid/unregistered FCM tokens.
 */

const { onDocumentWritten } = require("firebase-functions/v2/firestore");
const { logger } = require("firebase-functions");
const { getApp, getApps, initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

if (!getApps().length) {
  initializeApp();
}

const NAMED_DATABASE_ID = "ai-studio-android-familyta-a054dbd1-9c2d-4a83-ad6d-dcfe6fb0d2ca";
const PARENT_ROLE_VALUES = new Set([
  "PARENT",
  "PRIMARY_ADMIN",
  "ADMIN",
  "PARENT_ADMIN",
  "FATHER",
  "MOTHER"
]);

function getNamedDb() {
  return getFirestore(getApp(), NAMED_DATABASE_ID);
}

/**
 * Classifies a Firestore task write into one of the four required notification events (or null).
 * Exported for deterministic unit testing.
 */
function classifyTaskNotificationEvent(beforeData, afterData, taskId) {
  if (!afterData) {
    // Task deletion -> no push notification required
    return null;
  }

  const afterAssignee = (afterData.assignedToMemberId || afterData.assignedUid || "").trim();
  const beforeAssignee = beforeData
    ? (beforeData.assignedToMemberId || beforeData.assignedUid || "").trim()
    : "";

  const afterCompleted = Boolean(afterData.isCompleted);
  const beforeCompleted = beforeData ? Boolean(beforeData.isCompleted) : false;

  const afterApproved = Boolean(afterData.approvedByParent);
  const beforeApproved = beforeData ? Boolean(beforeData.approvedByParent) : false;

  const title = (afterData.title || "משימה משפחתית").trim();
  const points = Number(afterData.points || afterData.pointsValue || 10);
  const dueTime = (afterData.dueTimeFormatted || "16:00").trim();
  const actorUid = (afterData.completedByMemberId || afterData.userId || "").trim();

  // Event A: New task created and assigned to a family member
  if (!beforeData) {
    if (!afterAssignee) return null;
    return {
      eventType: "TASK_ASSIGNED",
      eventKey: `assign:${taskId}:${afterAssignee}`,
      targetMode: "SINGLE_MEMBER",
      targetUid: afterAssignee,
      actorUid: (afterData.userId || "").trim(),
      title: "משימה חדשה שובצה עבורך 📋",
      body: `שובצה לך משימה חדשה: "${title}" (שעה ${dueTime} • +${points} נק')`
    };
  }

  // Event D: Task reassigned to a different family member
  if (afterAssignee && beforeAssignee && afterAssignee !== beforeAssignee) {
    return {
      eventType: "TASK_REASSIGNED",
      eventKey: `reassign:${taskId}:${afterAssignee}:${afterData.updatedAt || Date.now()}`,
      targetMode: "SINGLE_MEMBER",
      targetUid: afterAssignee,
      actorUid,
      title: "משימה הועברה אליך 🔄",
      body: `המשימה "${title}" שובצה אליך (שעה ${dueTime} • +${points} נק')`
    };
  }

  // Event B: Child submits a task for parent approval (isCompleted: false -> true, approvedByParent == false)
  if (!beforeCompleted && afterCompleted && !afterApproved) {
    const completionStamp = afterData.completedAtMillis || afterData.updatedAt || "1";
    return {
      eventType: "TASK_SUBMITTED_FOR_APPROVAL",
      eventKey: `submit:${taskId}:${completionStamp}`,
      targetMode: "FAMILY_PARENTS",
      targetUid: "",
      actorUid: (afterData.completedByMemberId || afterAssignee).trim(),
      assigneeUid: afterAssignee,
      title: "משימה ממתינה לאישור הורה ⏳",
      body: `המשימה "${title}" סומנה כבוצעה וממתינה לאישורך (+${points} נק')`
    };
  }

  // Event C: Parent approves a completed task (approvedByParent: false -> true, isCompleted == true)
  if (!beforeApproved && afterCompleted && afterApproved && afterAssignee) {
    return {
      eventType: "TASK_APPROVED",
      eventKey: `approve:${taskId}`,
      targetMode: "SINGLE_MEMBER",
      targetUid: afterAssignee,
      actorUid,
      title: `המשימה אושרה! +${points} נק' 🎉`,
      body: `כל הכבוד! ביצוע המשימה "${title}" אושר וקיבלת +${points} נקודות!`
    };
  }

  return null;
}

/**
 * Resolves verified recipient UIDs in /families/{familyId}/members using authoritative server data.
 */
async function resolveVerifiedRecipientUids(db, familyId, eventSpec) {
  const membersSnap = await db.collection("families").doc(familyId).collection("members").get();
  if (membersSnap.empty) return [];

  const validMemberDocs = membersSnap.docs.filter((doc) => {
    const d = doc.data() || {};
    if (d.invitationPending === true && d.invitationAccepted === false) return false;
    return true;
  });

  if (eventSpec.targetMode === "SINGLE_MEMBER") {
    const isMember = validMemberDocs.some((doc) => doc.id === eventSpec.targetUid);
    if (!isMember) return [];
    // Do not notify the actor who performed the action themselves
    if (eventSpec.actorUid && eventSpec.actorUid === eventSpec.targetUid && eventSpec.eventType !== "TASK_APPROVED") {
      return [];
    }
    return [eventSpec.targetUid];
  }

  if (eventSpec.targetMode === "FAMILY_PARENTS") {
    return validMemberDocs
      .filter((doc) => {
        const d = doc.data() || {};
        const role = String(d.role || "").toUpperCase();
        return PARENT_ROLE_VALUES.has(role) && doc.id !== eventSpec.actorUid;
      })
      .map((doc) => doc.id);
  }

  return [];
}

/**
 * Dispatches FCM messages to all active device tokens of the verified recipient UIDs
 * and cleans up invalid/unregistered tokens automatically.
 */
async function dispatchToRecipientTokens(db, familyId, taskId, eventSpec, recipientUids) {
  for (const recipientUid of recipientUids) {
    const tokensSnap = await db
      .collection("users")
      .doc(recipientUid)
      .collection("fcm_tokens")
      .get();

    if (tokensSnap.empty) continue;

    const tokenRecords = [];
    tokensSnap.forEach((doc) => {
      const data = doc.data() || {};
      if (data.token && typeof data.token === "string" && data.isActive !== false) {
        tokenRecords.push({ docId: doc.id, token: data.token });
      }
    });

    if (tokenRecords.length === 0) continue;

    const message = {
      tokens: tokenRecords.map((r) => r.token),
      notification: {
        title: eventSpec.title,
        body: eventSpec.body
      },
      data: {
        eventKey: `${eventSpec.eventKey}:${recipientUid}`,
        eventType: eventSpec.eventType,
        familyId: String(familyId),
        taskId: String(taskId),
        targetUid: String(recipientUid),
        actorUid: String(eventSpec.actorUid || ""),
        title: eventSpec.title,
        body: eventSpec.body
      },
      android: {
        priority: "high",
        notification: {
          channelId: "family_tasks_channel",
          priority: "high",
          defaultSound: true,
          defaultVibrateTimings: true
        }
      }
    };

    const response = await getMessaging().sendEachForMulticast(message);

    // Clean up invalid or unregistered tokens
    const cleanupPromises = [];
    response.responses.forEach((resp, idx) => {
      if (!resp.success && resp.error) {
        const errCode = resp.error.code || "";
        if (
          errCode === "messaging/registration-token-not-registered" ||
          errCode === "messaging/invalid-registration-token" ||
          errCode === "messaging/invalid-argument"
        ) {
          const invalidTokenDocId = tokenRecords[idx].docId;
          cleanupPromises.push(
            db
              .collection("users")
              .doc(recipientUid)
              .collection("fcm_tokens")
              .doc(invalidTokenDocId)
              .delete()
          );
        }
      }
    });
    if (cleanupPromises.length > 0) {
      await Promise.allSettled(cleanupPromises);
    }
  }
}

exports.onFamilyTaskWritten = onDocumentWritten(
  {
    document: "families/{familyId}/tasks/{taskId}",
    database: NAMED_DATABASE_ID
  },
  async (event) => {
    const { familyId, taskId } = event.params;
    const beforeData = event.data && event.data.before && event.data.before.exists
      ? event.data.before.data()
      : null;
    const afterData = event.data && event.data.after && event.data.after.exists
      ? event.data.after.data()
      : null;

    const eventSpec = classifyTaskNotificationEvent(beforeData, afterData, taskId);
    if (!eventSpec) return;

    const db = getNamedDb();

    // Server-side idempotency guard: prevent duplicate dispatch on retries
    const safeDispatchId = eventSpec.eventKey.replace(/[^a-zA-Z0-9_:-]/g, "_");
    const dispatchRef = db
      .collection("families")
      .doc(familyId)
      .collection("notification_dispatches")
      .doc(safeDispatchId);

    const alreadyDispatched = await db.runTransaction(async (tx) => {
      const snap = await tx.get(dispatchRef);
      if (snap.exists) return true;
      tx.set(dispatchRef, {
        eventKey: eventSpec.eventKey,
        eventType: eventSpec.eventType,
        taskId,
        createdAt: Date.now()
      });
      return false;
    });

    if (alreadyDispatched) {
      logger.info("Skipping duplicate notification dispatch", { eventKey: eventSpec.eventKey });
      return;
    }

    const recipientUids = await resolveVerifiedRecipientUids(db, familyId, eventSpec);
    if (recipientUids.length === 0) return;

    await dispatchToRecipientTokens(db, familyId, taskId, eventSpec, recipientUids);
  }
);

/**
 * Classifies a Firestore alarm write under /families/{familyId}/alarms/{alarmId} into a
 * data-only FCM synchronization event (`ALARM_SYNC_REQUIRED`) when a parent creates, updates,
 * enables, disables, or deletes a child's alarm.
 *
 * Returns `null` when:
 * - The alarm is not assigned to a target member.
 * - The actor is the target member themselves (local personal alarm already scheduled locally).
 * - The write is only a target-device schedule acknowledgement (`scheduleStatus`, `acknowledgedBy*`,
 *   `nextTriggerAtMillis`, `lastScheduleError`) with no alarm schedule/configuration changes.
 */
function classifyAlarmSyncEvent(beforeData, afterData, alarmId) {
  if (!beforeData && !afterData) return null;

  // Case 1: Hard document deletion (afterData is null)
  if (beforeData && !afterData) {
    const targetUid = (beforeData.assignedToMemberId || beforeData.assignedMemberId || "").trim();
    if (!targetUid) return null;
    const actorUid = (beforeData.updatedByUid || beforeData.createdByMemberId || "").trim();
    return {
      eventType: "ALARM_SYNC_REQUIRED",
      action: "DELETE",
      eventKey: `alarm_del:${alarmId}:${targetUid}:${Date.now()}`,
      targetMode: "SINGLE_MEMBER",
      targetUid,
      actorUid,
      updatedAt: String(Date.now())
    };
  }

  // Case 2: Soft deletion via isDeleted == true or scheduleStatus == "DELETED"
  const afterDeleted = Boolean(afterData.isDeleted) || afterData.scheduleStatus === "DELETED";
  const beforeDeleted = beforeData
    ? Boolean(beforeData.isDeleted) || beforeData.scheduleStatus === "DELETED"
    : false;
  const afterTargetUid = (afterData.assignedToMemberId || afterData.assignedMemberId || "").trim();
  const beforeTargetUid = beforeData
    ? (beforeData.assignedToMemberId || beforeData.assignedMemberId || "").trim()
    : "";
  const actorUid = (afterData.updatedByUid || afterData.createdByMemberId || "").trim();
  const stamp = String(afterData.updatedAt || afterData.createdAtMillis || Date.now());

  if (afterDeleted && !beforeDeleted) {
    const targetUid = afterTargetUid || beforeTargetUid;
    if (!targetUid) return null;
    return {
      eventType: "ALARM_SYNC_REQUIRED",
      action: "DELETE",
      eventKey: `alarm_softdel:${alarmId}:${targetUid}:${stamp}`,
      targetMode: "SINGLE_MEMBER",
      targetUid,
      actorUid,
      updatedAt: stamp
    };
  }

  if (!afterTargetUid) return null;

  // Do not send background FCM push to the actor who performed the write on their own device
  if (actorUid && actorUid === afterTargetUid) {
    return null;
  }

  // Case 3: Newly created alarm for a child
  if (!beforeData) {
    return {
      eventType: "ALARM_SYNC_REQUIRED",
      action: "CREATE",
      eventKey: `alarm_create:${alarmId}:${afterTargetUid}:${stamp}`,
      targetMode: "SINGLE_MEMBER",
      targetUid: afterTargetUid,
      actorUid,
      updatedAt: stamp
    };
  }

  // Check whether any meaningful alarm schedule or configuration field changed
  // (ignoring target-device acknowledgement fields to avoid infinite loops)
  const beforeDays = Array.isArray(beforeData.daysOfWeek) ? beforeData.daysOfWeek.join(",") : "";
  const afterDays = Array.isArray(afterData.daysOfWeek) ? afterData.daysOfWeek.join(",") : "";
  const beforeEnabled = beforeData.isEnabled !== false;
  const afterEnabled = afterData.isEnabled !== false;

  const scheduleOrConfigChanged =
    beforeEnabled !== afterEnabled ||
    Number(beforeData.hour) !== Number(afterData.hour) ||
    Number(beforeData.minute) !== Number(afterData.minute) ||
    beforeDays !== afterDays ||
    beforeTargetUid !== afterTargetUid ||
    String(beforeData.title || "") !== String(afterData.title || "") ||
    String(beforeData.soundType || "") !== String(afterData.soundType || "") ||
    String(beforeData.customSoundUri || "") !== String(afterData.customSoundUri || "") ||
    Boolean(beforeData.vibrateEnabled) !== Boolean(afterData.vibrateEnabled) ||
    Number(beforeData.snoozeDurationMinutes || 5) !== Number(afterData.snoozeDurationMinutes || 5) ||
    Number(beforeData.volumePercent || 85) !== Number(afterData.volumePercent || 85);

  if (!scheduleOrConfigChanged) {
    return null;
  }

  let action = "UPDATE";
  if (beforeEnabled !== afterEnabled) {
    action = afterEnabled ? "ENABLE" : "DISABLE";
  }

  return {
    eventType: "ALARM_SYNC_REQUIRED",
    action,
    eventKey: `alarm_${action.toLowerCase()}:${alarmId}:${afterTargetUid}:${stamp}`,
    targetMode: "SINGLE_MEMBER",
    targetUid: afterTargetUid,
    actorUid,
    updatedAt: stamp
  };
}

/**
 * Dispatches a high-priority DATA-ONLY FCM message (`ALARM_SYNC_REQUIRED`) to all active device
 * tokens of the verified target child member. Contains only minimal identifiers (no sensitive alarm
 * titles or personal content) so the Android client (`FamilyMessagingService`) wakes up, fetches
 * authoritative state from Firestore, and reconciles local `AlarmManager` schedules.
 */
async function dispatchAlarmSyncDataMessage(db, familyId, alarmId, eventSpec, recipientUids) {
  for (const recipientUid of recipientUids) {
    const tokensSnap = await db
      .collection("users")
      .doc(recipientUid)
      .collection("fcm_tokens")
      .get();

    if (tokensSnap.empty) continue;

    const tokenRecords = [];
    tokensSnap.forEach((doc) => {
      const data = doc.data() || {};
      if (data.token && typeof data.token === "string" && data.isActive !== false) {
        tokenRecords.push({ docId: doc.id, token: data.token });
      }
    });

    if (tokenRecords.length === 0) continue;

    // Data-only FCM message with high Android delivery priority for time-sensitive alarm updates
    const message = {
      tokens: tokenRecords.map((r) => r.token),
      data: {
        eventKey: `${eventSpec.eventKey}:${recipientUid}`,
        eventType: eventSpec.eventType,
        action: String(eventSpec.action || "UPDATE"),
        familyId: String(familyId),
        alarmId: String(alarmId),
        targetUid: String(recipientUid),
        actorUid: String(eventSpec.actorUid || ""),
        updatedAt: String(eventSpec.updatedAt || Date.now())
      },
      android: {
        priority: "high"
      }
    };

    const response = await getMessaging().sendEachForMulticast(message);

    // Clean up invalid or unregistered tokens
    const cleanupPromises = [];
    response.responses.forEach((resp, idx) => {
      if (!resp.success && resp.error) {
        const errCode = resp.error.code || "";
        if (
          errCode === "messaging/registration-token-not-registered" ||
          errCode === "messaging/invalid-registration-token" ||
          errCode === "messaging/invalid-argument"
        ) {
          const invalidTokenDocId = tokenRecords[idx].docId;
          cleanupPromises.push(
            db
              .collection("users")
              .doc(recipientUid)
              .collection("fcm_tokens")
              .doc(invalidTokenDocId)
              .delete()
          );
        }
      }
    });
    if (cleanupPromises.length > 0) {
      await Promise.allSettled(cleanupPromises);
    }
  }
}

exports.onFamilyAlarmWritten = onDocumentWritten(
  {
    document: "families/{familyId}/alarms/{alarmId}",
    database: NAMED_DATABASE_ID
  },
  async (event) => {
    const { familyId, alarmId } = event.params;
    const beforeData = event.data && event.data.before && event.data.before.exists
      ? event.data.before.data()
      : null;
    const afterData = event.data && event.data.after && event.data.after.exists
      ? event.data.after.data()
      : null;

    const eventSpec = classifyAlarmSyncEvent(beforeData, afterData, alarmId);
    if (!eventSpec) return;

    const db = getNamedDb();

    // Server-side idempotency guard: prevent duplicate dispatch on Cloud Function retries
    const safeDispatchId = eventSpec.eventKey.replace(/[^a-zA-Z0-9_:-]/g, "_");
    const dispatchRef = db
      .collection("families")
      .doc(familyId)
      .collection("notification_dispatches")
      .doc(safeDispatchId);

    const alreadyDispatched = await db.runTransaction(async (tx) => {
      const snap = await tx.get(dispatchRef);
      if (snap.exists) return true;
      tx.set(dispatchRef, {
        eventKey: eventSpec.eventKey,
        eventType: eventSpec.eventType,
        action: eventSpec.action,
        alarmId,
        targetUid: eventSpec.targetUid,
        createdAt: Date.now()
      });
      return false;
    });

    if (alreadyDispatched) {
      logger.info("Skipping duplicate alarm sync dispatch", { eventKey: eventSpec.eventKey });
      return;
    }

    const recipientUids = await resolveVerifiedRecipientUids(db, familyId, eventSpec);
    if (recipientUids.length === 0) return;

    await dispatchAlarmSyncDataMessage(db, familyId, alarmId, eventSpec, recipientUids);
  }
);

exports.classifyTaskNotificationEvent = classifyTaskNotificationEvent;
exports.classifyAlarmSyncEvent = classifyAlarmSyncEvent;

