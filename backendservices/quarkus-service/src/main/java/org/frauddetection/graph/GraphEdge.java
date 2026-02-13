package org.frauddetection.graph;

/**
 * Directed edge representing transaction flow between two entities.
 * Stores only aggregated metrics – no raw transaction data is retained.
 */
public class GraphEdge {

    /** Half-life for exponential decay in milliseconds (1 hour). */
    private static final double DECAY_HALF_LIFE_MS = 3_600_000.0;

    private final long sourceId;
    private final long targetId;

    /** Cumulative weight (sum of transaction amounts on this edge). */
    private double weight;

    /** Number of transactions that traversed this edge. */
    private int transactionCount;

    /** Timestamp of the most recent transaction (epoch millis). */
    private long lastTransactionTime;

    public GraphEdge(long sourceId, long targetId) {
        this.sourceId = sourceId;
        this.targetId = targetId;
        this.weight = 0.0;
        this.transactionCount = 0;
        this.lastTransactionTime = System.currentTimeMillis();
    }

    /**
     * Convenience constructor that also accepts an initial confidence/weight.
     * Used when creating speculative edges in the hypothesis graph.
     */
    public GraphEdge(long sourceId, long targetId, double initialWeight) {
        this(sourceId, targetId);
        this.weight = initialWeight;
        this.transactionCount = 1;
    }

    // ---- Getters ----

    public long getSourceId() {
        return sourceId;
    }

    public long getTargetId() {
        return targetId;
    }

    public double getWeight() {
        return weight;
    }

    public int getTransactionCount() {
        return transactionCount;
    }

    public long getLastTransactionTime() {
        return lastTransactionTime;
    }

    // ---- Mutators ----

    /**
     * Records a new flow of {@code amount} across this edge.
     */
    public synchronized void incrementFlow(double amount) {
        this.weight += amount;
        this.transactionCount++;
        this.lastTransactionTime = System.currentTimeMillis();
    }

    /**
     * Returns the edge weight after applying exponential time-decay.
     * Edges that have not been active recently contribute less.
     *
     * @param currentTime current epoch time in milliseconds
     * @return decayed weight, always >= 0
     */
    public double getDecayedWeight(long currentTime) {
        long elapsed = currentTime - lastTransactionTime;
        if (elapsed <= 0) {
            return weight;
        }
        // decay = 2^(-elapsed / halfLife)
        double decay = Math.pow(2.0, -elapsed / DECAY_HALF_LIFE_MS);
        return weight * decay;
    }

    @Override
    public String toString() {
        return "GraphEdge{" + sourceId + " -> " + targetId
                + ", w=" + String.format("%.2f", weight)
                + ", txns=" + transactionCount + '}';
    }
}
