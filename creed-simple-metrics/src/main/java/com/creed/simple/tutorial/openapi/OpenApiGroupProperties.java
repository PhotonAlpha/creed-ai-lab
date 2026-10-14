package com.creed.simple.tutorial.openapi;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups of the OpenAPI specification, bound from {@code creed.tutorial.openapi} in application.yml.
 *
 * <p>A group is nothing but a set of tags: the served document is {@link #getSpec() the single
 * spec file} with every operation that does not carry one of those tags removed. Adding a group
 * is therefore a configuration change, not a code change.
 *
 * <p>Groups normally live in the spec itself, under the root {@code x-groups} extension. The list
 * here overrides that one when it is set; see {@link OpenApiGroupService#groups()}.
 */
@ConfigurationProperties(prefix = "creed.tutorial.openapi")
public class OpenApiGroupProperties {

    /** Location of the one and only specification; every group is a filtered view of it. */
    private String spec = "classpath:tutorial/openapi.yaml";

    private List<Group> groups = new ArrayList<>();

    public String getSpec() {
        return spec;
    }

    public void setSpec(String spec) {
        this.spec = spec;
    }

    public List<Group> getGroups() {
        return groups;
    }

    public void setGroups(List<Group> groups) {
        this.groups = groups;
    }

    public static class Group {

        /** URL segment of the group, e.g. {@code business} serves /openapi/business.yaml. */
        private String name;

        /** Label shown in the Swagger UI definition dropdown; defaults to {@link #name}. */
        private String displayName;

        // ------------------------------------------------------------------
        // Filter criteria. They are ANDed, and an empty one is simply not a
        // constraint - a group that sets none of them keeps the whole document.
        // ------------------------------------------------------------------

        /** Keep an operation when one of its tags is listed here. */
        private List<String> tags = new ArrayList<>();

        /** Keep a path when it matches one of these Ant patterns, e.g. {@code /api/v1/orders/**}. */
        private List<String> paths = new ArrayList<>();

        /** Keep an operation when its HTTP method is listed here, e.g. {@code [post]}. */
        private List<String> methods = new ArrayList<>();

        /** Drop operations marked {@code x-internal: true} in the spec. */
        private boolean excludeInternal;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDisplayName() {
            return displayName == null || displayName.isBlank() ? name : displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }

        public List<String> getPaths() {
            return paths;
        }

        public void setPaths(List<String> paths) {
            this.paths = paths;
        }

        public List<String> getMethods() {
            return methods;
        }

        public void setMethods(List<String> methods) {
            this.methods = methods;
        }

        public boolean isExcludeInternal() {
            return excludeInternal;
        }

        public void setExcludeInternal(boolean excludeInternal) {
            this.excludeInternal = excludeInternal;
        }

        /** True when the group states no criterion at all, i.e. it is the whole document. */
        public boolean isUnconstrained() {
            return tags.isEmpty() && paths.isEmpty() && methods.isEmpty() && !excludeInternal;
        }

        /** How the group reads in a log line. */
        public String describeCriteria() {
            if (isUnconstrained()) {
                return "(everything)";
            }
            StringBuilder text = new StringBuilder();
            if (!tags.isEmpty()) {
                text.append("tags=").append(tags);
            }
            if (!paths.isEmpty()) {
                text.append(text.isEmpty() ? "" : " AND ").append("paths=").append(paths);
            }
            if (!methods.isEmpty()) {
                text.append(text.isEmpty() ? "" : " AND ").append("methods=").append(methods);
            }
            if (excludeInternal) {
                text.append(text.isEmpty() ? "" : " AND ").append("not x-internal");
            }
            return text.toString();
        }
    }
}
