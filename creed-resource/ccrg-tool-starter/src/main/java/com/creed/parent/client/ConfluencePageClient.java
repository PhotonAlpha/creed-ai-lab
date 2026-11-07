package com.creed.parent.client;

import com.creed.parent.client.vo.confluence.attachment.AttachmentContentVO;
import com.creed.parent.client.vo.confluence.content.ConfluenceContentVO;
import com.creed.parent.client.vo.confluence.content.PageVO;
import com.creed.parent.client.vo.confluence.metadata.AttachmentPropertyVO;
import com.creed.parent.client.vo.confluence.metadata.ContentPropertyResourcesVO;
import com.creed.parent.client.vo.confluence.metadata.ContentPropertyVO;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;

import java.util.Map;


@HttpExchange("https://ethancaoq.atlassian.net/rest/api")
public interface ConfluencePageClient {
    @GetExchange("/content/{id}")
    ConfluenceContentVO getContentById(@PathVariable("id") String contentId,
                                       @RequestParam(required = false) Map<String, String> params,
                                       @RequestHeader HttpHeaders httpHeaders);


    @PostExchange("/content")
    ConfluenceContentVO createContent(@RequestBody ConfluenceContentVO content,
                                      @RequestHeader HttpHeaders httpHeaders);

    @PutExchange("/content/{id}")
    ConfluenceContentVO updateContent(@PathVariable("id") String contentId,
                                      @RequestBody ConfluenceContentVO content,
                                      @RequestHeader HttpHeaders httpHeaders);

    @DeleteExchange("/content/{id}")
    void deleteContent(@PathVariable("id") String contentId,
                                      @RequestHeader HttpHeaders httpHeaders);

    @GetExchange("/content/search")
    PageVO searchContent(@RequestParam(required = false) Map<String, Object> params,
                         @RequestHeader HttpHeaders httpHeaders);

    @GetExchange(url = "/content/{id}/child/attachment")
    AttachmentContentVO getAttachments(@PathVariable("id") String contentId,
                                       @RequestParam(required = false) Map<String, Object> params,
                                       @RequestHeader HttpHeaders httpHeaders);

    @PostExchange(url = "/content/{id}/child/attachment", contentType = MediaType.MULTIPART_FORM_DATA_VALUE)
    AttachmentContentVO uploadAttachment(@PathVariable("id") String contentId,
                                         @RequestPart("file") Resource file,
                                         @RequestPart(value = "comment", required = false) String comment,
                                         @RequestHeader HttpHeaders httpHeaders);

    @DeleteExchange( "/content/{id}/child/attachment/{attachmentId}")
    void removeAttachment(@PathVariable("id") String contentId,
                                         @PathVariable("attachmentId") String attachmentId,
                                         @RequestHeader HttpHeaders httpHeaders);

    @PostExchange("/content/{id}/child/attachment/{attachmentId}/move")
    void moveAttachment(@PathVariable("id") String contentId,
                        @PathVariable("attachmentId") String attachmentId,
                        @RequestParam Map<String, String> params,
                        @RequestHeader HttpHeaders httpHeaders);

    @GetExchange( "https://ethancaoq.atlassian.net/download/attachments/{attachmentId}/{filename}")
    byte[] downloadAttachment(@PathVariable("attachmentId") String attachmentId,
                          @PathVariable("filename") String filename,
                          @RequestHeader HttpHeaders httpHeaders);

    @PostExchange("/content/{id}/property")
    ContentPropertyVO<AttachmentPropertyVO> createContentProperty(@PathVariable("id") String contentId,
                                           @RequestBody ContentPropertyVO<AttachmentPropertyVO> content,
                                           @RequestHeader HttpHeaders httpHeaders);

    @GetExchange("/content/{id}/property")
    ContentPropertyResourcesVO<AttachmentPropertyVO> getContentProperty(@PathVariable("id") String contentId,
                                                                            @RequestParam(required = false) Map<String, String> params,
                                                                            @RequestHeader HttpHeaders httpHeaders);

    @GetExchange("/content/{id}/property/{key}")
    ContentPropertyVO<AttachmentPropertyVO> getContentPropertyByKey(@PathVariable("id") String contentId,
                                                     @PathVariable("key") String keyword,
                                                     @RequestParam(required = false) Map<String, String> params,
                                                     @RequestHeader HttpHeaders httpHeaders);
}
