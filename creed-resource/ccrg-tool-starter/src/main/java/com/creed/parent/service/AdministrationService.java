package com.creed.parent.service;

import com.creed.parent.client.vo.list.ArtifactoryResponseVO;
import com.creed.parent.client.vo.list.ChildrenVO;
import com.creed.parent.config.PropertiesMatrixConfig;
import com.creed.parent.constant.WalkPathStrategy;
import com.creed.parent.controller.admin.vo.InstalledInfoVo;
import com.creed.parent.controller.admin.vo.InstalledRequestVo;
import com.creed.parent.controller.admin.vo.UninstallRequestVo;
import com.creed.parent.controller.vo.SelectedOptionVo;
import com.creed.parent.dal.model.WalkPathInfo;
import com.creed.parent.exception.ServiceException;
import com.creed.parent.repo.ArtifactoryRepository;
import com.creed.parent.utils.R;
import com.creed.parent.utils.json.JacksonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 7/22/25
 */
@Service
@Slf4j
public class AdministrationService {
    private static final Pattern COUNTRY_PATTERN = Pattern.compile("[^_]+_(?<country>[^_]+)_[^_]+");
    private static final  DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssSSS");
    @Resource
    ArtifactoryRepository artifactoryRepository;
    @Resource
    FileUploadService fileUploadService;

    @Value("${access.arti.wui}")
    private String accessArtiWui;

    @Resource
    PropertiesMatrixConfig propertiesMatrixConfig;

    public R<List<InstalledInfoVo>> listInstallFiles() {
        String rootPath = propertiesMatrixConfig.getRootPath();
        List<Path> paths = WalkPathStrategy.listFiles(Path.of(rootPath));
        List<InstalledInfoVo> finalList = new ArrayList<>();
        for (Path path : paths) {
            String projectName = path.getFileName().toString();
            if (StringUtils.equals("pdf", path.getFileName().toString())) {
                continue;
            }
            String lastModifyTimeParent = StringUtils.EMPTY;
            try {
                FileTime lastModifiedTime = Files.getLastModifiedTime(path);
                LocalDateTime dateTime = LocalDateTime.ofInstant(lastModifiedTime.toInstant(), ZoneId.systemDefault());
                lastModifyTimeParent = dateTime.format(FORMATTER);
            } catch (IOException e) {
                log.error("getLastModifiedTime", e);
            }
            List<Path> releaseFiles = WalkPathStrategy.listFiles(path);

            List<InstalledInfoVo> releases = releaseFiles.stream()
                    .map(p -> {
                        try {
                            FileTime lastModifiedTime = Files.getLastModifiedTime(p);
                            LocalDateTime dateTime = LocalDateTime.ofInstant(lastModifiedTime.toInstant(), ZoneId.systemDefault());
                            String fileName = p.getFileName().toString();
                            String lastModifyTime = dateTime.format(FORMATTER);
                            return new InstalledInfoVo(projectName, fileName, lastModifyTime);
                        } catch (IOException e) {
                            log.error("getLastModifiedTime", e);
                        }
                        return null;
                    }).filter(Objects::nonNull).toList();
            InstalledInfoVo installedResponse = new InstalledInfoVo("NA", projectName, lastModifyTimeParent, releases);
            finalList.add(installedResponse);
        }
        finalList.sort(Comparator.comparing(InstalledInfoVo::getName));
        return R.success(finalList);
    }

    public R<List<WalkPathInfo>> uninstallFiles(UninstallRequestVo uninstallRequest) {
        String rootPathStr = propertiesMatrixConfig.getRootPath();
        Path rootPath = Path.of(rootPathStr);
        List<WalkPathInfo> uninstallList = Optional.ofNullable(uninstallRequest.getUninstallList()).orElse(Collections.emptyList());
        List<WalkPathInfo> delList  = new ArrayList<>();
        for (WalkPathInfo pathInfo : uninstallList) {
            Path projectPath = rootPath.resolve(pathInfo.getProjectName()).normalize();
            if (!projectPath.startsWith(rootPath)) {
                log.warn("illegal projectPath:{}! Will skip", projectPath);
            }
            if (!Files.exists(projectPath)) {
                log.warn("projectPath:{} is not valid! Will skip",projectPath);
            }
            Path releasePath = projectPath.resolve(pathInfo.getReleaseName());
            if (!Files.exists(releasePath)) {
                log.warn("releasePath:{} is not valid! Will skip", releasePath);
            }
            // delete files
            log.info("deleting:{}", releasePath);
            try {
                FileSystemUtils.deleteRecursively(releasePath);
                delList.add(pathInfo);
            } catch (IOException e) {
                log.error("delete encounter error:{}", ExceptionUtils.getMessage(e));
            }
        }

        return R.success(delList);
    }

    public R<List<SelectedOptionVo>> listReleases(WalkPathInfo walkPathInfo) {
        ArtifactoryResponseVO releaseList = artifactoryRepository.getReleaseList(walkPathInfo.getProjectName());
        AtomicLong i = new AtomicLong(1);

        List<SelectedOptionVo> selections = releaseList.getChildren()
                .stream().map(chi -> new SelectedOptionVo(i.getAndIncrement(), StringUtils.replace(chi.getUri(), "/", ""))).toList();
        return R.success(selections);
    }

