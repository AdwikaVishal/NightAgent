const { assertFails, assertSucceeds } = require("@firebase/rules-unit-testing");
const harness = require("./test-env");

const PROJECT_ID = harness.PROJECT_ID;
const aliceUid = "alice_uid";
const bobUid = "bob_uid";
const carolUid = "carol_uid";
const chatId = "alice_uid_bob_uid";

// Storage custom metadata values are strings, matching the Android client.
const voiceMetadata = {
  chatId,
  senderId: aliceUid,
  participant1: aliceUid,
  participant2: bobUid,
};

beforeAll(async () => {
  await harness.init({
    firestoreRules: "firestore.rules",
    storageRules: "storage.rules",
  });
});

beforeEach(async () => {
  await harness.clearStorage();
  await harness.clearFirestore();
});

afterAll(async () => {
  await harness.cleanup();
});

function authedApp(uid) {
  return harness.authedApp(uid);
}

function unauthedApp() {
  return harness.unauthedApp();
}

// ── voice_messages/{chatId}/{senderId}/{messageId}.m4a ───────────────────────

describe("voice_messages/{chatId}/{senderId}/{messageId}.m4a", () => {
  const path = `voice_messages/${chatId}/${aliceUid}/msg_001.m4a`;

  test("owner can upload voice message with valid metadata", async () => {
    const storage = authedApp(aliceUid).storage();
    const ref = storage.ref().child(path);
    await assertSucceeds(
      ref.putString("fake audio data", "raw", {
        contentType: "audio/m4a",
        customMetadata: voiceMetadata,
      })
    );
  });

  test("owner can upload AAC voice message with valid metadata", async () => {
    const storage = authedApp(aliceUid).storage();
    const ref = storage.ref().child(
      `voice_messages/${chatId}/${aliceUid}/msg_002.m4a`
    );
    await assertSucceeds(
      ref.putString("fake audio data", "raw", {
        contentType: "audio/aac",
        customMetadata: voiceMetadata,
      })
    );
  });

  test("cross-user cannot upload to another user's senderId path", async () => {
    const storage = authedApp(bobUid).storage();
    const ref = storage.ref().child(path);
    await assertFails(
      ref.putString("fake audio data", "raw", {
        contentType: "audio/m4a",
        customMetadata: voiceMetadata,
      })
    );
  });

  test("unauthenticated upload is denied", async () => {
    const storage = unauthedApp().storage();
    const ref = storage.ref().child(path);
    await assertFails(
      ref.putString("fake audio data", "raw", {
        contentType: "audio/m4a",
        customMetadata: voiceMetadata,
      })
    );
  });

  // REGRESSION: invalid content type is denied
  test("uploading a file with invalid content type is denied", async () => {
    const storage = authedApp(aliceUid).storage();
    const ref = storage.ref().child(
      `voice_messages/${chatId}/${aliceUid}/msg_bad.m4a`
    );
    await assertFails(
      ref.putString("fake data", "raw", {
        contentType: "image/png",
        customMetadata: voiceMetadata,
      })
    );
  });

  // REGRESSION: missing metadata is denied
  test("upload without metadata is denied", async () => {
    const storage = authedApp(aliceUid).storage();
    const ref = storage.ref().child(
      `voice_messages/${chatId}/${aliceUid}/msg_no_meta.m4a`
    );
    await assertFails(
      ref.putString("fake audio data", "raw", {
        contentType: "audio/m4a",
      })
    );
  });

  test("owner can read own voice message (participant)", async () => {
    const storage = authedApp(aliceUid).storage();
    const ref = storage.ref().child(path);
    await assertSucceeds(
      ref.putString("fake audio data", "raw", {
        contentType: "audio/m4a",
        customMetadata: voiceMetadata,
      })
    );
    await assertSucceeds(ref.getDownloadURL());
  });

  test("other chat participant can read voice message", async () => {
    // Only the sender may create the object; the receiver then downloads it.
    const senderStorage = authedApp(aliceUid).storage();
    await assertSucceeds(
      senderStorage.ref().child(path).putString("fake audio data", "raw", {
        contentType: "audio/m4a",
        customMetadata: voiceMetadata,
      })
    );
    const receiverRef = authedApp(bobUid).storage().ref().child(path);
    await assertSucceeds(receiverRef.getDownloadURL());
  });

  // REGRESSION: non-participant cannot read another user's voice message
  test("non-participant cannot read voice message", async () => {
    const storage = authedApp(carolUid).storage();
    const ref = storage.ref().child(path);
    // Carol is not a participant, so even after upload she cannot read
    await assertFails(
      ref.putString("fake audio data", "raw", {
        contentType: "audio/m4a",
        customMetadata: voiceMetadata,
      })
    );
    // But also test read directly - first upload as alice, then read as carol
    const aliceStorage = authedApp(aliceUid).storage();
    const aliceRef = aliceStorage.ref().child(path);
    await assertSucceeds(
      aliceRef.putString("fake audio data", "raw", {
        contentType: "audio/m4a",
        customMetadata: voiceMetadata,
      })
    );
    await assertFails(ref.getDownloadURL());
  });

  test("unauthenticated read is denied", async () => {
    const storage = unauthedApp().storage();
    const ref = storage.ref().child(path);
    await assertFails(ref.getDownloadURL());
  });

  // REGRESSION: owner can delete their own uploaded voice message
  test("owner can delete their own voice message", async () => {
    const storage = authedApp(aliceUid).storage();
    const ref = storage.ref().child(
      `voice_messages/${chatId}/${aliceUid}/msg_delete.m4a`
    );
    await assertSucceeds(
      ref.putString("fake audio", "raw", {
        contentType: "audio/m4a",
        customMetadata: voiceMetadata,
      })
    );
    await assertSucceeds(ref.delete());
  });

  test("cross-user cannot delete voice message", async () => {
    const storage = authedApp(bobUid).storage();
    const ref = storage.ref().child(path);
    await assertFails(ref.delete());
  });

  test("unauthenticated delete is denied", async () => {
    const storage = unauthedApp().storage();
    const ref = storage.ref().child(path);
    await assertFails(ref.delete());
  });
});

