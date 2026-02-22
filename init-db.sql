USE fraud_detection;

CREATE TABLE IF NOT EXISTS transactions (
    id INT AUTO_INCREMENT PRIMARY KEY,
    cc_num BIGINT NOT NULL,
    amt DECIMAL(10,2) NOT NULL,
    zip VARCHAR(10),
    lat FLOAT NOT NULL,
    `long` FLOAT NOT NULL,
    city_pop INT NOT NULL,
    unix_time BIGINT NOT NULL,
    merch_lat FLOAT NOT NULL,
    merch_long FLOAT NOT NULL,
    INDEX idx_cc_num (cc_num),
    INDEX idx_unix_time (unix_time)
);

CREATE TABLE IF NOT EXISTS fraud_logs (
    id INT AUTO_INCREMENT PRIMARY KEY,
    cc_num BIGINT NOT NULL,
    reason TEXT NOT NULL,
    detected_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    transaction_data JSON,
    INDEX idx_cc_num (cc_num)
);

-- ============================================================
-- Partner B Staging Tables
-- These tables are populated by Partner A's graph pipeline and
-- consumed by Partner B's Spark jobs and Neo4j import routines.
-- ============================================================

-- GNN node embeddings + extended 17-dim feature vectors.
-- Spark reads this for community detection, drift analysis,
-- and Neo4j imports these as node properties (vector index).
CREATE TABLE IF NOT EXISTS graph_extended_features (
    id INT AUTO_INCREMENT PRIMARY KEY,
    entity_id BIGINT NOT NULL,
    -- Extended state vector (R^17): packed as JSON array of doubles
    feature_vector JSON NOT NULL,
    -- GNN embedding (R^64): packed as JSON array of doubles
    embedding JSON,
    -- Risk scores
    risk_magnitude DOUBLE DEFAULT 0.0,
    extended_risk_magnitude DOUBLE DEFAULT 0.0,
    -- Community membership from label propagation
    community_id BIGINT,
    -- Kalman convergence state
    kalman_gain DOUBLE DEFAULT 0.5,
    -- Timestamps
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    -- One active row per entity; upsert pattern
    UNIQUE INDEX idx_entity_unique (entity_id),
    INDEX idx_community (community_id),
    INDEX idx_risk (extended_risk_magnitude),
    INDEX idx_updated (updated_at)
);

-- GAN-generated scenarios and anomaly candidates for batch evaluation.
-- Spark evaluates impact scores; high-impact scenarios are flagged in Neo4j.
CREATE TABLE IF NOT EXISTS graph_scenarios (
    id INT AUTO_INCREMENT PRIMARY KEY,
    -- Type: 'ANOMALY_CANDIDATE' | 'WHAT_IF_SCENARIO' | 'RING_HYPOTHESIS'
    scenario_type VARCHAR(30) NOT NULL DEFAULT 'ANOMALY_CANDIDATE',
    -- Source and target entities involved
    source_entity_id BIGINT NOT NULL,
    target_entity_id BIGINT,
    -- GAN discriminator score (0-1, higher = more plausible)
    discriminator_score DOUBLE DEFAULT 0.0,
    -- Impact score computed by Partner B's Spark job (initially NULL)
    impact_score DOUBLE,
    -- Full scenario payload (edge details, state vectors, context)
    scenario_json JSON NOT NULL,
    -- Processing state: 'PENDING' | 'EVALUATED' | 'FLAGGED' | 'DISMISSED'
    status VARCHAR(15) NOT NULL DEFAULT 'PENDING',
    -- Which pipeline run generated this
    txn_sequence BIGINT,
    -- Timestamps
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    evaluated_at TIMESTAMP,
    INDEX idx_source (source_entity_id),
    INDEX idx_status (status),
    INDEX idx_score (discriminator_score),
    INDEX idx_txn_seq (txn_sequence),
    INDEX idx_type_status (scenario_type, status)
);

-- Correction delta history for Spark time-series drift analysis.
-- Partner B queries windows of deltas to detect hypothesis degradation.
CREATE TABLE IF NOT EXISTS graph_correction_deltas (
    id INT AUTO_INCREMENT PRIMARY KEY,
    entity_id BIGINT NOT NULL,
    txn_sequence BIGINT NOT NULL,
    -- Bitmask of which of the 17 dims changed
    change_bitmask INT NOT NULL DEFAULT 0,
    -- Compressed delta values (only changed dims, JSON array)
    packed_delta JSON NOT NULL,
    -- L2 norm of the full delta vector
    delta_norm DOUBLE NOT NULL DEFAULT 0.0,
    -- 'MATH' or 'DL' — which speculation source caused this correction
    speculation_source VARCHAR(10) NOT NULL DEFAULT 'MATH',
    -- Kalman gain that was applied
    applied_gain DOUBLE NOT NULL DEFAULT 0.5,
    -- Timestamp
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_entity (entity_id),
    INDEX idx_txn_seq (txn_sequence),
    INDEX idx_source (speculation_source),
    INDEX idx_entity_txn (entity_id, txn_sequence)
);