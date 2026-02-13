# Integration Guide — Fraud Detection Monorepo

How Partner A's graph engine and Partner B's analytics layer work together.

---

## 1. System Overview

```
                                    ┌─────────────────────────────────────────────────────────┐
                                    │                    Docker Compose                        │
                                    │                    (fraud-network)                       │
                                    │                                                         │
  ┌──────────────┐  POST /data-handler  ┌─────────────────────────────┐                       │
  │ Lit Frontend  │────────────────────►│   Quarkus Backend (:8080)   │                       │
  │   (:3000)     │                     │                             │  POST /predict        │
  └──────────────┘                      │  DataReceiverServlet        │─────────────┐         │
                                        │       │                     │             │         │
                                        │       ▼                     │             ▼         │
                                        │  FraudDetectionHandler      │    ┌──────────────┐   │
                                        │       │                     │    │ Python ML    │   │
                                        │       ▼                     │    │ Flask (:5000)│   │
                                        │  GraphAnalyticsService      │    │ sklearn model│   │
                                        │   ├─ TruthGraph             │    └──────────────┘   │
                                        │   ├─ HypothesisGraph        │         (:5005 host)  │
                                        │   └─ GraphMathEngine        │                       │
                                        │       │                     │                       │
                                        │       ▼                     │                       │
                                        │  GraphStateEndpoint         │                       │
                                        │   /graph/state              │                       │
                                        │   /graph/metrics            │                       │
  ┌──────────────┐  GET /graph/*        │   /graph/entity/{id}        │                       │
  │ React Graph  │◄────────────────────│   /graph/reset              │                       │
  │ Visualizer   │                      └────────────┬────────────────┘                       │
  │   (:3001)    │                                   │                                        │
  └──────────────┘                                   │ JDBC                                   │
                                                     ▼                                        │
                                              ┌──────────────┐                                │
                                              │   MariaDB     │                                │
                                              │   (:3306)     │                                │
                                              │  transactions │                                │
                                              │  fraud_logs   │                                │
                                              └──────┬───────┘                                │
                                                     │                                        │
                                    ─ ─ ─ ─ ─ ─ ─ ─ ┼ ─ ─ ─ ─ ─ Partner B adds ─ ─ ─ ─ ─   │
                                                     │                                        │
                                              ┌──────▼───────┐    ┌──────────────┐            │
                                              │   Neo4j       │───►│ Spark Batch  │            │
                                              │  (persistent  │    │  Analytics   │            │
                                              │   graph DB)   │    └──────────────┘            │
                                              └──────────────┘                                │
                                    └─────────────────────────────────────────────────────────┘
```

---

## 2. Data Flow

### 2a. Real-Time Transaction Processing (Partner A)

A transaction flows through the system in this order:

1. **User submits** a transaction via the Lit Frontend at `http://localhost:3000`.
2. **Lit Frontend** sends `POST /data-handler` to the Quarkus backend.
3. **DataReceiverServlet** parses the JSON into a `TransactionData` object with 9 fields:
   `ccNum`, `amt`, `zip`, `lat`, `lon`, `cityPop`, `unixTime`, `merchLat`, `merchLon`.
4. **FraudDetectionHandler** runs fraud checks:
   - Retrieves the last transaction for this card from MariaDB.
   - **Impossible travel check**: if distance > 500 km and speed > 300 km/h → flag as fraud.
   - Calls the **Python ML service** (`POST http://python-quarkus-service:5000/predict`) for a prediction.
   - Logs fraud to `fraud_logs` if detected.
5. **GraphAnalyticsService** processes the transaction through the graph pipeline:
   - `TruthGraph.addTransaction(txn)` — adds/updates nodes and edges.
   - `HypothesisGraph.predictorStep(txn)` — speculates future edges.
   - `HypothesisGraph.correctorStep(truth)` — prunes wrong speculations.
   - `GraphMathEngine.fraudHotspotScore(truth, sourceId)` — scores neighborhood risk.
6. **MariaDB** stores the transaction in the `transactions` table and any fraud in `fraud_logs`.
7. The servlet returns a combined response with fraud verdict + graph metrics.

### 2b. Persistent Graph Sync (Partner B — future)

Partner B adds a persistence layer that syncs data out of MariaDB:

1. **MariaDB → Neo4j**: A sync service reads `transactions` and `fraud_logs` and writes them as a persistent graph in Neo4j.
2. **Neo4j → Spark**: Batch analytics jobs (e.g., community detection, pattern mining) run periodically via Spark, reading from Neo4j.
3. Results can be written back to MariaDB or exposed via new REST endpoints.

