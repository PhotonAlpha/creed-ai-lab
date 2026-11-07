package com.creed.parent.controller.admin.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 7/22/25
 */
@NoArgsConstructor
@AllArgsConstructor
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class InstalledInfoVo {
    private String parentName;
    private String name;
    private String lastUpdateTime;
    private List<InstalledInfoVo> children;

    public InstalledInfoVo(String parentName, String name, String lastUpdateTime) {
        this.parentName = parentName;
        this.name = name;
        this.lastUpdateTime = lastUpdateTime;
    }
}
