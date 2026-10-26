package com.creed.resource.migrationrisk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** A file pattern (and optionally one category) nobody needs to review — generated code, docs, CI files (V3). */
@Entity
@Table(name = "mr_ignore_rule")
@Getter
@Setter
public class MrIgnoreRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Ant-style, over repo-relative paths: {@code **}{@code /generated/**}, {@code docs/**}, {@code **}{@code /*.md}. */
    @Column(nullable = false, length = 500)
    private String pattern;

    /** Only findings of this category; null = the whole file. */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private RiskCategory category;

    /** The repo1 path or URL this rule is for; null = every project. */
    @Column(length = 1000)
    private String scope;

    @Column(length = 1000)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