### 2c. Visualization

The **React Graph Visualizer** at `http://localhost:3001` polls the Quarkus REST API:

- `GET /graph/state` — renders the full graph topology (truth + hypothesis).
- `GET /graph/metrics` — powers the analytics dashboard (PageRank, density, top risk).
- `GET /graph/entity/{id}` — drives the entity inspector panel.

Auto-refresh runs every 1–10 seconds (configurable in the UI).

---

## 3. Service Map

| Service               | Container Name         | Port (host:container) | Owner     | Responsibility                                    |
|-----------------------|------------------------|-----------------------|-----------|---------------------------------------------------|
| MariaDB               | fraud-db               | 3306:3306             | Shared    | Persistent storage (transactions, fraud_logs)     |
| Quarkus Backend       | quarkus-service        | 8080:8080             | Partner A | REST API, fraud detection, in-memory graph engine |
| Python ML Service     | python-quarkus-service | 5005:5000             | Shared    | sklearn-based fraud prediction model              |
| Lit Frontend          | fraud-frontend         | 3000:3000             | Shared    | Transaction submission form                       |
| React Graph Visualizer| graph-visualizer       | 3001:3001             | Partner A | Real-time graph state dashboard                   |
| Neo4j (future)        | —                      | TBD                   | Partner B | Persistent graph database                         |
| Spark (future)        | —                      | TBD                   | Partner B | Batch graph analytics                             |

---

## 4. Shared Interfaces

### REST API Endpoints (Quarkus `:8080`)

| Method | Path                   | Description                                   | Example Response (truncated)                        |
|--------|------------------------|-----------------------------------------------|-----------------------------------------------------|
| POST   | `/data-handler`        | Submit a transaction for fraud detection       | `{"fraud": false, "graphNodeCount": 15, ...}`       |
| GET    | `/graph/state`         | Full graph state (truth + hypothesis summary)  | `{"truthGraph": {...}, "hypothesisGraph": {...}}`   |
| GET    | `/graph/metrics`       | Analytics: density, PageRank, top risk nodes   | `{"density": 0.01, "pageRank": [...], ...}`         |
| GET    | `/graph/entity/{id}`   | Single entity detail with state vector         | `{"entityId": 123, "stateVector": {...}, ...}`      |
| POST   | `/graph/reset`         | Reset all graph state (development only)       | `{"status": "graph reset"}`                         |

#### POST `/data-handler` — Request Body

```json
{
  "cc_num": 1234567890,
  "amt": 125.50,
  "zip": "90210",
  "lat": 34.0901,
  "long": -118.4065,
  "city_pop": 21000,
  "unix_time": 1700000000,
  "merch_lat": 34.0522,
  "merch_long": -118.2437
}
```

#### GET `/graph/entity/{id}` — Response

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

### MariaDB Tables

Both partners read from and write to the same database (`fraud_detection`):

```sql
-- Stores every transaction processed by the system
CREATE TABLE transactions (
    id INT AUTO_INCREMENT PRIMARY KEY,
    cc_num BIGINT NOT NULL,         -- credit card number (entity ID)
    amt DECIMAL(10,2) NOT NULL,     -- transaction amount
    zip VARCHAR(10),                -- zip code
    lat FLOAT NOT NULL,             -- user latitude
    `long` FLOAT NOT NULL,          -- user longitude
    city_pop INT NOT NULL,          -- city population
    unix_time BIGINT NOT NULL,      -- epoch timestamp
    merch_lat FLOAT NOT NULL,       -- merchant latitude
    merch_long FLOAT NOT NULL,      -- merchant longitude
    INDEX idx_cc_num (cc_num),
    INDEX idx_unix_time (unix_time)
);

-- Logs flagged fraud events with the full transaction JSON
CREATE TABLE fraud_logs (
    id INT AUTO_INCREMENT PRIMARY KEY,
    cc_num BIGINT NOT NULL,
    reason TEXT NOT NULL,            -- e.g., "Impossible travel detected"
    detected_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    transaction_data JSON,          -- full transaction payload
    INDEX idx_cc_num (cc_num)
);
```

**Credentials** (development only):
- Host: `mariadb` (container name) or `localhost` from host
- Port: `3306`
- Database: `fraud_detection`
- User: `fraud_user` / Password: `fraud_pass`
- Root: `root` / Password: `root`

---

## 5. How to Build From Ground Up

### Prerequisites

