const {
  assertFails,
  assertSucceeds,
  initializeTestApp,
  initializeAdminApp,
  clearFirestoreData,
} = require("@firebase/rules-unit-testing");
const firebase = require("@firebase/rules-unit-testing");

const PROJECT_ID = "nightagent-35d38";
const RULES_FILE = "firestore.rules";

const aliceUid = "alice_uid";
const bobUid = "bob_uid";
const carolUid = "carol_uid";

beforeAll(async () => {
  await firebase.loadFirestoreRules({
    projectId: PROJECT_ID,
    rules: RULES_FILE,
  });
});

beforeEach(async () => {
  await clearFirestoreData({ projectId: PROJECT_ID });
});

afterAll(async () => {
  await Promise.all(firebase.apps().map((app) => app.delete()));
});

// ── Helper: create an authenticated app for a given UID ──────────────────────

function authedApp(uid) {
  return initializeTestApp({ projectId: PROJECT_ID, auth: { uid } });
}

// ── Helper: create an unauthenticated app ────────────────────────────────────

function unauthedApp() {
  return initializeTestApp({ projectId: PROJECT_ID, auth: null });
}

// ── users/{uid} ──────────────────────────────────────────────────────────────

describe("users/{uid}", () => {
  test("owner can read own profile", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertSucceeds(db.doc(`users/${aliceUid}`).get());
  });

  test("owner can write own profile", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertSucceeds(
      db.doc(`users/${aliceUid}`).set({
        uid: aliceUid,
        displayName: "Alice",
        phoneNumber: "+919876543210",
        fcmToken: "token_abc",
      })
    );
  });

  test("cross-user read is denied", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertFails(db.doc(`users/${bobUid}`).get());
  });

  test("cross-user write is denied", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertFails(
      db.doc(`users/${bobUid}`).set({ uid: bobUid, displayName: "Bob" })
    );
  });

  test("unauthenticated read is denied", async () => {
    const db = unauthedApp().firestore();
    await assertFails(db.doc(`users/${aliceUid}`).get());
  });

  test("unauthenticated write is denied", async () => {
    const db = unauthedApp().firestore();
    await assertFails(
      db.doc(`users/${aliceUid}`).set({ uid: aliceUid })
    );
  });
});

// ── chats/{chatId} ───────────────────────────────────────────────────────────

describe("chats/{chatId}", () => {
  const chatId = "alice_uid_bob_uid";

  beforeEach(async () => {
    const admin = initializeAdminApp({ projectId: PROJECT_ID }).firestore();
    await admin
      .doc(`chats/${chatId}`)
      .set({
        chatId,
        participants: [aliceUid, bobUid],
        lastMessage: "🎙 Voice message",
        lastTimestamp: Date.now(),
      });
  });

  test("participant can read own chat", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertSucceeds(db.doc(`chats/${chatId}`).get());
  });

  test("non-participant read is denied", async () => {
    const db = authedApp(carolUid).firestore();
    await assertFails(db.doc(`chats/${chatId}`).get());
  });

  test("unauthenticated read is denied", async () => {
    const db = unauthedApp().firestore();
    await assertFails(db.doc(`chats/${chatId}`).get());
  });

  test("participant can create chat where they are a participant", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertSucceeds(
      db
        .doc("chats/alice_uid_carol_uid")
        .set({
          chatId: "alice_uid_carol_uid",
          participants: [aliceUid, carolUid],
          createdAt: firebase.firestore.FieldValue.serverTimestamp(),
        })
    );
  });

  test("user cannot create chat where they are not a participant", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertFails(
      db
        .doc("chats/bob_uid_carol_uid")
        .set({
          chatId: "bob_uid_carol_uid",
          participants: [bobUid, carolUid],
        })
    );
  });

  test("participant cannot change participants on update", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertFails(
      db.doc(`chats/${chatId}`).update({
        participants: [aliceUid, carolUid],
      })
    );
  });

  // REGRESSION: cannot add unauthorized participant via update
  test("user cannot add unauthorized participant via chat update", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertFails(
      db.doc(`chats/${chatId}`).update({
        participants: [aliceUid, carolUid],
        lastMessage: "tamper attempt",
      })
    );
  });

  // REGRESSION: cannot create chat with invalid participant structure
  test("chat creation with invalid participants is denied", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertFails(
      db.doc("chats/invalid_chat").set({
        chatId: "invalid_chat",
        participants: [aliceUid], // only 1 participant
      })
    );
  });

  test("unauthenticated create is denied", async () => {
    const db = unauthedApp().firestore();
    await assertFails(
      db.doc("chats/alice_uid_carol_uid").set({
        chatId: "alice_uid_carol_uid",
        participants: [aliceUid, carolUid],
      })
    );
  });
});

