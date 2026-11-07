package com.creed.parent.repo;

import com.creed.parent.client.ArtifactoryClient;
import com.creed.parent.client.vo.list.ArtifactoryResponseVO;
import com.creed.parent.exception.ServiceException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 7/22/25
 */
@Service
@Slf4j
public class ArtifactoryRepository {
    public static final String PROJECT_KEY = "IDB";
    @Resource
    ArtifactoryClient artifactoryClient;
    @Resource
    RestTemplate restTemplate;

    @Resource
    AccessConfiguration accessConfiguration;

    public ArtifactoryResponseVO getReleaseList( String repositorySlug) {
        HttpHeaders headers = createHeaders();
        return artifactoryClient.getReleaseList(PROJECT_KEY, repositorySlug, headers);
    }
    public ArtifactoryResponseVO getMasterList( String repositorySlug) {
        HttpHeaders headers = createHeaders();
        return artifactoryClient.getMasterList(PROJECT_KEY, repositorySlug, headers);
    }

    public ArtifactoryResponseVO getReleaseBuildList( String repositorySlug, String releaseName) {
        HttpHeaders headers = createHeaders();
        return artifactoryClient.getReleaseBuildList(PROJECT_KEY, repositorySlug, releaseName, headers);
    }
    public ArtifactoryResponseVO getReleaseFileList( String repositorySlug, String releaseName, String buildNo) {
        HttpHeaders headers = createHeaders();
        return artifactoryClient.getReleaseFileList(PROJECT_KEY, repositorySlug, releaseName, buildNo, headers);
    }
    public ArtifactoryResponseVO getMasterFileList( String repositorySlug, String buildNo) {
        HttpHeaders headers = createHeaders();
        return artifactoryClient.getMasterFileList(PROJECT_KEY, repositorySlug, buildNo, headers);
    }

    public ArtifactoryResponseVO getRepoList(String repositorySlug, String branchName, String releaseName, String buildNo) {
        HttpHeaders headers = createHeaders();
        String segments = ArtifactoryClient.buildSegments(PROJECT_KEY, repositorySlug, branchName, releaseName, buildNo);
        log.info("getRepoList:{}", segments);
        return artifactoryClient.getRepoList(segments, headers);
    }


    public ArtifactoryResponseVO getDownloadInfo( String repositorySlug, String releaseName, String buildNo, String fileName) {
        HttpHeaders headers = createHeaders();
        return artifactoryClient.getDownloadInfo(PROJECT_KEY, repositorySlug, releaseName, buildNo, fileName, headers);
    }

    public InputStream downloadConfigs(String downloadUrl) {
        log.info("download url:{}", downloadUrl);
        HttpHeaders headers = createHeaders();
        HttpEntity<String> httpEntity = new HttpEntity<>(headers);
        try {
            ResponseEntity<byte[]> responseEntity = restTemplate.exchange(downloadUrl, HttpMethod.GET, httpEntity, byte[].class);
            return new ByteArrayInputStream(responseEntity.getBody());
        } catch (RestClientException e) {
            if (e instanceof HttpStatusCodeException hsce) {
                log.error("HttpStatusCodeException ", hsce);
            } else {
                log.error("RestClientException ", e);
            }
            throw new ServiceException(500, ExceptionUtils.getMessage(e));
        }
    }

    protected HttpHeaders createHeaders() {
        String tokenKey = accessConfiguration.getTokenKey();
        String tokenValue = accessConfiguration.getTokenValue();
        String authStr = "%s:%s".formatted(tokenKey, tokenValue);
        String authToken = Base64.getEncoder().encodeToString(authStr.getBytes(StandardCharsets.UTF_8));
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        httpHeaders.set(HttpHeaders.AUTHORIZATION, "Basic " + authToken);
        return httpHeaders;
    }
}


