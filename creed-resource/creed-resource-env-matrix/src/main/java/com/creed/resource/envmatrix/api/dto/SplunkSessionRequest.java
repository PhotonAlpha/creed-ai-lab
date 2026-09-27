package com.creed.resource.envmatrix.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** The code the user typed. Digits only; the length is checked against {@code env-matrix.totp.digits}. */
public record SplunkSessionRequest(
        @NotBlank @Pattern(regexp = "\\d{6,8}", message = "must be 6-8 digits") String code) {
}
