package com.creed.parent.repo;

import com.creed.parent.client.ConfluencePageClient;
import com.creed.parent.client.vo.confluence.attachment.AttachmentContentVO;
import com.creed.parent.client.vo.confluence.attachment.ResultsVO;
import com.creed.parent.client.vo.confluence.content.ConfluenceContentVO;
import com.creed.parent.client.vo.confluence.content.LinksVO;
import com.creed.parent.client.vo.confluence.content.PageVO;
import com.creed.parent.client.vo.confluence.content.VersionVO;
import com.creed.parent.client.vo.confluence.metadata.AttachmentPropertyVO;
import com.creed.parent.client.vo.confluence.metadata.ContentPropertyVO;
import com.creed.parent.controller.prompt.vo.PageParam;
import com.creed.parent.convert.ConfluenceConvert;
import com.creed.parent.exception.ServiceException;
import com.creed.parent.utils.json.JacksonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
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
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 7/22/25
 */
@Service
@Slf4j
public class ConfluenceRepository {
    public static final String PROJECT_KEY = "IDB";
    public static final String X_ATLASSIAN_TOKEN = "X-Atlassian-Token";
    public static final String DOWNLOAD_URL = "https://ethancaoq.atlassian.net/download/attachments/{attachmentId}/{filename}";
    public static final String LIMIT = "limit";
    @Resource
    ConfluencePageClient confluencePageClient;
    @Resource
    RestTemplate restTemplate;
    @Resource
    AccessConfiguration accessConfiguration;


    public ConfluenceContentVO getContentById(String contentId) {
        try {
            HttpHeaders httpHeaders = createHeaders();
            Map<String, String> params = Map.of("expand", "space,body.view,children.page");
            ConfluenceContentVO result = confluencePageClient.getContentById(contentId, params, httpHeaders);
            log.info("confluencePageClient getContentById result:{}", JacksonUtils.toJsonString(result));
            return result;
        } catch (Exception e) {
            loggingError(e);
            throw e;
        }
    }

    public PageVO searchContent(String contentId, PageParam pageParam) {
        try {
            int start = (pageParam.getPageNo() - 1) * pageParam.getPageSize();
            log.info("confluencePageClient searchContent start:{} pageSize:{}", (pageParam.getPageNo() - 1), pageParam.getPageSize());
            HttpHeaders httpHeaders = createHeaders();
            Map<String, Object> params = Map.of(
                    "cql", "parent=%s AND type=page".formatted(contentId),
                    "start", start,
                    LIMIT, pageParam.getPageSize()
            );
            PageVO result = confluencePageClient.searchContent(params, httpHeaders);
            log.info("confluencePageClient searchContent result:{}", JacksonUtils.toJsonString(result));
            return result;
        } catch (Exception e) {
            loggingError(e);
            throw e;
        }
    }

    private static void loggingError(Exception e) {
        log.error("errorMessage:{}", ExceptionUtils.getMessage(e));
    }

    public ConfluenceContentVO generateDraftConfluencePage(String spaceName, String ancestorId, String titleName, String bodyContent) {
        PageVO parentContent = searchContent(ancestorId, new PageParam());
        int totalSize = parentContent.getTotalSize();
        totalSize += 1;
        String titleSuffix = StringUtils.leftPad(Integer.toString(totalSize), 5, "0");
        ConfluenceContentVO confluenceContent = ConfluenceConvert.INSTANCE.buildContent(spaceName, ancestorId, "%s_%s".formatted(titleName, titleSuffix), bodyContent);
        return createContent(confluenceContent);
    }

    public ConfluenceContentVO updateDraftConfluencePage(ConfluenceContentVO previousConfluenceContent, String updatedContent) {
        String contentId = previousConfluenceContent.getId();
        String title = Optional.of(previousConfluenceContent)
                .map(ConfluenceContentVO::getTitle)
                .orElse(StringUtils.EMPTY);

        Integer versionNumber = Optional.of(previousConfluenceContent)
                .map(ConfluenceContentVO::getVersion)
                .map(VersionVO::getNumber)
                .orElse(1);
        ConfluenceContentVO confluenceContent = ConfluenceConvert.INSTANCE.buildUpdateContent(title, updatedContent);
        ConfluenceConvert.INSTANCE.updateVersion(confluenceContent, versionNumber + 1);
        return updateContent(contentId, confluenceContent);
    }

