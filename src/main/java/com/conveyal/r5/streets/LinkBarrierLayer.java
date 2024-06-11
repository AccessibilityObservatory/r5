package com.conveyal.r5.streets;

import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.FeatureSource;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.conveyal.r5.streets.VertexStore.fixedDegreesToFloating;

/**
 * A geometry layer that blocks links between origins/destinations and a StreetLayer.
 */
public class LinkBarrierLayer {
    private final GeometryFactory geomFactory;
    private final STRtree featureIndex;

    public LinkBarrierLayer(FeatureCollection<SimpleFeatureType, SimpleFeature> collection) {
        geomFactory = new GeometryFactory();
        featureIndex = new STRtree();
        // Build index of geometries to speed up intersection testing
        try (FeatureIterator<SimpleFeature> it = collection.features()) {
            while (it.hasNext()) {
                SimpleFeature feature = it.next();
                Envelope env = ((Geometry) feature.getDefaultGeometry()).getEnvelopeInternal();
                featureIndex.insert(env, feature);
            }
        }
        System.out.println("Loaded " + featureIndex.size() + " barrier features");
    }

    public static LinkBarrierLayer fromShapefile(String path) throws IOException {
        File file = new File(path);
        Map<String, Object> map = new HashMap<>();
        map.put("url", file.toURI().toURL());

        DataStore dataStore = DataStoreFinder.getDataStore(map);
        String typeName = dataStore.getTypeNames()[0];
        FeatureSource<SimpleFeatureType, SimpleFeature> source = dataStore.getFeatureSource(typeName);
        FeatureCollection<SimpleFeatureType, SimpleFeature> collection = source.getFeatures();

        return new LinkBarrierLayer(collection);
    }

    public static LinkBarrierLayer fromGeopackage(String path) throws IOException {
        Map<String, Object> params = new HashMap<>();
        params.put("dbtype", "geopkg");
        params.put("database", path);
        params.put("read-only", true);

        DataStore dataStore = DataStoreFinder.getDataStore(params);
        String typeName = dataStore.getTypeNames()[0];
        FeatureSource<SimpleFeatureType, SimpleFeature> source = dataStore.getFeatureSource(typeName);
        FeatureCollection<SimpleFeatureType, SimpleFeature> collection = source.getFeatures();

        return new LinkBarrierLayer(collection);
    }

    boolean intersects(Geometry q) {
        List<SimpleFeature> candidates = featureIndex.query(q.getEnvelopeInternal());
        for (SimpleFeature f: candidates) {
            Geometry geom = (Geometry) f.getDefaultGeometry();
            if (geom.intersects(q))
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
