package com.creed.parent.service.walkpath;

import com.creed.parent.constant.WalkPathStrategy;
import com.creed.parent.dal.model.WalkPathInfo;
import com.creed.parent.model.DocumentInfo;
import com.creed.parent.service.DictIndexingStorage;
import com.creed.parent.utils.PropertySourcesTranslateUtil;
import jakarta.annotation.Resource;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 28/5/25
 */
@Setter
@Slf4j
@Service
public class ZoneWalkPathStrategy implements WalkPathStrategy {
    public static final Pattern ZONES_PATH = Pattern.compile("-(?<profile>([^\\\\-]+))$");

    @Resource
    @Setter
    DictIndexingStorage dictIndexingStorage;

    @Override
    public List<Path> apply(Path rootPath, WalkPathInfo info) {
        List<DocumentInfo> documentInfos = dictIndexingStorage.searchZoneInMemory(rootPath, info, "on-profile");
        Set<String> zones = PropertySourcesTranslateUtil.searchPropertyKey(documentInfos, "spring.config.activate.on-profile");


        log.info("calculated zones:{}", zones);
        List<Path> results = zones.stream()
                .filter(StringUtils::isNotBlank)
                .map(Path::of)
                .toList();
        /* if (CollectionUtils.isEmpty(results)) {
            results = additionalZoneFromPath(rootPath, info);
            log.info("calculated zones from path:{}", results);
        } */
        if (CollectionUtils.isEmpty(results)) {
            return List.of(Path.of("default"));
        } else {
            return results;
        }
    }

    protected List<Path> additionalZoneFromPath(Path rootPath, WalkPathInfo info) {
        log.info("calculated zones from path:{}", rootPath);
        List<DocumentInfo> documentPathInfos = dictIndexingStorage.searchZone4PathInMemory(rootPath, info, "lc OR opi");
        List<Path> result = new ArrayList<>();
        result.add(Path.of("default"));
        List<Path> pathList = documentPathInfos.stream()
                .map(DocumentInfo::getPath)
                .filter(StringUtils::isNotBlank)
                .map(Path::of)
                .map(Path::getParent)
                .map(this::matchingFolder)
                .distinct()
                .filter(StringUtils::isNotBlank)
                .map(Path::of)
                .toList();
        result.addAll(pathList);
        return result;

    }

    private String matchingFolder(Path path) {
        String fileName = Optional.ofNullable(path).map(Path::getFileName).map(Path::toString).orElse("");
        Matcher instPathMatcher = ZONES_PATH.matcher(fileName);
        if (instPathMatcher.find()) {
            return instPathMatcher.group("profile");
        }
        return StringUtils.EMPTY;
    }

}
