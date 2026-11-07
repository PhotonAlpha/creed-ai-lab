package com.creed.parent.service.walkpath;

import com.creed.parent.config.MetadataConfigConfig;
import com.creed.parent.config.VoidInstance;
import com.creed.parent.constant.AppCodeEnum;
import com.creed.parent.dal.model.WalkPathInfo;
import com.creed.parent.model.DocumentInfo;
import com.creed.parent.service.DictIndexingStorage;
import com.creed.parent.utils.PropertySourcesTranslateUtil;
import com.creed.parent.utils.json.JacksonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.lucene.search.Query;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
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
public class AppHomeDirCalculateService {
    @Resource
    DictIndexingStorage dictIndexingStorage;
    @Resource
    MetadataConfigConfig metadataConfigConfig;
    private static final Pattern APP_HOME_PATTERN = Pattern.compile("/[^/]+/(?<env>\\D+)(?<envNo>\\d?)/(?<nxg>\\D+)(?<nxgNo>\\d?)");
    private static final String ENV_PATTERN_REGEX = ".*(LX|LX_APP|%s)/(?<type>QUA[^/]?|PDN[^/]?)/(?<env>[^/]+).*";
    private static final String INST_PATTERN_REGEX = ".*(LX|LX_APP|%s)/(?<inst>QUA[^/]{2,}|PDN[^/]{2,})/(?<env>[^/]+).*";

    // private static final Pattern ENV_PATTERN = Pattern.compile(".*(LX|LX_APP)/(?<type>QUA\\d?|PDN\\d?)/(?<env>[^/]+).*");
    // private static final Pattern INST_PATTERN = Pattern.compile(".*(LX|LX_APP)/(?<inst>QUA\\d{2,}|PDN\\d{2,})/(?<env>[^/]+).*");
    private static final Pattern PDN_NXG_PATTERN = Pattern.compile("PDN(\\d{3}|\\S{4})(?<nxgNo>\\d?)");

    /**
     * description AppHomeDirCalculateService
     * @author EthanCao
     * @since 2025-12-18T17
     */
    public Map<String, Map<String, Set<String>>> searchInstanceMatrix(Path rootPath, WalkPathInfo info) {
        info.setEnvironment("QUA* OR PDN*"); // search environment condition

        Map<String, TreeSet<String>> groupEnvironment = doGroupEnvironment(rootPath, info);
        Map<String, TreeSet<String>> groupEnvInstance = doGroupEnvInstance(rootPath, info);
        Map<String, Map<String, Set<String>>> envInstMatrix = new TreeMap<>();

        for (Map.Entry<String, TreeSet<String>> envEntry : groupEnvironment.entrySet()) {
            String envName = envEntry.getKey();
            Map<String, Set<String>> map = new TreeMap<>();
            TreeSet<String> envZone = envEntry.getValue();
            for (String zone : envZone) {
                map.put(zone, groupEnvInstance.getOrDefault(zone, new TreeSet<>()));
            }
            envInstMatrix.put(envName, map);
        }
        return envInstMatrix;
    }


