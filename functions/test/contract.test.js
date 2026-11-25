const assert = require("node:assert/strict");
const { test } = require("node:test");
const fs = require("node:fs");
const path = require("node:path");

const source = fs.readFileSync(path.join(__dirname, "..", "src", "index.ts"), "utf8");

test("callables are exported from the canonical v2 entrypoint", () => {
  for (const name of [
    "getSignedVoiceUrl",
    "sendVoiceNotification",
    "sendEmergencyNotification",
    "syncGuardianLinks",
    "acknowledgeEmergency",
    "escalateEmergency",
    "resolveEmergency",
  ]) {
    assert.match(source, new RegExp(`export const ${name} = onCall`));
  }
});

test("push delivery uses the Admin SDK rather than a client-writable queue", () => {
  assert.equal((source.match(/admin\.messaging\(\)\.send\(/g) || []).length >= 2, true);
  assert.doesNotMatch(source, /collection\("fcm_requests"\)/);
});

test("callables use structured HttpsError codes", () => {
  assert.match(source, /HttpsError\("unauthenticated"/);
  assert.match(source, /HttpsError\("permission-denied"/);
  assert.match(source, /HttpsError\("resource-exhausted"/);
});

test("signed URLs are restricted to canonical voice object paths", () => {
  assert.match(source, /voice_messages\/\$\{chatId\}\/\$\{messageInfo\.senderId\}\/\$\{messageId\}\.m4a/);
  assert.match(source, /messageInfo\.storagePath !== expectedPath/);
});

test("acknowledgement and escalation update the canonical SOS session", () => {
  assert.match(source, /state: "ACKNOWLEDGED"/);
  assert.match(source, /escalated: true/);
  assert.match(source, /acknowledgedBy/);
});

test("emergency delivery requires owner-provisioned guardian links", () => {
  assert.match(source, /linkedGuardianUids\(uid\)/);
  assert.match(source, /verifyEmergencyActor\(ownerUid, uid\)/);
  assert.match(source, /guardianUids: linkedUids/);
});

test("guardian consent callables are exported and server-owned", () => {
  for (const name of [
    "inviteGuardian",
    "respondGuardianInvite",
    "getGuardianInvitations",
    "unlinkGuardian",
  ]) {
    assert.match(source, new RegExp(`export const ${name} = onCall`));
  }
  assert.match(source, /collection\("guardianInvites"\)/);
  assert.match(source, /status: "PENDING"/);
  assert.match(source, /accept \? "ACCEPTED" : "REJECTED"/);
  assert.match(source, /findVerifiedAccountByPhone/);
  assert.match(source, /findVerifiedAccountByEmail/);
  assert.match(source, /authUser\.phoneNumber && normalizePhone\(authUser\.phoneNumber\) === expected/);
  assert.match(source, /authUser\.emailVerified && normalizeEmail\(authUser\.email \|\| ""\) === email/);
});

test("delivery is idempotent per SOS session", () => {
  assert.match(source, /emergency_notification_requests"\)\.doc\(sosSessionId\)/);
  assert.match(source, /priorData\.deliveredGuardianIds/);
  assert.match(source, /status: deliveredGuardianIds\.length > 0 \? "delivered" : "not_delivered"/);
});

test("invalid FCM tokens are cleared server-side", () => {
  assert.match(source, /isInvalidTokenError/);
  assert.match(source, /fcmToken: admin\.firestore\.FieldValue\.delete\(\)/);
});