// ── chats/{chatId}/messages/{msgId} ──────────────────────────────────────────

describe("chats/{chatId}/messages/{msgId}", () => {
  const chatId = "alice_uid_bob_uid";
  const msgId = "msg_001";

  beforeEach(async () => {
    const admin = initializeAdminApp({ projectId: PROJECT_ID }).firestore();
    await admin.doc(`chats/${chatId}`).set({
      chatId,
      participants: [aliceUid, bobUid],
    });
    await admin
      .doc(`chats/${chatId}/messages/${msgId}`)
      .set({
        messageId: msgId,
        chatId,
        senderId: aliceUid,
        receiverId: bobUid,
        audioUrl: "https://storage.example.com/voice_messages/alice_uid/msg_001.m4a",
        durationMs: 5000,
        fileSize: 12000,
        timestamp: Date.now(),
        uploadStatus: "DONE",
        messageStatus: "SENT",
      });
  });

  test("participant can read message", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertSucceeds(
      db.doc(`chats/${chatId}/messages/${msgId}`).get()
    );
    const db2 = authedApp(bobUid).firestore();
    await assertSucceeds(
      db2.doc(`chats/${chatId}/messages/${msgId}`).get()
    );
  });

  test("non-participant read is denied", async () => {
    const db = authedApp(carolUid).firestore();
    await assertFails(
      db.doc(`chats/${chatId}/messages/${msgId}`).get()
    );
  });

  test("sender can create message", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertSucceeds(
      db
        .doc(`chats/${chatId}/messages/msg_002`)
        .set({
          messageId: "msg_002",
          chatId,
          senderId: aliceUid,
          receiverId: bobUid,
          audioUrl: "https://storage.example.com/voice_messages/alice_uid/msg_002.m4a",
          durationMs: 3000,
          fileSize: 8000,
          timestamp: Date.now(),
          uploadStatus: "DONE",
          messageStatus: "SENT",
        })
    );
  });

  test("user cannot create message spoofing another sender", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertFails(
      db
        .doc(`chats/${chatId}/messages/msg_003`)
        .set({
          messageId: "msg_003",
          chatId,
          senderId: bobUid,
          receiverId: carolUid,
          audioUrl: "https://example.com/x.m4a",
          durationMs: 3000,
          fileSize: 8000,
          timestamp: Date.now(),
          uploadStatus: "DONE",
          messageStatus: "SENT",
        })
    );
  });

  // REGRESSION: sender cannot spoof receiverId to a non-participant
  test("sender cannot set receiverId to non-participant", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertFails(
      db
        .doc(`chats/${chatId}/messages/msg_004`)
        .set({
          messageId: "msg_004",
          chatId,
          senderId: aliceUid,
          receiverId: carolUid, // carol is not in this chat
          audioUrl: "https://storage.example.com/voice_messages/alice_uid/msg_004.m4a",
          durationMs: 3000,
          fileSize: 8000,
          timestamp: Date.now(),
          uploadStatus: "DONE",
          messageStatus: "SENT",
        })
    );
  });

  test("receiver can mark message as seen", async () => {
    const db = authedApp(bobUid).firestore();
    await assertSucceeds(
      db.doc(`chats/${chatId}/messages/${msgId}`).update({
        messageStatus: "SEEN",
      })
    );
  });

  test("sender cannot mark own message as seen", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertFails(
      db.doc(`chats/${chatId}/messages/${msgId}`).update({
        messageStatus: "SEEN",
      })
    );
  });

  test("non-participant cannot mark as seen", async () => {
    const db = authedApp(carolUid).firestore();
    await assertFails(
      db.doc(`chats/${chatId}/messages/${msgId}`).update({
        messageStatus: "SEEN",
      })
    );
  });

  test("message cannot be deleted", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertFails(db.doc(`chats/${chatId}/messages/${msgId}`).delete());
  });

  test("unauthenticated write is denied", async () => {
    const db = unauthedApp().firestore();
    await assertFails(
      db.doc(`chats/${chatId}/messages/msg_004`).set({
        messageId: "msg_004",
        chatId,
        senderId: aliceUid,
        receiverId: bobUid,
        audioUrl: "https://example.com/x.m4a",
        timestamp: Date.now(),
      })
    );
  });
});

// ── fcm_requests/{requestId} ─────────────────────────────────────────────────

