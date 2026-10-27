package com.creed.resource.migrationrisk.service;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * A Camel XML DSL file ({@code <routes>}, {@code <camelContext>}, {@code <routeContext>}; Spring, Blueprint or plain)
 * reduced to its routes, each a list of normalized step lines:
 *
 * <pre>
 * from uri=direct:order
 * log message=order ${body}
 * filter
 *   simple: ${body} != null
 *   to uri=bean:orderService?method=placeOrder
 * </pre>
 *
 * One line per element, nesting by indentation, attributes sorted, namespace prefixes and declarations dropped, text
 * whitespace collapsed — so formatting, attribute order, comments and {@code camel:} prefixes are not differences.
 * Routes are keyed by their {@code id} (what a CamelContext keys them by too), else by their {@code from} uri. What is
 * not a route (beans, {@code restConfiguration}, data formats, REST DSL ...) is kept as {@link Parsed#rest()} lines.
 */
final class CamelRoutes {

    private static final Set<String> CONTAINERS = Set.of("routes", "camelContext", "routeContext");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** {@code key}: the route id, else {@code from <uri>}, else {@code #n} (n-th route of the file). */
    record Route(String key, List<String> steps) {
    }

    record Parsed(Map<String, Route> routes, List<String> rest) {
    }

    private CamelRoutes() {
    }

    /** Cheap pre-check before parsing: no {@code route} element, no Camel file. */
    static boolean mayContainRoutes(String text) {
        return text != null && (text.contains("<route") || text.contains(":route"));
    }

    /** Empty routes when the XML has none (not a Camel file); throws on XML that does not parse. */
    static Parsed parse(String text) {
        Document doc = parseXml(text);
        Map<String, Route> routes = new LinkedHashMap<>();
        List<String> rest = new ArrayList<>();
        collect(doc.getDocumentElement(), 0, routes, rest);
        return new Parsed(routes, rest);
    }

    private static void collect(Element e, int depth, Map<String, Route> routes, List<String> rest) {
        String name = local(e);
        Node parent = e.getParentNode();
        if (name.equals("route") && parent instanceof Element p && CONTAINERS.contains(local(p))) {
            String key = key(e, routes.size() + 1);
            List<String> steps = new ArrayList<>();
            String own = attributes(e, true);
            if (!own.isEmpty()) {
                steps.add("route" + own); // autoStartup, streamCache ... (the id is the key)
            }
            for (Element child : children(e)) {
                lines(child, 0, steps);
            }
            routes.putIfAbsent(key, new Route(key, steps));
            return;
        }
        rest.add("  ".repeat(depth) + name + attributes(e, false) + text(e));
        for (Element child : children(e)) {
            collect(child, depth + 1, routes, rest);
        }
    }

    private static String key(Element route, int index) {
        String id = route.getAttribute("id");
        if (!id.isBlank()) {
            return id.trim();
        }
        return children(route).stream().filter(c -> local(c).equals("from")).findFirst()
                .map(from -> "from " + from.getAttribute("uri").trim()).orElse("#" + index);
    }

    private static void lines(Element e, int depth, List<String> out) {
        out.add("  ".repeat(depth) + local(e) + attributes(e, false) + text(e));
        for (Element child : children(e)) {
            lines(child, depth + 1, out);
        }
    }

    /** {@code " a=1 b=2"}, sorted, without namespace declarations ({@code withoutId}: the route's own id is its key). */
    private static String attributes(Element e, boolean withoutId) {
        NamedNodeMap attrs = e.getAttributes();
        Map<String, String> sorted = new TreeMap<>();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            String n = a.getName();
            if (n.equals("xmlns") || n.startsWith("xmlns:") || n.startsWith("xsi:") || (withoutId && n.equals("id"))) {
                continue;
            }
            sorted.put(n.contains(":") ? n.substring(n.indexOf(':') + 1) : n, SPACES.matcher(a.getValue()).replaceAll(" ").trim());
        }
        StringBuilder sb = new StringBuilder();
        sorted.forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
        return sb.toString();
    }

    /** The element's own text (an expression: {@code simple}, {@code constant}, {@code xpath} ...), collapsed. */
    private static String text(Element e) {
        StringBuilder sb = new StringBuilder();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(n.getNodeValue());
            }
        }
        String t = SPACES.matcher(sb).replaceAll(" ").trim();
        return t.isEmpty() ? "" : ": " + t;
    }

    private static String local(Element e) {
        String n = e.getTagName();
        return n.contains(":") ? n.substring(n.indexOf(':') + 1) : n;
    }

    private static List<Element> children(Element parent) {
        List<Element> elements = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                elements.add(e);
            }
        }
        return elements;
    }

    private static Document parseXml(String text) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Someone else's repository: no doctype, no external entities.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setNamespaceAware(false);
            factory.setIgnoringComments(true);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("not well-formed XML: " + e.getMessage(), e);
        }
    }
}
