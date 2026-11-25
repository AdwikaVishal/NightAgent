// Browser-smoke-only Firebase SDK stand-in. It is never shipped or deployed.
const listeners = [];
const authListeners = [];
let authUser = null;

function emitAuth() {
  authListeners.slice().forEach((cb) => cb(authUser));
}

export function initializeApp() { return { mock: true }; }
export function getAuth() { return { mock: true }; }
export function getFirestore() { return { mock: true }; }

export function onAuthStateChanged(_auth, callback) {
  authListeners.push(callback);
  callback(authUser);
  return () => {
    const i = authListeners.indexOf(callback);
    if (i >= 0) authListeners.splice(i, 1);
  };
}

export async function signInWithEmailAndPassword(_auth, email) {
  authUser = { uid: `uid-${email || 'guardian'}`, email };
  emitAuth();
  return { user: authUser };
}

export async function signOut() {
  authUser = null;
  emitAuth();
}

export function collection(_db, ...parts) {
  return { kind: 'collection', path: parts.join('/') };
}

export function doc(_db, ...parts) {
  return { kind: 'doc', path: parts.join('/') };
}

export function where(field, op, value) { return { type: 'where', field, op, value }; }
export function orderBy(field, direction) { return { type: 'orderBy', field, direction }; }
export function limit(value) { return { type: 'limit', value }; }

export function query(source, ...constraints) {
  return { ...source, constraints };
}

export function onSnapshot(source, onNext, onError) {
  const listener = { source, onNext, onError, active: true };
  listeners.push(listener);
  return () => { listener.active = false; };
}

// Test-only controls.
window.__mockFirebase = {
  listeners,
  get authListenerCount() { return authListeners.length; },
  setUser(user) {
    authUser = user;
    emitAuth();
  },
  emitByPath(pathFragment, snapshot, error) {
    const matches = listeners.filter((l) => l.active
      && (l.source.path === pathFragment || l.source.path.endsWith('/' + pathFragment)));
    matches.forEach((l) => {
      if (error) l.onError(error);
      else l.onNext(snapshot);
    });
    return matches.length;
  },
  reset() {
    listeners.length = 0;
    authListeners.length = 0;
    authUser = null;
  },
};
window.__mockFirebaseReady = true;
