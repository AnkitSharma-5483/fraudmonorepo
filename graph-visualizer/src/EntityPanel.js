import React, { useState } from 'react';

const SV_LABELS = ['Txn Volume', 'Avg Amount', 'Risk Score', 'Velocity', 'Diversity'];
const SV_COLORS = ['#58a6ff', '#3fb950', '#f85149', '#d29922', '#bc8cff'];

function EntityPanel({ entityData, selectedEntity, onSelectEntity, loading  }) {
  const [searchId, setSearchId] = useState('');

  const handleSearch = (e) => {
    e.preventDefault();
    if (searchId.trim()) {
      onSelectEntity(searchId.trim());
    }
  };

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
            <span className="value" style={{
              color: entityData.riskMagnitude > 10 ? '#f85149' :
                     entityData.riskMagnitude > 5 ? '#d29922' : '#3fb950'
            }}>
              {entityData.riskMagnitude.toFixed(4)}
            </span>
          </div>
          <div className="detail-row">
            <span className="label">Hotspot Score</span>
            <span className="value">{entityData.fraudHotspotScore.toFixed(4)}</span>
          </div>
          <div className="detail-row">
            <span className="label">Neighbors</span>
            <span className="value">{entityData.neighborCount}</span>
          </div>

          {entityData.stateVector && (
            <div style={{ marginTop: 16 }}>
              <div className="metric-label" style={{ marginBottom: 8 }}>State Vector</div>
              {SV_LABELS.map((label, i) => {
                const raw = entityData.stateVector[
                  ['txnVolume', 'avgAmount', 'riskScore', 'velocity', 'diversity'][i]
                ] || 0;
                const maxVal = [100, 5000, 5, 100, 50][i];
                const pct = Math.min((raw / maxVal) * 100, 100);
                return (
                  <div key={i} className="state-vector-bar">
                    <span className="sv-label">{label}</span>
                    <div className="sv-bar-container">
                      <div
                        className="sv-bar-fill"
                        style={{ width: `${pct}%`, background: SV_COLORS[i] }}
                      />
                    </div>
                    <span className="sv-value">{raw.toFixed(2)}</span>
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
                [{entityData.projectedState.map(v => v.toFixed(4)).join(', ')}]
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

export default EntityPanel;