describe("fcm_requests/{requestId}", () => {
  const chatId = "alice_uid_bob_uid";

  beforeEach(async () => {
    const admin = initializeAdminApp({ projectId: PROJECT_ID }).firestore();
    await admin.doc(`chats/${chatId}`).set({
      chatId,
      participants: [aliceUid, bobUid],
    });
  });

  test("authenticated client can create FCM request for own chat", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertSucceeds(
      db.collection("fcm_requests").add({
        token: "fcm_token_bob",
        chatId,
        senderId: aliceUid,
        senderName: "Alice",
        timestamp: Date.now(),
      })
    );
  });

  test("client cannot create FCM request for chat they are not a participant in", async () => {
    const db = authedApp(carolUid).firestore();
    await assertFails(
      db.collection("fcm_requests").add({
        token: "fcm_token_carol",
        chatId,
        senderId: carolUid,
        senderName: "Carol",
        timestamp: Date.now(),
      })
    );
  });

  test("client cannot create FCM request with spoofed senderId", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertFails(
      db.collection("fcm_requests").add({
        token: "fcm_token_bob",
        chatId,
        senderId: bobUid,
        senderName: "Alice",
        timestamp: Date.now(),
      })
    );
  });

  test("client cannot create FCM request with extra fields", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertFails(
      db.collection("fcm_requests").add({
        token: "fcm_token_bob",
        chatId,
        senderId: aliceUid,
        senderName: "Alice",
        timestamp: Date.now(),
        extraField: "should not be allowed",
      })
    );
  });

  test("client cannot create FCM request with oversized token", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertFails(
      db.collection("fcm_requests").add({
        token: "x".repeat(201),
        chatId,
        senderId: aliceUid,
        senderName: "Alice",
        timestamp: Date.now(),
      })
    );
  });

  test("client cannot create FCM request with missing field", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertFails(
      db.collection("fcm_requests").add({
        chatId,
        senderId: aliceUid,
        senderName: "Alice",
        timestamp: Date.now(),
      })
    );
  });

  test("client cannot read FCM requests", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertFails(db.collection("fcm_requests").get());
  });

  test("client cannot delete FCM requests", async () => {
    const admin = initializeAdminApp({ projectId: PROJECT_ID }).firestore();
    const doc = await admin.collection("fcm_requests").add({
      token: "fcm_token_bob",
      chatId,
      senderId: aliceUid,
      senderName: "Alice",
      timestamp: Date.now(),
    });
    const db = authedApp(aliceUid).firestore();
    await assertFails(db.doc(doc.id).delete());
  });

  test("unauthenticated create is denied", async () => {
    const db = unauthedApp().firestore();
    await assertFails(
      db.collection("fcm_requests").add({
        token: "fcm_token_bob",
        chatId,
        senderId: aliceUid,
        senderName: "Alice",
        timestamp: Date.now(),
      })
    );
  });
});

// ── sos_alerts/{uid}/sessions/{sessionId} ────────────────────────────────────

describe("sos_alerts/{uid}/sessions/{sessionId}", () => {
  test("owner can create SOS session", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertSucceeds(
      db
        .doc(`sos_alerts/${aliceUid}/sessions/session_001`)
        .set({
          message: "Emergency! Need help.",
          startLat: 28.6139,
          startLng: 77.2090,
          startTime: Date.now(),
        })
    );
  });

  test("owner can read own SOS session", async () => {
    const admin = initializeAdminApp({ projectId: PROJECT_ID }).firestore();
    await admin
      .doc(`sos_alerts/${aliceUid}/sessions/session_002`)
      .set({
        message: "Test SOS",
        startLat: 28.6139,
        startLng: 77.2090,
        startTime: Date.now(),
      });
    const db = authedApp(aliceUid).firestore();
    await assertSucceeds(
      db.doc(`sos_alerts/${aliceUid}/sessions/session_002`).get()
    );
  });

  test("cross-user cannot read SOS session", async () => {
    const admin = initializeAdminApp({ projectId: PROJECT_ID }).firestore();
    await admin
      .doc(`sos_alerts/${aliceUid}/sessions/session_003`)
      .set({
        message: "Test SOS",
        startLat: 28.6139,
        startLng: 77.2090,
        startTime: Date.now(),
      });
    const db = authedApp(bobUid).firestore();
    await assertFails(
      db.doc(`sos_alerts/${aliceUid}/sessions/session_003`).get()
    );
  });

  test("cross-user cannot write SOS session", async () => {
    const db = authedApp(bobUid).firestore();
    await assertFails(
      db.doc(`sos_alerts/${aliceUid}/sessions/session_004`).set({
        message: "Unauthorized SOS",
        startLat: 28.6139,
        startLng: 77.2090,
        startTime: Date.now(),
      })
    );
  });

  test("unauthenticated write is denied", async () => {
    const db = unauthedApp().firestore();
    await assertFails(
      db.doc(`sos_alerts/${aliceUid}/sessions/session_005`).set({
        message: "Unauth SOS",
        startLat: 28.6139,
        startLng: 77.2090,
        startTime: Date.now(),
      })
    );
  });

  test("SOS session cannot be deleted", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertFails(
      db.doc(`sos_alerts/${aliceUid}/sessions/session_001`).delete()
    );
  });
});