    public Map<String, TreeSet<String>> doGroupEnvInstance(WalkPathInfo infoVo) {
        return doGroupEnvInstance(Path.of("../scripts/conf"), infoVo);
    }
    public Map<String, TreeSet<String>> doGroupEnvInstance(Path rootPath, WalkPathInfo infoVo) {
        Supplier<Query> allFilesSupplier = dictIndexingStorage.querySupplier4Common("", infoVo);
        List<DocumentInfo> documentInfos = dictIndexingStorage.searchInMemory(rootPath, infoVo, List.of(allFilesSupplier));
        return doGroupEnvInstance(infoVo, documentInfos);
    }
    public Map<String, TreeSet<String>> doGroupEnvInstance(WalkPathInfo infoVo, List<DocumentInfo> documentInfos) {
        List<VoidInstance> voidList = Optional.ofNullable(metadataConfigConfig.getVoidList()).orElse(Collections.emptyList());
        Set<String> voidInstances = voidList.stream().filter(v -> StringUtils.equalsIgnoreCase(v.getCountry(), infoVo.getCountryCode()))
                .map(VoidInstance::getInstance).collect(Collectors.toSet());
        String appName = AppCodeEnum.toFileName(infoVo.getApp());
        String envRegex = ENV_PATTERN_REGEX.formatted(appName);
        String instRegex = INST_PATTERN_REGEX.formatted(appName);
        log.info("::envRegex<<{}>>::instRegex<<{}>>::{}", envRegex, instRegex, JacksonUtils.toJsonString(infoVo));

        // exclude
        String suffix = Strings.CI.replace(infoVo.getApp(), appName, "");
        boolean need2Filter = StringUtils.isNotBlank(suffix) && !AppWalkPathStrategy.LEGACY_REPOS.contains(infoVo.getProjectName());

        log.info("result:{}", documentInfos.size());
        List<Pair<String, String>> typeEnvPair = new ArrayList<>();
        List<Pair<String, String>> instEnvPair = new ArrayList<>();
        for (DocumentInfo info : documentInfos) {
            if (need2Filter && !Strings.CI.equals(info.getFolderName(), infoVo.getProjectName()+suffix)) {
                log.info("❌filter out path:{} extension:{}", info.getPath(), info.getExtension());
                continue;
            }
            log.info("path:{} extension:{}", info.getPath(), info.getExtension());

            String path = info.getPath();
            Matcher envMatcher = Pattern.compile(envRegex).matcher(path);
            Matcher instMatcher = Pattern.compile(instRegex).matcher(path);
            if (envMatcher.matches()) {
                String type = envMatcher.group("type");
                String env = envMatcher.group("env");
                if (StringUtils.startsWith(type, "PDN")) {
                    typeEnvPair.add(Pair.of("PDN", type));
                } else {
                    typeEnvPair.add(Pair.of(type, env));
                }
            }
            if (instMatcher.matches()) {
                String inst = instMatcher.group("inst");
                String env = instMatcher.group("env");
                if (voidInstances.contains(inst)) {
                    continue;
                }
                if (StringUtils.startsWith(inst, "PDN")) {
                    Matcher pdnNxgMatcher = PDN_NXG_PATTERN.matcher(inst);
                    String nxgNo = StringUtils.EMPTY;
                    if (pdnNxgMatcher.matches()) {
                        nxgNo = StringUtils.defaultString(pdnNxgMatcher.group("nxgNo"), StringUtils.EMPTY);
                    }
                    instEnvPair.add(Pair.of("PDN" + nxgNo, inst));
                } else {
                    instEnvPair.add(Pair.of(env, inst));
                }
            }
        }
        Map<String, String> envTypesMapping = typeEnvPair.stream().collect(Collectors.toMap(Pair::getRight, Pair::getLeft, (o, n) -> n));
        log.info("typeEnvMap:{}", JacksonUtils.toJsonString(envTypesMapping));
        Map<String, TreeSet<String>> instEnvMap = instEnvPair.stream().collect(Collectors.groupingBy(Pair::getKey, TreeMap::new, Collectors.mapping(Pair::getRight, Collectors.toCollection(TreeSet::new))));
        // 补充不存在envsSet中的env inst mapping,特殊情况下
        Collection<String> subtractEnvs = CollectionUtils.subtract(envTypesMapping.keySet(), instEnvMap.keySet());
        log.info("subtractEnvs:{}", subtractEnvs);
        if (CollectionUtils.isNotEmpty(subtractEnvs)) {
            Map<String, TreeSet<String>> tmpMap = new HashMap<>();
            subtractEnvs.forEach(env -> tmpMap.put(env, new TreeSet<>(Set.of(envTypesMapping.get(env)))));
            tmpMap.putAll(instEnvMap);
            instEnvMap = tmpMap;
        }

        log.info("instEnvMap:{}", JacksonUtils.toJsonString(instEnvMap));
        // for (Map.Entry<String, TreeSet<String>> entry : instEnvMap.entrySet()) {
        //     log.info("env:{} inst:{}", entry.getKey(), entry.getValue());
        // }
        return instEnvMap;
    }

