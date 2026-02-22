package org.frauddetection.graph;

import org.frauddetection.model.TransactionData;

/**
 * Graph node representing an account or entity in the fraud detection graph.
 * Maintains a compact fixed-size state vector for memory efficiency.
 * <p>
 * Extended 17-dimensional state vector layout:
 * <pre>
 *   [0]  txnVolume              (base — incremental)
 *   [1]  avgAmount              (base — incremental)
 *   [2]  riskScore              (base — EMA)
 *   [3]  velocity               (base — incremental)
 *   [4]  diversity              (base — incremental)
 *   [5]  predictedNextTxnTime   (temporal — set by ML layer)
 *   [6]  burstProbability       (temporal — set by ML layer)
 *   [7]  periodicityScore       (temporal — set by ML layer)
 *   [8]  timeOfDayRisk          (temporal — set by ML layer)
 *   [9]  isolationScore         (behavioral — set by ML layer)
 *   [10] clusterDeviation       (behavioral — set by ML layer)
 *   [11] spendingDrift          (behavioral — CUSUM, computed here)
 *   [12] merchantAffinityShift  (behavioral — set by ML layer)
 *   [13] communityId            (network — set by NetworkFeatureEngine)
 *   [14] bridgeScore            (network — set by NetworkFeatureEngine)
 *   [15] localClusteringCoeff   (network — set by NetworkFeatureEngine)
 *   [16] kHopRiskPropagation    (network — set by NetworkFeatureEngine)
 * </pre>
 */
public class EntityNode {

    /** Base dimensionality (backward-compatible constant). */
    public static final int STATE_DIM = 5;

    /** Extended dimensionality (full 17-dim vector). */
    public static final int EXTENDED_DIM = ExtendedStateVector.EXTENDED_DIM;

    private final long entityId;

    /** Extended state vector: primitive double[17] for memory efficiency. */
    private final double[] stateVector;

    private long lastUpdateTime;

    // Running counters used to derive base state values incrementally
    private long txnCount;
    private double totalAmount;
    private long firstTxnTime;

    // CUSUM state for spending drift detection (dimension 11)
    private double cusumState = 0.0;
    private static final double CUSUM_DRIFT_ALLOWANCE = 0.5;

    // Inter-transaction interval tracking for temporal features
    private long lastTxnUnixTime = 0;
    private double emaInterTxnInterval = 0.0;
    private static final double TEMPORAL_ALPHA = 0.2;

