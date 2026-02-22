package org.frauddetection.graph;

import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * JAX-RS endpoint exposing the graph analytical layer for visualization,
 * monitoring dashboards, and Partner B data contracts.
 *
 * <h3>Partner A Endpoints (internal dashboards)</h3>
 * <ul>
 *   <li>GET /graph/state — full graph summary</li>
 *   <li>GET /graph/entity/{id} — single entity detail</li>
 *   <li>GET /graph/metrics — analytical metrics &amp; convergence</li>
 *   <li>POST /graph/reset — reset all state (dev only)</li>
 * </ul>
 *
 * <h3>Partner B Contract Endpoints</h3>
 * <ul>
 *   <li>GET /graph/embeddings — GNN node embeddings (→ Neo4j &amp; Spark)</li>
 *   <li>GET /graph/deltas?since=N — correction deltas since txn N (→ Spark drift analysis)</li>
 *   <li>GET /graph/anomalies — GAN anomaly candidates (→ Neo4j fraud ring flags)</li>
 * </ul>
 */
@Path("/graph")
@Produces(MediaType.APPLICATION_JSON)
public class GraphStateEndpoint {

    @Inject
    GraphAnalyticsService graphAnalyticsService;

    // ---- Partner A: Internal Dashboard Endpoints ----

    /**
     * Returns the full state of truth, hypothesis, and correction delta graphs,
     * along with convergence metrics.
     */
    @GET
    @Path("/state")
    public Response getGraphState() {
        return Response.ok(graphAnalyticsService.getGraphState().toString()).build();
    }

    /**
     * Returns detailed state for a single entity: extended 17-dim vector,
     * predicted state, recent correction deltas, community membership, and Kalman gain.
     */
    @GET
    @Path("/entity/{id}")
    public Response getEntityState(@PathParam("id") long entityId) {
        return Response.ok(graphAnalyticsService.getEntityState(entityId).toString()).build();
    }

    /**
     * Returns graph-wide analytical metrics: density, PageRank, top risk nodes,
     * convergence statistics, and network structure summaries.
     */
    @GET
    @Path("/metrics")
    public Response getGraphMetrics() {
        return Response.ok(graphAnalyticsService.getGraphMetrics().toString()).build();
    }

    /**
     * Resets all graph state (truth, hypothesis, corrections, Kalman, network).
     * Intended for testing / development only.
     */
    @POST
    @Path("/reset")
    public Response resetGraph() {
        graphAnalyticsService.resetGraph();
        return Response.ok("{\"status\":\"graph reset\"}").build();
    }

    // ---- Partner B: Contract Endpoints ----

    /**
     * Returns GNN embeddings for all nodes in the graph.
     * <p>
     * Partner B consumes these embeddings for:
     * <ul>
     *   <li>Neo4j node property storage (vector index for similarity search)</li>
     *   <li>Spark community detection and clustering jobs</li>
     * </ul>
     *
     * Response shape:
     * <pre>{
     *   "embeddingDimension": 64,
     *   "nodeCount": N,
     *   "nodes": [{ "entityId": long, "embedding": double[] }, ...]
     * }</pre>
     */
    @GET
    @Path("/embeddings")
    public Response getEmbeddings() {
        return Response.ok(graphAnalyticsService.getEmbeddings().toString()).build();
    }

    /**
     * Returns correction deltas since a given transaction sequence number.
     * <p>
     * Partner B uses these deltas to:
     * <ul>
     *   <li>Track hypothesis accuracy over time (Spark time-series analysis)</li>
     *   <li>Detect model drift (rising delta norms)</li>
     *   <li>Audit the correction process (math vs DL speculation accuracy)</li>
     * </ul>
     *
     * @param since Transaction sequence number to start from (inclusive, default 0)
     *
     * Response shape:
     * <pre>{
     *   "totalCorrections": long,
     *   "requestedSince": long,
     *   "returnedCount": int,
     *   "deltas": [{ "entityId", "txnSequence", "deltaNorm", "changedDims",
     *                 "source", "gain", "deltaVector": double[17] }, ...]
     * }</pre>
     */
    @GET
    @Path("/deltas")
    public Response getDeltas(@QueryParam("since") @DefaultValue("0") long since) {
        return Response.ok(graphAnalyticsService.getDeltas(since).toString()).build();
    }

    /**
     * Returns GAN-detected anomaly candidates: edges the discriminator believes
     * should exist but are absent from the truth graph.
     * <p>
     * Partner B flags these in Neo4j for fraud ring investigation and
     * routes them to Spark for scenario evaluation.
     *
     * Response shape:
     * <pre>{
     *   "threshold": 0.6,
     *   "candidateCount": int,
     *   "anomalies": [{ "sourceId", "targetId", "weight",
     *                    "discriminatorScore", "isInTruth" }, ...]
     * }</pre>
     */
    @GET
    @Path("/anomalies")
    public Response getAnomalies() {
        return Response.ok(graphAnalyticsService.getAnomalies().toString()).build();
    }
}
