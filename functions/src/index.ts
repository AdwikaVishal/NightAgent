import { onCall } from "firebase-functions/v2/https";
import * as logger from "firebase-functions/logger";
import * as admin from "firebase-admin";

admin.initializeApp();

const db = admin.firestore();
const storage = admin.storage();

// Security constants
const SIGNED_URL_EXPIRATION_MINUTES = 15;
const MAX_CALLS_PER_MINUTE = 10;

// Simple in-memory rate limiter (per IP)
// In production, use Redis or a distributed store.
const rateLimitMap = new Map<string, { count: number; resetTime: number }>();

function rateLimit(ip: string): boolean {
  const now = Date.now();
  const entry = rateLimitMap.get(ip);
  if (!entry || now > entry.resetTime) {
    rateLimitMap.set(ip, { count: 1, resetTime: now + 60_000 });
    return true;
  }
  if (entry.count >= MAX_CALLS_PER_MINUTE) {
    return false;
  }
  entry.count++;
  return true;
}

// ── Helper: verify caller authentication ─────────────────────────────────

async function verifyCaller(context: any): Promise<string> {
  if (!context.auth) {
    throw new Error("User must be authenticated to request voice download.");
  }
  return context.auth.uid;
}

// ── Helper: validate chat membership (trusted server-side) ─────────────

async function verifyChatMembership(chatId: string, uid: string): Promise<void> {
  const chatDoc = await db.collection("chats").doc(chatId).get();
  if (!chatDoc.exists) {
    throw new Error(`Chat ${chatId} does not exist.`);
  }
  const participants = chatDoc.data()?.participants as string[] | undefined;
  if (!participants || !participants.includes(uid)) {
    throw new Error("You are not a participant in this chat.");
  }
}

// ── Helper: validate message belongs to chat ───────────────────────────

async function validateMessageInChat(chatId: string, messageId: string): Promise<{
  senderId: string;
  receiverId: string;
  audioUrl: string;
  storagePath: string;
}> {
  const msgDoc = await db
    .collection("chats")
    .doc(chatId)
    .collection("messages")
    .doc(messageId)
    .get();

  if (!msgDoc.exists) {
    throw new Error(`Message ${messageId} not found in chat ${chatId}.`);
  }

  const data = msgDoc.data();
  const audioUrl = data?.audioUrl as string | undefined;
  if (!audioUrl) {
    throw new Error("Message has no audio URL.");
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
      throw new Error("Invalid download URL format");
    }
    const encodedPath = pathname.slice(oIndex + 3);
    storagePath = decodeURIComponent(encodedPath);
  } catch {
    throw new Error("Unable to parse audio URL storage path.");
  }

  return {
    senderId: data?.senderId as string,
    receiverId: data?.receiverId as string,
    audioUrl,
    storagePath,
  };
}

// ── Callable: getSignedVoiceUrl ─────────────────────────────────────────

