import React, { useRef, useEffect, useCallback } from 'react';

/**
 * Canvas-based graph visualization.
 * Uses a simple force-directed layout computed each frame.
 * Nodes are colored by risk magnitude; edges show transaction flow.
 */
function GraphCanvas({ graphState, metrics, onNodeClick, selectedEntity }) {
  const canvasRef = useRef(null);
  const nodesRef = useRef([]);
  const edgesRef = useRef([]);
  const animRef = useRef(null);

  // Build layout data from metrics
  useEffect(() => {
    if (!metrics || !graphState) return;

    const truthGraph = graphState.truthGraph || {};
    const topRiskNodes = metrics.topRiskNodes || [];
    const topPageRank = metrics.topPageRank || [];

    // Merge all known entity IDs
    const entitySet = new Set();
    topRiskNodes.forEach(n => entitySet.add(String(n.entityId)));
    topPageRank.forEach(n => entitySet.add(String(n.entityId)));

    const riskMap = {};
    topRiskNodes.forEach(n => { riskMap[String(n.entityId)] = n.riskMagnitude; });

    const prMap = {};
    topPageRank.forEach(n => { prMap[String(n.entityId)] = n.pageRank; });

    const existing = {};
    nodesRef.current.forEach(n => { existing[n.id] = n; });

    const newNodes = Array.from(entitySet).map(id => {
      const prev = existing[id];
      return {
        id,
        x: prev ? prev.x : 100 + Math.random() * 600,
        y: prev ? prev.y : 100 + Math.random() * 400,
        vx: prev ? prev.vx : 0,
        vy: prev ? prev.vy : 0,
        risk: riskMap[id] || 0,
        pr: prMap[id] || 0,
      };
    });

    nodesRef.current = newNodes;

    // Build edges from speculative + truth hints
    const newEdges = [];
    const specEdges = (graphState.hypothesisGraph || {}).topEdges || [];
    specEdges.forEach(e => {
      const src = String(e.source);
      const tgt = String(e.target);
      if (entitySet.has(src) && entitySet.has(tgt)) {
        newEdges.push({ source: src, target: tgt, weight: e.confidence || 1, speculative: true });
      }
    });

    edgesRef.current = newEdges;
  }, [graphState, metrics]);

  // Force-directed animation loop
  const animate = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    const W = canvas.width;
    const H = canvas.height;
    const nodes = nodesRef.current;
    const edges = edgesRef.current;
    const cellSize = 100; // tweak based on graph density
    const grid = new Map();

    function getCellKey(x, y) {
      const cx = Math.floor(x / cellSize);
      const cy = Math.floor(y / cellSize);
      return `${cx},${cy}`;
    }


    // Simple force layout step
    const nodeMap = {};
    nodes.forEach(n => { 
      nodeMap[n.id] = n; 
    
      const key = getCellKey(n.x, n.y);
      if (!grid.has(key)) grid.set(key, []);
      grid.get(key).push(n);
    
    });

    // Repulsion
    // for (let i = 0; i < nodes.length; i++) {
    //   for (let j = i + 1; j < nodes.length; j++) {
    //     const a = nodes[i], b = nodes[j];
    //     let dx = a.x - b.x, dy = a.y - b.y;
    //     let dist = Math.sqrt(dx * dx + dy * dy) || 1;
    //     let force = 2000 / (dist * dist);
    //     let fx = (dx / dist) * force;
    //     let fy = (dy / dist) * force;
    //     a.vx += fx; a.vy += fy;
    //     b.vx -= fx; b.vy -= fy;
    //   }
    // }


    // optimized repulsion using spatial grid - only compare nodes in neighboring cells
    const neighborOffsets = [
      [0, 0], [1, 0], [-1, 0],
      [0, 1], [0, -1],
      [1, 1], [-1, -1],
      [1, -1], [-1, 1]
    ];

    grid.forEach((cellNodes, key) => {
      const [cx, cy] = key.split(',').map(Number);

      neighborOffsets.forEach(([dxCell, dyCell]) => {
        const neighborKey = `${cx + dxCell},${cy + dyCell}`;
        const neighborNodes = grid.get(neighborKey);
        if (!neighborNodes) return;

        cellNodes.forEach(a => {
          neighborNodes.forEach(b => {
            if (a === b || a.id > b.id) return;

            let dx = a.x - b.x;
            let dy = a.y - b.y;
            let dist = Math.sqrt(dx * dx + dy * dy) || 1;

            if (dist > 150) return;

            let force = 2000 / (dist * dist);
            let fx = (dx / dist) * force;
            let fy = (dy / dist) * force;

            a.vx += fx;
            a.vy += fy;
          });
        });
      });
    });


    // Attraction along edges
    edges.forEach(e => {
      const a = nodeMap[e.source], b = nodeMap[e.target];
      if (!a || !b) return;
      let dx = b.x - a.x, dy = b.y - a.y;
      let dist = Math.sqrt(dx * dx + dy * dy) || 1;
      let force = (dist - 150) * 0.01;
      let fx = (dx / dist) * force;
      let fy = (dy / dist) * force;
      a.vx += fx; a.vy += fy;
      b.vx -= fx; b.vy -= fy;
    });

    // Center gravity
    nodes.forEach(n => {
      n.vx += (W / 2 - n.x) * 0.001;
      n.vy += (H / 2 - n.y) * 0.001;
    });

    // Apply velocity with damping
    nodes.forEach(n => {
      n.vx *= 0.9;
      n.vy *= 0.9;
      n.x += n.vx;
      n.y += n.vy;
      n.x = Math.max(30, Math.min(W - 30, n.x));
      n.y = Math.max(30, Math.min(H - 30, n.y));
    });

    // Draw
    ctx.clearRect(0, 0, W, H);

    // Draw edges
    edges.forEach(e => {
      const a = nodeMap[e.source], b = nodeMap[e.target];
      if (!a || !b) return;
      ctx.beginPath();
      ctx.moveTo(a.x, a.y);
      ctx.lineTo(b.x, b.y);
      ctx.strokeStyle = e.speculative ? '#d2992244' : '#30363d';
      ctx.lineWidth = e.speculative ? 1 : 2;
      if (e.speculative) ctx.setLineDash([4, 4]);
      else ctx.setLineDash([]);
      ctx.stroke();
      ctx.setLineDash([]);
    });

    // Draw nodes
    nodes.forEach(n => {
      const radius = 8 + Math.min(n.risk, 20);
      const isSelected = String(selectedEntity) === n.id;

      ctx.beginPath();
      ctx.arc(n.x, n.y, radius, 0, Math.PI * 2);

      // Color by risk
      if (n.risk > 10) ctx.fillStyle = '#f85149';
      else if (n.risk > 5) ctx.fillStyle = '#d29922';
      else ctx.fillStyle = '#3fb950';

      ctx.fill();

      if (isSelected) {
        ctx.strokeStyle = '#58a6ff';
        ctx.lineWidth = 3;
        ctx.stroke();
      }

      // Label
      ctx.fillStyle = '#e6edf3';
      ctx.font = '10px monospace';
      ctx.textAlign = 'center';
      ctx.fillText('#' + n.id.slice(-6), n.x, n.y - radius - 4);
    });

    animRef.current = requestAnimationFrame(animate);
  }, [selectedEntity]);

  useEffect(() => {
    animRef.current = requestAnimationFrame(animate);
    return () => {
      if (animRef.current) cancelAnimationFrame(animRef.current);
    };
  }, [animate]);

  // Handle canvas resize
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const resize = () => {
      const parent = canvas.parentElement;
      canvas.width = parent.clientWidth;
      canvas.height = parent.clientHeight;
    };
    resize();
    window.addEventListener('resize', resize);
    return () => window.removeEventListener('resize', resize);
  }, []);

  // Handle click on nodes
  const handleClick = (e) => {
    const rect = canvasRef.current.getBoundingClientRect();
    const mx = e.clientX - rect.left;
    const my = e.clientY - rect.top;

    for (const n of nodesRef.current) {
      const radius = 8 + Math.min(n.risk, 20);
      const dx = mx - n.x, dy = my - n.y;
      if (dx * dx + dy * dy < radius * radius) {
        onNodeClick(n.id);
        return;
      }
    }
  };

  const hasData = nodesRef.current.length > 0;

  return (
    <>
      {!hasData && (
        <div className="empty-state">
          <div>
            <div style={{ fontSize: 32, marginBottom: 8 }}>📊</div>
            <div>No graph data yet.</div>
            <div style={{ fontSize: 12, marginTop: 4 }}>
              Submit transactions through the fraud detection form to populate the graph.
            </div>
          </div>
        </div>
      )}
      <canvas
        ref={canvasRef}
        onClick={handleClick}
        style={{ cursor: 'crosshair', display: hasData ? 'block' : 'none' }}
      />
      {hasData && (
        <div className="graph-legend">
          <div className="legend-item">
            <span className="legend-dot" style={{ background: '#3fb950' }}></span>
            Low risk
          </div>
          <div className="legend-item">
            <span className="legend-dot" style={{ background: '#d29922' }}></span>
            Medium risk
          </div>
          <div className="legend-item">
            <span className="legend-dot" style={{ background: '#f85149' }}></span>
            High risk
          </div>
          <div className="legend-item">
            <span className="legend-dot" style={{ background: '#d29922', opacity: 0.5 }}></span>
            <span style={{ borderBottom: '1px dashed #d29922' }}>Speculative edge</span>
          </div>
        </div>
      )}
    </>
  );
}

export default GraphCanvas;
