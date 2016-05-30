package apoc.gephi;

import apoc.Description;
import apoc.graph.Graphs;
import apoc.result.ProgressInfo;
import apoc.util.JsonUtil;
import apoc.util.UrlResolver;
import apoc.util.Util;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.PropertyContainer;
import org.neo4j.graphdb.Relationship;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.awt.*;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static apoc.util.MapUtil.map;

/**
 * @author mh
 * @since 29.05.16
 */
// https://github.com/gephi/gephi/wiki/GraphStreaming#Gephi_as_Master
// https://marketplace.gephi.org/plugin/graph-streaming/
public class Gephi {

    private String getGephiUrl(String hostOrKey) {
        return new UrlResolver("http", "localhost", 8080).getUrl("gephi", hostOrKey);
    }


    public static final String[] CAPTIONS = new String[]{"name", "title", "label"};

    // http://127.0.0.1:8080/workspace0?operation=updateGraph
    // TODO configure property-filters or transfer all properties
    @Procedure
    @Description("apoc.gephi.add(url-or-key, workspace, data) | streams passed in data to Gephi")
    public Stream<ProgressInfo> add(@Name("urlOrKey") String keyOrUrl, @Name("workspace") String workspace, @Name("data") Object data) {
        if (workspace == null) workspace = "workspace0";
        String url = getGephiUrl(keyOrUrl)+"/"+Util.encodeUrlComponent(workspace)+"?operation=updateGraph";
        long start = System.currentTimeMillis();
        HashSet<Node> nodes = new HashSet<>(1000);
        HashSet<Relationship> rels = new HashSet<>(10000);
        if (Graphs.extract(data, nodes, rels)) {
            String payload = toGephiStreaming(nodes, rels);
            JsonUtil.loadJson(url,map("method","POST"), payload);
            return Stream.of(new ProgressInfo(url,"graph","gephi").update(nodes.size(),rels.size(),nodes.size()).done(start));
        }
        return Stream.empty();
    }

    private String toGephiStreaming(Collection<Node> nodes, Collection<Relationship> rels) {
        return Stream.concat(toGraphStream(nodes, "an"), toGraphStream(rels, "ae")).collect(Collectors.joining("\r\n"));
    }

    private Stream<String> toGraphStream(Collection<? extends PropertyContainer> source, String operation) {
        return source.stream().map(n -> map(operation, data(n))).map(Util::toJson);
    }

    private Map<String, Object> data(PropertyContainer pc) {
        if (pc instanceof Node) {
            Node n = (Node) pc;
            String labels = Util.labelString(n);
            Map<String, Object> attributes = map("label", caption(n), "TYPE", labels);
            attributes.putAll(positions());
            attributes.putAll(color(labels));
            return map(idStr(n), attributes);
        }
        if (pc instanceof Relationship) {
            Relationship r = (Relationship) pc;
            String type = r.getType().name();
            Map<String, Object> attributes = map("label", type, "TYPE", type);
            attributes.putAll(map("source", idStr(r.getStartNode()), "target", idStr(r.getEndNode()), "directed", true));
            attributes.putAll(color(type));
            return map(String.valueOf(r.getId()), attributes);
        }
        return map();
    }

    private Map<String, Object> positions() {
        return map("size", 10, "x", 100 - Math.random() * 200, "y", 100 - Math.random() * 200);
    }

    private Map<String, Object> color(String type) {
        float[] c = new Color(type.hashCode()).getRGBColorComponents(null);
        return map("r", c[0], "g", c[1], "b", c[2]);
    }

    private String idStr(Node n) {
        return String.valueOf(n.getId());
    }

    private String caption(Node n) {
        for (String caption : CAPTIONS) {
            if (n.hasProperty(caption)) return n.getProperty(caption).toString();
        }
        String first=null;
        for (String caption : CAPTIONS) {
            for (String key : n.getPropertyKeys()) {
                if (first==null) first = key;
                if (key.toLowerCase().contains(caption)) return n.getProperty(caption).toString();
            }
        }
        return first == null ? idStr(n) : n.getProperty(first).toString();
    }
}