    // Track unique zip codes seen as a lightweight diversity measure
    private final java.util.Set<String> uniqueZips = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<>());

    // Track recent merchant ids for affinity computation
    private final java.util.Deque<Long> recentMerchants = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private static final int MERCHANT_HISTORY_SIZE = 50;

    public EntityNode(long entityId) {
        this.entityId = entityId;
        this.stateVector = new double[EXTENDED_DIM];
        this.lastUpdateTime = System.currentTimeMillis();
        this.txnCount = 0;
        this.totalAmount = 0.0;
        this.firstTxnTime = 0;
    }

    // ---- Getters ----

    public long getEntityId() {
        return entityId;
    }

    /**
     * Returns a defensive copy of the state vector to prevent external mutation.
     */
    public double[] getStateVector() {
        return stateVector.clone();
    }

    /**
     * Returns a direct reference to the internal state vector.
     * Use only when performance matters and caller guarantees read-only access.
     */
    double[] getStateVectorRef() {
        return stateVector;
    }

    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    // ---- State update ----

    /**
     * Updates the node state incrementally from a new transaction.
     * Base dimensions (0–4) are always recomputed. Partial temporal/behavioral
     * features (5–12) that can be derived incrementally are also updated.
     * Network features (13–16) are set externally by {@link NetworkFeatureEngine}.
     */
    public synchronized void updateState(TransactionData txn) {
        txnCount++;
        totalAmount += txn.getAmt();

        if (firstTxnTime == 0) {
            firstTxnTime = txn.getUnixTime();
        }

        // Track geographic diversity via unique zip codes
        if (txn.getZip() != null) {
            uniqueZips.add(txn.getZip());
        }

        // ---- Base dimensions (0–4) ----

        // [0] txnVolume – total transaction count
        stateVector[0] = txnCount;

        // [1] avgAmount – running average
        stateVector[1] = totalAmount / txnCount;

        // [2] riskScore – heuristic: large amounts relative to average increase risk
        double amountRatio = (stateVector[1] > 0) ? txn.getAmt() / stateVector[1] : 1.0;
        // Blend existing risk with new signal (exponential moving average, alpha = 0.3)
        stateVector[2] = 0.7 * stateVector[2] + 0.3 * Math.min(amountRatio, 5.0);

        // [3] velocity – transactions per hour since first transaction
        long elapsedSeconds = txn.getUnixTime() - firstTxnTime;
        if (elapsedSeconds > 0) {
            stateVector[3] = txnCount / (elapsedSeconds / 3600.0);
        } else {
            stateVector[3] = txnCount; // all txns in same second
        }

        // [4] diversity – number of unique zip codes observed
        stateVector[4] = uniqueZips.size();

        // ---- Incremental temporal features (5–8, partial) ----

        // [5] predictedNextTxnTime — EMA of inter-transaction intervals
        if (lastTxnUnixTime > 0 && txn.getUnixTime() > lastTxnUnixTime) {
            double interval = txn.getUnixTime() - lastTxnUnixTime;
            emaInterTxnInterval = (1 - TEMPORAL_ALPHA) * emaInterTxnInterval
                    + TEMPORAL_ALPHA * interval;
            stateVector[5] = txn.getUnixTime() + emaInterTxnInterval;
        }
        lastTxnUnixTime = txn.getUnixTime();

        // [6] burstProbability — estimated from velocity (Poisson λ approximation)
        // P(burst) = 1 - exp(-λ) where λ = velocity / baseline
        double lambda = stateVector[3] / Math.max(1.0, stateVector[3]); // normalized
        if (txnCount > 1 && emaInterTxnInterval > 0) {
            lambda = 3600.0 / emaInterTxnInterval; // txns per hour from interval
            stateVector[6] = 1.0 - Math.exp(-lambda);
        }

        // [7] periodicityScore — placeholder; full autocorrelation requires Python ML layer
        // Incremental approx: track variance of hour-of-day
        // (set externally by ML layer for accurate computation)

        // [8] timeOfDayRisk — hour-based risk
        long hourOfDay = (txn.getUnixTime() % 86400) / 3600;
        // Higher risk during 0-6 AM (late night / early morning)
        stateVector[8] = (hourOfDay >= 0 && hourOfDay <= 6) ? 0.7 + 0.3 * (1.0 - hourOfDay / 6.0) : 0.2;

        // ---- Incremental behavioral features (9–12, partial) ----

        // [11] spendingDrift — CUSUM of amount residuals
        // S_t = max(0, S_{t-1} + (x_t - μ_t) - δ)
        double amountResidual = txn.getAmt() - stateVector[1]; // deviation from running mean
        cusumState = Math.max(0.0, cusumState + amountResidual - CUSUM_DRIFT_ALLOWANCE);
        stateVector[11] = cusumState;

        // [12] merchantAffinityShift — track recent merchants for Jaccard computation
        long merchantId = TruthGraph.deriveMerchantId(txn);
        recentMerchants.addLast(merchantId);
        while (recentMerchants.size() > MERCHANT_HISTORY_SIZE) {
            recentMerchants.pollFirst();
        }
        // (Full Jaccard computation between recent vs historical done by ML layer)

        this.lastUpdateTime = System.currentTimeMillis();
    }

    /**
     * Computes the L2 (Euclidean) norm of the state vector.
     * Higher magnitude generally indicates higher risk or activity.
     * Uses base dimensions (0–4) for backward compatibility.
     */
    public double computeRiskMagnitude() {
        double sumSq = 0.0;
        for (int i = 0; i < STATE_DIM; i++) {
            sumSq += stateVector[i] * stateVector[i];
        }
        return Math.sqrt(sumSq);
    }

    /**
     * Computes the L2 norm of the full extended state vector (all 17 dims).
     */
    public double computeExtendedRiskMagnitude() {
        double sumSq = 0.0;
        for (int i = 0; i < EXTENDED_DIM; i++) {
            sumSq += stateVector[i] * stateVector[i];
        }
        return Math.sqrt(sumSq);
    }

    /**
     * Returns a defensive copy of the full extended state vector (17-dim).
     */
    public double[] getExtendedStateVector() {
        return stateVector.clone();
    }

    /**
     * Returns a direct reference to the internal extended state vector.
     * Use only when performance matters and caller guarantees read-only access.
     */
    double[] getExtendedStateVectorRef() {
        return stateVector;
    }

    /**
     * Sets an individual extended dimension. Used by ML/DL layers to inject
     * computed features into dimensions 5–16.
     *
     * @param index dimension index (must be in [0, EXTENDED_DIM))
     * @param value the value to set
     */
    public void setExtendedDimension(int index, double value) {
        if (index < 0 || index >= EXTENDED_DIM) {
            throw new IndexOutOfBoundsException("Dimension index " + index
                    + " out of range [0, " + EXTENDED_DIM + ")");
        }
        stateVector[index] = value;
    }

    /**
     * Sets a block of extended dimensions. Used by NetworkFeatureEngine
     * and ML layer for batch updates.
     *
     * @param startIndex first dimension to set (inclusive)
     * @param values     values to write starting from startIndex
     */
    public void setExtendedBlock(int startIndex, double[] values) {
        for (int i = 0; i < values.length && (startIndex + i) < EXTENDED_DIM; i++) {
            stateVector[startIndex + i] = values[i];
        }
    }

    /**
     * Returns the recent merchant IDs for affinity computation.
     */
    public java.util.List<Long> getRecentMerchants() {
        return new java.util.ArrayList<>(recentMerchants);
    }

    /**
     * Returns the CUSUM state for spending drift.
     */
    public double getCusumState() {
        return cusumState;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("EntityNode{id=");
        sb.append(entityId).append(", state=[");
        for (int i = 0; i < EXTENDED_DIM; i++) {
            if (i > 0) sb.append(", ");
            if (i == STATE_DIM) sb.append("| "); // visual separator between base and extended
            sb.append(String.format("%.4f", stateVector[i]));
        }
        sb.append("], riskMag=").append(String.format("%.4f", computeRiskMagnitude()));
        sb.append('}');
        return sb.toString();
    }
}
