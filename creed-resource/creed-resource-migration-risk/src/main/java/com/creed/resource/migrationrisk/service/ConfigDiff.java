package com.creed.resource.migrationrisk.service;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Key-level diffs for the files RefactoringMiner cannot read and Boot 4 changes most: Spring config
 * ({@code application*.yml|yaml|properties}, {@code bootstrap*}) and {@code pom.xml}. Each changed key is
 * also looked up in repo2's version of the file — a key repo2 does not have is exactly the
 * "renamed in Boot 4" case worth checking.
 */
public final class ConfigDiff {

    private static final Pattern SPRING_CONFIG = Pattern.compile("(application|bootstrap)([-.][^/]*)?\\.(ya?ml|properties)");

    private ConfigDiff() {
    }

    public static boolean isSpringConfig(String path) {
        return SPRING_CONFIG.matcher(fileName(path)).matches();
    }

    public static boolean isPom(String path) {
        return fileName(path).equals("pom.xml");
    }

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * {@code + key = value}, {@code - key}, {@code ~ key: old -> new}, each followed by what repo2 has:
     * {@code (repo2: value)} or {@code (repo2: missing)}. {@code target} null = repo2 has no such file.
     */
    public static List<String> configLines(String path, String before, String after, String target) {
        return lines(flatten(path, before), flatten(path, after), target == null ? null : flatten(path, target));
    }

    public static List<String> pomLines(String before, String after, String target) {
        return lines(pom(before), pom(after), target == null ? null : pom(target));
    }

    private static List<String> lines(Map<String, String> before, Map<String, String> after, Map<String, String> target) {
        List<String> lines = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>(after.keySet());
        keys.addAll(before.keySet());
        for (String key : keys) {
            String old = before.get(key);
            String now = after.get(key);
            if (Objects.equals(old, now)) {
                continue;
            }
            String change = old == null ? "+ " + key + " = " + now
                    : now == null ? "- " + key
                    : "~ " + key + ": " + old + " -> " + now;
            lines.add(change + "  " + inTarget(target, key));
        }
        return lines;
    }

    private static String inTarget(Map<String, String> target, String key) {
        if (target == null) {
            return "(repo2: no such file)";
        }
        return target.containsKey(key) ? "(repo2: " + target.get(key) + ")" : "(repo2: missing)";
    }

    // ---------------------------------------------------------------- Spring config

    static Map<String, String> flatten(String path, String text) {
        Map<String, String> keys = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return keys;
        }
        if (path.endsWith(".properties")) {
            Properties properties = new Properties();
            try {
                properties.load(new StringReader(text));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            properties.stringPropertyNames().stream().sorted().forEach(k -> keys.put(k, properties.getProperty(k)));
            return keys;
        }
        // SafeConstructor: a config file from someone else's repository must not instantiate arbitrary types.
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        int document = 0;
        for (Object doc : yaml.loadAll(text)) {
            // Profile documents after '---' can repeat a key with another value; keep them apart.
            flattenInto(keys, document == 0 ? "" : "[doc " + document + "] ", doc);
            document++;
        }
        return keys;
    }

    private static void flattenInto(Map<String, String> keys, String prefix, Object value) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> flattenInto(keys, prefix.isEmpty() || prefix.endsWith(" ")
                    ? prefix + k : prefix + "." + k, v));
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                flattenInto(keys, prefix + "[" + i + "]", list.get(i));
            }
        } else if (!prefix.isEmpty()) {
            keys.put(prefix.trim(), String.valueOf(value));
        }
    }

    // ---------------------------------------------------------------- pom.xml

    /**
     * {@code dependency g:a[:classifier]} -> version (or {@code managed}) + non-default scope,
     * {@code parent g:a} -> version, {@code property name} -> value.
     */
    static Map<String, String> pom(String text) {
        Map<String, String> keys = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return keys;
        }
        Document doc = parseXml(text);
        Element project = doc.getDocumentElement();
        Element parent = child(project, "parent");
        if (parent != null) {
            keys.put("parent " + text(parent, "groupId") + ":" + text(parent, "artifactId"), text(parent, "version"));
        }
        Element properties = child(project, "properties");
        if (properties != null) {
            for (Element p : children(properties)) {
                keys.put("property " + p.getTagName(), p.getTextContent().trim());
            }
        }
        NodeList dependencies = doc.getElementsByTagName("dependency");
        for (int i = 0; i < dependencies.getLength(); i++) {
            Element d = (Element) dependencies.item(i);
            String section = ((Element) d.getParentNode().getParentNode()).getTagName().equals("dependencyManagement")
                    ? "managed dependency " : "dependency ";
            String classifier = text(d, "classifier");
            String key = section + text(d, "groupId") + ":" + text(d, "artifactId")
                    + (classifier == null ? "" : ":" + classifier);
            String version = Objects.requireNonNullElse(text(d, "version"), "managed");
            String scope = text(d, "scope");
            keys.put(key, scope == null || scope.equals("compile") ? version : version + " (" + scope + ")");
        }
        return keys;
    }

    private static Document parseXml(String text) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // External entities from another team's pom are not something to resolve.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setNamespaceAware(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("pom.xml is not well-formed: " + e.getMessage(), e);
        }
    }

    private static Element child(Element parent, String name) {
        return children(parent).stream().filter(e -> e.getTagName().equals(name)).findFirst().orElse(null);
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

    private static String text(Element parent, String name) {
        Element e = child(parent, name);
        return e == null ? null : e.getTextContent().trim();
    }
}
