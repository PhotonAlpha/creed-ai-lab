package com.creed.parent.service;

import com.creed.parent.constant.WalkPathStrategy;
import com.creed.parent.constant.WalkPathTypeEnum;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 28/5/25
 */
@Component
public class WalkPathStrategyFactory {
    @Resource
    Map<String, WalkPathStrategy> walkPathStrategyMap;

    private Map<WalkPathTypeEnum, WalkPathStrategy> FACTORY = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        FACTORY.put(WalkPathTypeEnum.COMMON, WalkPathStrategy.simple());
        for (Map.Entry<String, WalkPathStrategy> entry : walkPathStrategyMap.entrySet()) {
            FACTORY.put(WalkPathTypeEnum.to(entry.getKey()), entry.getValue());
        }
    }


    public WalkPathStrategy getWalkPathStrategy(WalkPathTypeEnum type) {
        return FACTORY.get(type);
    }
}
