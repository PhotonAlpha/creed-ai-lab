package com.creed.resource.envmatrix.api.dto;

import java.util.List;

/**
 * One page of the result list.
 *
 * @param page  1-based, as the page's table counts
 * @param total matching records across all pages
 */
public record AesRecordPage(List<AesRecordDto> items, long total, int page, int size) {
}
