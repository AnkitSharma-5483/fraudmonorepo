# React Graph Visualizer — Developer Documentation

Real-time graph state visualization for the fraud detection system.

---

## 1. Overview

The Graph Visualizer is a React app that renders the in-memory fraud graph in real time. It polls the Quarkus backend's `/graph/*` REST endpoints and displays:

- **Force-directed graph layout** — nodes (accounts/merchants) and edges (transaction flows) drawn on a canvas.
- **Risk-colored nodes** — green (low risk), orange (medium), red (high).
- **Analytics dashboard** — graph density, node/edge counts, PageRank rankings, top risk entities.
- **Entity inspector** — click any node or search by ID to see its full state vector.

Access it at **http://localhost:3001** after running `docker compose up`.

---

## 2. File Structure

```
graph-visualizer/
├── public/
│   └── index.html
├── src/
│   ├── App.js            # Main layout, state management, API polling
│   ├── App.css            # Dark theme styling for all panels
│   ├── GraphCanvas.js     # Canvas-based force-directed graph rendering
│   ├── MetricsPanel.js    # Left sidebar — graph-wide analytics
│   ├── EntityPanel.js     # Right sidebar — entity detail inspector
│   └── index.js           # React entry point
├── Dockerfile
├── package.json
└── nginx.conf
```

### App.js — Main Layout & API Calls

The root component manages all application state and API communication.

**State variables:**
- `graphState` — full graph topology (nodes, edges, hypothesis data)
- `metrics` — density, PageRank, top risk nodes
- `selectedEntity` / `entityData` — currently inspected entity
- `autoRefresh` — polling interval (1–10 seconds)
- `error` / `lastUpdate` — error state and timestamp

**Layout:** Three-panel design:
```
┌──────────────┬──────────────────────┬──────────────┐
│ MetricsPanel │     GraphCanvas      │ EntityPanel  │
│  (left bar)  │   (center canvas)    │ (right bar)  │
└──────────────┴──────────────────────┴──────────────┘
```

**API calls** (all to the Quarkus backend at port 8080):

```javascript
// Fetch full graph state
fetch(`${API_BASE}/graph/state`)

// Fetch analytics metrics
fetch(`${API_BASE}/graph/metrics`)

// Fetch single entity details
fetch(`${API_BASE}/graph/entity/${entityId}`)

// Reset graph state (dev only)
fetch(`${API_BASE}/graph/reset`, { method: 'POST' })
```

### GraphCanvas.js — Force-Directed Graph Rendering

Renders the graph on an HTML5 `<canvas>` element using a custom force-directed layout algorithm.

**Physics simulation:**
- **Node repulsion**: `force = 2000 / distance²` (prevents overlap)
- **Edge attraction**: `force = (distance - 150) * 0.01` (keeps connected nodes close)
- **Center gravity**: Factor of `0.001` (prevents drift)
- **Damping**: `0.9` per frame (stabilizes layout)

**Visual encoding:**
- Node color by risk magnitude: **green** (< 5), **orange** (5–10), **red** (> 10)
- Node size: `8 + min(riskMagnitude, 20)` pixels
- Edge style: **solid** line = truth graph, **dashed** line = speculative (hypothesis)
- Selected node: **blue stroke** (3px border)

**Interaction:**
- Click detection uses ray-circle collision testing
- Clicking a node triggers entity lookup via the parent `App.js`

### MetricsPanel.js — Left Sidebar

Displays graph-wide analytics:

- **Node count** and **Edge count**
- **Graph density** — color-coded: red (> 0.5 = high), orange (> 0.1 = medium), green (low)
- **Transactions processed** — total count
- **Top 5 Risk Nodes** — sorted by risk magnitude
- **Top 5 PageRank Entities** — sorted by structural importance

### EntityPanel.js — Right Sidebar

Entity inspector with search and visualization:

