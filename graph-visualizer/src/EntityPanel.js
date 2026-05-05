import React, { useState } from 'react';

const STATE_VECTOR_CONFIG = [
  { key: 'txnVolume', label: 'Txn Volume', max: 100, color: '#58a6ff' },
  { key: 'avgAmount', label: 'Avg Amount', max: 5000, color: '#3fb950' },
  { key: 'riskScore', label: 'Risk Score', max: 5, color: '#f85149' },
  { key: 'velocity', label: 'Velocity', max: 100, color: '#d29922' },
  { key: 'diversity', label: 'Diversity', max: 50, color: '#bc8cff' }
];

function EntityPanel({ entityData, selectedEntity, onSelectEntity, loading  }) {
  const [searchId, setSearchId] = useState('');

  const handleSearch = (e) => {
    e.preventDefault();
    if (searchId.trim()) {
      onSelectEntity(searchId.trim());
      setSearchId('');
    }
  };

  const getRiskColor = (val = 0) =>
    val > 10 ? '#f85149' :
    val > 5 ? '#d29922' : '#3fb950';

  return (
    <div>
      <div className="panel-title">Entity Inspector</div>

      <form onSubmit={handleSearch}>
        <input
          className="entity-search"
          type="text"
          placeholder="Enter entity ID..."
          value={searchId}
          onChange={(e) => setSearchId(e.target.value)}
        />
      </form>

      {loading && (
        <div style={{ marginTop: 10 }}>
          <img src="/loading.gif" alt="Loading..." width={40} />
        </div>
      )}

      {!loading && !entityData && (
        <div style={{ color: '#8b949e', fontSize: 13 }}>
          Click a node or search to inspect.
        </div>
      )}

      {!loading && entityData && !entityData.found && !entityData.error && (
        <div style={{ color: '#d29922', fontSize: 13 }}>
          Entity not found in graph.
        </div>
      )}

      {!loading && entityData && entityData.error && (
        <div style={{ color: '#f85149', fontSize: 13 }}>
          {entityData.error}
        </div>
      )}

      {!loading && entityData && entityData.found && (
        <div className="entity-detail">
          <div className="detail-row">
            <span className="label">Entity ID</span>
            <span className="value">{entityData.entityId}</span>
          </div>
          <div className="detail-row">
            <span className="label">Risk Magnitude</span>
            <span className="value" style={{color: getRiskColor(entityData.riskMagnitude)}}>
              {(entityData.riskMagnitude ?? 0).toFixed(4)}
            </span>
          </div>
          <div className="detail-row">
            <span className="label">Hotspot Score</span>
            <span className="value">{(entityData.fraudHotspotScore ?? 0).toFixed(4)}</span>
          </div>
          <div className="detail-row">
            <span className="label">Neighbors</span>
            <span className="value">{entityData.neighborCount ?? 0}</span>
          </div>

          {entityData.stateVector && (
            <div style={{ marginTop: 16 }}>
              <div className="metric-label" style={{ marginBottom: 8 }}>State Vector</div>
              {STATE_VECTOR_CONFIG.map((item) => {
                const raw = entityData.stateVector?.[item.key] || 0;
                const pct = Math.min((raw / item.max) * 100, 100);
                return (
                  <div key={item.key} className="state-vector-bar">
                    <span className="sv-label">{item.label}</span>
                    <div className="sv-bar-container">
                      <div
                        className="sv-bar-fill"
                        style={{ width: `${pct}%`, background: item.color }}
                      />
                    </div>
                    <span className="sv-value">{(raw ?? 0).toFixed(2)}</span>
                  </div>
                );
              })}
            </div>
          )}

          {entityData.projectedState && (
            <div style={{ marginTop: 12 }}>
              <div className="metric-label" style={{ marginBottom: 4 }}>
                Projected State (unit sphere)
              </div>
              <div style={{ fontSize: 12, color: '#8b949e', fontFamily: 'monospace' }}>
                [{(entityData.projectedState || []).map(v => (v ?? 0).toFixed(4)).join(', ')}]
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

export default EntityPanel;
