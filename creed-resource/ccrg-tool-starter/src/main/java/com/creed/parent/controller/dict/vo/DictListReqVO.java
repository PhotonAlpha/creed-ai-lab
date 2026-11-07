package com.creed.parent.controller.dict.vo;

import com.creed.parent.constant.CommonStatusEnum;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class DictListReqVO {

    private String parentId;
    private String parentName;
    private String id;
    private String name;

    private CommonStatusEnum status;

    public DictListReqVO() {
    }

    public DictListReqVO(String parentName) {
        this.parentName = parentName;
    }
}