// ── sos_alerts/{uid}/sessions/{sessionId}/location_updates/{locationId} ──────

describe("sos_alerts/{uid}/sessions/{sessionId}/location_updates/{locationId}", () => {
  beforeEach(async () => {
    const admin = initializeAdminApp({ projectId: PROJECT_ID }).firestore();
    await admin
      .doc(`sos_alerts/${aliceUid}/sessions/session_loc`)
      .set({
        message: "SOS with location",
        startLat: 28.6139,
        startLng: 77.2090,
        startTime: Date.now(),
      });
  });

  test("owner can write location update", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertSucceeds(
      db
        .doc(`sos_alerts/${aliceUid}/sessions/session_loc/location_updates/loc_001`)
        .set({ lat: 28.6139, lng: 77.2090, time: Date.now() })
    );
  });

  test("cross-user cannot write location update", async () => {
    const db = authedApp(bobUid).firestore();
    await assertFails(
      db
        .doc(`sos_alerts/${aliceUid}/sessions/session_loc/location_updates/loc_002`)
        .set({ lat: 28.6139, lng: 77.2090, time: Date.now() })
    );
  });

  test("invalid location update is denied", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertFails(
      db
        .doc(`sos_alerts/${aliceUid}/sessions/session_loc/location_updates/loc_003`)
        .set({ lat: "not_a_number", lng: 77.2090, time: Date.now() })
    );
  });

  // REGRESSION: latitude out of bounds is denied
  test("latitude out of bounds is denied", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertFails(
      db
        .doc(`sos_alerts/${aliceUid}/sessions/session_loc/location_updates/loc_005`)
        .set({ lat: 91.0, lng: 77.2090, time: Date.now() })
    );
  });

  // REGRESSION: longitude out of bounds is denied
  test("longitude out of bounds is denied", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertFails(
      db
        .doc(`sos_alerts/${aliceUid}/sessions/session_loc/location_updates/loc_006`)
        .set({ lat: 28.6139, lng: 181.0, time: Date.now() })
    );
  });

  // REGRESSION: extra field in location update is denied
  test("location update with extra field is denied", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertFails(
      db
        .doc(`sos_alerts/${aliceUid}/sessions/session_loc/location_updates/loc_007`)
        .set({ lat: 28.6139, lng: 77.2090, time: Date.now(), extra: "bad" })
    );
  });

  test("unauthenticated location write is denied", async () => {
    const db = unauthedApp().firestore();
    await assertFails(
      db
        .doc(`sos_alerts/${aliceUid}/sessions/session_loc/location_updates/loc_004`)
        .set({ lat: 28.6139, lng: 77.2090, time: Date.now() })
    );
  });
});

// ── sos_logs/{logId} ─────────────────────────────────────────────────────────

describe("sos_logs/{logId}", () => {
  test("owner can create SOS log", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertSucceeds(
      db.collection("sos_logs").add({
        userId: aliceUid,
        latitude: 28.6139,
        longitude: 77.2090,
        timestamp: Date.now(),
      })
    );
  });

  test("cross-user cannot read SOS log", async () => {
    const admin = initializeAdminApp({ projectId: PROJECT_ID }).firestore();
    const doc = await admin.collection("sos_logs").add({
      userId: aliceUid,
      latitude: 28.6139,
      longitude: 77.2090,
      timestamp: Date.now(),
    });
    const db = authedApp(bobUid).firestore();
    await assertFails(db.doc(doc.id).get());
  });

  test("user cannot create SOS log with spoofed userId", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertFails(
      db.collection("sos_logs").add({
        userId: bobUid,
        latitude: 28.6139,
        longitude: 77.2090,
        timestamp: Date.now(),
      })
    );
  });

  test("only evidenceUrl can be added on update", async () => {
    const admin = initializeAdminApp({ projectId: PROJECT_ID }).firestore();
    const doc = await admin.collection("sos_logs").add({
      userId: aliceUid,
      latitude: 28.6139,
      longitude: 77.2090,
      timestamp: Date.now(),
    });
    const db = authedApp(aliceUid).firestore();
    await assertSucceeds(
      db.doc(doc.id).update({ evidenceUrl: "https://storage.example.com/evidence/alice_uid/123.mp4" })
    );
    await assertFails(
      db.doc(doc.id).update({ userId: bobUid })
    );
  });

  // REGRESSION: SOS log cannot be deleted
  test("SOS log cannot be deleted", async () => {
    const admin = initializeAdminApp({ projectId: PROJECT_ID }).firestore();
    const doc = await admin.collection("sos_logs").add({
      userId: aliceUid,
      latitude: 28.6139,
      longitude: 77.2090,
      timestamp: Date.now(),
    });
    const db = authedApp(aliceUid).firestore();
    await assertFails(db.doc(doc.id).delete());
  });
});

