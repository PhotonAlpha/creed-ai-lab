package com.creed.parent.controller.dict.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.creed.parent.constant.CommonStatusEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DictConfigVO {

    private Long id;

    private String name;

    private Long parentId;

    private Integer sort;

    private Long nodeId;
    private Long zoneId;

    private CommonStatusEnum status;

    private List<DictConfigVO> children;

}
