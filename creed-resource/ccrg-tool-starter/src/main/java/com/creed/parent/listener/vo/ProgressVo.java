package com.creed.parent.listener.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 2026-01-27T00
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProgressVo {
    private String connectionId;
    private AtomicInteger propertyCount;
    private AtomicInteger reportCount;
    private AtomicInteger total;
}
