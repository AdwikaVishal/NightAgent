export declare const getSignedVoiceUrl: import("firebase-functions/v2/https").CallableFunction<any, Promise<{
    signedUrl: string;
    expiresInMinutes: number;
    chatId: string;
    messageId: string;
    senderId: string;
}>>;
export declare const sendVoiceNotification: import("firebase-functions/v2/https").CallableFunction<any, Promise<{
    status: string;
    receiverUid: string;
    chatId: string;
}>>;
/**
 * Owner asks a specific account to become a guardian. No link is created here:
 * the candidate must explicitly accept the invite before `guardianUids` changes.
 */
export declare const inviteGuardian: import("firebase-functions/v2/https").CallableFunction<any, Promise<{
    status: string;
    inviteId: string;
    guardianUid: any;
    notified: boolean;
    deliveryStatus: string;
}>>;
/** Candidate guardian accepts or declines; only acceptance creates a link. */
export declare const respondGuardianInvite: import("firebase-functions/v2/https").CallableFunction<any, Promise<{
    status: string;
    inviteId: string;
    ownerUid: any;
}>>;
/** Owner or linked guardian revokes a relationship; both sides are updated. */
export declare const unlinkGuardian: import("firebase-functions/v2/https").CallableFunction<any, Promise<{
    status: string;
    ownerUid: string;
    guardianUid: string;
}>>;
/**
 * Rebuilds `users/{owner}.guardianUids` from accepted invitations only.
 * The supplied phone list is retained for older clients but is never trusted.
 */
export declare const syncGuardianLinks: import("firebase-functions/v2/https").CallableFunction<any, Promise<{
    status: string;
    phoneToUid: Record<string, string>;
    guardianUids: string[];
}>>;
/** Incoming invitations for the signed-in candidate. */
export declare const getGuardianInvitations: import("firebase-functions/v2/https").CallableFunction<any, Promise<{
    status: string;
    invitations: {
        inviteId: string;
    }[];
}>>;
export declare const sendEmergencyNotification: import("firebase-functions/v2/https").CallableFunction<any, Promise<any>>;
export declare const acknowledgeEmergency: import("firebase-functions/v2/https").CallableFunction<any, Promise<{
    status: string;
    sosSessionId: string;
    ownerUid: string;
    acknowledgedBy: string;
}>>;
export declare const escalateEmergency: import("firebase-functions/v2/https").CallableFunction<any, Promise<{
    status: string;
    sosSessionId: string;
    ownerUid: string;
}>>;
export declare const resolveEmergency: import("firebase-functions/v2/https").CallableFunction<any, Promise<{
    status: string;
    sosSessionId: string;
}>>;
//# sourceMappingURL=index.d.ts.map