    public ConfluenceContentVO createContent(ConfluenceContentVO content) {
        try {
            HttpHeaders httpHeaders = createHeaders();
            log.info("confluencePageClient createContent request:{} httpHeaders:{}", JacksonUtils.toJsonString(content), httpHeaders);
            ConfluenceContentVO result = confluencePageClient.createContent(content, httpHeaders);
            log.info("confluencePageClient createContent result:{}", JacksonUtils.toJsonString(result));
            return result;
        } catch (Exception e) {
            loggingError(e);
            throw e;
        }
    }

    public boolean deleteContent(String contentId) {
        try {
            HttpHeaders httpHeaders = createHeaders();
            log.info("confluencePageClient deleteContent request:{}", contentId);
            confluencePageClient.deleteContent(contentId, httpHeaders);
            log.info("confluencePageClient deleteContent result:{}", true);
            return true;
        } catch (Exception e) {
            loggingError(e);
            return false;
        }
    }

    public ConfluenceContentVO updateContent(String contentId, ConfluenceContentVO content) {
        try {
            HttpHeaders httpHeaders = createHeaders();
            log.info("confluencePageClient updateContent request:{} httpHeaders:{}", JacksonUtils.toJsonString(content), httpHeaders);
            ConfluenceContentVO result = confluencePageClient.updateContent(contentId, content, httpHeaders);
            log.info("confluencePageClient updateContent result:{}", JacksonUtils.toJsonString(result));
            return result;
        } catch (Exception e) {
            loggingError(e);
            throw e;
        }
    }


    public AttachmentContentVO uploadAttachment(String contentId, org.springframework.core.io.Resource file) {
        return uploadAttachment(contentId, file, file.getFilename());
    }

    public AttachmentContentVO uploadAttachment(String contentId, org.springframework.core.io.Resource file, String comment) {
        try {
            HttpHeaders xsrfHeaders = createXSRFHeaders();
            AttachmentContentVO result = confluencePageClient.uploadAttachment(contentId, file, comment, xsrfHeaders);
            log.info("confluencePageClient uploadAttachment result:{}", result);
            return result;
        } catch (Exception e) {
            loggingError(e);
            throw e;
        }
    }

    public AttachmentContentVO getAttachments(String contentId) {
        try {
            HttpHeaders httpHeaders = createHeaders();
            AttachmentContentVO attachments = confluencePageClient.getAttachments(contentId,Map.of(), httpHeaders);
            log.info("getAttachments:{} successful", JacksonUtils.toJsonString(attachments));
            return attachments;
        } catch (Exception e) {
            loggingError(e);
            throw e;
        }
    }

    public static final Integer ATTACHMENT_LIMIT = 200;
    public AttachmentContentVO getAllAttachments(String contentId) {
        try {
            HttpHeaders httpHeaders = createHeaders();
            AttachmentContentVO attachments = confluencePageClient.getAttachments(contentId, Map.of(LIMIT, ATTACHMENT_LIMIT), httpHeaders);
            Optional<AttachmentContentVO> attachmentOptional = Optional.of(attachments);
            boolean hasNextPage = attachmentOptional.map(AttachmentContentVO::getLinks)
                    .map(LinksVO::getNext).isPresent();
            List<ResultsVO> results = attachmentOptional.map(AttachmentContentVO::getResults)
                    .orElse(Collections.emptyList());
            List<ResultsVO> finalResults = new ArrayList<>(results);
            log.info("getAttachments:{}, hasNextPage:{}", contentId, hasNextPage);
            while (hasNextPage) {
                Integer limit = Optional.of(attachments)
                        .map(AttachmentContentVO::getLimit).orElse(ATTACHMENT_LIMIT);
                Integer preStartIdx = Optional.of(attachments)
                        .map(AttachmentContentVO::getStart).orElse(0);
                log.info("get next page Attachments limit:{} start:{}", limit, preStartIdx + limit);
                Map<String, Object> params = Map.of(LIMIT, limit, "start", preStartIdx + limit);
                attachments = confluencePageClient.getAttachments(contentId, params, httpHeaders);
                hasNextPage = Optional.of(attachments).map(AttachmentContentVO::getLinks)
                        .map(LinksVO::getNext).isPresent();
                results = Optional.of(attachments).map(AttachmentContentVO::getResults)
                        .orElse(Collections.emptyList());
                log.debug("get next page Attachments:{} successful, hasNextPage:{}", JacksonUtils.toJsonString(attachments), hasNextPage);
                finalResults.addAll(results);
            }
            attachments.setResults(finalResults);
            attachments.setSize(finalResults.size());
            log.info("get All Attachments:{} successful, hasNextPage:{}", JacksonUtils.toJsonString(attachments), hasNextPage);
            return attachments;
        } catch (Exception e) {
            loggingError(e);
            throw e;
        }
    }

