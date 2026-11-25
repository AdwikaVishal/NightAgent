/*
 * Pure, dependency-free helpers shared by the Live Tracker UI and its tests.
 * Loaded as a classic script in the browser and required by Node/Jest.
 */
(function (root) {
  'use strict';

  function toMillis(value) {
    if (typeof value === 'number') return value < 1e12 ? value * 1000 : value;
    if (typeof value === 'string') return Date.parse(value);
    return 0;
  }

  function targetFromPath(pathname, search) {
    var params = new URLSearchParams(search || '');
    var fromQuery = (params.get('userId') || '').trim();
    if (fromQuery) return fromQuery;
    var segments = (pathname || '').split('/').filter(Boolean);
    var last = segments.pop() || '';
    if (['index.html', 'track', 'tracker'].indexOf(last) !== -1) return '';
    try { return decodeURIComponent(last); } catch (e) { return last; }
  }

  function isValidPoint(point) {
    if (!point || !Number.isFinite(point.latitude) || !Number.isFinite(point.longitude)) return false;
    if (point.latitude < -90 || point.latitude > 90) return false;
    if (point.longitude < -180 || point.longitude > 180) return false;
    // Android LocationTracker rejects the null island; the browser must not
    // render it as a live fix or re-centre the map on an uninitialised point.
    return !(point.latitude === 0 && point.longitude === 0);
  }

  function isStale(timestamp, now, maxAgeMs) {
    var stamp = toMillis(timestamp);
    if (!stamp) return true;
    return now - stamp > maxAgeMs;
  }

  // Canonical contract written by FirestoreJourneyRepository. The web viewer
  // never reads sos_alerts; safe_walks is only a legacy fallback.
  function activeJourneyQuery(userId) {
    return {
      collection: 'journeys',
      where: [['userId', '==', userId], ['status', '==', 'ACTIVE']],
      orderBy: ['createdAt', 'desc'],
      limit: 1
    };
  }

  function latestLocationQuery(journeyId, ownerUid) {
    return {
      path: 'journeys/' + journeyId + '/locations',
      ownerUid: ownerUid,
      orderBy: ['timestamp', 'desc'],
      limit: 1,
      readsFullHistory: false
    };
  }

  function legacyLocationQuery(userId) {
    return {
      path: 'safe_walks/' + userId + '/locations',
      orderBy: ['timestamp', 'desc'],
      limit: 1,
      fallbackOnly: true
    };
  }

  var api = {
    toMillis: toMillis,
    targetFromPath: targetFromPath,
    isValidPoint: isValidPoint,
    isStale: isStale,
    activeJourneyQuery: activeJourneyQuery,
    latestLocationQuery: latestLocationQuery,
    legacyLocationQuery: legacyLocationQuery,
    STALE_AFTER_MS: 90000
  };

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  } else {
    root.NightAgentTrackerContract = api;
  }
})(typeof globalThis !== 'undefined' ? globalThis : this);
