package org.frauddetection.graph;

import org.frauddetection.model.TransactionData;

/**
 * Graph node representing an account or entity in the fraud detection graph.
 * Maintains a compact fixed-size state vector for memory efficiency.
 *
 * State vector indices:
 *   [0] = txnVolume   - total number of transactions
 *   [1] = avgAmount   - running average transaction amount
 *   [2] = riskScore   - accumulated risk score
 *   [3] = velocity    - transaction frequency (txns per hour)
 *   [4] = diversity   - geographic diversity of transactions
 */
public class EntityNode {

    /** Dimensionality of the state vector. */
    static final int STATE_DIM = 5;

    private final long entityId;

    /** Low-dimensional state vector stored as primitive array for efficiency. */
    private final double[] stateVector;

    private long lastUpdateTime;

    // Running counters used to derive state values incrementally
    private long txnCount;
    private double totalAmount;
    private long firstTxnTime;

    // Track unique zip codes seen as a lightweight diversity measure
    private final java.util.Set<String> uniqueZips = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<>());

    public EntityNode(long entityId) {
        this.entityId = entityId;
        this.stateVector = new double[STATE_DIM];
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
     * All five dimensions are recomputed to stay consistent.
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

        this.lastUpdateTime = System.currentTimeMillis();
    }

    /**
     * Computes the L2 (Euclidean) norm of the state vector.
     * Higher magnitude generally indicates higher risk or activity.
     */
    public double computeRiskMagnitude() {
        double sumSq = 0.0;
        for (int i = 0; i < STATE_DIM; i++) {
            sumSq += stateVector[i] * stateVector[i];
        }
        return Math.sqrt(sumSq);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("EntityNode{id=");
        sb.append(entityId).append(", state=[");
        for (int i = 0; i < STATE_DIM; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format("%.4f", stateVector[i]));
        }
        sb.append("], riskMag=").append(String.format("%.4f", computeRiskMagnitude()));
        sb.append('}');
        return sb.toString();
    }
}
