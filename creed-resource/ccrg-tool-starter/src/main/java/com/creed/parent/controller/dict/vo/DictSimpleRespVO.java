package com.creed.parent.controller.dict.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DictSimpleRespVO {

    private Long id;

    private String name;

    private Long parentId;

}
