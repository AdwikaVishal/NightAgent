const { assertFails, assertSucceeds } = require("@firebase/rules-unit-testing");
const harness = require("./test-env");

const OWNER = "journey_owner";
const GUARDIAN = "linked_guardian";
const STRANGER = "unlinked_user";
const JOURNEY_ID = "journey_rules_test";
const LOCATION_ID = "loc_1";

beforeAll(async () => {
  await harness.init({ firestoreRules: "firestore.rules" });
});

beforeEach(async () => {
  await harness.clearFirestore();
  await harness.withAdmin(async (db) => {
    await db.doc(`users/${OWNER}`).set({
      uid: OWNER,
      guardianUids: [GUARDIAN],
    });
    await db.doc(`journeys/${JOURNEY_ID}`).set({
      journeyId: JOURNEY_ID,
      userId: OWNER,
      status: "ACTIVE",
      createdAt: 1_000,
      updatedAt: 1_000,
    });
    await db.doc(`journeys/${JOURNEY_ID}/locations/${LOCATION_ID}`).set({
      latitude: 12.5,
      longitude: 77.1,
      timestamp: 1_000,
    });
    await db.doc(`safe_walks/${OWNER}`).set({
      userId: OWNER,
      journeyId: JOURNEY_ID,
      isActive: true,
    });
  });
});

afterAll(async () => {
  await harness.cleanup();
});

function db(uid) {
  return harness.authedApp(uid).firestore();
}

describe("Person 3 journey guardian access", () => {
  test("owner and linked guardian can read the canonical journey; stranger cannot", async () => {
    await assertSucceeds(db(OWNER).doc(`journeys/${JOURNEY_ID}`).get());
    await assertSucceeds(db(GUARDIAN).doc(`journeys/${JOURNEY_ID}`).get());
    await assertFails(db(STRANGER).doc(`journeys/${JOURNEY_ID}`).get());
  });

  test("linked guardian can read but never write canonical live locations", async () => {
    await assertSucceeds(
      db(GUARDIAN).doc(`journeys/${JOURNEY_ID}/locations/${LOCATION_ID}`).get()
    );
    await assertFails(
      db(GUARDIAN).doc(`journeys/${JOURNEY_ID}/locations/injected`).set({
        latitude: 0,
        longitude: 0,
        timestamp: 2_000,
      })
    );
  });

  test("journey owner can write locations; stranger cannot", async () => {
    await assertSucceeds(
      db(OWNER).doc(`journeys/${JOURNEY_ID}/locations/owner_loc`).set({
        latitude: 12.6,
        longitude: 77.2,
        timestamp: 2_000,
      })
    );
    await assertFails(
      db(STRANGER).doc(`journeys/${JOURNEY_ID}/locations/stranger_loc`).set({
        latitude: 12.6,
        longitude: 77.2,
        timestamp: 2_000,
      })
    );
  });

  test("legacy safe_walks mirror remains guardian-readable and owner-write-only", async () => {
    await assertSucceeds(db(GUARDIAN).doc(`safe_walks/${OWNER}`).get());
    await assertFails(db(STRANGER).doc(`safe_walks/${OWNER}`).get());
    await assertSucceeds(
      db(OWNER).collection(`safe_walks/${OWNER}/locations`).add({
        latitude: 12.5,
        longitude: 77.1,
        timestamp: 3_000,
        accuracy: 10,
        journeyId: JOURNEY_ID,
      })
    );
    await assertFails(
      db(GUARDIAN).collection(`safe_walks/${OWNER}/locations`).add({
        latitude: 12.5,
        longitude: 77.1,
        timestamp: 3_000,
      })
    );
  });

  test("unbounded or out-of-range legacy location writes are denied", async () => {
    await assertFails(
      db(OWNER).collection(`safe_walks/${OWNER}/locations`).add({
        latitude: 91,
        longitude: 77.1,
        timestamp: 3_000,
      })
    );
    await assertFails(
      db(OWNER).collection(`safe_walks/${OWNER}/locations`).add({
        latitude: 12.5,
        longitude: 77.1,
        timestamp: 3_000,
        injected: true,
      })
    );
  });
});
