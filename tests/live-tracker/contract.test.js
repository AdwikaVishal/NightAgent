const contract = require('../../live-tracker/contract.js');

describe('Live tracker Firestore synchronization contract', () => {
  test('active journey query uses canonical journeys collection with owner/status filter', () => {
    const spec = contract.activeJourneyQuery('user-123');
    expect(spec.collection).toBe('journeys');
    expect(spec.where).toEqual([
      ['userId', '==', 'user-123'],
      ['status', '==', 'ACTIVE'],
    ]);
    expect(spec.orderBy).toEqual(['createdAt', 'desc']);
    expect(spec.limit).toBe(1);
    expect(JSON.stringify(spec)).not.toContain('sos_alerts');
  });

  test('latest location query reads only the newest canonical location document', () => {
    const spec = contract.latestLocationQuery('journey-1', 'user-123');
    expect(spec.path).toBe('journeys/journey-1/locations');
    expect(spec.orderBy).toEqual(['timestamp', 'desc']);
    expect(spec.limit).toBe(1);
    expect(spec.readsFullHistory).toBe(false);
  });

  test('safe_walks is treated as a legacy fallback, never as the primary SOS path', () => {
    const spec = contract.legacyLocationQuery('user-123');
    expect(spec.path).toBe('safe_walks/user-123/locations');
    expect(spec.fallbackOnly).toBe(true);
    expect(spec.limit).toBe(1);
  });

  test('timestamps accept both legacy seconds and canonical milliseconds', () => {
    const seconds = 1700000000;
    const millis = 1700000000000;
    expect(contract.toMillis(seconds)).toBe(millis);
    expect(contract.toMillis(millis)).toBe(millis);
    expect(contract.toMillis('2024-01-01T00:00:00Z')).toBe(Date.parse('2024-01-01T00:00:00Z'));
  });

  test('stale detection flags old and unknown fixes', () => {
    const now = 1700000030000; // 30 seconds after the fresh fix
    expect(contract.isStale(1700000000000, now, contract.STALE_AFTER_MS)).toBe(false);
    expect(contract.isStale(1699000000000, now, contract.STALE_AFTER_MS)).toBe(true);
    expect(contract.isStale(null, now, contract.STALE_AFTER_MS)).toBe(true);
  });

  test('only valid coordinates are rendered', () => {
    expect(contract.isValidPoint({ latitude: 12.5, longitude: 77.1 })).toBe(true);
    expect(contract.isValidPoint({ latitude: 91, longitude: 0 })).toBe(false);
    expect(contract.isValidPoint({ latitude: 0, longitude: 0 })).toBe(false);
    expect(contract.isValidPoint(null)).toBe(false);
    expect(contract.isValidPoint({ latitude: 0.000001, longitude: 0 })).toBe(true);
  });

  test('target uid comes from query parameter or /track/{uid} path', () => {
    expect(contract.targetFromPath('/track/abc', '')).toBe('abc');
    expect(contract.targetFromPath('/', '?userId=xyz')).toBe('xyz');
    expect(contract.targetFromPath('/index.html', '')).toBe('');
  });
});
