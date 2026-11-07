package com.creed.parent.service.walkpath;

import com.creed.parent.config.MetadataConfigConfig;
import com.creed.parent.config.VoidInstance;
import com.creed.parent.constant.AppCodeEnum;
import com.creed.parent.constant.WalkPathStrategy;
import com.creed.parent.dal.model.WalkPathInfo;
import com.creed.parent.exception.ServiceException;
import jakarta.annotation.Resource;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.creed.parent.constant.GlobalErrorCodeConstants.DICT_NOT_FOUND;
import static com.creed.parent.service.walkpath.EnvironmentWalkPathStrategy.ENVIRONMENT_PATTERN;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 28/5/25
 */
@Setter
@Slf4j
@Service
public class InstanceWalkPathStrategy implements WalkPathStrategy {
    public static final Pattern INSTANCE_PATTERN = Pattern.compile("^((PDN|QUA)(?<token>\\S{2,})|DR)$");
    private Set<String> specialSets = Set.of("QUA");
    @Resource
    MetadataConfigConfig metadataConfigConfig;
    @Override
    public List<Path> apply(Path rootPath, WalkPathInfo info) {

        Path partialPath = WalkPathStrategy.resolvePath(info.getProjectName(), info.getReleaseName(), info.getCountryCode(), AppCodeEnum.toFileName(info.getApp())).normalize();
        Path finalPath = rootPath.resolve(partialPath);

        Predicate<Path> pathPredicate = path -> INSTANCE_PATTERN.matcher(path.getFileName().toString()).matches();

        String environment = info.getEnvironment();

        try (Stream<Path> currentDir = Files.list(finalPath)) {
            List<Path> fileNameList = currentDir.filter(Files::isDirectory)
                    .toList();
            List<Path> standardEnv = fileNameList.stream()
                    .filter(pathPredicate)
                    .toList();
            Matcher matcher = ENVIRONMENT_PATTERN.matcher(environment);
            List<Path> finalList;
            if (matcher.matches()) {
                String envKey = matcher.group("env");
                log.info("ENVIRONMENT_PATTERN matches:{}", envKey);
                finalList = standardEnv.stream().filter(path -> StringUtils.startsWith(path.getFileName().toString(), envKey))
                        .toList();
            } else {
                //check special list
                log.info("check special list");
                finalList = filterSpecialList(info, standardEnv);
                if (CollectionUtils.isEmpty(finalList)) {
                    // if filterSpecialList is empty, take self env
                    finalList = List.of(Path.of(environment));
                }
            }
            log.info("final list size:{}", finalList.size());
            return finalList;
        } catch (IOException e) {
            log.error("exception occur:{}", ExceptionUtils.getMessage(e));
            throw new ServiceException(DICT_NOT_FOUND);
        }
    }

    public List<Path> filterSpecialList(WalkPathInfo info, List<Path> standardEnv) {
        List<VoidInstance> voidList = Optional.ofNullable(metadataConfigConfig.getVoidList()).orElse(Collections.emptyList());
        Set<String> voidInstances = voidList.stream().filter(v -> StringUtils.equalsIgnoreCase(v.getCountry(), info.getCountryCode()))
                .map(VoidInstance::getInstance).collect(Collectors.toSet());

        Predicate<Path> envContainsPredicate = path ->
                listFilesFunc().apply(path)
                        .stream().anyMatch(p -> StringUtils.equals(info.getEnvironment(), p.getFileName().toString()));
        return standardEnv.stream()
                .filter(path -> StringUtils.startsWithAny(path.getFileName().toString(), specialSets.toArray(new String[0])))
                .filter(path -> !voidInstances.contains(path.getFileName().toString()))
                .filter(envContainsPredicate)
                .toList();

    }
}
