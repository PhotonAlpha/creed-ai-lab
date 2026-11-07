package com.creed.parent.constant;

import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

import java.util.stream.Stream;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 28/5/25
 */
public enum WalkPathTypeEnum {
    /**
     * calculate path from
     * Project Name
     * Release Folder
     * Country
     * Application
     */
    COMMON("commonWalkPathStrategy"),
    /**
     * calculate path for ENVIRONMENT
     */
    APP("appWalkPathStrategy"),
    /**
     * calculate path for ENVIRONMENT
     */
    ENVIRONMENT("environmentWalkPathStrategy"),
    /**
     * calculate path for INSTANCE
     */
    INSTANCE("instanceWalkPathStrategy"),
    /**
     * calculate path for MODULE
     */
    MODULE("moduleWalkPathStrategy"),
    /**
     * calculate path for ZONE
     */
    ZONE("zoneWalkPathStrategy"),
    /**
     * if not found
     */
    NA("emptyWalkPathStrategy");
    @Getter
    private String beanName;

    WalkPathTypeEnum(String beanName) {
        this.beanName = beanName;
    }

    public static WalkPathTypeEnum of(String name) {
        return Stream.of(WalkPathTypeEnum.values())
                .filter(e -> StringUtils.equalsIgnoreCase(e.name(), name))
                .findFirst()
                .orElse(WalkPathTypeEnum.COMMON);
    }
    public static WalkPathTypeEnum to(String beanName) {
        return Stream.of(WalkPathTypeEnum.values())
                .filter(e -> StringUtils.equals(e.getBeanName(), beanName))
                .findFirst()
                .orElse(WalkPathTypeEnum.NA);
    }

}
