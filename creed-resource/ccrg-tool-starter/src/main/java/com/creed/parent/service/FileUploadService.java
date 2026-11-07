package com.creed.parent.service;

import com.creed.parent.config.PropertiesMatrixConfig;
import com.creed.parent.constant.GlobalErrorCodeConstants;
import com.creed.parent.controller.upload.vo.UploadFileVO;
import com.creed.parent.exception.ServiceException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 26/5/25
 */
@Service
@Slf4j
public class FileUploadService {
    public static final Pattern CONFIGS_PATTERN = Pattern.compile("(?<prefix>\\S+)/configs/(?<configsCountry>[^/]+)(?<suffix>.*)");
    @Resource
    private PropertiesMatrixConfig propertiesMatrixConfig;

    public String fileInstall(UploadFileVO file) {
        try {
            extract(file);
            return "extract file successful";
        } catch (IOException e) {
            log.error("getting IOException", e);
            throw new ServiceException(GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR);
        }
    }

    public void extract(UploadFileVO file) throws IOException {
        String rootPath = propertiesMatrixConfig.getRootPath();
        String removedReleasePrefix = StringUtils.replace(StringUtils.upperCase(file.getReleaseId()), "RELEASE_", "");
        String folderPath = "%s_%s".formatted(file.getReleasePrefix(), removedReleasePrefix);
        Path targetPath = Path.of(rootPath).resolve(file.getProject()).resolve(folderPath);
        log.info("folderPath:{}", targetPath);
        installFile(file.getProject(), folderPath, file.getFile().getInputStream());
    }

    public void installFile(String projectName, String folderName, InputStream in) throws IOException {
        String rootPath = propertiesMatrixConfig.getRootPath();
        Path targetPath = Path.of(rootPath).resolve(projectName).resolve(folderName).normalize();
        log.info("folderPath:{}", targetPath);
        //clean folder
        if (Files.exists(targetPath) && Files.isDirectory(targetPath)) {
            log.info("folderPath exists!");
            FileSystemUtils.deleteRecursively(targetPath);
        }

        try (
                ZipArchiveInputStream zipFile = new ZipArchiveInputStream(in, StandardCharsets.UTF_8.displayName())
        ) {
            ZipArchiveEntry entry;
            while ((entry = zipFile.getNextEntry()) != null) {
                Path path = targetPath.resolve(entry.getName());
                Matcher matcher = CONFIGS_PATTERN.matcher(path.toString());
                boolean matches = matcher.matches();
                if (matches) {
                    String prefix = matcher.group("prefix");
                    String configsCountry = matcher.group("configsCountry");
                    String suffix = matcher.group("suffix");
                    path = Path.of(prefix, configsCountry, suffix);
                }
                if (entry.isDirectory()) {
                    log.info("matches:{} {} isDirectory", matches, path);
                    Files.createDirectories(path);
                    continue;
                }
                Path parent = path.getParent();
                if (!Files.exists(parent)) {
                    log.info("{} not exists", parent);
                    Files.createDirectories(parent);
                } else {
                    log.info("writing file to {}", path);
                    try (OutputStream out = Files.newOutputStream(path, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE)) {
                        byte[] bufferArea = new byte[4096];
                        int len;
                        while ((len = zipFile.read(bufferArea)) != -1) {
                            out.write(bufferArea, 0, len);
                        }
                    }
                }
            }
        }
    }

}
