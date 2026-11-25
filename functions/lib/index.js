"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.resolveEmergency = exports.escalateEmergency = exports.acknowledgeEmergency = exports.sendEmergencyNotification = exports.getGuardianInvitations = exports.syncGuardianLinks = exports.unlinkGuardian = exports.respondGuardianInvite = exports.inviteGuardian = exports.sendVoiceNotification = exports.getSignedVoiceUrl = void 0;
const https_1 = require("firebase-functions/v2/https");
const logger = __importStar(require("firebase-functions/logger"));
const admin = __importStar(require("firebase-admin"));
admin.initializeApp();
const db = admin.firestore();
const storage = admin.storage();
// Security constants
const SIGNED_URL_EXPIRATION_MINUTES = 15;
const MAX_CALLS_PER_MINUTE = 10;
// In-memory rate limiter keyed by authenticated UID (falls back to IP only for
// unauthenticated calls, which are rejected below). This is per instance only;
// a distributed store is still required for a multi-instance deployment.
const rateLimitMap = new Map();
function rateLimit(key) {
    const now = Date.now();
    const entry = rateLimitMap.get(key);
    if (!entry || now > entry.resetTime) {
        rateLimitMap.set(key, { count: 1, resetTime: now + 60000 });
        return true;
    }
    if (entry.count >= MAX_CALLS_PER_MINUTE) {
        return false;
    }
    entry.count++;
    return true;
}
function requiredString(value, field) {
    if (typeof value !== "string" || value.trim().length === 0) {
        throw new https_1.HttpsError("invalid-argument", `${field} is required.`);
    }
    return value.trim();
}
/** Normalize phone numbers consistently with the Android client. */
function normalizePhone(value) {
    const digits = value.replace(/\D/g, "");
    if (digits.length === 10)
        return `+91${digits}`;
    if (digits.length === 12 && digits.startsWith("91"))
        return `+${digits}`;
    if (digits.length === 13 && digits.startsWith("919"))
        return `+${digits}`;
    return `+${digits}`;
}
function normalizeEmail(value) {
    return value.trim().toLowerCase();
}
function asStringArray(value) {
    if (!Array.isArray(value))
        return [];
    return [...new Set(value.filter((entry) => typeof entry === "string" && entry.length > 0))];
}
function isInvalidTokenError(error) {
    const code = error?.code ?? "";
    return code.includes("registration-token-not-registered")
        || code.includes("invalid-argument")
        || code === "messaging/invalid-registration-token";
}
/** Clear invalid FCM tokens server-side so delivery can self-heal on refresh. */
async function clearInvalidFcmToken(uid, token) {
    await db.collection("users").doc(uid).update({ fcmToken: admin.firestore.FieldValue.delete() }).catch(() => {
        // The token may already have been refreshed/deleted by another instance.
    });
    logger.info("Cleared invalid FCM token", { uid, tokenPrefix: token.slice(0, 8) });
}
// ── Helper: verify caller authentication ─────────────────────────────────
function verifyCaller(request) {
    if (!request.auth?.uid) {
        throw new https_1.HttpsError("unauthenticated", "User must be authenticated.");
    }
    return request.auth.uid;
}
// ── Helper: validate chat membership (trusted server-side) ─────────────
async function verifyChatMembership(chatId, uid) {
    const chatDoc = await db.collection("chats").doc(chatId).get();
    if (!chatDoc.exists) {
        throw new https_1.HttpsError("not-found", `Chat ${chatId} does not exist.`);
    }
    const participants = chatDoc.data()?.participants;
    if (!Array.isArray(participants) || !participants.includes(uid)) {
        throw new https_1.HttpsError("permission-denied", "You are not a participant in this chat.");
    }
}
// ── Helper: validate message belongs to chat ───────────────────────────
async function validateMessageInChat(chatId, messageId) {
    const msgDoc = await db
        .collection("chats")
        .doc(chatId)
        .collection("messages")
        .doc(messageId)
        .get();
    if (!msgDoc.exists) {
        throw new https_1.HttpsError("not-found", `Message ${messageId} not found in chat ${chatId}.`);
    }
    const data = msgDoc.data();
    const audioUrl = data?.audioUrl;
    if (!audioUrl) {
        throw new https_1.HttpsError("failed-precondition", "Message has no audio URL.");
    }
    // Derive storage path from the download URL.
    // Firebase download URLs have format:
    // https://firebasestorage.googleapis.com/v0/b/{bucket}/o/{encodedPath}?alt=media&token={token}
    let storagePath = "";
    try {
        const urlObj = new URL(audioUrl);
        const pathname = urlObj.pathname; // /v0/b/{bucket}/o/{path}
        const oIndex = pathname.indexOf("/o/");
        if (oIndex === -1) {
            throw new https_1.HttpsError("failed-precondition", "Invalid download URL format");
        }
        const encodedPath = pathname.slice(oIndex + 3);
        storagePath = decodeURIComponent(encodedPath);
    }
    catch (error) {
        if (error instanceof https_1.HttpsError)
            throw error;
        throw new https_1.HttpsError("failed-precondition", "Unable to parse audio URL storage path.");
    }
    return {
        senderId: data?.senderId,
        receiverId: data?.receiverId,
        audioUrl,
        storagePath,
    };
}
// ── Callable: getSignedVoiceUrl ─────────────────────────────────────────
exports.getSignedVoiceUrl = (0, https_1.onCall)(async (request) => {
    const uid = verifyCaller(request);
    if (!rateLimit(uid)) {
        throw new https_1.HttpsError("resource-exhausted", "Too many requests. Please try again later.");
    }
    const data = (request.data ?? {});
    const chatId = requiredString(data.chatId, "chatId");
    const messageId = requiredString(data.messageId, "messageId");
    // Trusted server-side authorization: verify chat membership via Firestore
    await verifyChatMembership(chatId, uid);
    // Validate message exists and belongs to this chat
    const messageInfo = await validateMessageInChat(chatId, messageId);
    // Additional check: ensure the requesting user is either sender or receiver
    if (uid !== messageInfo.senderId && uid !== messageInfo.receiverId) {
        throw new https_1.HttpsError("permission-denied", "You are not authorized to access this message.");
    }
    // Never sign an arbitrary path parsed out of a download URL: it must be the
    // canonical object written by VoiceMessageFirebase.uploadAudio.
    const expectedPath = `voice_messages/${chatId}/${messageInfo.senderId}/${messageId}.m4a`;
    if (messageInfo.storagePath !== expectedPath) {
        logger.warn("Rejected non-canonical voice storage path", {
            chatId,
            messageId,
            storagePath: messageInfo.storagePath,
        });
        throw new https_1.HttpsError("failed-precondition", "Voice object path is not canonical.");
    }
    // Generate a short-lived signed URL for the Storage object
    const bucket = storage.bucket();
    const file = bucket.file(messageInfo.storagePath);
    const [signedUrl] = await file.getSignedUrl({
        version: "v4",
        action: "read",
        expires: Date.now() + SIGNED_URL_EXPIRATION_MINUTES * 60 * 1000,
    });
    logger.info("Signed voice URL generated", {
        chatId,
        messageId,
        uid,
        expiresInMinutes: SIGNED_URL_EXPIRATION_MINUTES,
    });
    return {
        signedUrl,
        expiresInMinutes: SIGNED_URL_EXPIRATION_MINUTES,
        chatId,
        messageId,
        senderId: messageInfo.senderId,
    };
});
// ── Callable: sendVoiceNotification (replaces fcm_requests trigger) ─────
exports.sendVoiceNotification = (0, https_1.onCall)(async (request) => {
    const uid = verifyCaller(request);
    if (!rateLimit(uid)) {
        throw new https_1.HttpsError("resource-exhausted", "Too many requests. Please try again later.");
    }
    const data = (request.data ?? {});
    const receiverUid = requiredString(data.receiverUid, "receiverUid");
    const chatId = requiredString(data.chatId, "chatId");
    const senderName = typeof data.senderName === "string"
        ? data.senderName.slice(0, 80)
        : undefined;
    // Verify caller is a participant in the chat
    await verifyChatMembership(chatId, uid);
    // Verify receiver is also a participant
    await verifyChatMembership(chatId, receiverUid);
    // Get receiver's FCM token from trusted Firestore
    const receiverDoc = await db.collection("users").doc(receiverUid).get();
    if (!receiverDoc.exists) {
        throw new https_1.HttpsError("not-found", "Receiver user not found.");
    }
    const fcmToken = receiverDoc.data()?.fcmToken;
    if (!fcmToken) {
        throw new https_1.HttpsError("failed-precondition", "Receiver has no FCM token registered.");
    }
    // Get sender profile for notification
    const senderDoc = await db.collection("users").doc(uid).get();
    const senderDisplayName = senderDoc.data()?.displayName || uid;
    // Send via Firebase Admin SDK (trusted server-side). Only a resolved send
    // is reported as delivered; invalid tokens are removed for future refresh.
    try {
        await admin.messaging().send({
            token: fcmToken,
            data: {
                type: "voice_message",
                chatId,
                senderId: uid,
                senderName: senderName || senderDisplayName,
            },
            android: { priority: "high" },
            notification: {
                title: `Voice message from ${senderName || senderDisplayName}`,
                body: "🎙 Tap to listen",
            },
        });
    }
    catch (error) {
        if (isInvalidTokenError(error)) {
            await clearInvalidFcmToken(receiverUid, fcmToken);
            throw new https_1.HttpsError("failed-precondition", "Receiver FCM token is no longer valid.");
        }
        throw new https_1.HttpsError("internal", "Voice notification could not be delivered.");
    }
    logger.info("Voice notification sent", { receiverUid, chatId, senderId: uid });
    return { status: "sent", receiverUid, chatId };
});
const GUARDIAN_INVITE_TTL_MS = 7 * 24 * 60 * 60 * 1000;
/** Resolve both client phone formats (10-digit local and +91 E.164). */
function phoneCandidates(raw) {
    const digits = raw.replace(/\D/g, "");
    const candidates = new Set([raw.trim(), digits]);
    if (digits.length === 10) {
        candidates.add(digits);
        candidates.add(`+91${digits}`);
        candidates.add(`919${digits.slice(1)}`);
    }
    else if (digits.length === 12 && digits.startsWith("91")) {
        candidates.add(digits.slice(2));
        candidates.add(`+${digits}`);
    }
    else if (digits.length === 13 && digits.startsWith("919")) {
        candidates.add(digits.slice(2));
        candidates.add(`+${digits}`);
    }
    return [...candidates].filter((value) => value.length > 0);
}
/** Look up an account by phone, but only trust Firebase Auth's verified phone. */
async function findVerifiedAccountByPhone(rawPhone) {
    const candidates = phoneCandidates(rawPhone);
    const snapshot = await db.collection("users").where("phoneNumber", "in", candidates).limit(5).get();
    const expected = normalizePhone(rawPhone);
    for (const doc of snapshot.docs) {
        try {
            const authUser = await admin.auth().getUser(doc.id);
            if (authUser.phoneNumber && normalizePhone(authUser.phoneNumber) === expected) {
                return doc.id;
            }
        }
        catch (_) {
            // Ignore accounts that do not exist in Auth.
        }
    }
    return null;
}
/** Look up an account by email, but only trust Firebase Auth's verified email. */
async function findVerifiedAccountByEmail(rawEmail) {
    const email = normalizeEmail(rawEmail);
    const snapshot = await db.collection("users").where("email", "==", email).limit(5).get();
    for (const doc of snapshot.docs) {
        try {
            const authUser = await admin.auth().getUser(doc.id);
            if (authUser.emailVerified && normalizeEmail(authUser.email || "") === email) {
                return doc.id;
            }
        }
        catch (_) {
            // Ignore accounts that do not exist in Auth.
        }
    }
    return null;
}
async function deliverToUserFcmToken(uid, message) {
    const doc = await db.collection("users").doc(uid).get();
    const token = doc.data()?.fcmToken;
    if (typeof token !== "string" || token.length === 0) {
        return { delivered: false, failed: true, invalidToken: false };
    }
    try {
        await admin.messaging().send({ token, android: { priority: "high" }, ...message });
        return { delivered: true, failed: false, invalidToken: false };
    }
    catch (error) {
        const invalidToken = isInvalidTokenError(error);
        if (invalidToken)
            await clearInvalidFcmToken(uid, token);
        logger.warn("FCM delivery failed", { uid, invalidToken, error });
        return { delivered: false, failed: true, invalidToken };
    }
}
/**
 * Owner asks a specific account to become a guardian. No link is created here:
 * the candidate must explicitly accept the invite before `guardianUids` changes.
 */
