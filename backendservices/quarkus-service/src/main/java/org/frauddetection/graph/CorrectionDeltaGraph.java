package org.frauddetection.graph;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Compressed correction-delta store using a ring buffer.
 * <p>
 * Stores only the "surprises" — the difference between what the hypothesis
 * graph predicted and what was actually observed. This is the <b>Real Graph</b>
 * in the inverted predictor-corrector paradigm: instead of storing the full
 * confirmed graph state, we store only the corrections needed.
 * <p>
 * Memory layout:
 * <ul>
 *   <li>Ring buffer of the last {@code W} correction snapshots (configurable window)</li>
 *   <li>Per-entity index for fast lookup of recent corrections</li>
 *   <li>Running statistics (accuracy, average delta norm) for Kalman gain tuning</li>
 * </ul>
 * <p>
 * If hypothesis accuracy is 70%+, this graph is ~30% the size of a full truth graph.
 */
public class CorrectionDeltaGraph {

    /** Default ring buffer capacity (number of correction snapshots to retain). */
    private static final int DEFAULT_WINDOW_SIZE = 1000;

    /** Ring buffer of correction deltas, oldest evicted first. */
    private final CorrectionDelta[] ringBuffer;
    private int head = 0;
    private int size = 0;
    private final int capacity;

    /** Per-entity recent delta index: entityId → list of indices into ringBuffer. */
    private final ConcurrentHashMap<Long, List<Integer>> entityIndex = new ConcurrentHashMap<>();

    /** Per-entity corrected edge list: stores edges that were wrong in hypothesis. */
    private final ConcurrentHashMap<Long, List<CorrectedEdge>> correctedEdges = new ConcurrentHashMap<>();

    /** Running statistics. */
    private final AtomicLong totalCorrections = new AtomicLong(0);
    private volatile double runningAvgDeltaNorm = 0.0;
    private volatile double mathSpeculationAccuracy = 1.0;
    private volatile double dlSpeculationAccuracy = 1.0;

    // EMA alpha for running statistics
    private static final double STATS_ALPHA = 0.05;

    public CorrectionDeltaGraph() {
        this(DEFAULT_WINDOW_SIZE);
    }

    public CorrectionDeltaGraph(int windowSize) {
        this.capacity = windowSize;
        this.ringBuffer = new CorrectionDelta[windowSize];
    }

    // ---- Recording corrections ----

    /**
     * Records a correction delta. If the ring buffer is full, the oldest
     * correction is evicted.
     */
    public synchronized void recordDelta(CorrectionDelta delta) {
        // Evict oldest if buffer is full
        if (size == capacity) {
            CorrectionDelta evicted = ringBuffer[head];
            if (evicted != null) {
                removeFromEntityIndex(evicted.getEntityId(), head);
            }
        }

        // Insert at head position
        ringBuffer[head] = delta;
        addToEntityIndex(delta.getEntityId(), head);

        // Advance head (circular)
        head = (head + 1) % capacity;
        if (size < capacity) {
            size++;
        }
        totalCorrections.incrementAndGet();

        // Update running statistics
        updateStats(delta);
    }

    /**
     * Records an edge correction (edge predicted but not observed, or observed
     * but not predicted).
     */
    public void recordEdgeCorrection(long sourceId, long targetId,
                                     boolean wasPredicted, boolean wasObserved,
                                     double confidence) {
        CorrectedEdge ce = new CorrectedEdge(sourceId, targetId, wasPredicted, wasObserved, confidence);
        correctedEdges.computeIfAbsent(sourceId,
                k -> Collections.synchronizedList(new ArrayList<>())).add(ce);
    }

    // ---- Queries ----

    /**
     * Returns the most recent corrections for a given entity.
     *
     * @param entityId entity to look up
     * @param maxCount maximum number of deltas to return
     * @return list of recent deltas, newest first
     */
    public List<CorrectionDelta> getRecentDeltas(long entityId, int maxCount) {
        List<Integer> indices = entityIndex.get(entityId);
        if (indices == null || indices.isEmpty()) {
            return Collections.emptyList();
        }

        List<CorrectionDelta> result = new ArrayList<>();
        synchronized (indices) {
            // Iterate from newest to oldest
            for (int i = indices.size() - 1; i >= 0 && result.size() < maxCount; i--) {
                int idx = indices.get(i);
                CorrectionDelta d = ringBuffer[idx];
                if (d != null && d.getEntityId() == entityId) {
                    result.add(d);
                }
            }
        }
        return result;
    }

    /**
     * Returns all deltas recorded since a given transaction sequence number.
     *
     * @param sinceTxnSequence inclusive lower bound
     * @return list of matching deltas, oldest first
     */
    public List<CorrectionDelta> getDeltasSince(long sinceTxnSequence) {
        List<CorrectionDelta> result = new ArrayList<>();
        // Scan the ring buffer (not index-optimized; acceptable for moderate window sizes)
        for (int i = 0; i < size; i++) {
            int idx = (head - size + i + capacity) % capacity;
            CorrectionDelta d = ringBuffer[idx];
            if (d != null && d.getTxnSequence() >= sinceTxnSequence) {
                result.add(d);
            }
        }
        return result;
    }

    /**
     * Returns the corrected edges for a given source entity.
     */
    public List<CorrectedEdge> getCorrectedEdges(long sourceId) {
        List<CorrectedEdge> edges = correctedEdges.get(sourceId);
        return (edges != null) ? Collections.unmodifiableList(edges) : Collections.emptyList();
    }

    // ---- Statistics ----