- **Search bar** — enter an entity ID to look up any node
- **State vector visualization** — 5 horizontal bars representing each dimension:

  | Dimension   | Color  |
  |-------------|--------|
  | Txn Volume  | Blue   |
  | Avg Amount  | Green  |
  | Risk Score  | Red    |
  | Velocity    | Orange |
  | Diversity   | Purple |

- **Projected state** — unit-sphere projection values
- **Risk magnitude** — color-coded: red (> 10), orange (> 5), green (< 5)

### App.css — Dark Theme

All panels use a dark color scheme. Key design choices:
- Dark background with light text for readability
- Color-coded risk indicators consistent across all panels
- Responsive panel widths with fixed sidebars

---

## 3. How to Run

### With Docker (recommended)

From the repository root:

```bash
docker compose up
```

The visualizer is available at **http://localhost:3001**.

> **Note**: The visualizer needs the Quarkus backend running on port 8080 to fetch data. Make sure `quarkus-service` is up.

### Local Development

For faster iteration without Docker:

```bash
cd graph-visualizer
npm install
npm start
```

This starts the React dev server with hot reload. By default it proxies API requests to `http://localhost:8080`.

> **Prerequisite**: Node.js 18+ and npm.

### Verify It Works

1. Start all services: `docker compose up`
2. Open `http://localhost:3000` (Lit Frontend) and submit a few transactions.
3. Open `http://localhost:3001` (Graph Visualizer) — you should see nodes and edges appear.
4. Click on a node to inspect its state vector in the right panel.

---

## 4. API Endpoints Used

The visualizer consumes three read-only endpoints from the Quarkus backend:

### GET `/graph/state`

Returns the full graph topology. Used to render nodes and edges on the canvas.

```json
{
  "transactionsProcessed": 42,
  "truthGraph": {
    "nodeCount": 15,
    "edgeCount": 23,
    "density": 0.0109,
    "topRiskNodes": [
      { "entityId": 123, "riskMagnitude": 12.5 }
    ]
  },
  "hypothesisGraph": {
    "speculativeEdgeCount": 8,
    "topEdges": [
      { "source": 123, "target": 456, "confidence": 0.7 }
    ]
  }
}
```

### GET `/graph/metrics`

Returns analytics data. Powers the MetricsPanel sidebar.

```json
{
  "density": 0.0109,
  "nodeCount": 15,
  "edgeCount": 23,
  "topRiskNodes": [
    { "entityId": 123, "riskMagnitude": 12.5 }
  ],
  "pageRank": [
    { "entityId": 456, "score": 0.087 }
  ]
}
```

### GET `/graph/entity/{id}`

Returns detailed state for a single entity. Powers the EntityPanel sidebar.

```json
{
  "entityId": 1234567890,
  "found": true,
  "riskMagnitude": 7.42,
  "stateVector": {
    "txnVolume": 5,
    "avgAmount": 125.50,
    "riskScore": 0.38,
    "velocity": 2.1,
    "diversity": 3
  },
  "projectedState": [0.04, 0.99, 0.003, 0.017, 0.024],
  "neighborCount": 3,
  "fraudHotspotScore": 4.21
}
```

---

## 5. Features

| Feature                     | Description                                                     |
|-----------------------------|-----------------------------------------------------------------|
| **Auto-refresh**            | Polls `/graph/state` and `/graph/metrics` every 1–10 seconds    |
| **Node click inspection**   | Click any node to load its full state vector in the right panel |
| **State vector bars**       | Visual bar chart of all 5 state dimensions per entity           |
| **Risk color coding**       | Nodes colored green/orange/red by risk magnitude                |
| **Truth vs hypothesis**     | Solid edges = confirmed, dashed edges = speculative             |
| **PageRank display**        | Top entities ranked by structural importance                    |
| **Density indicator**       | Graph density with color-coded severity                         |
| **Entity search**           | Search any entity by ID without clicking on the canvas          |
| **Graph legend**            | On-canvas legend explaining node colors and edge styles         |
| **Reset button**            | Clear all graph state (dev only, calls POST `/graph/reset`)     |
