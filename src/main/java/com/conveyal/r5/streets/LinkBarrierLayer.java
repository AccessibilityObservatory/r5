package com.conveyal.r5.streets;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.quadtree.Quadtree;

import java.util.List;

import static com.conveyal.r5.streets.VertexStore.fixedDegreesToFloating;

/**
 * A geometry layer that blocks links between origins/destinations and a StreetLayer.
 */
public class LinkBarrierLayer {
    private GeometryFactory geomFactory;
    private Quadtree featureIndex;

    public LinkBarrierLayer(GeometryCollection geoms) {
        this.geomFactory = new GeometryFactory();
        this.featureIndex = new Quadtree();

        // Build index of geometries to speed up intersection testing
        for (GeometryCollectionIterator it = new GeometryCollectionIterator(geoms); it.hasNext(); ) {
            Geometry g = (Geometry) it.next();
            featureIndex.insert(g.getEnvelopeInternal(), g);
        }
    }

    boolean intersects(Geometry q) {
        List<Geometry> candidates = featureIndex.query(q.getEnvelopeInternal());
        for (Geometry g: candidates) {
            if (g.intersects(q))
                return true;
        }
        return false;
    }

    boolean intersects(int fixedLon0, int fixedLat0, int fixedLon1, int fixedLat1) {
        LineString queryLineString = geomFactory.createLineString(
            new Coordinate[] {
                new Coordinate(fixedDegreesToFloating(fixedLon0), fixedDegreesToFloating(fixedLat0)),
                new Coordinate(fixedDegreesToFloating(fixedLon1), fixedDegreesToFloating(fixedLat1))
            }
        );
        return this.intersects(queryLineString);
    }
}
