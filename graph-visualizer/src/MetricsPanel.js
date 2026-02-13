import React from 'react';

function MetricsPanel({ metrics, graphState }) {
  if (!metrics) {
    return (
      <div>
        <div className="panel-title">Graph Metrics</div>
        <div className="metric-card">
          <div className="metric-label">Status</div>
          <div className="metric-value">Waiting...</div>
        </div>
      </div>
    );
  }

  const density = metrics.density || 0;
  const densityClass = density > 0.5 ? 'risk-high' : density > 0.1 ? 'risk-medium' : 'risk-low';

  return (
    <div>
      <div className="panel-title">Graph Metrics</div>

      <div className="metric-card">
        <div className="metric-label">Nodes</div>
        <div className="metric-value">{metrics.nodeCount || 0}</div>
      </div>

      <div className="metric-card">
        <div className="metric-label">Edges</div>
        <div className="metric-value">{metrics.edgeCount || 0}</div>
      </div>

      <div className="metric-card">
        <div className="metric-label">Graph Density</div>
        <div className={`metric-value ${densityClass}`}>{density.toFixed(4)}</div>
      </div>

      <div className="metric-card">
        <div className="metric-label">Transactions</div>
        <div className="metric-value">{metrics.transactionsProcessed || 0}</div>
      </div>

      {graphState && graphState.hypothesisGraph && (
        <div className="metric-card">
          <div className="metric-label">Speculative Edges</div>
          <div className="metric-value">
            {graphState.hypothesisGraph.speculativeEdgeCount || 0}
          </div>
        </div>
      )}

      {metrics.topRiskNodes && metrics.topRiskNodes.length > 0 && (
        <div className="metric-card">
          <div className="metric-label">Top Risk Entities</div>
          <ul className="risk-node-list">
            {metrics.topRiskNodes.slice(0, 5).map((node, i) => (
              <li key={i} className="risk-node-item">
                <span>#{String(node.entityId).slice(-6)}</span>
                <span className="risk-high">{node.riskMagnitude.toFixed(2)}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {metrics.topPageRank && metrics.topPageRank.length > 0 && (
        <div className="metric-card">
          <div className="metric-label">PageRank Leaders</div>
          <ul className="risk-node-list">
            {metrics.topPageRank.slice(0, 5).map((node, i) => (
              <li key={i} className="risk-node-item">
                <span>#{String(node.entityId).slice(-6)}</span>
                <span>{node.pageRank.toFixed(4)}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

export default MetricsPanel;
