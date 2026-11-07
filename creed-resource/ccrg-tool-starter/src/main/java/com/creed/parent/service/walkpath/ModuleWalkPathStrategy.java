package com.creed.parent.service.walkpath;

import com.creed.parent.constant.AppCodeEnum;
import com.creed.parent.constant.GlobalErrorCodeConstants;
import com.creed.parent.constant.WalkPathStrategy;
import com.creed.parent.dal.model.WalkPathInfo;
import com.creed.parent.exception.ServiceException;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;

import static com.creed.parent.service.DictIndexingStorage.ENVIRONMENT_STORAGE_PATTERN;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 28/5/25
 */
@Setter
@Slf4j
@Service
public class ModuleWalkPathStrategy implements WalkPathStrategy {
    @Override
    public List<Path> apply(Path rootPath, WalkPathInfo info) {
        if (! AppCodeEnum.MS.equals(AppCodeEnum.toAppEnum(info.getApp()))) {
            throw new ServiceException(GlobalErrorCodeConstants.OPERATION_NOT_SUPPORT);
        }
        Path partialPath = WalkPathStrategy.resolvePath(info.getProjectName(), info.getReleaseName(), info.getCountryCode(), AppCodeEnum.toFileName(info.getApp())).normalize();
        partialPath = rootPath.resolve(partialPath);
        Matcher matcher = ENVIRONMENT_STORAGE_PATTERN.matcher(info.getInstance());
        String environmentVariable =StringUtils.EMPTY;
        if (matcher.matches()) {
            environmentVariable= matcher.group("env");
        }
        log.info("environmentVariable:{}", environmentVariable);
        List<Path> list = new ArrayList<>();
        if (StringUtils.isNotBlank(environmentVariable)) {
            if (StringUtils.equals("QUA", environmentVariable)) {
                Path finalPath = partialPath.resolve(info.getInstance()).resolve(info.getEnvironment()).resolve("application/config");
                Path finalPath2 = partialPath.resolve(environmentVariable).resolve(info.getEnvironment()).resolve("application/config");
                Path finalPath3 = partialPath.resolve(environmentVariable).resolve(info.getEnvironment()).resolve("batch/application");
                log.info("{}:finalPath:{} finalPath2:{}", environmentVariable, finalPath, finalPath2);
                list.addAll(WalkPathStrategy.listFiles(finalPath));
                list.addAll(WalkPathStrategy.listFiles(finalPath2));
                list.addAll(WalkPathStrategy.listFiles(finalPath3));
            } else {
                Path finalPath = partialPath.resolve(info.getInstance()).resolve("application/config");
                Path finalPath2 = partialPath.resolve(info.getEnvironment()).resolve("application/config");
                Path finalPath3 = partialPath.resolve(info.getEnvironment()).resolve("batch/application");
                log.info("{}:finalPath:{} finalPath2:{}", environmentVariable, finalPath, finalPath2);
                list.addAll(WalkPathStrategy.listFiles(finalPath));
                list.addAll(WalkPathStrategy.listFiles(finalPath2));
                list.addAll(WalkPathStrategy.listFiles(finalPath3));
            }
            //distinct modules
            return list.stream()
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .distinct().sorted(Comparator.naturalOrder())
                    .map(Path::of)
                    .toList();
        } else {
            throw new ServiceException(GlobalErrorCodeConstants.OPERATION_NOT_SUPPORT);
        }
    }
}
