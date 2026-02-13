package org.frauddetection.graph;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * JAX-RS endpoint exposing the graph analytical layer for visualization
 * and monitoring dashboards.
 */
@Path("/graph")
@Produces(MediaType.APPLICATION_JSON)
public class GraphStateEndpoint {

    @Inject
    GraphAnalyticsService graphAnalyticsService;

    /**
     * Returns the full state of both truth and hypothesis graphs.
     */
    @GET
    @Path("/state")
    public Response getGraphState() {
        return Response.ok(graphAnalyticsService.getGraphState().toString()).build();
    }

    /**
     * Returns detailed state for a single entity by id.
     */
    @GET
    @Path("/entity/{id}")
    public Response getEntityState(@PathParam("id") long entityId) {
        return Response.ok(graphAnalyticsService.getEntityState(entityId).toString()).build();
    }

    /**
     * Returns graph-wide analytical metrics (density, PageRank, top risk nodes).
     */
    @GET
    @Path("/metrics")
    public Response getGraphMetrics() {
        return Response.ok(graphAnalyticsService.getGraphMetrics().toString()).build();
    }

    /**
     * Resets the graph state. Intended for testing / development only.
     */
    @POST
    @Path("/reset")
    public Response resetGraph() {
        graphAnalyticsService.resetGraph();
        return Response.ok("{\"status\":\"graph reset\"}").build();
    }
}
