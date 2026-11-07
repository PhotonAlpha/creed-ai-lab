package com.creed.parent.controller.dict.vo;

import com.creed.parent.constant.CommonStatusEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DictSaveReqVO {

    private Long id;

    @NotBlank(message = "name can not be empty")
    @Size(max = 30, message = "max length is 30")
    private String name;

    private Long parentId;

    @NotNull(message = "sort can not be empty")
    private Integer sort;

    private Long nodeId;
    private Long zoneId;

    @NotNull(message = "状态不能为空")
    private CommonStatusEnum status;

}
