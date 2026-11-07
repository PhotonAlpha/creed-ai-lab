package com.creed.parent.controller.vo;

import com.creed.parent.dal.model.DiffDisplay;

import java.util.List;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 2026-06-18T16
 */
public record PropertiesOverviewVo(PropertyReviewVo leftPropertyReview, PropertyReviewVo rightPropertyReview, List<DiffDisplay> displayList) {
}