    public byte[] downloadAttachment(String attachmentId, String filename) {
        log.info("downloadAttachment:{}-{}", attachmentId, filename);
        HttpHeaders headers = createHeaders();
        HttpEntity<String> httpEntity = new HttpEntity<>(headers);
        String downloadUrl = UriComponentsBuilder.fromUriString(DOWNLOAD_URL)
                .buildAndExpand(attachmentId, filename)
                .encode(StandardCharsets.UTF_8).toUriString();
        log.info("download url:{}", downloadUrl);
        try {
            ResponseEntity<byte[]> responseEntity = restTemplate.exchange(downloadUrl, HttpMethod.GET, httpEntity, byte[].class);
            return responseEntity.getBody();
        } catch (RestClientException e) {
            if (e instanceof HttpStatusCodeException hsce) {
                log.error("HttpStatusCodeException ", hsce);
            } else {
                log.error("RestClientException ", e);
            }
            throw new ServiceException(500, ExceptionUtils.getMessage(e));
        }
    }


    public boolean moveAttachment(String contentId, String attachmentId,String newContentId) {
        try {
            HttpHeaders xsrfHeaders = createXSRFHeaders();
            Map<String, String> params = Map.of("newContentId", newContentId);
            confluencePageClient.moveAttachment(contentId, attachmentId, params, xsrfHeaders);
            log.info("confluencePageClient moveAttachment result:{}", true);
            return true;
        } catch (Exception e) {
            loggingError(e);
            return false;
        }
    }

    public void removeAttachment(String contentId, String attachmentId) {
        try {
            HttpHeaders httpHeaders = createHeaders();
            confluencePageClient.removeAttachment(contentId, attachmentId, httpHeaders);
            log.info("removeAttachment:{} successful", attachmentId);
        } catch (Exception e) {
            loggingError(e);
            throw e;
        }
    }

    public ContentPropertyVO<AttachmentPropertyVO> createContentProperty(String contentId, ContentPropertyVO<AttachmentPropertyVO> content) {
        try {
            HttpHeaders httpHeaders = createHeaders();
            log.info("createContentProperty request:{}", JacksonUtils.toJsonString(content));
            ContentPropertyVO<AttachmentPropertyVO> contentPropertyResp = confluencePageClient.createContentProperty(contentId, content, httpHeaders);
            log.info("createContentProperty:{} successful", JacksonUtils.toJsonString(contentPropertyResp));
            return contentPropertyResp;
        } catch (Exception e) {
            loggingError(e);
            throw e;
        }
    }

    public ContentPropertyVO<AttachmentPropertyVO> getContentPropertyByKey(String contentId, String keyword) {
        try {
            HttpHeaders httpHeaders = createHeaders();
            Map<String, String> params = Map.of("expand", "content,version");
            ContentPropertyVO<AttachmentPropertyVO> contentPropertyResp = confluencePageClient.getContentPropertyByKey(contentId, keyword, params, httpHeaders);
            log.info("getContentPropertyByKey:{} successful", JacksonUtils.toJsonString(contentPropertyResp));
            return contentPropertyResp;
        } catch (Exception e) {
            loggingError(e);
        }
        return null;
    }

    protected HttpHeaders createHeaders() {
        String bearerToken = accessConfiguration.getBearerToken();
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        httpHeaders.set(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
        return httpHeaders;
    }

    protected HttpHeaders createXSRFHeaders() {
        String bearerToken = accessConfiguration.getBearerToken();
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        httpHeaders.set(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
        httpHeaders.set(X_ATLASSIAN_TOKEN, "nocheck");
        return httpHeaders;
    }
}


