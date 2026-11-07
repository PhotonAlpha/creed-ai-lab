package com.creed.parent.service.walkpath;

import com.creed.parent.constant.AppCodeEnum;
import com.creed.parent.constant.WalkPathStrategy;
import com.creed.parent.dal.model.WalkPathInfo;
import com.creed.parent.model.DocumentInfo;
import com.creed.parent.service.DictIndexingStorage;
import jakarta.annotation.Resource;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.lucene.search.Query;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 28/5/25
 */
@Setter
@Slf4j
@Service
public class AppWalkPathStrategy implements WalkPathStrategy {
    @Resource
    DictIndexingStorage dictIndexingStorage;
    public static final Set<String> LEGACY_REPOS = Set.of("idbcommonutils", "idbconfigs", "idbproxy", "idbnotificationservice", "idb-batch-service", "idb-global-ccs");
    public static final Set<AppCodeEnum> LEGACY_APP_CODE = Set.of(AppCodeEnum.MS, AppCodeEnum.CCS, AppCodeEnum.CCS_LC, AppCodeEnum.CCS_AGGREGATOR, AppCodeEnum.CCS_LC_AGGREGATOR);
    @Override
    public List<Path> apply(Path rootPath, WalkPathInfo info) {
        List<Supplier<Query>> suppliers = dictIndexingStorage.querySupplier4App(info, "application-aggregator.properties", "application-lc-aggregator.properties", "application-lc.properties");
        List<DocumentInfo> propertiesCheck = dictIndexingStorage.searchInMemory(rootPath, info, suppliers);
        Set<Path> appInstances = propertiesCheck.stream()
                .map(DocumentInfo::getPath)
                .map(Path::of)
                .map(Path::getFileName)
                .map(Path::toString)
                .map(this::appCodeDetect)
                .filter(StringUtils::isNotBlank)
                .map(Path::of)
                .collect(Collectors.toSet());
        Path partialPath = WalkPathStrategy.resolvePath(info.getProjectName(), info.getReleaseName(), info.getCountryCode()).normalize();
        List<Path> paths = WalkPathStrategy.listFiles(rootPath.resolve(partialPath));
        if (!LEGACY_REPOS.contains(info.getProjectName())) {
            paths = extraCheck4Repo(rootPath, info, paths);
        }

        List<Path> finalList = new ArrayList<>(paths);
        finalList.addAll(appInstances);
        log.info("finalList:{}", finalList);
        return finalList;
    }

    private List<Path> extraCheck4Repo(Path rootPath, WalkPathInfo info, List<Path> appList) {
        // resolve idb-xxx-service-lc idb-xxx-service-opi case
        Supplier<Query> pathSuppliers = dictIndexingStorage.querySupplier4Path(info, info.getProjectName());
        List<DocumentInfo> propertiesCheck = dictIndexingStorage.searchInMemory(rootPath, info, List.of(pathSuppliers));
        if (CollectionUtils.size(propertiesCheck) > 1) {
            List<String> appListDetection = propertiesCheck.stream().map(DocumentInfo::getFolderName)
                    .distinct()
                    .map(n -> Strings.CI.replace(n, info.getProjectName(), ""))
                    .map(String::trim)
                    .map(String::toUpperCase)
                    .toList();
            Function<Path, List<Path>> appendSuffix = appName -> appListDetection.stream().map(suffix -> appName.getFileName().toString().toUpperCase() + suffix).map(Path::of).toList();
            return appList.stream().map(appendSuffix).flatMap(Collection::stream).toList();
        }
        return appList;
    }

    private String appCodeDetect(String fileName) {
        String baseName = FilenameUtils.getBaseName(fileName);
        return switch (baseName) {
            case "application-aggregator" -> AppCodeEnum.CCS_AGGREGATOR.name();
            case "application-lc-aggregator" -> AppCodeEnum.CCS_LC_AGGREGATOR.name();
            case "application-lc" -> AppCodeEnum.CCS_LC.name();
            default -> null;
        };
    }
}
