package com.creed.parent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.creed.parent.config.PropertiesMatrixConfig;
import com.creed.parent.constant.AppCodeEnum;
import com.creed.parent.constant.WalkPathTypeEnum;
import com.creed.parent.controller.vo.SelectedOptionVo;
import com.creed.parent.dal.model.WalkPathInfo;
import com.creed.parent.service.walkpath.AppWalkPathStrategy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static com.creed.parent.service.walkpath.AppWalkPathStrategy.LEGACY_APP_CODE;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 9/5/24
 */
@Service
@Slf4j
public class DictLoadService {
    @Resource
    PropertiesMatrixConfig propertiesMatrixConfig;
    @Resource
    WalkPathStrategyFactory walkPathStrategyFactory;

    public static final ObjectMapper mapper = new ObjectMapper();

    public List<SelectedOptionVo> pathWalk(WalkPathInfo info) {
        String rootPath = propertiesMatrixConfig.getRootPath();
        WalkPathTypeEnum walkPathType = WalkPathTypeEnum.of(info.getDictType());
        log.info("rootPath:{} walkPathType:{}", rootPath, walkPathType);
        Predicate<AppCodeEnum> appCodeFilter;
        if (AppWalkPathStrategy.LEGACY_REPOS.contains(info.getProjectName())) {
            appCodeFilter = LEGACY_APP_CODE::contains;
        } else {
            appCodeFilter = appCode -> true;
        }

        List<Path> list = walkPathStrategyFactory.getWalkPathStrategy(walkPathType).apply(Path.of(rootPath), info);
        log.info("list:{}", list);
        AtomicLong index = new AtomicLong(1);
        List<SelectedOptionVo> selectors = list.stream()
                .map(Path::getFileName)
                .map(Path::toString)
                .sorted((s1, s2) -> {
                    boolean s1IsPdn = Strings.CI.startsWith(s1, "PDN");
                    boolean s2IsPdn = Strings.CI.startsWith(s2, "PDN");
                    if (s1IsPdn && !s2IsPdn) {
                        return -1;
                    }
                    if (!s1IsPdn && s2IsPdn) {
                        return 1;
                    }
                    return s1.compareTo(s2);
                })
                .map(name -> StringUtils.defaultIfBlank(AppCodeEnum.toDisplayName(name, appCodeFilter), name))
                .map(name -> new SelectedOptionVo(index.getAndIncrement(), name, name, Boolean.FALSE))
                .toList();
        if (selectors.size() == 1) {
            selectors.forEach(op -> op.setSelected(true));
        }
        return selectors;
    }
}
