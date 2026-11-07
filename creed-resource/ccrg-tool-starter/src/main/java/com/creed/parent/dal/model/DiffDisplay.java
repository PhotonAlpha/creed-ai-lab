package com.creed.parent.dal.model;

import lombok.Data;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 25/6/25
 */
@Data
public class DiffDisplay {
    private DiffInfo left;
    private DiffInfo right;
}
