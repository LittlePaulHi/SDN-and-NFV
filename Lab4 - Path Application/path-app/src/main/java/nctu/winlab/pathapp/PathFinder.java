package nctu.winlab.pathapp;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

import org.onosproject.net.DeviceId;
import org.onosproject.net.topology.TopologyEdge;
import org.onosproject.net.topology.TopologyGraph;
import org.onosproject.net.topology.TopologyVertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public enum PathFinder {

    INSTANCE;
    private final Logger log = LoggerFactory.getLogger(AppComponent.class);
    
    private class Vertex {
        TopologyVertex itself;
        Vertex parent;

        Vertex(TopologyVertex itself, Vertex parent) {
            this.itself = itself;
            this.parent = parent;
        }
    }
    
    private PathFinder() {}

    // BFS
    public Queue<DeviceId> Search(TopologyGraph graph, TopologyVertex srcVertex, TopologyVertex dstVertex) {

        if (srcVertex.deviceId().equals(dstVertex.deviceId())) {
            return backtrackPath(new Vertex(dstVertex, null));
        }

        Set<DeviceId> alreadyVisitedVertices = new HashSet<>();
        Queue<Vertex> nextVertex = new LinkedList<>();
        Vertex visitVertex;

        nextVertex.add(new Vertex(srcVertex, null));
        alreadyVisitedVertices.add(srcVertex.deviceId());
        while (!nextVertex.isEmpty()) {
            visitVertex = nextVertex.poll();

            for (TopologyEdge edge : graph.getEdgesFrom(visitVertex.itself)) {
                if (alreadyVisitedVertices.contains(edge.dst().deviceId())) {
                    continue;
                }
                else {
                    alreadyVisitedVertices.add(edge.dst().deviceId());

                    if (edge.dst().deviceId().equals(dstVertex.deviceId())) {
                        return backtrackPath(new Vertex(edge.dst(), visitVertex));
                    }
                    else if (!nextVertex.offer(new Vertex(edge.dst(), visitVertex))) {
                        log.info("Error: occurs exception when add nextVertex");
                    }
                }
            }
        }

        return null;
    }

    private Queue<DeviceId> backtrackPath(Vertex dstVertex) {

        Queue<DeviceId> deviceIdsOfReversePath = new LinkedList<>();

        while (dstVertex != null) {
            deviceIdsOfReversePath.offer(dstVertex.itself.deviceId());
            dstVertex = dstVertex.parent;
        }

        return deviceIdsOfReversePath;
    }
}
