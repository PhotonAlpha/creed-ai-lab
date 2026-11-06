package com.creed.resource.veracode.mock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Stand-in for the Confluence REST API v1 calls the service makes: {@code user/current}, a page, its attachments
 * (paged by {@code start}/{@code limit}, {@code _links.next} while more follow) and their downloads.
 *
 * <p>Page {@value #PAGE} holds the sample report as XML, both sample reports zipped with their {@code .xsd} - the
 * zip downloads through a redirect, as Cloud hands files to its media service (outside the base URL, here
 * {@code /mock/confluence-media}), whose signed URL takes no token - and a PDF
 * that cannot be analysed. Page {@value #EMPTY_PAGE} has no attachments; any other id is Confluence's 404.
 *
 * <p>Authentication as {@link MockJiraController}'s ({@code Bearer} or {@code Basic}, {@code invalid} refused).
 * Without any, {@code user/current} answers {@code anonymous} as Cloud does, and everything else 401s.
 */
@RestController
@ConditionalOnProperty(name = "veracode.mock.enabled", havingValue = "true")
public class MockConfluenceController {

    static final String BASE = "/mock/confluence";
    public static final String PAGE = "98765";
    public static final String EMPTY_PAGE = "98766";
    static final String XML_ID = "att1001";
    static final String ZIP_ID = "att1002";
    static final String PDF_ID = "att1003";
    /** The media URL's "signature": the download is refused without it, as an expired Cloud link would be. */
    static final String MEDIA_SIGNATURE = "sig-1";

    private record File(String id, String title, String mediaType, byte[] content) {
    }

    private final Map<String, File> files = new LinkedHashMap<>();

    public MockConfluenceController() {
        byte[] xml = resource("sample/veracode-detailedreport.xml");
        files.put(XML_ID, new File(XML_ID, "veracode-detailedreport.xml", "application/xml", xml));
        files.put(ZIP_ID, new File(ZIP_ID, "veracode-reports.zip", "application/zip", zip(Map.of(
                "reports/veracode-detailedreport.xml", xml,
                "reports/veracode-detailedreport-2.xml", resource("sample/veracode-detailedreport-2.xml"),
                "reports/detailedreport.xsd", "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>"
                        .getBytes(StandardCharsets.UTF_8)))));
        files.put(PDF_ID, new File(PDF_ID, "scan-summary.pdf", "application/pdf", "%PDF-1.7 mock".getBytes(StandardCharsets.UTF_8)));
    }

    @GetMapping(BASE + "/rest/api/user/current")
    ResponseEntity<Map<String, Object>> currentUser(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        if (auth == null) {
            return ResponseEntity.ok(Map.of("type", "anonymous", "displayName", "Anonymous"));
        }
        String user = MockJiraController.user(auth);
        if (user == null) {
            return unauthorized();
        }
        return ResponseEntity.ok(Map.of("type", "known", "username", user, "displayName", "Mock " + user));
    }

    @GetMapping(BASE + "/rest/api/content/{id}")
    ResponseEntity<Map<String, Object>> page(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                             @PathVariable String id) {
        if (MockJiraController.user(auth) == null) {
            return unauthorized();
        }
        if (!PAGE.equals(id) && !EMPTY_PAGE.equals(id)) {
            return notFound(id);
        }
        return ResponseEntity.ok(Map.of("id", id, "type", "page",
                "title", PAGE.equals(id) ? "Veracode reports - creed" : "Release notes",
                "space", Map.of("key", "CREED", "name", "Creed")));
    }

    @GetMapping(BASE + "/rest/api/content/{id}/child/attachment")
    ResponseEntity<Map<String, Object>> attachments(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                                    @PathVariable String id,
                                                    @RequestParam(defaultValue = "0") int start,
                                                    @RequestParam(defaultValue = "25") int limit) {
        if (MockJiraController.user(auth) == null) {
            return unauthorized();
        }
        if (!PAGE.equals(id) && !EMPTY_PAGE.equals(id)) {
            return notFound(id);
        }
        List<File> all = PAGE.equals(id) ? List.copyOf(files.values()) : List.of();
        List<Map<String, Object>> results = all.stream().skip(start).limit(limit).map(f -> Map.<String, Object>of(
                "id", f.id(), "type", "attachment", "title", f.title(),
                "extensions", Map.of("mediaType", f.mediaType(), "fileSize", f.content().length),
                "version", Map.of("number", 1, "when", "2026-11-06T08:30:00.000Z", "by", Map.of("displayName", "ci-veracode")),
                "_links", Map.of("download", downloadLink(id, f)))).toList();
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("base", ServletUriComponentsBuilder.fromCurrentContextPath().path(BASE).toUriString());
        if (start + results.size() < all.size()) {
            links.put("next", "/rest/api/content/" + id + "/child/attachment?start=" + (start + results.size()) + "&limit=" + limit);
        }
        return ResponseEntity.ok(Map.of("results", results, "start", start, "limit", limit, "size", results.size(),
                "_links", links));
    }

    @GetMapping(BASE + "/download/attachments/{id}/{title}")
    ResponseEntity<?> download(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                               @PathVariable String id, @PathVariable String title) {
        if (MockJiraController.user(auth) == null) {
            return unauthorized();
        }
        File file = PAGE.equals(id) ? files.values().stream().filter(f -> f.title().equals(title)).findFirst().orElse(null) : null;
        if (file == null) {
            return notFound(id);
        }
        if (ZIP_ID.equals(file.id())) {
            URI media = ServletUriComponentsBuilder.fromCurrentContextPath()
                    .path("/mock/confluence-media/{id}").queryParam("token", MEDIA_SIGNATURE).build(file.id());
            return ResponseEntity.status(HttpStatus.FOUND).location(media).build();
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType())).body(file.content());
    }

    /**
     * Data Center's (and older Cloud's) {@code /download/attachments/...}, and Cloud's current REST download for the
     * zip - which redirects to the media service like the other.
     */
    private static String downloadLink(String pageId, File f) {
        return ZIP_ID.equals(f.id()) ? "/rest/api/content/" + pageId + "/child/attachment/" + f.id() + "/download"
                : "/download/attachments/" + pageId + "/" + f.title() + "?version=1&api=v2";
    }

    @GetMapping(BASE + "/rest/api/content/{id}/child/attachment/{attachmentId}/download")
    ResponseEntity<?> restDownload(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                   @PathVariable String id, @PathVariable String attachmentId) {
        if (MockJiraController.user(auth) == null) {
            return unauthorized();
        }
        File file = PAGE.equals(id) ? files.get(attachmentId) : null;
        if (file == null) {
            return notFound(id);
        }
        URI media = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/mock/confluence-media/{id}").queryParam("token", MEDIA_SIGNATURE).build(file.id());
        return ResponseEntity.status(HttpStatus.FOUND).location(media).build();
    }

    /** The media service: the signed URL is the credential; an {@code Authorization} it was not meant to get is refused. */
    @GetMapping("/mock/confluence-media/{id}")
    ResponseEntity<?> media(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                            @PathVariable String id, @RequestParam(required = false) String token) {
        File file = files.get(id);
        if (file == null || !MEDIA_SIGNATURE.equals(token) || auth != null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "invalid media token"));
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType())).body(file.content());
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("statusCode", 401,
                "message", "This request requires authentication"));
    }

    private static ResponseEntity<Map<String, Object>> notFound(String id) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("statusCode", 404,
                "message", "No content found with id: ContentId{id=" + id + "}"));
    }

    private static byte[] resource(String path) {
        try {
            return new ClassPathResource(path).getContentAsByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : new java.util.TreeMap<>(entries).entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
