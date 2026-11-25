const { assertFails, assertSucceeds } = require("@firebase/rules-unit-testing");
const harness = require("./test-env");

const OWNER = "consent_owner";
const CANDIDATE = "consent_candidate";
const STRANGER = "consent_stranger";
const JOURNEY_ID = "consent_journey";
const SESSION_ID = "consent_session";

beforeAll(async () => {
  await harness.init({ firestoreRules: "firestore.rules" });
});

beforeEach(async () => {
  await harness.clearFirestore();
  await harness.withAdmin(async (db) => {
    await db.doc(`users/${OWNER}`).set({ uid: OWNER, fcmToken: "owner-token" });
    await db.doc(`journeys/${JOURNEY_ID}`).set({
      journeyId: JOURNEY_ID,
      userId: OWNER,
      status: "ACTIVE",
    });
    await db.doc(`sos_alerts/${OWNER}/sessions/${SESSION_ID}`).set({
      id: SESSION_ID,
      sessionId: SESSION_ID,
      userId: OWNER,
      state: "ACTIVE",
      message: "help",
      startTime: 1_000,
      createdAt: 1_000,
      updatedAt: 1_000,
    });
  });
});

afterAll(async () => {
  await harness.cleanup();
});

function db(uid) {
  return harness.authedApp(uid).firestore();
}

describe("consent-based guardian linking rules", () => {
  test("a client cannot grant guardian access by editing guardianUids", async () => {
    await assertFails(
      db(OWNER).doc(`users/${OWNER}`).set({
        uid: OWNER,
        guardianUids: [CANDIDATE],
      })
    );
    await assertFails(
      db(OWNER).doc(`users/${OWNER}`).update({ guardianUids: [CANDIDATE] })
    );
    await assertSucceeds(db(OWNER).doc(`users/${OWNER}`).get());
  });

  test("unaccepted candidates and strangers cannot read owner journey or SOS data", async () => {
    await assertFails(db(CANDIDATE).doc(`journeys/${JOURNEY_ID}`).get());
    await assertFails(
      db(CANDIDATE).doc(`sos_alerts/${OWNER}/sessions/${SESSION_ID}`).get()
    );
    await assertFails(db(STRANGER).doc(`journeys/${JOURNEY_ID}`).get());
    await assertFails(
      db(STRANGER).doc(`sos_alerts/${OWNER}/sessions/${SESSION_ID}`).get()
    );
  });

  test("a server-created link grants read access but still forbids client writes", async () => {
    await harness.withAdmin(async (adminDb) => {
      await adminDb.doc(`users/${OWNER}`).update({ guardianUids: [CANDIDATE] });
    });
    await assertSucceeds(db(CANDIDATE).doc(`journeys/${JOURNEY_ID}`).get());
    await assertSucceeds(
      db(CANDIDATE).doc(`sos_alerts/${OWNER}/sessions/${SESSION_ID}`).get()
    );
    await assertFails(
      db(CANDIDATE).doc(`sos_alerts/${OWNER}/sessions/${SESSION_ID}`).update({
        state: "RESOLVED",
      })
    );
    await assertFails(
      db(CANDIDATE).collection(`sos_alerts/${OWNER}/sessions/${SESSION_ID}/location_updates`).add({
        lat: 12.5,
        lng: 77.1,
        time: 2_000,
      })
    );
  });

  test("clients cannot fabricate invitations or notification idempotency records", async () => {
    await assertFails(db(OWNER).collection("guardianInvites").add({
      ownerUid: OWNER,
      candidateUid: CANDIDATE,
      status: "ACCEPTED",
    }));
    await assertFails(db(CANDIDATE).collection("guardianInvites").add({
      ownerUid: OWNER,
      candidateUid: CANDIDATE,
      status: "ACCEPTED",
    }));
    await assertFails(db(OWNER).doc(`emergency_notification_requests/${SESSION_ID}`).set({
      ownerUid: OWNER,
      guardianUid: CANDIDATE,
      status: "SENT",
    }));
  });
});