exports.inviteGuardian = (0, https_1.onCall)(async (request) => {
    const ownerUid = verifyCaller(request);
    if (!rateLimit(ownerUid)) {
        throw new https_1.HttpsError("resource-exhausted", "Too many requests. Please try again later.");
    }
    const data = request.data ?? {};
    let guardianUid = typeof data.guardianUid === "string" ? data.guardianUid.trim() : "";
    const phone = typeof data.phoneNumber === "string" ? data.phoneNumber.trim() : "";
    const email = typeof data.email === "string" ? data.email.trim() : "";
    if (!guardianUid && phone)
        guardianUid = await findVerifiedAccountByPhone(phone) || "";
    if (!guardianUid && email)
        guardianUid = await findVerifiedAccountByEmail(email) || "";
    if (!guardianUid) {
        throw new https_1.HttpsError("not-found", "No verified account matches that phone or email.");
    }
    if (guardianUid === ownerUid) {
        throw new https_1.HttpsError("invalid-argument", "You cannot link your own account as a guardian.");
    }
    const pending = await db.collection("guardianInvites")
        .where("ownerUid", "==", ownerUid)
        .where("guardianUid", "==", guardianUid)
        .where("status", "==", "PENDING")
        .limit(1)
        .get();
    const existing = pending.docs[0];
    const inviteId = existing?.id ?? db.collection("guardianInvites").doc().id;
    if (!existing) {
        await db.collection("guardianInvites").doc(inviteId).set({
            inviteId,
            ownerUid,
            guardianUid,
            phoneNumber: phone || null,
            email: email ? normalizeEmail(email) : null,
            displayName: typeof data.displayName === "string" ? data.displayName.slice(0, 80) : "",
            status: "PENDING",
            createdAt: admin.firestore.FieldValue.serverTimestamp(),
            expiresAt: Date.now() + GUARDIAN_INVITE_TTL_MS,
        });
    }
    const delivery = await deliverToUserFcmToken(guardianUid, {
        data: { type: "guardian_invite", inviteId, ownerUid },
        notification: {
            title: "Guardian request",
            body: "Someone invited you to be a NightAgent guardian. Open the app to accept or decline.",
        },
    });
    logger.info("Guardian invitation created", {
        ownerUid,
        guardianUid,
        delivered: delivery.delivered,
    });
    return {
        status: "invited",
        inviteId,
        guardianUid,
        notified: delivery.delivered,
        deliveryStatus: delivery.delivered ? "delivered" : "not_delivered",
    };
});
/** Candidate guardian accepts or declines; only acceptance creates a link. */
exports.respondGuardianInvite = (0, https_1.onCall)(async (request) => {
    const uid = verifyCaller(request);
    if (!rateLimit(uid)) {
        throw new https_1.HttpsError("resource-exhausted", "Too many requests. Please try again later.");
    }
    const inviteId = requiredString(request.data?.inviteId, "inviteId");
    const accept = request.data?.accept === true;
    const inviteRef = db.collection("guardianInvites").doc(inviteId);
    const inviteSnap = await inviteRef.get();
    if (!inviteSnap.exists)
        throw new https_1.HttpsError("not-found", "Invitation not found.");
    const invite = inviteSnap.data() || {};
    if (invite.guardianUid !== uid) {
        throw new https_1.HttpsError("permission-denied", "This invitation belongs to another account.");
    }
    if (invite.status !== "PENDING") {
        throw new https_1.HttpsError("failed-precondition", "This invitation has already been answered.");
    }
    const expiresAt = typeof invite.expiresAt === "number" ? invite.expiresAt : 0;
    if (expiresAt > 0 && expiresAt < Date.now()) {
        await inviteRef.update({ status: "EXPIRED" });
        throw new https_1.HttpsError("failed-precondition", "This invitation has expired.");
    }
    await db.runTransaction(async (transaction) => {
        const fresh = await transaction.get(inviteRef);
        if (!fresh.exists || fresh.data()?.status !== "PENDING") {
            throw new https_1.HttpsError("failed-precondition", "This invitation has already been answered.");
        }
        transaction.update(inviteRef, {
            status: accept ? "ACCEPTED" : "REJECTED",
            respondedAt: admin.firestore.FieldValue.serverTimestamp(),
        });
        if (accept) {
            transaction.update(db.collection("users").doc(invite.ownerUid), {
                guardianUids: admin.firestore.FieldValue.arrayUnion(uid),
                guardianLinksUpdatedAt: admin.firestore.FieldValue.serverTimestamp(),
            });
            transaction.update(db.collection("users").doc(uid), {
                guardianOfUids: admin.firestore.FieldValue.arrayUnion(invite.ownerUid),
                guardianLinksUpdatedAt: admin.firestore.FieldValue.serverTimestamp(),
            });
        }
    });
    return { status: accept ? "accepted" : "rejected", inviteId, ownerUid: invite.ownerUid };
});
/** Owner or linked guardian revokes a relationship; both sides are updated. */
exports.unlinkGuardian = (0, https_1.onCall)(async (request) => {
    const uid = verifyCaller(request);
    if (!rateLimit(uid)) {
        throw new https_1.HttpsError("resource-exhausted", "Too many requests. Please try again later.");
    }
    const ownerUid = requiredString(request.data?.ownerUid, "ownerUid");
    const guardianUid = requiredString(request.data?.guardianUid, "guardianUid");
    if (uid !== ownerUid && uid !== guardianUid) {
        throw new https_1.HttpsError("permission-denied", "Only the owner or linked guardian may unlink.");
    }
    const ownerDoc = await db.collection("users").doc(ownerUid).get();
    const linked = asStringArray(ownerDoc.data()?.guardianUids);
    if (uid === guardianUid && !linked.includes(uid)) {
        throw new https_1.HttpsError("permission-denied", "This account is not linked to that owner.");
    }
    await db.runTransaction(async (transaction) => {
        transaction.update(db.collection("users").doc(ownerUid), {
            guardianUids: admin.firestore.FieldValue.arrayRemove(guardianUid),
            guardianLinksUpdatedAt: admin.firestore.FieldValue.serverTimestamp(),
        });
        transaction.update(db.collection("users").doc(guardianUid), {
            guardianOfUids: admin.firestore.FieldValue.arrayRemove(ownerUid),
            guardianLinksUpdatedAt: admin.firestore.FieldValue.serverTimestamp(),
        });
        const invites = await transaction.get(db.collection("guardianInvites")
            .where("ownerUid", "==", ownerUid)
            .where("guardianUid", "==", guardianUid)
            .where("status", "==", "ACCEPTED"));
        invites.docs.forEach((doc) => transaction.update(doc.ref, { status: "REVOKED" }));
    });
    return { status: "unlinked", ownerUid, guardianUid };
});
/**
 * Rebuilds `users/{owner}.guardianUids` from accepted invitations only.
 * The supplied phone list is retained for older clients but is never trusted.
 */
