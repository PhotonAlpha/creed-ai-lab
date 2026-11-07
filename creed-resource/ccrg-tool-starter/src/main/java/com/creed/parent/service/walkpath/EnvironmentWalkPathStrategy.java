package com.creed.parent.service.walkpath;

import com.creed.parent.constant.AppCodeEnum;
import com.creed.parent.constant.WalkPathStrategy;
import com.creed.parent.dal.model.WalkPathInfo;
import com.creed.parent.exception.ServiceException;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.creed.parent.constant.GlobalErrorCodeConstants.DICT_NOT_FOUND;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 28/5/25
 */
@Setter
@Slf4j
@Service
public class EnvironmentWalkPathStrategy implements WalkPathStrategy {
    // private static final Pattern environmentPattern = Pattern.compile("^(PDN|DR)(?<token>\\d?)$");
    public static final Pattern ENVIRONMENT_PATTERN = Pattern.compile("^(?<env>PDN|DR)(\\d?)$");
    // public static final Pattern ENVIRONMENT_LIKE_PATTERN = Pattern.compile("^(?<env>PDN|DR)(\\d{2,})$");
    public static final Pattern ENVIRONMENT_LIKE_PATTERN = Pattern.compile("^(?<env>PDN|DR)(\\S{2,})$");//TODO
    private Set<String> specialSets = Set.of("QUA");

    @Override
    public List<Path> apply(Path rootPath, WalkPathInfo info) {

        Path partialPath = WalkPathStrategy.resolvePath(info.getProjectName(), info.getReleaseName(), info.getCountryCode(), AppCodeEnum.toFileName(info.getApp())).normalize();
        Path finalPath = rootPath.resolve(partialPath);

        Predicate<Path> environmentPathPredicate = path -> ENVIRONMENT_PATTERN.matcher(path.getFileName().toString()).matches();
        Predicate<Path> specialPathPredicate = path -> specialSets.contains(path.getFileName().toString());

        try (Stream<Path> currentDir = Files.list(finalPath)) {
            List<Path> fileNameList = currentDir.filter(Files::isDirectory)
                    .toList();
            List<Path> standardEnv = fileNameList.stream()
                    .filter(environmentPathPredicate)
                    .toList();
            // if standardEnv not found,
            boolean drOnly = standardEnv.stream().map(Path::getFileName).map(Path::toString).allMatch("DR"::equals);
            if (CollectionUtils.isEmpty(standardEnv) || drOnly) {
                List<Path> possibleEnvs = listEnvLikePaths(fileNameList);
                standardEnv = Stream.concat(standardEnv.stream(), possibleEnvs.stream())
                        .toList();
            }
            List<Path> specialEnv = fileNameList.stream()
                    .filter(specialPathPredicate)
                    .map(listFilesFunc())
                    .flatMap(Collection::stream)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .map(Path::of)
                    .toList();
            return Stream.concat(standardEnv.stream(), specialEnv.stream())
                    .distinct()
                    .toList();
        } catch (IOException e) {
            log.error("exception occur:{}", ExceptionUtils.getMessage(e));
            throw new ServiceException(DICT_NOT_FOUND);
        }
    }

    private List<Path> listEnvLikePaths(List<Path> fileNameList) {
        // PDN DR like paths
        List<Path> possibleEnvs = fileNameList.stream()
                .map(path -> ENVIRONMENT_LIKE_PATTERN.matcher(path.getFileName().toString()))
                .filter(Matcher::matches)
                .map(match -> match.group("env"))
                .distinct()
                .map(Path::of)
                .toList();
        // QUA like paths
        List<Path> quaLikeEnvs = fileNameList.stream()
                .filter(path -> StringUtils.startsWithAny(path.getFileName().toString(), specialSets.toArray(new String[0])))
                .map(listFilesFunc())
                .flatMap(Collection::stream)
                .map(Path::getFileName)
                .map(Path::toString)
                .distinct()
                .map(Path::of)
                .toList();
        return Stream.concat(possibleEnvs.stream(), quaLikeEnvs.stream())
                .toList();
    }
}
