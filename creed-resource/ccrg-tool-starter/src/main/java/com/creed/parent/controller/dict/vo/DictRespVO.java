package com.creed.parent.controller.dict.vo;

import com.creed.parent.constant.CommonStatusEnum;
import lombok.Data;

@Data
public class DictRespVO {

    private Long id;

    private String name;

    private Long parentId;

    private Integer sort;

    private Long nodeId;
    private Long zoneId;

    private CommonStatusEnum status;



}
