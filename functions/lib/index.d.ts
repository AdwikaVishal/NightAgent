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
export declare const sendEmergencyNotification: import("firebase-functions/v2/https").CallableFunction<any, Promise<{
    status: string;
    sosSessionId: string;
}>>;
export declare const acknowledgeEmergency: import("firebase-functions/v2/https").CallableFunction<any, Promise<{
    status: string;
    sosSessionId: string;
}>>;
export declare const escalateEmergency: import("firebase-functions/v2/https").CallableFunction<any, Promise<{
    status: string;
    sosSessionId: string;
}>>;
export declare const resolveEmergency: import("firebase-functions/v2/https").CallableFunction<any, Promise<{
    status: string;
    sosSessionId: string;
}>>;
//# sourceMappingURL=index.d.ts.map