    public Map<String, TreeSet<String>> doGroupEnvironment(WalkPathInfo infoVo) {
        List<Pair<String, DocumentInfo>> appHomeDirs;
        if (AppCodeEnum.MS.getAppNature().equals(AppCodeEnum.toAppEnum(infoVo.getApp()).getAppNature())) {
            Supplier<Query> supplier = dictIndexingStorage.querySupplier4AppHome("(ms AND app AND home AND dir) OR (ms.app.home.dir) NOT batch", infoVo);
            List<DocumentInfo> result = dictIndexingStorage.searchInMemory(Path.of("../scripts/conf"), infoVo, List.of(supplier));
            appHomeDirs = PropertySourcesTranslateUtil.searchPropertyKeyWithMeta(result, "ms.app.home.dir");
        } else {
            Supplier<Query> supplier = dictIndexingStorage.querySupplier4AppHome("(cs AND app AND home AND dir) OR (cs.app.home.dir)", infoVo);
            List<DocumentInfo> result = dictIndexingStorage.searchInMemory(Path.of("../scripts/conf"), infoVo, List.of(supplier));
            appHomeDirs = PropertySourcesTranslateUtil.searchPropertyKeyWithMeta(result, "cs.app.home.dir");
        }
        return doGroupEnvironment(infoVo, appHomeDirs);
    }
    public Map<String, TreeSet<String>> doGroupEnvironment(Path rootPath, WalkPathInfo infoVo) {
        List<Pair<String, DocumentInfo>> appHomeDirs;
        if (AppCodeEnum.MS.getAppNature().equals(AppCodeEnum.toAppEnum(infoVo.getApp()).getAppNature())) {
            Supplier<Query> supplier = dictIndexingStorage.querySupplier4AppHome("(ms AND app AND home AND dir) OR (ms.app.home.dir) NOT batch", infoVo);
            List<DocumentInfo> result = dictIndexingStorage.searchInMemory(rootPath, infoVo, List.of(supplier));
            appHomeDirs = PropertySourcesTranslateUtil.searchPropertyKeyWithMeta(result, "ms.app.home.dir");
        } else {
            Supplier<Query> supplier = dictIndexingStorage.querySupplier4AppHome("(cs AND app AND home AND dir) OR (cs.app.home.dir)", infoVo);
            List<DocumentInfo> result = dictIndexingStorage.searchInMemory(rootPath, infoVo, List.of(supplier));
            appHomeDirs = PropertySourcesTranslateUtil.searchPropertyKeyWithMeta(result, "cs.app.home.dir");
        }
        return doGroupEnvironment(infoVo, appHomeDirs);
    }
    protected Map<String, TreeSet<String>> doGroupEnvironment(WalkPathInfo infoVo, List<Pair<String, DocumentInfo>> appHomeDirs) {
        List<VoidInstance> voidList = Optional.ofNullable(metadataConfigConfig.getVoidList()).orElse(Collections.emptyList());
        Set<String> voidInstances = voidList.stream().filter(v -> StringUtils.equalsIgnoreCase(v.getCountry(), infoVo.getCountryCode()))
                .map(VoidInstance::getInstance).collect(Collectors.toSet());
        String appName = AppCodeEnum.toFileName(infoVo.getApp());
        String envRegex = ENV_PATTERN_REGEX.formatted(appName);
        String instRegex = INST_PATTERN_REGEX.formatted(appName);
        log.info("::envRegex {}::instRegex {}::", envRegex, instRegex);

        List<Pair<String, String>> typeEnvPair = new ArrayList<>();
        for (var dir : appHomeDirs) {
            if (StringUtils.isBlank(dir.getLeft())) {
                continue;
            }
            log.info(":::::::dir:{} path:{}", dir.getLeft(), dir.getRight().getPath());
            Matcher appHomeMatcher = APP_HOME_PATTERN.matcher(dir.getLeft());
            String envNo = StringUtils.EMPTY;
            if (appHomeMatcher.matches()) {
                envNo = StringUtils.defaultIfBlank(appHomeMatcher.group("envNo"), "1");
            }
            String path = dir.getRight().getPath();
            Matcher pathMatcher = Pattern.compile(envRegex).matcher(path);
            if (pathMatcher.matches()) {
                String type = pathMatcher.group("type");
                String env = pathMatcher.group("env");
                if (StringUtils.startsWith(type, "PDN")) {
                    typeEnvPair.add(Pair.of("PDN", type));
                } else {
                    if (StringUtils.startsWith(env, "NFT")) {
                        typeEnvPair.add(Pair.of("NFT", env));
                    } else {
                        typeEnvPair.add(Pair.of("UAT" + envNo, env));
                    }
                }
            }
            if (!pathMatcher.matches()) {
                Matcher instPathMatcher = Pattern.compile(instRegex).matcher(path);
                if (instPathMatcher.matches()) {
                    String inst = instPathMatcher.group("inst");
                    String env = instPathMatcher.group("env");
                    if (voidInstances.contains(inst)) {
                        continue;
                    }
                    if (StringUtils.startsWith(inst, "PDN")) {
                        Matcher pdnNxgMatcher = PDN_NXG_PATTERN.matcher(inst);
                        String nxgNo = StringUtils.EMPTY;
                        if (pdnNxgMatcher.matches()) {
                            nxgNo = StringUtils.defaultString(pdnNxgMatcher.group("nxgNo"), StringUtils.EMPTY);
                        }
                        typeEnvPair.add(Pair.of("PDN", "PDN" + nxgNo));
                    } else {
                        if (StringUtils.startsWith(env, "NFT")) {
                            typeEnvPair.add(Pair.of("NFT", env));
                        } else {
                            typeEnvPair.add(Pair.of("UAT" + envNo, env));
                        }
                    }
                }
            }

        }
        // add PDN if missing
        if (typeEnvPair.stream().map(Pair::getLeft).noneMatch("PDN"::equals)) {
            typeEnvPair.add(Pair.of("PDN", "PDN1"));
        }
        Map<String, TreeSet<String>> envMap = typeEnvPair.stream().collect(Collectors.groupingBy(Pair::getKey, TreeMap::new, Collectors.mapping(Pair::getRight, Collectors.toCollection(TreeSet::new))));
        log.info("envMap:{}", JacksonUtils.toJsonString(envMap));
        return envMap;
    }
}