// ── safe_walks/{userId} ──────────────────────────────────────────────────────

describe("safe_walks/{userId}", () => {
  test("owner can create Safe Walk", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertSucceeds(
      db.doc(`safe_walks/${aliceUid}`).set({
        userId: aliceUid,
        startTime: Date.now(),
        isActive: true,
      })
    );
  });

  test("cross-user cannot read Safe Walk", async () => {
    const admin = initializeAdminApp({ projectId: PROJECT_ID }).firestore();
    await admin.doc(`safe_walks/${aliceUid}`).set({
      userId: aliceUid,
      startTime: Date.now(),
      isActive: true,
    });
    const db = authedApp(bobUid).firestore();
    await assertFails(db.doc(`safe_walks/${aliceUid}`).get());
  });

  test("cross-user cannot write Safe Walk", async () => {
    const db = authedApp(bobUid).firestore();
    await assertFails(
      db.doc(`safe_walks/${aliceUid}`).set({
        userId: aliceUid,
        startTime: Date.now(),
        isActive: true,
      })
    );
  });

  test("owner can end Safe Walk (set isActive=false)", async () => {
    const admin = initializeAdminApp({ projectId: PROJECT_ID }).firestore();
    await admin.doc(`safe_walks/${aliceUid}`).set({
      userId: aliceUid,
      startTime: Date.now(),
      isActive: true,
    });
    const db = authedApp(aliceUid).firestore();
    await assertSucceeds(
      db.doc(`safe_walks/${aliceUid}`).update({ isActive: false })
    );
  });

  test("unauthenticated write is denied", async () => {
    const db = unauthedApp().firestore();
    await assertFails(
      db.doc(`safe_walks/${aliceUid}`).set({
        userId: aliceUid,
        startTime: Date.now(),
        isActive: true,
      })
    );
  });
});

// ── safe_walks/{userId}/locations/{locationId} ───────────────────────────────

describe("safe_walks/{userId}/locations/{locationId}", () => {
  beforeEach(async () => {
    const admin = initializeAdminApp({ projectId: PROJECT_ID }).firestore();
    await admin.doc(`safe_walks/${aliceUid}`).set({
      userId: aliceUid,
      startTime: Date.now(),
      isActive: true,
    });
  });

  test("owner can write location", async () => {
    const db = authedApp(aliceUid).firestore();
    await assertSucceeds(
      db
        .doc(`safe_walks/${aliceUid}/locations/loc_sw_001`)
        .set({ latitude: 28.6139, longitude: 77.2090, timestamp: Date.now() })
    );
  });

  test("cross-user cannot write location", async () => {
    const db = authedApp(bobUid).firestore();
    await assertFails(
      db
        .doc(`safe_walks/${aliceUid}/locations/loc_sw_002`)
        .set({ latitude: 28.6139, longitude: 77.2090, timestamp: Date.now() })
    );
  });

  test("owner can read own locations", async () => {
    const admin = initializeAdminApp({ projectId: PROJECT_ID }).firestore();
    await admin
      .doc(`safe_walks/${aliceUid}/locations/loc_sw_003`)
      .set({ latitude: 28.6139, longitude: 77.2090, timestamp: Date.now() });
    const db = authedApp(aliceUid).firestore();
    await assertSucceeds(
      db.doc(`safe_walks/${aliceUid}/locations/loc_sw_003`).get()
    );
  });

  test("cross-user cannot read locations", async () => {
    const admin = initializeAdminApp({ projectId: PROJECT_ID }).firestore();
    await admin
      .doc(`safe_walks/${aliceUid}/locations/loc_sw_004`)
      .set({ latitude: 28.6139, longitude: 77.2090, timestamp: Date.now() });
    const db = authedApp(bobUid).firestore();
    await assertFails(
      db.doc(`safe_walks/${aliceUid}/locations/loc_sw_004`).get()
    );
  });
});
