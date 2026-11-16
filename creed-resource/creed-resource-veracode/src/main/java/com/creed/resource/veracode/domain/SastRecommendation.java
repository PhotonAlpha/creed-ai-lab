package com.creed.resource.veracode.domain;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One paragraph of Veracode's remediation advice: a {@code <para text>} under
 * {@code severity/category/recommendations}, with its {@code <bulletitem text>}s. Veracode words it per flaw
 * <em>category</em>, not per flaw - every flaw of a category carries the same list.
 */
public record SastRecommendation(String text, List<String> bullets) {

    public SastRecommendation {
        bullets = bullets == null ? List.of() : List.copyOf(bullets);
    }

    /**
     * The flaws' advice, each paragraph once, in the order first met. A SAST issue groups by CWE + {@code categoryname},
     * which normally sits under one {@code <category>}; should two categories meet in one issue, both are kept.
     */
    public static List<SastRecommendation> of(Collection<SastFlaw> flaws) {
        Set<SastRecommendation> all = new LinkedHashSet<>();
        flaws.forEach(f -> all.addAll(f.recommendations()));
        return List.copyOf(all);
    }

    /** Plain text: one line per paragraph, its bullets below it as {@code • item}. */
    public static String plain(List<SastRecommendation> recommendations) {
        return recommendations.stream()
                .map(r -> r.bullets().isEmpty() ? r.text()
                        : r.text() + r.bullets().stream().map(b -> "\n• " + b).collect(Collectors.joining()))
                .collect(Collectors.joining("\n"));
    }
}