export const getSignedVoiceUrl = onCall(
  async (request: any) => {
    // Rate limiting based on caller IP
    const ip = request.rawRequest?.ip ?? "unknown";
    if (!rateLimit(ip)) {
      throw new Error("Too many requests. Please try again later.");
    }

    const uid = await verifyCaller(request.auth);

    const chatId = request.data?.chatId as string | undefined;
    const messageId = request.data?.messageId as string | undefined;

    if (!chatId || typeof chatId !== "string" || chatId.length === 0) {
      throw new Error("chatId is required.");
    }
    if (!messageId || typeof messageId !== "string" || messageId.length === 0) {
      throw new Error("messageId is required.");
    }

    // Trusted server-side authorization: verify chat membership via Firestore
    await verifyChatMembership(chatId, uid);

    // Validate message exists and belongs to this chat
    const messageInfo = await validateMessageInChat(chatId, messageId);

    // Additional check: ensure the requesting user is either sender or receiver
    if (uid !== messageInfo.senderId && uid !== messageInfo.receiverId) {
      throw new Error("You are not authorized to access this message.");
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
  }
);

// ── Callable: sendVoiceNotification (replaces fcm_requests trigger) ─────

export const sendVoiceNotification = onCall(
  async (request: any) => {
    const uid = await verifyCaller(request.auth);

    const receiverUid = request.data?.receiverUid as string | undefined;
    const chatId = request.data?.chatId as string | undefined;
    const senderName = request.data?.senderName as string | undefined;

    if (!receiverUid || typeof receiverUid !== "string") {
      throw new Error("receiverUid is required.");
    }
    if (!chatId || typeof chatId !== "string") {
      throw new Error("chatId is required.");
    }

    // Verify caller is a participant in the chat
    await verifyChatMembership(chatId, uid);

    // Verify receiver is also a participant
    await verifyChatMembership(chatId, receiverUid);

    // Get receiver's FCM token from trusted Firestore
    const receiverDoc = await db.collection("users").doc(receiverUid).get();
    if (!receiverDoc.exists) {
      throw new Error("Receiver user not found.");
    }
    const fcmToken = receiverDoc.data()?.fcmToken as string | undefined;
    if (!fcmToken) {
      throw new Error("Receiver has no FCM token registered.");
    }

    // Get sender profile for notification
    const senderDoc = await db.collection("users").doc(uid).get();
    const senderDisplayName = senderDoc.data()?.displayName as string | undefined || uid;

    // Send via Firebase Admin SDK (trusted server-side)
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

    logger.info("Voice notification sent", { receiverUid, chatId, senderId: uid });

    return { status: "sent", receiverUid, chatId };
  }
);

// ── Emergency workflow functions ───────────────────────────────────────

async function verifySosOwnership(sosSessionId: string, uid: string): Promise<void> {
  const sosDoc = await db
    .collection("sos_alerts")
    .doc(uid)
    .collection("sessions")
    .doc(sosSessionId)
    .get();
  if (!sosDoc.exists) {
    throw new Error(`SOS session ${sosSessionId} not found for user ${uid}.`);
  }
}

export const sendEmergencyNotification = onCall(
  async (request: any) => {
    const uid = await verifyCaller(request.auth);
    const sosSessionId = request.data?.sosSessionId as string | undefined;
    if (!sosSessionId) {
      throw new Error("sosSessionId is required.");
    }
    await verifySosOwnership(sosSessionId, uid);

    const userDoc = await db.collection("users").doc(uid).get();
    const fcmToken = userDoc.data()?.fcmToken as string | undefined;

    await db.collection("emergency_notifications").add({
      uid,
      sosSessionId,
      action: "sent",
      timestamp: admin.firestore.FieldValue.serverTimestamp(),
      senderFcmToken: fcmToken ?? null,
    });

    logger.info("Emergency notification sent", { uid, sosSessionId });

    return { status: "emergency_notification_sent", sosSessionId };
  }
);

export const acknowledgeEmergency = onCall(
  async (request: any) => {
    const uid = await verifyCaller(request.auth);
    const sosSessionId = request.data?.sosSessionId as string | undefined;
    const guardianUid = request.data?.guardianUid as string | undefined;
    if (!sosSessionId) {
      throw new Error("sosSessionId is required.");
    }
    await verifySosOwnership(sosSessionId, uid);

    await db.collection("emergency_acknowledgements").add({
      sosSessionId,
      acknowledgedBy: uid,
      guardianUid: guardianUid ?? null,
      timestamp: admin.firestore.FieldValue.serverTimestamp(),
    });

    return { status: "acknowledged", sosSessionId };
  }
);

export const escalateEmergency = onCall(
  async (request: any) => {
    const uid = await verifyCaller(request.auth);
    const sosSessionId = request.data?.sosSessionId as string | undefined;
    if (!sosSessionId) {
      throw new Error("sosSessionId is required.");
    }
    await verifySosOwnership(sosSessionId, uid);

    await db.collection("emergency_escalations").add({
      sosSessionId,
      escalatedBy: uid,
      timestamp: admin.firestore.FieldValue.serverTimestamp(),
    });

    return { status: "escalated", sosSessionId };
  }
);

export const resolveEmergency = onCall(
  async (request: any) => {
    const uid = await verifyCaller(request.auth);
    const sosSessionId = request.data?.sosSessionId as string | undefined;
    if (!sosSessionId) {
      throw new Error("sosSessionId is required.");
    }
    await verifySosOwnership(sosSessionId, uid);

    await db
      .collection("sos_alerts")
      .doc(uid)
      .collection("sessions")
      .doc(sosSessionId)
      .update({
        resolved: true,
        resolvedAt: admin.firestore.FieldValue.serverTimestamp(),
      });

    return { status: "resolved", sosSessionId };
  }
);
