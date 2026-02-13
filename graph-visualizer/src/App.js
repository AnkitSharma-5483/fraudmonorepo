import React, { useState, useEffect, useCallback } from 'react';
import GraphCanvas from './GraphCanvas';
import MetricsPanel from './MetricsPanel';
import EntityPanel from './EntityPanel';
import './App.css';

const API_BASE = window.location.hostname === 'localhost'
  ? 'http://localhost:8080'
  : 'http://quarkus-service:8080';

function App() {
  const [graphState, setGraphState] = useState(null);
  const [metrics, setMetrics] = useState(null);
  const [selectedEntity, setSelectedEntity] = useState(null);
  const [entityData, setEntityData] = useState(null);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [refreshInterval, setRefreshInterval] = useState(3);
  const [error, setError] = useState(null);
  const [lastUpdate, setLastUpdate] = useState(null);

  const fetchGraphState = useCallback(async () => {
    try {
      const [stateRes, metricsRes] = await Promise.all([
        fetch(`${API_BASE}/graph/state`),
        fetch(`${API_BASE}/graph/metrics`)
      ]);
      if (stateRes.ok && metricsRes.ok) {
        setGraphState(await stateRes.json());
        setMetrics(await metricsRes.json());
        setLastUpdate(new Date().toLocaleTimeString());
        setError(null);
      }
    } catch (err) {
      setError('Cannot reach graph API. Is Quarkus running?');
    }
  }, []);

  const fetchEntityState = useCallback(async (entityId) => {
    try {
      const res = await fetch(`${API_BASE}/graph/entity/${entityId}`);
      if (res.ok) {
        setEntityData(await res.json());
      }
    } catch (err) {
      setEntityData({ error: 'Failed to fetch entity' });
    }
  }, []);

  useEffect(() => {
    fetchGraphState();
  }, [fetchGraphState]);

  useEffect(() => {
    if (!autoRefresh) return;
    const id = setInterval(fetchGraphState, refreshInterval * 1000);
    return () => clearInterval(id);
  }, [autoRefresh, refreshInterval, fetchGraphState]);

  useEffect(() => {
    if (selectedEntity) {
      fetchEntityState(selectedEntity);
    }
  }, [selectedEntity, fetchEntityState]);

  const handleResetGraph = async () => {
    if (!window.confirm('Reset graph state?')) return;
    try {
      await fetch(`${API_BASE}/graph/reset`, { method: 'POST' });
      fetchGraphState();
      setSelectedEntity(null);
      setEntityData(null);
    } catch (err) {
      setError('Failed to reset graph');
    }
  };

  return (
    <div className="app">
      <header className="header">
        <h1>Fraud Graph — Developer Preview</h1>
        <div className="header-controls">
          <label className="toggle-label">
            <input
              type="checkbox"
              checked={autoRefresh}
              onChange={(e) => setAutoRefresh(e.target.checked)}
            />
            Auto-refresh
          </label>
          <select
            value={refreshInterval}
            onChange={(e) => setRefreshInterval(Number(e.target.value))}
          >
            <option value={1}>1s</option>
            <option value={3}>3s</option>
            <option value={5}>5s</option>
            <option value={10}>10s</option>
          </select>
          <button className="btn btn-refresh" onClick={fetchGraphState}>
            Refresh Now
          </button>
          <button className="btn btn-danger" onClick={handleResetGraph}>
            Reset Graph
          </button>
          {lastUpdate && <span className="last-update">Updated: {lastUpdate}</span>}
        </div>
      </header>

      {error && <div className="error-bar">{error}</div>}

      <div className="main-layout">
        <div className="left-panel">
          <MetricsPanel metrics={metrics} graphState={graphState} />
        </div>
        <div className="center-panel">
          <GraphCanvas
            graphState={graphState}
            metrics={metrics}
            onNodeClick={setSelectedEntity}
            selectedEntity={selectedEntity}
          />
        </div>
        <div className="right-panel">
          <EntityPanel
            entityData={entityData}
            selectedEntity={selectedEntity}
            onSelectEntity={setSelectedEntity}
          />
        </div>
      </div>
    </div>
  );
}

export default App;
