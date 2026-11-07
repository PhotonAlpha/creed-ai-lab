package com.creed.parent.controller.vo;

/**
 * Property counts behind the "Overall Comparison" tab, keyed by property name across both sources:
 * {@code total = identical + changed + onlyInBaseline + onlyInComparison}.
 *
 * @author EthanCao
 * @description ccrg-tool
 * @date 2026-11-07
 */
public record OverallSummaryVo(int total, int identical, int changed, int onlyInBaseline, int onlyInComparison) {
}