// ── evidence/{userId}/{timestamp}.mp4 ────────────────────────────────────────

describe("evidence/{userId}/{timestamp}.mp4", () => {
  const path = `evidence/${aliceUid}/1234567890.mp4`;

  test("owner can upload evidence", async () => {
    const storage = authedApp(aliceUid).storage();
    const ref = storage.ref().child(path);
    await assertSucceeds(
      ref.putString("fake evidence data", "raw", {
        contentType: "video/mp4",
      })
    );
  });

  test("owner can upload audio evidence", async () => {
    const storage = authedApp(aliceUid).storage();
    const ref = storage.ref().child(`evidence/${aliceUid}/1234567891.m4a`);
    await assertSucceeds(
      ref.putString("fake audio evidence", "raw", {
        contentType: "audio/m4a",
      })
    );
  });

  test("owner can upload JPEG photo evidence", async () => {
    const storage = authedApp(aliceUid).storage();
    const ref = storage.ref().child(`evidence/${aliceUid}/1234567892/photos/photo.jpg`);
    await assertSucceeds(
      ref.putString("fake photo evidence", "raw", { contentType: "image/jpeg" })
    );
  });

  test("cross-user cannot upload evidence to another user's path", async () => {
    const storage = authedApp(bobUid).storage();
    const ref = storage.ref().child(path);
    await assertFails(
      ref.putString("fake evidence", "raw", { contentType: "video/mp4" })
    );
  });

  test("unauthenticated upload is denied", async () => {
    const storage = unauthedApp().storage();
    const ref = storage.ref().child(path);
    await assertFails(
      ref.putString("fake evidence", "raw", { contentType: "video/mp4" })
    );
  });

  test("oversized evidence upload is denied (>50 MB)", async () => {
    const storage = authedApp(aliceUid).storage();
    const ref = storage.ref().child(`evidence/${aliceUid}/9999999999.mp4`);
    const largeData = "x".repeat(51 * 1024 * 1024); // 51 MB
    await assertFails(ref.putString(largeData, "raw", { contentType: "video/mp4" }));
  });

  test("invalid evidence content type is denied", async () => {
    const storage = authedApp(aliceUid).storage();
    const ref = storage.ref().child(`evidence/${aliceUid}/9999999998.txt`);
    await assertFails(
      ref.putString("not evidence", "raw", { contentType: "text/plain" })
    );
  });

  test("owner can read own evidence", async () => {
    const storage = authedApp(aliceUid).storage();
    const ref = storage.ref().child(path);
    await assertSucceeds(
      ref.putString("fake evidence", "raw", { contentType: "video/mp4" })
    );
    await assertSucceeds(ref.getDownloadURL());
  });

  // REGRESSION: unauthorized evidence access (cross-user read)
  test("cross-user cannot read evidence", async () => {
    const storage = authedApp(bobUid).storage();
    const ref = storage.ref().child(path);
    await assertFails(ref.getDownloadURL());
  });

  // REGRESSION: unauthenticated evidence access
  test("unauthenticated read is denied", async () => {
    const storage = unauthedApp().storage();
    const ref = storage.ref().child(path);
    await assertFails(ref.getDownloadURL());
  });

  test("owner can delete own evidence", async () => {
    const storage = authedApp(aliceUid).storage();
    const ref = storage.ref().child(`evidence/${aliceUid}/9999999997.mp4`);
    await assertSucceeds(
      ref.putString("fake evidence", "raw", { contentType: "video/mp4" })
    );
    await assertSucceeds(ref.delete());
  });

  test("cross-user cannot delete evidence", async () => {
    const storage = authedApp(bobUid).storage();
    const ref = storage.ref().child(path);
    await assertFails(ref.delete());
  });
});

// ── Catch-all: deny everything else ──────────────────────────────────────────

describe("catch-all deny", () => {
  test("unmatched path is denied for write", async () => {
    const storage = authedApp(aliceUid).storage();
    const ref = storage.ref().child("unauthorized/path/file.txt");
    await assertFails(
      ref.putString("data", "raw", { contentType: "text/plain" })
    );
  });

  test("unmatched path is denied for read", async () => {
    const storage = authedApp(aliceUid).storage();
    const ref = storage.ref().child("unauthorized/path/file.txt");
    await assertFails(ref.getDownloadURL());
  });
});