    public long getTotalCorrections() {
        return totalCorrections.get();
    }

    public double getRunningAvgDeltaNorm() {
        return runningAvgDeltaNorm;
    }

    public double getMathSpeculationAccuracy() {
        return mathSpeculationAccuracy;
    }

    public double getDlSpeculationAccuracy() {
        return dlSpeculationAccuracy;
    }

    public int getCurrentBufferSize() {
        return size;
    }

    /**
     * Estimates total memory usage in bytes.
     */
    public long estimatedMemoryBytes() {
        long mem = (long) capacity * 8; // reference array
        for (int i = 0; i < size; i++) {
            int idx = (head - size + i + capacity) % capacity;
            if (ringBuffer[idx] != null) {
                mem += ringBuffer[idx].estimatedBytes();
            }
        }
        return mem;
    }

    // ---- JSON export ----

    /**
     * Produces a JSON summary for the /graph/deltas endpoint.
     */
    public JSONObject toSummaryJson() {
        JSONObject json = new JSONObject();
        json.put("totalCorrections", totalCorrections.get());
        json.put("bufferSize", size);
        json.put("bufferCapacity", capacity);
        json.put("avgDeltaNorm", runningAvgDeltaNorm);
        json.put("mathSpeculationAccuracy", mathSpeculationAccuracy);
        json.put("dlSpeculationAccuracy", dlSpeculationAccuracy);
        json.put("estimatedMemoryBytes", estimatedMemoryBytes());

        // Recent deltas (last 10)
        JSONArray recentArr = new JSONArray();
        int count = Math.min(10, size);
        for (int i = 0; i < count; i++) {
            int idx = (head - 1 - i + capacity) % capacity;
            CorrectionDelta d = ringBuffer[idx];
            if (d != null) {
                JSONObject dj = new JSONObject();
                dj.put("entityId", d.getEntityId());
                dj.put("txnSequence", d.getTxnSequence());
                dj.put("deltaNorm", d.getDeltaNorm());
                dj.put("changedDims", d.getChangedDimensionCount());
                dj.put("source", d.getSpeculationSource());
                dj.put("gain", d.getAppliedGain());
                recentArr.put(dj);
            }
        }
        json.put("recentDeltas", recentArr);

        return json;
    }

    /** Clears all stored corrections (for testing). */
    public synchronized void clear() {
        for (int i = 0; i < capacity; i++) {
            ringBuffer[i] = null;
        }
        head = 0;
        size = 0;
        entityIndex.clear();
        correctedEdges.clear();
        totalCorrections.set(0);
        runningAvgDeltaNorm = 0.0;
        mathSpeculationAccuracy = 1.0;
        dlSpeculationAccuracy = 1.0;
    }

    // ---- Internal helpers ----

    private void addToEntityIndex(long entityId, int bufferIndex) {
        entityIndex.computeIfAbsent(entityId,
                k -> Collections.synchronizedList(new ArrayList<>())).add(bufferIndex);
    }

    private void removeFromEntityIndex(long entityId, int bufferIndex) {
        List<Integer> indices = entityIndex.get(entityId);
        if (indices != null) {
            synchronized (indices) {
                indices.remove(Integer.valueOf(bufferIndex));
                if (indices.isEmpty()) {
                    entityIndex.remove(entityId);
                }
            }
        }
    }

    private void updateStats(CorrectionDelta delta) {
        // EMA of delta norm
        runningAvgDeltaNorm = (1 - STATS_ALPHA) * runningAvgDeltaNorm
                + STATS_ALPHA * delta.getDeltaNorm();

        // Track accuracy by speculation source
        // A delta with low norm means the speculation was accurate
        boolean isAccurate = delta.getDeltaNorm() < 0.5; // threshold for "close enough"
        double accuracySignal = isAccurate ? 1.0 : 0.0;

        if ("DL".equals(delta.getSpeculationSource())) {
            dlSpeculationAccuracy = (1 - STATS_ALPHA) * dlSpeculationAccuracy
                    + STATS_ALPHA * accuracySignal;
        } else {
            mathSpeculationAccuracy = (1 - STATS_ALPHA) * mathSpeculationAccuracy
                    + STATS_ALPHA * accuracySignal;
        }
    }

    // ---- Nested class for edge corrections ----

    /**
     * Represents a single edge correction: an edge that was either predicted
     * but not observed, or observed but not predicted.
     */
    public static final class CorrectedEdge {
        private final long sourceId;
        private final long targetId;
        private final boolean wasPredicted;
        private final boolean wasObserved;
        private final double confidence;
        private final long timestamp;

        CorrectedEdge(long sourceId, long targetId, boolean wasPredicted,
                      boolean wasObserved, double confidence) {
            this.sourceId = sourceId;
            this.targetId = targetId;
            this.wasPredicted = wasPredicted;
            this.wasObserved = wasObserved;
            this.confidence = confidence;
            this.timestamp = System.currentTimeMillis();
        }

        public long getSourceId() { return sourceId; }
        public long getTargetId() { return targetId; }
        public boolean wasPredicted() { return wasPredicted; }
        public boolean wasObserved() { return wasObserved; }
        public double getConfidence() { return confidence; }
        public long getTimestamp() { return timestamp; }

        /** True if the edge was predicted but did NOT appear in reality. */
        public boolean isFalsePositive() { return wasPredicted && !wasObserved; }

        /** True if the edge appeared in reality but was NOT predicted. */
        public boolean isFalseNegative() { return !wasPredicted && wasObserved; }
    }
}
