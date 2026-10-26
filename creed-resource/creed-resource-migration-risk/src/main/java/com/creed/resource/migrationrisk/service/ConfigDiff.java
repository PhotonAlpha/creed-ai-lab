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
 * Key-level reading of the files RefactoringMiner cannot read and Boot 4 changes most: properties / yml (as
 * global key names, see {@link #globalKeys} and {@link GlobalConfigAnalyzer}) and {@code pom.xml} (diffed per file,
 * each changed key looked up in repo2's pom — a key repo2 does not have is the "renamed in Boot 4" case).
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

    /** A properties / yml file the global config comparison reads: Spring config anywhere, else per {@code configFiles}. */
    public static boolean isConfigFile(String path, Pattern configFiles) {
        return isSpringConfig(path) || configFiles.matcher(path).matches();
    }

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * pom keys, {@code + key = value}, {@code - key}, {@code ~ key: old -> new}, each followed by what repo2 has:
     * {@code (repo2: value)} or {@code (repo2: missing)}. {@code target} null = repo2 has no such file.
     */
    public static List<String> pomLines(String before, String after, String target) {
        return lines(pom(before), pom(after), target == null ? null : pom(target));
    }

    /** Every pom key repo1 changed has repo1 HEAD's value in repo2's pom (a removed key: repo2 lacks it too). */
    public static boolean pomInSync(String before, String after, String target) {
        if (target == null) {
            return false;
        }
        Map<String, String> was = pom(before);
        Map<String, String> now = pom(after);
        Map<String, String> there = pom(target);
        Set<String> keys = new LinkedHashSet<>(now.keySet());
        keys.addAll(was.keySet());
        return keys.stream().filter(k -> !Objects.equals(was.get(k), now.get(k)))
                .allMatch(k -> Objects.equals(now.get(k), there.get(k)));
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

    // ---------------------------------------------------------------- global keys

    /**
     * The file's keys as the global comparison names them, so the same setting in another file is the same key:
     * <ul>
     *   <li>Spring config: the bare key for the default profile ({@code shop.order.max-items}), else
     *       {@code [dev] key} — from {@code application-dev.yml} or a yml document's
     *       {@code spring.config.activate.on-profile} / {@code spring.profiles};</li>
     *   <li>any other file: {@code [messages_zh.properties] key} — its keys mean nothing outside that file name;</li>
     *   <li>test resources get a {@code test:} scope, so a test override never reads as the production value.</li>
     * </ul>
     * Whether {@code application.yml} or {@code application.properties}, in which module, under {@code config/}
     * or not, does not change the name — that is what makes a key moved between those files no change at all.
     */
    public static Map<String, String> globalKeys(String path, String text, boolean test) {
        Map<String, String> keys = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return keys;
        }
        String name = fileName(path);
        String fileScope = isSpringConfig(path) ? springProfile(name) : name;
        if (path.endsWith(".properties")) {
            Properties properties = new Properties();
            try {
                properties.load(new StringReader(text));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            properties.stringPropertyNames().stream().sorted()
                    .forEach(k -> keys.put(scoped(test, fileScope, k), properties.getProperty(k)));
            return keys;
        }
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        int document = 0;
        for (Object doc : yaml.loadAll(text)) {
            Map<String, String> flat = new LinkedHashMap<>();
            flattenInto(flat, "", doc);
            String scope = fileScope;
            if (isSpringConfig(path)) {
                String profile = flat.getOrDefault("spring.config.activate.on-profile", flat.get("spring.profiles"));
                if (profile != null) {
                    scope = profile;
                } else if (document > 0) {
                    scope = scope.isEmpty() ? "doc " + document : scope + " doc " + document;
                }
            } else if (document > 0) {
                scope = scope + " doc " + document;
            }
            String finalScope = scope;
            flat.forEach((k, v) -> keys.put(scoped(test, finalScope, k), v));
            document++;
        }
        return keys;
    }

    /** {@code application-dev.yml} -> {@code dev}; {@code application.yml} / {@code bootstrap.yml} -> "". */
    private static String springProfile(String fileName) {
        String base = fileName.substring(0, fileName.lastIndexOf('.'));
        int dash = base.indexOf('-');
        return dash < 0 ? "" : base.substring(dash + 1);
    }

    private static String scoped(boolean test, String scope, String key) {
        String s = (test ? "test" + (scope.isEmpty() ? "" : ":") : "") + scope;
        return s.isEmpty() ? key : "[" + s + "] " + key;
    }

    // ---------------------------------------------------------------- yml

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
