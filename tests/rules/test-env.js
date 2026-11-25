/*
 * Shared harness for @firebase/rules-unit-testing v4.
 * The emulator connection is discovered from FIRESTORE_EMULATOR_HOST and
 * FIREBASE_STORAGE_EMULATOR_HOST when run through `firebase emulators:exec`.
 */
const fs = require("fs");
const path = require("path");
const { initializeTestEnvironment } = require("@firebase/rules-unit-testing");
const firebase = require("firebase/compat/app");
require("firebase/compat/firestore");
require("firebase/compat/storage");

const PROJECT_ID = process.env.GCLOUD_PROJECT || "nightagent-35d38";
let testEnv = null;

function readRules(file) {
  return fs.readFileSync(path.join(__dirname, "../../", file), "utf8");
}

async function init({ firestoreRules, storageRules } = {}) {
  const config = { projectId: PROJECT_ID };
  if (firestoreRules) config.firestore = { rules: readRules(firestoreRules) };
  if (storageRules) config.storage = { rules: readRules(storageRules) };
  testEnv = await initializeTestEnvironment(config);
  return testEnv;
}

function requireEnv() {
  if (!testEnv) throw new Error("Rules test environment is not initialized");
  return testEnv;
}

function authedApp(uid) {
  return requireEnv().authenticatedContext(uid);
}

function unauthedApp() {
  return requireEnv().unauthenticatedContext();
}

async function withAdmin(work) {
  return requireEnv().withSecurityRulesDisabled(async (context) => work(context.firestore()));
}

async function clearFirestore() {
  return requireEnv().clearFirestore();
}

async function clearStorage() {
  return requireEnv().clearStorage();
}

async function cleanup() {
  if (testEnv) await testEnv.cleanup();
  testEnv = null;
}

module.exports = {
  PROJECT_ID,
  init,
  authedApp,
  unauthedApp,
  withAdmin,
  clearFirestore,
  clearStorage,
  cleanup,
  FieldValue: firebase.firestore.FieldValue,
};
