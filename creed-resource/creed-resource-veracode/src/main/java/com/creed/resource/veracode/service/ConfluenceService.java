package com.creed.resource.veracode.service;

import com.creed.resource.veracode.client.confluence.ConfluenceAttachmentPage;
import com.creed.resource.veracode.client.confluence.ConfluenceAttachmentPage.Attachment;
import com.creed.resource.veracode.client.confluence.ConfluenceClient;
import com.creed.resource.veracode.client.confluence.ConfluencePage;
import com.creed.resource.veracode.client.confluence.ConfluenceUser;
import com.creed.resource.veracode.config.ConfluenceProperties;
import com.creed.resource.veracode.service.VeracodeAnalysisService.UploadedFile;
import com.creed.resource.veracode.service.VeracodeXmlParser.UnsupportedFileException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A Confluence page's attachments as a report source: list them, then download the chosen ones as if uploaded.
 *
 * <p>The page names attachments by id only. The server lists the page again and downloads from the listing's
 * {@code _links.download} under the configured base URL - never a URL from the request, so the page cannot make
 * this service fetch anything but that Confluence's attachments.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConfluenceService {

    static final String CONFLUENCE = "Confluence";
    private static final int MAX_REDIRECTS = 5;

    private final ConfluenceClient confluence;
    private final ConfluenceProperties props;

    /**
     * The page's "test connection".
     *
     * @param user    whom the token signs in as ({@code null} unless {@code ok})
     * @param problem {@code UNREACHABLE}, {@code TOKEN_REJECTED} (401, or Cloud answering as anonymous)
     * @param status  Confluence's HTTP status, {@code 0} when it could not be reached
     */
    public record Connection(boolean ok, String baseUrl, String user, Problem problem, int status, String message) {
    }

    public enum Problem { UNREACHABLE, TOKEN_REJECTED }

    /**
     * @param supported a {@code .xml} or {@code .zip} - what the page lets the user choose
     * @param size      bytes, as Confluence lists it; {@code null} when it does not say
     * @param created   when this version was uploaded (ISO-8601)
     */
    public record AttachmentView(String id, String title, String mediaType, Long size, int version, String created,
                                 String author, boolean supported) {
    }

    public record PageAttachments(String pageId, String title, String spaceKey, String url,
                                  List<AttachmentView> attachments) {
    }

    public Connection connection(ConfluenceToken token) {
        requireEnabled();
        token.require();
        try {
            ConfluenceUser user = UpstreamException.call(CONFLUENCE, () -> confluence.currentUser(token.authorization()));
            if (user == null || user.anonymous()) {
                return new Connection(false, props.baseUrl(), null, Problem.TOKEN_REJECTED, 200,
                        "Confluence took the request as anonymous: check the token (and, on Cloud, the e-mail)");
            }
            return new Connection(true, props.baseUrl(), user.name(), null, 200,
                    "signed in to Confluence as " + user.name());
        } catch (UpstreamException e) {
            Problem problem = e.status() == 0 ? Problem.UNREACHABLE : Problem.TOKEN_REJECTED;
            return new Connection(false, props.baseUrl(), null, problem, e.status(), e.getMessage());
        }
    }

    public PageAttachments attachments(String pageId, ConfluenceToken token) {
        Listing listing = list(pageId, token);
        List<AttachmentView> views = listing.attachments().values().stream().map(a -> new AttachmentView(a.id(),
                a.title(), a.mediaType(), a.fileSize(), a.version() == null ? 0 : a.version().number(),
                a.version() == null ? null : a.version().when(),
                a.version() == null || a.version().by() == null ? null : a.version().by().displayName(),
                supported(a.title()))).toList();
        return new PageAttachments(listing.page().id(), listing.page().title(),
                listing.page().space() == null ? null : listing.page().space().key(), props.pageUrl(pageId), views);
    }

    /** The chosen attachments' bytes, named by their titles - what an upload of the same files would be. */
    public List<UploadedFile> download(String pageId, List<String> attachmentIds, ConfluenceToken token) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            throw new IllegalArgumentException("no attachment chosen: pick one or more .xml / .zip attachments");
        }
        log.info("download:{}", attachmentIds);
        Listing listing = list(pageId, token);
        List<UploadedFile> files = new ArrayList<>();
        for (String id : attachmentIds.stream().filter(Objects::nonNull).distinct().toList()) {
            Attachment a = listing.attachments().get(id);
            if (a == null) {
                throw new IllegalArgumentException("attachment " + id + " is not on page " + pageId
                        + " (any more): list the attachments again");
            }
            if (!supported(a.title())) {
                throw new UnsupportedFileException(a.title() + " is neither .xml nor .zip");
            }
            long max = props.maxDownloadSize().toBytes();
            if (a.fileSize() != null && a.fileSize() > max) {
                throw new UnsupportedFileException(a.title() + " is " + a.fileSize() + " bytes, more than "
                        + props.maxDownloadSize().toMegabytes() + "MB (veracode.confluence.max-download-size)");
            }
            byte[] content = fetch(downloadUrl(a), token, a.title());
            if (content.length > max) {
                throw new UnsupportedFileException(a.title() + " is larger than "
                        + props.maxDownloadSize().toMegabytes() + "MB (veracode.confluence.max-download-size)");
            }
            log.info("Confluence page {}: downloaded '{}' ({} bytes)", pageId, a.title(), content.length);
            files.add(new UploadedFile(a.title(), content));
        }
        return files;
    }

    /**
     * The listing's {@code _links.download} under the base URL. Its shape differs by version - Data Center and older
     * Cloud {@code /download/attachments/{page}/{file}?version=1}, Cloud now
     * {@code /rest/api/content/{page}/child/attachment/{id}/download} - so any path relative to the base URL is
     * taken, as long as it stays under it once normalised ({@code ..}, an absolute or protocol-relative URL do not).
     */
    URI downloadUrl(Attachment a) {
        String path = a.download();
        if (path == null || path.isBlank()) {
            throw new UpstreamException(CONFLUENCE, 200, a.title() + " has no download link");
        }
        URI base = URI.create(props.baseUrl());
        URI url;
        try {
            url = path.startsWith("/") && !path.startsWith("//") ? URI.create(props.baseUrl() + path).normalize() : null;
        } catch (IllegalArgumentException e) {
            url = null;
        }
        if (url == null || !withinBase(url, base)) {
            throw new UpstreamException(CONFLUENCE, 200, a.title() + ": download link '" + path
                    + "' is not under " + props.baseUrl());
        }
        return url;
    }

    static boolean supported(String title) {
        String name = title == null ? "" : title.toLowerCase(Locale.ROOT);
        return name.endsWith(".xml") || name.endsWith(".zip");
    }

    private record Listing(ConfluencePage page, Map<String, Attachment> attachments) {
    }

    /**
     * @param url             the page as a person opens it
     * @param attachments     the files attached to it, as Confluence titled them
     * @param attachmentError why attaching failed - the page exists either way; {@code null} when it did not
     */
    public record CreatedPage(String id, String title, String spaceKey, String parentId, String url,
                              List<String> attachments, String attachmentError) {

        CreatedPage withAttachments(List<String> titles, String error) {
            return new CreatedPage(id, title, spaceKey, parentId, url, titles, error);
        }
    }

    /**
     * A new page under {@code parentPageId}, in the parent's space, as the user ({@code storage} = Confluence storage
     * format, XHTML). The parent is read first, so a wrong id is a 404 naming it rather than Confluence's 400 about
     * ancestors.
     */
    public CreatedPage createChildPage(String parentPageId, String title, String storage, ConfluenceToken token) {
        requireEnabled();
        token.require();
        String parentId = pageId(parentPageId);
        ConfluencePage parent = page(parentId, token);
        if (parent.space() == null || parent.space().key() == null) {
            throw new IllegalArgumentException("Confluence did not say which space page " + parentId + " is in");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "page");
        body.put("title", title);
        body.put("space", Map.of("key", parent.space().key()));
        body.put("ancestors", List.of(Map.of("id", parentId)));
        body.put("body", Map.of("storage", Map.of("value", storage, "representation", "storage")));
        ConfluencePage created = UpstreamException.call(CONFLUENCE,
                () -> confluence.createPage(token.authorization(), body));
        log.info("created Confluence page {} '{}' under {} in {}", created.id(), title, parentId, parent.space().key());
        return new CreatedPage(created.id(), created.title() != null ? created.title() : title, parent.space().key(),
                parentId, props.pageUrl(created.id()), List.of(), null);
    }

    /**
     * Attaches the files to a page (one request). The page must be one this token may edit - the export calls it on
     * the page it just created.
     *
     * @return the attachments' titles as Confluence stored them
     */
    public List<String> attach(String pageId, List<UploadedFile> files, ConfluenceToken token) {
        requireEnabled();
        token.require();
        if (files.isEmpty()) {
            return List.of();
        }
        // a plain map, not MultipartBodyBuilder: that one needs Reactive Streams, which this servlet app lacks
        MultiValueMap<String, HttpEntity<?>> parts = new LinkedMultiValueMap<>();
        for (UploadedFile f : files) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(mediaType(f.filename()));
            parts.add("file", new HttpEntity<>(new ByteArrayResource(f.content()) {
                @Override
                public String getFilename() {
                    return f.filename();
                }
            }, headers));
        }
        ConfluenceAttachmentPage result = UpstreamException.call(CONFLUENCE, () -> confluence.addAttachments(
                token.authorization(), "nocheck", pageId(pageId), parts));
        List<String> titles = result == null || result.results() == null ? List.of()
                : result.results().stream().map(Attachment::title).toList();
        log.info("Confluence page {}: attached {}", pageId, titles);
        return titles;
    }

    private static MediaType mediaType(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        return name.endsWith(".xml") ? MediaType.APPLICATION_XML
                : name.endsWith(".zip") ? MediaType.parseMediaType("application/zip")
                : MediaType.APPLICATION_OCTET_STREAM;
    }

    private static String pageId(String pageId) {
        String id = pageId == null ? "" : pageId.trim();
        if (!id.matches("\\d{1,19}")) {
            throw new IllegalArgumentException("a Confluence page id is a number (pageId=... in the page's URL), got '"
                    + pageId + "'");
        }
        return id;
    }

    private ConfluencePage page(String id, ConfluenceToken token) {
        try {
            return UpstreamException.call(CONFLUENCE, () -> confluence.page(token.authorization(), id, "space"));
        } catch (UpstreamException e) {
            // Confluence says 404 for a page that is not there and for one this user may not see
            if (e.status() == 404 || e.status() == 403) {
                throw new PageNotFoundException(id, props.pageUrl(id));
            }
            throw e;
        }
    }

    private Listing list(String pageId, ConfluenceToken token) {
        requireEnabled();
        token.require();
        String id = pageId(pageId);
        ConfluencePage page = page(id, token);
        Map<String, Attachment> attachments = new LinkedHashMap<>();
        for (int start = 0; ; ) {
            int from = start;
            ConfluenceAttachmentPage chunk = UpstreamException.call(CONFLUENCE, () -> confluence.attachments(
                    token.authorization(), id, from, props.pageSize(), "version"));
            List<Attachment> results = chunk == null || chunk.results() == null ? List.of() : chunk.results();
            results.forEach(a -> attachments.putIfAbsent(a.id(), a));
            // an empty page, or no "next", ends it - a size alone can spin on a server that ignores start
            if (results.isEmpty() || !chunk.hasNext()) {
                break;
            }
            start += results.size();
        }
        return new Listing(page, attachments);
    }

    /**
     * GET, following redirects by hand: the token goes only under the base URL ({@link #withinBase}) - Cloud
     * redirects downloads to its media service with a signed URL, which must not see it. An HTML answer is a login page (a Data Center
     * session redirect, a proxy), not the file.
     */
    private byte[] fetch(URI url, ConfluenceToken token, String title) {
        URI current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URI target = current;
            String authorization = withinBase(target, URI.create(props.baseUrl())) ? token.authorization() : null;
            ResponseEntity<byte[]> response = UpstreamException.call(CONFLUENCE,
                    () -> confluence.download(authorization, target));
            if (response.getStatusCode().is3xxRedirection()) {
                URI location = response.getHeaders().getLocation();
                if (location == null) {
                    throw new UpstreamException(CONFLUENCE, response.getStatusCode().value(),
                            "redirect without a Location while downloading " + title);
                }
                current = target.resolve(location);
                continue;
            }
            MediaType type = response.getHeaders().getContentType();
            if (type != null && MediaType.TEXT_HTML.isCompatibleWith(type)) {
                throw new UpstreamException(CONFLUENCE, response.getStatusCode().value(),
                        "answered an HTML page instead of " + title + " (a login page? check the base URL and the token)");
            }
            return response.getBody() == null ? new byte[0] : response.getBody();
        }
        throw new UpstreamException(CONFLUENCE, 0, "more than " + MAX_REDIRECTS + " redirects downloading " + title);
    }

    /**
     * The token goes only to URLs under the base URL: same scheme, host and port, and within its path - Cloud's
     * {@code https://acme.atlassian.net/wiki} does not cover its media service, nor a Jira on the same host.
     */
    static boolean withinBase(URI target, URI base) {
        String basePath = base.getPath() == null ? "" : base.getPath().replaceAll("/+$", "");
        String path = target.getPath() == null ? "" : target.getPath();
        return Objects.equals(lower(target.getScheme()), lower(base.getScheme()))
                && Objects.equals(lower(target.getHost()), lower(base.getHost())) && port(target) == port(base)
                && (basePath.isEmpty() || path.equals(basePath) || path.startsWith(basePath + "/"));
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static int port(URI u) {
        return u.getPort() != -1 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    private void requireEnabled() {
        if (!props.enabled()) {
            throw new NotConfiguredException();
        }
    }

    public static class PageNotFoundException extends RuntimeException {
        public PageNotFoundException(String pageId, String url) {
            super("Confluence page " + pageId + " does not exist, or your token may not see it (" + url + ")");
        }
    }

    public static class NotConfiguredException extends RuntimeException {
        public NotConfiguredException() {
            super("no Confluence configured: set veracode.confluence.base-url");
        }
    }
}
