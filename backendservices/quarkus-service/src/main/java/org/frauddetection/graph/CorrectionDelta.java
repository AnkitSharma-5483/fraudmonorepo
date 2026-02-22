package org.frauddetection.graph;

/**
 * Immutable data class representing a single correction delta — the difference
 * between what the hypothesis graph predicted and what was actually observed.
 * <p>
 * Uses bitmask-based compression: only dimensions that actually changed are
 * stored. For an entity with 3/17 dimensions changing, storage is:
 * 8 bytes (entityId) + 4 bytes (bitmask) + 24 bytes (3 doubles) = 36 bytes
 * vs 144 bytes for the full 17-dim vector (75% savings).
 */
public final class CorrectionDelta {

    private final long entityId;

    /** Transaction sequence number when this delta was computed. */
    private final long txnSequence;

    /** Bitmask: bit i is set if dimension i changed beyond epsilon. */
    private final int changeBitmask;

    /** Packed non-zero delta values (matches set bits in changeBitmask). */
    private final double[] packedDelta;

    /** L2 norm of the full delta vector (precomputed for fast access). */
    private final double deltaNorm;

    /** Source of the correction: "MATH" or "DL" */
    private final String speculationSource;

    /** Kalman gain applied during this correction. */
    private final double appliedGain;

    /** Timestamp (epoch millis) when this delta was recorded. */
    private final long timestamp;

    /**
     * Constructs a CorrectionDelta from the full delta vector.
     * Automatically compresses via bitmask.
     *
     * @param entityId         entity this delta applies to
     * @param txnSequence      transaction sequence number
     * @param fullDelta        full EXTENDED_DIM delta vector
     * @param epsilon          threshold for considering a dimension changed
     * @param speculationSource "MATH" or "DL"
     * @param appliedGain      Kalman gain that was used
     */
    public CorrectionDelta(long entityId, long txnSequence, double[] fullDelta,
                           double epsilon, String speculationSource, double appliedGain) {
        this.entityId = entityId;
        this.txnSequence = txnSequence;
        this.speculationSource = speculationSource;
        this.appliedGain = appliedGain;
        this.timestamp = System.currentTimeMillis();

        // Compute norm before compression
        double normSq = 0.0;
        for (double d : fullDelta) {
            normSq += d * d;
        }
        this.deltaNorm = Math.sqrt(normSq);

        // Compress
        this.changeBitmask = ExtendedStateVector.computeChangeBitmask(
                fullDelta, new double[fullDelta.length], epsilon);
        this.packedDelta = ExtendedStateVector.packDelta(fullDelta, this.changeBitmask);
    }

    /**
     * Constructs directly from pre-packed data (used during deserialization).
     */
    CorrectionDelta(long entityId, long txnSequence, int changeBitmask,
                    double[] packedDelta, double deltaNorm,
                    String speculationSource, double appliedGain, long timestamp) {
        this.entityId = entityId;
        this.txnSequence = txnSequence;
        this.changeBitmask = changeBitmask;
        this.packedDelta = packedDelta;
        this.deltaNorm = deltaNorm;
        this.speculationSource = speculationSource;
        this.appliedGain = appliedGain;
        this.timestamp = timestamp;
    }

    // ---- Getters ----

    public long getEntityId() {
        return entityId;
    }

    public long getTxnSequence() {
        return txnSequence;
    }

    public int getChangeBitmask() {
        return changeBitmask;
    }

    public double[] getPackedDelta() {
        return packedDelta.clone();
    }

    public double getDeltaNorm() {
        return deltaNorm;
    }

    public String getSpeculationSource() {
        return speculationSource;
    }

    public double getAppliedGain() {
        return appliedGain;
    }

    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Returns the number of dimensions that changed (number of set bits).
     */
    public int getChangedDimensionCount() {
        return Integer.bitCount(changeBitmask);
    }

    /**
     * Unpacks the delta back into a full EXTENDED_DIM vector.
     */
    public double[] toFullDelta() {
        return ExtendedStateVector.unpackDelta(packedDelta, changeBitmask);
    }

    /**
     * Returns the approximate memory footprint in bytes.
     * entityId (8) + txnSeq (8) + bitmask (4) + packed doubles (8 each)
     * + norm (8) + gain (8) + timestamp (8) + string ref (~40) + overhead (~16)
     */
    public int estimatedBytes() {
        return 8 + 8 + 4 + (packedDelta.length * 8) + 8 + 8 + 8 + 40 + 16;
    }

    @Override
    public String toString() {
        return "CorrectionDelta{entity=" + entityId
                + ", txn=" + txnSequence
                + ", changed=" + getChangedDimensionCount() + "/" + ExtendedStateVector.EXTENDED_DIM
                + ", norm=" + String.format("%.4f", deltaNorm)
                + ", source=" + speculationSource
                + ", gain=" + String.format("%.4f", appliedGain)
                + '}';
    }
}