- **Docker** and **Docker Compose** (required)
- **Git** (required)
- **Java 21** (optional — only needed for local Quarkus development outside Docker)
- **Node.js 18+** (optional — only needed for local frontend development)

### Step 1: Clone and Start

```bash
git clone <repo-url>
cd fraudmonorepo
docker compose up
```

This builds and starts all 5 services. First build takes a few minutes (Maven + npm installs).

### Step 2: Access Each Service

| Service             | URL                                      |
|---------------------|------------------------------------------|
| Lit Frontend        | http://localhost:3000                     |
| Graph Visualizer    | http://localhost:3001                     |
| Quarkus REST API    | http://localhost:8080                     |
| Graph State         | http://localhost:8080/graph/state         |
| Graph Metrics       | http://localhost:8080/graph/metrics       |
| Python ML (direct)  | http://localhost:5005/predict             |
| MariaDB             | `mysql -h 127.0.0.1 -P 3306 -u fraud_user -pfraud_pass fraud_detection` |

### Step 3: Submit a Test Transaction

Open `http://localhost:3000`, fill in the form, and submit. Or use curl:

```bash
curl -X POST http://localhost:8080/data-handler \
  -H "Content-Type: application/json" \
  -d '{
    "cc_num": 1234567890,
    "amt": 50.00,
    "zip": "10001",
    "lat": 40.7128,
    "long": -74.0060,
    "city_pop": 8336817,
    "unix_time": 1700000000,
    "merch_lat": 40.7580,
    "merch_long": -73.9855
  }'
```

### Step 4: Watch the Graph Build

Open `http://localhost:3001` to see the graph visualizer update in real time. Submit multiple transactions to see nodes and edges appear.

### Step 5: API Key Setup (Geocoding)

The Lit Frontend uses geocoding APIs to convert addresses to coordinates. To enable this:

1. Get an API key from [OpenCage](https://opencagedata.com/) or Google Maps.
2. Configure it in `frontendservices/src/main.js` where the geocoding URL is built.

> **Note**: Geocoding is optional. You can submit transactions directly with lat/lon coordinates via the API.

### Step 6: Rebuild After Changes

```bash
# Rebuild a specific service
docker compose build quarkus-service
docker compose up -d quarkus-service

# Rebuild everything
docker compose up --build
```

---

## 6. Workflow Separation

### Partner A Owns

```
backendservices/quarkus-service/src/main/java/org/frauddetection/graph/
  ├── EntityNode.java            # 5-dimensional state vector node
  ├── GraphEdge.java             # Time-decayed directed edge
  ├── TruthGraph.java            # Confirmed transaction graph
  ├── HypothesisGraph.java       # Speculative predictor-corrector layer
  ├── GraphMathEngine.java       # PageRank, projections, hotspot scores
  ├── GraphAnalyticsService.java # CDI orchestrator (singleton)
  ├── GraphStateEndpoint.java    # REST API at /graph/*
  ├── MLIntegrationInterface.java
  ├── EnsembleMLPlugin.java
  ├── GNNPluginInterface.java
  └── GANPluginInterface.java

graph-visualizer/
  ├── src/App.js                 # Main layout, API calls, auto-refresh
  ├── src/GraphCanvas.js         # Force-directed canvas rendering
  ├── src/MetricsPanel.js        # Left sidebar: density, PageRank
  ├── src/EntityPanel.js         # Right sidebar: entity inspector
  └── src/App.css                # Dark theme styling
```

### Partner B Owns

Any new analytics services added to the stack:

- Neo4j integration service (MariaDB → Neo4j sync)
- Spark batch analytics bridge
- New containers and their Dockerfiles
- Any new REST endpoints for batch analytics results

### Shared (Coordinate Changes)

| Resource         | How to Coordinate                                                    |
|------------------|----------------------------------------------------------------------|
| `compose.yaml`   | Both partners add services here. Communicate before editing.         |
| `init-db.sql`    | New tables need agreement. Don't modify existing columns.            |
| `docs/`          | Both partners contribute documentation.                              |
| Python ML service| Changes affect both partners. Discuss API contract changes first.    |
| Lit Frontend     | Shared UI — coordinate if adding new features.                       |

### Ground Rules

1. **Don't break the shared API contract.** The POST `/data-handler` request shape and the GET `/graph/*` response shapes are stable interfaces.
2. **Add new services to `compose.yaml`** on the same `fraud-network` so they can reach MariaDB and Quarkus by container name.
3. **Use feature branches.** Merge to main only after both partners verify `docker compose up` still works.
4. **Document new endpoints** in this guide when adding them.
