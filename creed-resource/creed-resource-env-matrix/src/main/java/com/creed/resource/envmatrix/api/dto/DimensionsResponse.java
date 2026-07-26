package com.creed.resource.envmatrix.api.dto;

import java.util.List;

/**
 * Distinct values currently present for each dimension — the source for the UI's filter dropdowns
 * and the config form's select options.
 *
 * <p>Derived from the data rather than hard-coded, so adding a row with a new country or a new
 * {@code UATn} instance extends the option lists without a code change (the requirement outline's
 * "new dimension values" rule).
 */
public record DimensionsResponse(
        List<String> appSystem,
        List<String> tier,
        List<String> envInstance,
        List<String> country,
        List<String> service,
        List<String> instance,
        List<String> scheme) {
}