    public R<List<SelectedOptionVo>> listBuilds(WalkPathInfo walkPathInfo) {
        ArtifactoryResponseVO releaseList = artifactoryRepository.getReleaseBuildList(walkPathInfo.getProjectName(), walkPathInfo.getReleaseName());
        AtomicLong i = new AtomicLong(1);

        List<SelectedOptionVo> selections = releaseList.getChildren()
                .stream().map(chi -> new SelectedOptionVo(i.getAndIncrement(), StringUtils.replace(chi.getUri(), "/", "")))
                .sorted(Comparator.comparing(SelectedOptionVo::getName, Comparator.naturalOrder()).reversed())
                .toList();
        return R.success(selections);
    }

    public R<List<SelectedOptionVo>> getShortcutInfo(String repositorySlug, String branchName, String releaseName) {
        log.info("getShortcutInfo:== repositorySlug:{} branchName:{} releaseName:{}", repositorySlug, branchName, releaseName);
        String releaseStr;
        if (StringUtils.equalsIgnoreCase(branchName, "master")) {
            releaseStr = null;
        } else {
            releaseStr = releaseName;
        }
        ArtifactoryResponseVO repoDetails = artifactoryRepository.getRepoList(repositorySlug, branchName, releaseStr, null);

        AtomicLong i = new AtomicLong(1);
        List<SelectedOptionVo> selections = repoDetails.getChildren()
                .stream()
                .filter(ChildrenVO::isFolder)
                .map(chi -> new SelectedOptionVo(i.getAndIncrement(), StringUtils.replace(chi.getUri(), "/", "")))
                .sorted(Comparator.comparing(SelectedOptionVo::getName, Comparator.naturalOrder()).reversed())
                .toList();

        if (StringUtils.isNotBlank(releaseName) || StringUtils.equalsIgnoreCase(branchName, "master")) {
            selections.forEach(selector -> {
                ArtifactoryResponseVO buildDetails = artifactoryRepository.getRepoList(repositorySlug, branchName, releaseStr, selector.getName());
                selector.setCreated(buildDetails.getCreated());
                selector.setUiPath("%s/%s%s/%s".formatted(accessArtiWui, repoDetails.getRepo(), repoDetails.getPath(), selector.getName()));
                selector.setRepositoryPath(repoDetails.getPath());
            });
        }
        return R.success(selections);
    }


    public R<String> install(InstalledRequestVo installRequest) {
        log.info("install ProjectName:{} ReleaseName:{} BuildNo:{}", installRequest.getProjectName(), installRequest.getReleaseName(), installRequest.getBuildNo());
        ArtifactoryResponseVO releaseList = artifactoryRepository.getReleaseFileList(installRequest.getProjectName(), installRequest.getReleaseName(), installRequest.getBuildNo());
        final String possibleFileName = "configs";
        Set<String> files = releaseList.getChildren().stream()
                .map(ChildrenVO::getUri)
                .map(u -> StringUtils.replace(u, "/", "")).collect(Collectors.toSet());
        Optional<String> fileName = files.stream().filter(u -> StringUtils.contains(u, possibleFileName))
                .findFirst();
        if (fileName.isEmpty()) {
            Matcher matcher = COUNTRY_PATTERN.matcher(installRequest.getReleaseName());
            if (matcher.matches()) {
                String country = matcher.group("country");
                fileName = files.stream().filter(u -> StringUtils.contains(u, country))
                        .findFirst();
            }
        }
        if (fileName.isEmpty()) {
            log.error("file not found:{}", JacksonUtils.toJsonString(installRequest));
            throw new ServiceException(500, "file not exists");
        }
        String fileNameStr = fileName.get();
        log.info("get file:{}", fileNameStr);
        ArtifactoryResponseVO downloadInfo = artifactoryRepository.getDownloadInfo(installRequest.getProjectName(), installRequest.getReleaseName(), installRequest.getBuildNo(), fileNameStr);
        String downloadUri = downloadInfo.getDownloadUri();
        if (StringUtils.isBlank(downloadUri)) {
            log.error("downloadUri not found:{}", fileNameStr);
            throw new ServiceException(500, "downloadUri not exists");
        }

        InputStream inputStream = artifactoryRepository.downloadConfigs(downloadUri);
        // install file
        String releaseFolderName = "%s_%s".formatted(installRequest.getReleaseName(), installRequest.getBuildNo());
        log.info("releaseFolderName:{}", releaseFolderName);
        try {
            fileUploadService.installFile(installRequest.getProjectName(), "%s_%s".formatted(installRequest.getReleaseName(), installRequest.getBuildNo()), inputStream);
        } catch (IOException e) {
            throw new ServiceException(500, "file install failure");
        }
        return R.success(releaseFolderName + " installed successful!");
    }

}
