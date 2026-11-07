package com.creed.parent.service.walkpath;

import com.creed.parent.config.MetadataConfigConfig;
import com.creed.parent.constant.AppCodeEnum;
import com.creed.parent.dal.model.WalkPathInfo;
import com.creed.parent.model.DocumentInfo;
import com.creed.parent.service.DictIndexingStorage;
import com.creed.parent.utils.PropertySourcesTranslateUtil;
import com.creed.parent.utils.json.JacksonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.lucene.search.Query;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 28/5/25
 */
@Slf4j
@Service
public class ZonesCalculateService {
    public static final  Pattern LX_INSTANCE_PATTERN = Pattern.compile(".*LX/(?<instance>(QUA|PDN)(\\d*\\d|\\d*))/([^/]+).*");
    public static final  Pattern LX_APP_INSTANCE_PATTERN = Pattern.compile(".*LX_APP/(?<instance>(QUA/[^/]+)|([^/]+))/[^/]+.*");

    @Resource
    DictIndexingStorage dictIndexingStorage;
    @Resource
    MetadataConfigConfig metadataConfigConfig;

    public Map<String, Set<String>> searchInstanceMatrix(Path rootPath, WalkPathInfo walkPathInfo, String searchString, String propertyKey, AppCodeEnum appCodeEnum) {
        Supplier<Query> querySupplier = dictIndexingStorage.querySupplier4GroupZone(searchString, walkPathInfo);

        List<DocumentInfo> documentInfos = dictIndexingStorage.searchInMemory(rootPath, walkPathInfo, Collections.singletonList(querySupplier));
        log.info("documentInfo size:{}", documentInfos.size());
        for (DocumentInfo info : documentInfos) {
            log.info("path:{} extension:{}", info.getPath(), info.getExtension());
        }
        List<Pair<String, DocumentInfo>> profileSets = PropertySourcesTranslateUtil.searchPropertyKeyWithMeta(documentInfos, propertyKey, StringUtils::isNotBlank);
        log.info("calculated zones:{}", profileSets.size());

        // MS CCS has respective logic
        Map<String, Set<String>> zoneMatrix;
        if (AppCodeEnum.MS.equals(appCodeEnum)) {
            zoneMatrix = lxInstanceMatrix(profileSets);
        } else {
            zoneMatrix = lxAppInstanceMatrix(profileSets);
        }
        log.info("zoneMatrix:{}", JacksonUtils.toJsonString(zoneMatrix));
        return zoneMatrix;
    }

    public Map<String, Set<String>> lxInstanceMatrix(List<Pair<String, DocumentInfo>> profileSets) {
        UnaryOperator<String> instanceFunc = path -> {
            Matcher pathMatcher = LX_INSTANCE_PATTERN.matcher(path);
            String instance = StringUtils.EMPTY;
            if (pathMatcher.matches()) {
                instance = pathMatcher.group("instance"); // MS required
            }
            return instance;
        };
        return instanceMatrixConclude(profileSets, instanceFunc);
    }

    public Map<String, Set<String>> lxAppInstanceMatrix(List<Pair<String, DocumentInfo>> profileSets) {
        UnaryOperator<String> instanceFunc = path -> {
            Matcher pathMatcher = LX_APP_INSTANCE_PATTERN.matcher(path);
            String instance = StringUtils.EMPTY;
            if (pathMatcher.matches()) {
                instance = pathMatcher.group("instance"); // MS required
                if (!StringUtils.isBlank(instance)) {
                    instance = StringUtils.replace(instance, "QUA/", "");
                }
            }
            return instance;
        };
        return instanceMatrixConclude(profileSets, instanceFunc);
    }


    public Map<String, Set<String>> instanceMatrixConclude(List<Pair<String, DocumentInfo>> profileSets, UnaryOperator<String> instanceFunc) {
        List< Pair<String, String>> combList = new ArrayList<>();

        for (var dir : profileSets) {
            log.info(":::::::dir:{} path:{}", dir.getLeft(), dir.getRight().getPath());
            String instance = instanceFunc.apply(dir.getRight().getPath());
            if (StringUtils.isBlank(instance)) {
                continue;
            }
            combList.add(Pair.of(instance, dir.getLeft()));
        }
        return combList.stream().collect(Collectors.groupingBy(Pair::getLeft, Collectors.mapping(Pair::getRight, Collectors.toSet())));
    }
}
