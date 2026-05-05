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
  const [loadingGraph, setLoadingGraph] = useState(true);
  const [loadingEntity, setLoadingEntity] = useState(false);


  const fetchGraphState = useCallback(async (signal) => {
    if (!graphState) setLoadingGraph(true);

    let success = true;

    try {
      const [stateRes, metricsRes] = await Promise.allSettled([
        fetch(`${API_BASE}/graph/state`, { signal }),
        fetch(`${API_BASE}/graph/metrics`, { signal })
      ]);

      // HANDLE graph state result
      if (stateRes.status === 'fulfilled') {
        const res = stateRes.value;
        if(res.ok){
          setGraphState(await res.json());
        }else{
          setError(`Graph state API fetch failed: ${res.status}`);
          success = false;
        }
      }else{
        setError('Graph state request failed');
        success = false;
      }


      // HANDLE graph metrics result
      if(metricsRes.status === 'fulfilled') {
        const res = metricsRes.value;
        if(res.ok){
          setMetrics(await res.json());
        }else{
          setError(`Graph metrics API fetch failed: ${res.status}`);
          success = false;
        }
      }else{
        setError('Graph metrics request failed');
        success = false;
      }      
      
      if(success){
        setError(null);
      }
      setLastUpdate(new Date().toLocaleTimeString());
      
    } catch (err) {
      if (err.name === 'AbortError') return;
      setError(err.message ?? 'Cannot reach graph API. Is Quarkus running?');
    }
    finally{
      setLoadingGraph(false);
    }
  }, [graphState]);

  const fetchEntityState = useCallback(async (entityId,signal) => {
    if(!entityData) setLoadingEntity(true);
    try {
      const res = await fetch(`${API_BASE}/graph/entity/${entityId}`, {signal});
      if (res.ok) {
        setEntityData(await res.json());
      }
    } catch (err) {
      if(err.name === 'AbortError') return;
      setEntityData({ error: 'Failed to fetch entity' });
    }
    finally{
      setLoadingEntity(false);
    }
  }, [entityData]);

  useEffect(() => {
    const controller = new AbortController();

    fetchGraphState(controller.signal);
    return () => {
      controller.abort();
    };
  }, [fetchGraphState]);

  useEffect(() => {
    if (!autoRefresh) return;

    const id = setInterval(() => {
      const controller = new AbortController();
      fetchGraphState(controller.signal);
    }, refreshInterval * 1000);
    
    return () => {
      clearInterval(id);
    };
  }, [autoRefresh, refreshInterval, fetchGraphState]);

  useEffect(() => {
    if (!selectedEntity) return;

    const controller = new AbortController();

    fetchEntityState(selectedEntity, controller.signal);

    return () => {
      controller.abort();
    };
  }, [selectedEntity, fetchEntityState]);

  const handleResetGraph = async () => {
    if (!window.confirm('Reset graph state?')) return;
    try {
      await fetch(`${API_BASE}/graph/reset`, { method: 'POST' });

      const controller = new AbortController();
      fetchGraphState(controller.signal);
      
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
          <button className="btn btn-refresh" onClick={()=>{
            const controller = new AbortController();
            fetchGraphState(controller.signal);
          }}>
            Refresh Now
          </button>
          <button className="btn btn-danger" onClick={handleResetGraph}>
            Reset Graph
          </button>
          {lastUpdate && <span className="last-update">Updated: {lastUpdate}</span>}
        </div>
      </header>

      {error && <div className="error-bar">{error}</div>}

      {loadingGraph ? (
        <div className='loading'>
          <img className='loader' src="/loading.gif" alt="Loading..." />
        </div>
        ) : (
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
              loading={loadingEntity}
            />
          </div>
        </div>)
      }
    </div>
  );
}

export default App;
