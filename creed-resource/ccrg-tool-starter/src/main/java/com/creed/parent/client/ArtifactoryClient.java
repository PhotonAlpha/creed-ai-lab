package com.creed.parent.client;

import com.creed.parent.client.vo.list.ArtifactoryResponseVO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.stream.Collectors;
import java.util.stream.Stream;


@HttpExchange("https://artifactory.example.com/artifactory/api")
public interface ArtifactoryClient {
    @GetExchange("/storage/{projectKey}/{repositorySlug}/master")
    ArtifactoryResponseVO getMasterList(@PathVariable("projectKey") String projectKey,
                                         @PathVariable("repositorySlug") String repositorySlug,
                                         @RequestHeader HttpHeaders httpHeaders);

    @GetExchange("/storage/{projectKey}/{repositorySlug}/release")
    ArtifactoryResponseVO getReleaseList(@PathVariable("projectKey") String projectKey,
                                             @PathVariable("repositorySlug") String repositorySlug,
                                             @RequestHeader HttpHeaders httpHeaders);
    @GetExchange("/storage/{projectKey}/{repositorySlug}/release/{releaseName}")
    ArtifactoryResponseVO getReleaseBuildList(@PathVariable("projectKey") String projectKey,
                                             @PathVariable("repositorySlug") String repositorySlug,
                                             @PathVariable("releaseName") String releaseName,
                                             @RequestHeader HttpHeaders httpHeaders);

    @GetExchange("/storage/{projectKey}/{repositorySlug}/release/{releaseName}/{buildNo}")
    ArtifactoryResponseVO getReleaseFileList(@PathVariable("projectKey") String projectKey,
                                             @PathVariable("repositorySlug") String repositorySlug,
                                             @PathVariable("releaseName") String releaseName,
                                             @PathVariable("buildNo") String buildNo,
                                             @RequestHeader HttpHeaders httpHeaders);

    @GetExchange("/storage/{projectKey}/{repositorySlug}/master/{buildNo}")
    ArtifactoryResponseVO getMasterFileList(@PathVariable("projectKey") String projectKey,
                                             @PathVariable("repositorySlug") String repositorySlug,
                                             @PathVariable("buildNo") String buildNo,
                                             @RequestHeader HttpHeaders httpHeaders);

    @GetExchange("/storage/{segments}")
    ArtifactoryResponseVO getRepoList(@PathVariable("segments") String segments,
                                             @RequestHeader HttpHeaders httpHeaders);

    static String buildSegments(String projectKey, String repo, String branch, String release, String buildNo) {
        return Stream.of(projectKey, repo, branch, release, buildNo)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining("/"));
    }


    @GetExchange("/storage/{projectKey}/{repositorySlug}/release/{releaseName}/{buildNo}/{fileName}")
    ArtifactoryResponseVO getDownloadInfo(@PathVariable("projectKey") String projectKey,
                                         @PathVariable("repositorySlug") String repositorySlug,
                                         @PathVariable("releaseName") String releaseName,
                                         @PathVariable("buildNo") String buildNo,
                                         @PathVariable("fileName") String fileName,
                                         @RequestHeader HttpHeaders httpHeaders);
}
