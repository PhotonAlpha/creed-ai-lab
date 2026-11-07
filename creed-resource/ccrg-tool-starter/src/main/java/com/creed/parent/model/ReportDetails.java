package com.creed.parent.model;

import lombok.Data;

@Data
public class ReportDetails {
    private String releaseName;
    private String countryCode;
    private String profile;
    private String environment;
    private String instance;
    private String fileNames;
}