exports.syncGuardianLinks = (0, https_1.onCall)(async (request) => {
    const uid = verifyCaller(request);
    if (!rateLimit(uid)) {
        throw new https_1.HttpsError("resource-exhausted", "Too many requests. Please try again later.");
    }
    const rawPhones = request.data?.phoneNumbers;
    if (rawPhones !== undefined && (!Array.isArray(rawPhones) || rawPhones.length > 5)) {
        throw new https_1.HttpsError("invalid-argument", "phoneNumbers must be an array of at most 5 numbers.");
    }
    const accepted = await db.collection("guardianInvites")
        .where("ownerUid", "==", uid)
        .where("status", "==", "ACCEPTED")
        .get();
    const phoneToUid = {};
    const linkedUids = [];
    accepted.docs.forEach((doc) => {
        const data = doc.data();
        const guardianUid = data?.guardianUid;
        if (typeof guardianUid !== "string" || !guardianUid || guardianUid === uid)
            return;
        if (!linkedUids.includes(guardianUid))
            linkedUids.push(guardianUid);
        if (typeof data.phoneNumber === "string" && data.phoneNumber) {
            phoneToUid[data.phoneNumber] = guardianUid;
        }
        if (typeof data.email === "string" && data.email) {
            phoneToUid[data.email] = guardianUid;
        }
    });
    await db.collection("users").doc(uid).set({ guardianUids: linkedUids, guardianLinksUpdatedAt: admin.firestore.FieldValue.serverTimestamp() }, { merge: true });
    logger.info("Guardian links synchronized from accepted invitations", { uid, linked: linkedUids.length });
    return { status: "linked", phoneToUid, guardianUids: linkedUids };
});
/** Incoming invitations for the signed-in candidate. */
exports.getGuardianInvitations = (0, https_1.onCall)(async (request) => {
    const uid = verifyCaller(request);
    const snapshot = await db.collection("guardianInvites")
        .where("guardianUid", "==", uid)
        .where("status", "==", "PENDING")
        .get();
    return {
        status: "ok",
        invitations: snapshot.docs.map((doc) => ({ inviteId: doc.id, ...doc.data() })),
    };
});
// ── Emergency workflow functions ───────────────────────────────────────
async function verifySosOwnership(sosSessionId, ownerUid) {
    const sosDoc = await db
        .collection("sos_alerts")
        .doc(ownerUid)
        .collection("sessions")
        .doc(sosSessionId)
        .get();
    if (!sosDoc.exists) {
        throw new https_1.HttpsError("not-found", `SOS session ${sosSessionId} not found for user ${ownerUid}.`);
    }
}
async function linkedGuardianUids(ownerUid) {
    const ownerDoc = await db.collection("users").doc(ownerUid).get();
    const raw = ownerDoc.data()?.guardianUids;
    if (!Array.isArray(raw))
        return [];
    return [...new Set(raw.filter((value) => typeof value === "string" && value.length > 0 && value !== ownerUid))];
}
/** Only the owner or an owner-linked guardian may act on an emergency. */
async function verifyEmergencyActor(ownerUid, actorUid) {
    if (actorUid === ownerUid)
        return;
    const linked = await linkedGuardianUids(ownerUid);
    if (!linked.includes(actorUid)) {
        throw new https_1.HttpsError("permission-denied", "Only linked guardians may act on this emergency.");
    }
}
exports.sendEmergencyNotification = (0, https_1.onCall)(async (request) => {
    const uid = verifyCaller(request);
    if (!rateLimit(uid)) {
        throw new https_1.HttpsError("resource-exhausted", "Too many requests. Please try again later.");
    }
    const data = (request.data ?? {});
    const sosSessionId = requiredString(data.sosSessionId, "sosSessionId");
    await verifySosOwnership(sosSessionId, uid);
    // Server-side idempotency: a retried trigger must not fan out a second push.
    const requestRef = db.collection("emergency_notification_requests").doc(sosSessionId);
    const prior = await requestRef.get();
    if (prior.exists) {
        const priorData = prior.data() || {};
        if (priorData.ownerUid !== uid) {
            throw new https_1.HttpsError("permission-denied", "This SOS session belongs to another account.");
        }
        if (data.retry !== true || priorData.status === "delivered") {
            return priorData.response ?? {
                status: priorData.status === "delivered" ? "delivered" : "not_delivered",
                sosSessionId,
                deliveredGuardianIds: asStringArray(priorData.deliveredGuardianIds),
                failedGuardianIds: asStringArray(priorData.failedGuardianIds),
            };
        }
    }
    else {
        await requestRef.set({
            ownerUid: uid,
            sosSessionId,
            status: "pending",
            createdAt: admin.firestore.FieldValue.serverTimestamp(),
        });
    }
    const guardianUidsRaw = data.guardianUids;
    // Legacy callers omit guardianUids. Write an audit record, but never report
    // that as confirmed guardian delivery.
    if (!Array.isArray(guardianUidsRaw) || guardianUidsRaw.length === 0) {
        await db.collection("emergency_notifications").add({
            uid,
            sosSessionId,
            action: "requested",
            timestamp: admin.firestore.FieldValue.serverTimestamp(),
            hasLocation: false,
        });
        logger.info("Emergency notification requested without recipients", { uid, sosSessionId });
        const response = { status: "emergency_notification_requested", sosSessionId };
        await requestRef.set({ ...response, completedAt: admin.firestore.FieldValue.serverTimestamp() }, { merge: true });
        return response;
    }
    const guardianUids = [...new Set(guardianUidsRaw.filter((value) => typeof value === "string" && value.length > 0 && value !== uid))].slice(0, 5);
    if (guardianUids.length === 0) {
        throw new https_1.HttpsError("invalid-argument", "No valid guardian UIDs supplied.");
    }
    // Authorization: only accounts explicitly linked by the session owner may
    // receive a location-bearing emergency notification.
    const linkedUids = await linkedGuardianUids(uid);
    const unlinked = guardianUids.filter((candidate) => !linkedUids.includes(candidate));
    if (unlinked.length > 0 || linkedUids.length === 0) {
        throw new https_1.HttpsError("permission-denied", "Guardian accounts must be linked by the owner before notification.");
    }
    const latitude = data.latitude;
    const longitude = data.longitude;
    const hasLocation = typeof latitude === "number" && typeof longitude === "number" &&
        latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
    const deliveredGuardianIds = [];
    const failedGuardianIds = [];
    for (const guardianUid of guardianUids) {
        const delivery = await deliverToUserFcmToken(guardianUid, {
            data: {
                type: "sos_alert",
                sosSessionId,
                ownerUid: uid,
                latitude: hasLocation ? String(latitude) : "",
                longitude: hasLocation ? String(longitude) : "",
            },
            notification: {
                title: "Emergency alert from a NightAgent contact",
                body: hasLocation
                    ? "Tap to view their shared location."
                    : "They have requested help.",
            },
        });
        if (delivery.delivered) {
            deliveredGuardianIds.push(guardianUid);
            await db.collection("emergency_notifications").add({
                uid,
                guardianUid,
                sosSessionId,
                action: "delivered",
                timestamp: admin.firestore.FieldValue.serverTimestamp(),
                hasLocation,
            });
        }
        else {
            failedGuardianIds.push(guardianUid);
            await db.collection("emergency_notifications").add({
                uid,
                guardianUid,
                sosSessionId,
                action: delivery.invalidToken ? "token_invalid" : "delivery_failed",
                timestamp: admin.firestore.FieldValue.serverTimestamp(),
                hasLocation,
            });
        }
    }
    const response = {
        status: deliveredGuardianIds.length > 0 ? "delivered" : "not_delivered",
        sosSessionId,
        deliveredGuardianIds,
        failedGuardianIds,
    };
    await requestRef.set({ ...response, completedAt: admin.firestore.FieldValue.serverTimestamp() }, { merge: true });
    logger.info("Emergency notification delivery result", {
        uid,
        sosSessionId,
        delivered: deliveredGuardianIds.length,
        failed: failedGuardianIds.length,
    });
    return response;
});
exports.acknowledgeEmergency = (0, https_1.onCall)(async (request) => {
    const uid = verifyCaller(request);
    if (!rateLimit(uid)) {
        throw new https_1.HttpsError("resource-exhausted", "Too many requests. Please try again later.");
    }
    const data = (request.data ?? {});
    const sosSessionId = requiredString(data.sosSessionId, "sosSessionId");
    // Guardians supply ownerUid; the owner may omit it.
    const ownerUid = typeof data.ownerUid === "string" && data.ownerUid.length > 0
        ? data.ownerUid
        : uid;
    await verifySosOwnership(sosSessionId, ownerUid);
    await verifyEmergencyActor(ownerUid, uid);
    const acknowledgedBy = uid;
    const timestamp = admin.firestore.FieldValue.serverTimestamp();
    // Write the canonical session fields the owner app listens for, plus the
    // server-only audit record.
    await db
        .collection("sos_alerts")
        .doc(ownerUid)
        .collection("sessions")
        .doc(sosSessionId)
        .set({
        state: "ACKNOWLEDGED",
        acknowledgedBy,
        acknowledgedAt: timestamp,
        guardianUid: uid === ownerUid ? null : uid,
    }, { merge: true });
    await db.collection("emergency_acknowledgements").add({
        sosSessionId,
        ownerUid,
        acknowledgedBy,
        guardianUid: uid === ownerUid ? null : uid,
        timestamp,
    });
    return { status: "acknowledged", sosSessionId, ownerUid, acknowledgedBy };
});
exports.escalateEmergency = (0, https_1.onCall)(async (request) => {
    const uid = verifyCaller(request);
    if (!rateLimit(uid)) {
        throw new https_1.HttpsError("resource-exhausted", "Too many requests. Please try again later.");
    }
    const data = (request.data ?? {});
    const sosSessionId = requiredString(data.sosSessionId, "sosSessionId");
    const ownerUid = typeof data.ownerUid === "string" && data.ownerUid.length > 0
        ? data.ownerUid
        : uid;
    await verifySosOwnership(sosSessionId, ownerUid);
    await verifyEmergencyActor(ownerUid, uid);
    const timestamp = admin.firestore.FieldValue.serverTimestamp();
    await db
        .collection("sos_alerts")
        .doc(ownerUid)
        .collection("sessions")
        .doc(sosSessionId)
        .set({ escalated: true, escalatedBy: uid, escalatedAt: timestamp }, { merge: true });
    await db.collection("emergency_escalations").add({
        sosSessionId,
        ownerUid,
        escalatedBy: uid,
        timestamp,
    });
    return { status: "escalated", sosSessionId, ownerUid };
});
exports.resolveEmergency = (0, https_1.onCall)(async (request) => {
    const uid = verifyCaller(request);
    if (!rateLimit(uid)) {
        throw new https_1.HttpsError("resource-exhausted", "Too many requests. Please try again later.");
    }
    const data = (request.data ?? {});
    const sosSessionId = requiredString(data.sosSessionId, "sosSessionId");
    await verifySosOwnership(sosSessionId, uid);
    await db
        .collection("sos_alerts")
        .doc(uid)
        .collection("sessions")
        .doc(sosSessionId)
        .set({
        resolved: true,
        state: "RESOLVED",
        resolvedAt: admin.firestore.FieldValue.serverTimestamp(),
    }, { merge: true });
    return { status: "resolved", sosSessionId };
});
//# sourceMappingURL=index.js.map