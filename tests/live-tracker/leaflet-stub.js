// Browser-smoke-only Leaflet stand-in: records render calls without tiles/network.
(function () {
  const state = { polylines: [], markerUpdates: [], mapCentres: [] };
  const marker = () => ({
    opacity: 0,
    setLatLng(latlng) { state.markerUpdates.push({ type: 'marker', latlng }); return this; },
    setOpacity(value) { this.opacity = value; return this; },
    bindPopup() { return this; },
    addTo() { return this; },
  });
  const map = () => ({
    setView(latlng) { state.mapCentres.push(latlng); return this; },
    getZoom() { return 2; },
    getBounds() { return { contains() { return false; } }; },
    removeLayer() { return this; },
  });
  window.L = {
    map,
    marker,
    tileLayer() { return { addTo() { return this; } }; },
    polyline(coords) { state.polylines.push(coords); return { addTo() { return this; } }; },
    latLng(latlng) { return latlng; },
  };
  window.__leafletStub = state;
})();
