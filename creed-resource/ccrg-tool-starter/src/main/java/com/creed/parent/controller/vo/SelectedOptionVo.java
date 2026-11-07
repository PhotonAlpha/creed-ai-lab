package com.creed.parent.controller.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 9/2/24
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SelectedOptionVo {
    private Long id;
    private String value;
    private String name;
    private Boolean selected = Boolean.FALSE;

    private String created;
    private String repositoryPath;
    private String uiPath;

    public SelectedOptionVo(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public SelectedOptionVo(Long id, String name, Boolean selected) {
        this.id = id;
        this.name = name;
        this.selected = selected;
    }

    public SelectedOptionVo(Long id, String value, String name, Boolean selected) {
        this.id = id;
        this.value = value;
        this.name = name;
        this.selected = selected;
    }
}
