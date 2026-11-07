package com.creed.parent.controller.admin.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.creed.parent.dal.model.WalkPathInfo;
import lombok.Data;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 7/22/25
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class InstalledRequestVo extends WalkPathInfo {
    private String buildNo;
}
