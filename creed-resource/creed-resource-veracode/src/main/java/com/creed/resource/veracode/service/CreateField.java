package com.creed.resource.veracode.service;

import com.creed.resource.veracode.client.jira.JiraFieldMeta;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A create-screen field the page asks the user for: Component/s, and every other required field without a
 * default that this service does not fill itself. Read through the user's token, so the options are what that
 * user may choose.
 *
 * @param kind     {@code OPTIONS} (a choice from {@code options}), {@code USER} (a login), {@code NUMBER} or
 *                 {@code TEXT}
 * @param multiple the value is a list ({@code schema.type = array})
 * @param defaults preselected values: {@code veracode.jira.defaults.fields} as far as Jira offers them, else Jira's
 *                 default, else the only option of a required field (option ids for {@code OPTIONS})
 */
public record CreateField(String fieldId, String name, boolean required, Kind kind, boolean multiple,
                          List<Option> options, List<String> defaults) {

    public enum Kind { OPTIONS, USER, NUMBER, TEXT }

    /** @param label a component's / version's {@code name}, a custom option's {@code value} */
    public record Option(String id, String label) {
    }

    static CreateField of(JiraFieldMeta meta) {
        JiraFieldMeta.Schema schema = meta.schema();
        boolean multiple = schema != null && "array".equals(schema.type());
        List<Option> options = new ArrayList<>();
        for (JsonNode v : meta.allowedValues() == null ? List.<JsonNode>of() : meta.allowedValues()) {
            String id = v.path("id").asString(null);
            String label = firstText(v, "name", "value");
            if (id != null) {
                options.add(new Option(id, label != null ? label : id));
            }
        }
        String valueType = schema == null ? "" : multiple && schema.items() != null ? schema.items() : schema.type();
        Kind kind = !options.isEmpty() ? Kind.OPTIONS
                : "user".equals(valueType) ? Kind.USER
                : "number".equals(valueType) ? Kind.NUMBER
                : Kind.TEXT;
        List<String> defaults = defaults(meta.defaultValue(), kind);
        if (defaults.isEmpty() && meta.required() && options.size() == 1) {
            defaults = List.of(options.getFirst().id());
        }
        String name = meta.name() != null ? meta.name() : meta.fieldId();
        return new CreateField(meta.fieldId(), name, meta.required(), kind, multiple, List.copyOf(options), defaults);
    }

    /**
     * The configured values in place of {@link #defaults}: option ids for a choice, the configured ones it does not
     * offer dropped. None left (or none configured) = unchanged.
     */
    CreateField withConfigured(List<String> configured) {
        List<String> values = new ArrayList<>();
        for (String value : configured) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (kind != Kind.OPTIONS) {
                values.add(value.trim());
            } else if (option(value.trim()) != null && !values.contains(option(value.trim()).id())) {
                values.add(option(value.trim()).id());
            }
        }
        if (values.isEmpty()) {
            return this;
        }
        return new CreateField(fieldId, name, required, kind, multiple, options,
                List.copyOf(multiple ? values : values.subList(0, 1)));
    }

    /** The option for an id, or for a label (any case) - the API takes either. */
    Option option(String idOrLabel) {
        for (Option o : options) {
            if (o.id().equals(idOrLabel)) {
                return o;
            }
        }
        for (Option o : options) {
            if (o.label().toLowerCase(Locale.ROOT).equals(idOrLabel.toLowerCase(Locale.ROOT))) {
                return o;
            }
        }
        return null;
    }

    private static List<String> defaults(JsonNode value, Kind kind) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode v : value.isArray() ? value : List.of(value)) {
            String text = kind == Kind.OPTIONS ? v.path("id").asString(null)
                    : v.isValueNode() ? v.asString(null) : firstText(v, "name", "value");
            if (text != null && !text.isBlank()) {
                out.add(text);
            }
        }
        return List.copyOf(out);
    }

    private static String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            String text = node.path(key).asString(null);
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }
}
