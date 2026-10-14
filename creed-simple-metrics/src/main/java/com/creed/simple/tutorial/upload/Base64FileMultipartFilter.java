package com.creed.simple.tutorial.upload;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Used by API-8: turns the Base64 text in a {@code multipart/form-data} field back into a binary file part.
 *
 * <p>The client sends a form whose {@code file} field is not a file but a <b>text</b> field holding
 * the file bytes encoded as Base64 (optionally as a data URL, {@code data:image/png;base64,...}).
 * Two optional text fields describe it: {@code fileName} and {@code contentType}.
 * <pre>
 * Content-Disposition: form-data; name="file"
 *
 * aGVsbG8gY2FtZWwgdXBsb2FkCg==
 * </pre>
 * This filter decodes that text and replaces the part with a real file part, as if the client had
 * uploaded the file itself:
 * <pre>
 * Content-Disposition: form-data; name="file"; filename="hello.txt"
 * Content-Type: text/plain
 *
 * &lt;the raw bytes&gt;
 * </pre>
 * Everything downstream then handles an ordinary file upload: Spring's MultipartResolver wraps the
 * request, and the Camel servlet (attachmentMultipartBinding) turns the file part into an attachment named {@code file}
 * (plus {@code CamelFileName} / {@code CamelFileContentType} / {@code CamelFileLength} headers),
 * which is where {@code fileUploadProcessor} reads it from. Nothing after this filter sees Base64.
 *
 * <p>A {@code file} part that already carries a filename is a genuine binary upload and is left alone.
 * Invalid Base64 is answered with 400 and an oversized upload with 413, both here, before the
 * request reaches Camel.
 *
 * <p>{@code request.getParts()} works here even though Spring's MultipartResolver has not run yet:
 * Tomcat parses parts according to the multipart config of the servlet the request is mapped to,
 * and Spring Boot registers the DispatcherServlet with one ({@code spring.servlet.multipart.*}).
 */
public class Base64FileMultipartFilter extends OncePerRequestFilter {

    public static final String FILE_FIELD = "file";
    public static final String FILE_NAME_FIELD = "fileName";
    public static final String CONTENT_TYPE_FIELD = "contentType";

    private static final String DEFAULT_FILE_NAME = "upload.bin";
    private static final Logger log = LoggerFactory.getLogger(Base64FileMultipartFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType == null
                || !contentType.toLowerCase().startsWith(MediaType.MULTIPART_FORM_DATA_VALUE);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Part filePart;
        try {
            filePart = request.getPart(FILE_FIELD);
        } catch (IllegalStateException e) {
            // Tomcat: a text field over server.tomcat.max-http-form-post-size, or a part over
            // spring.servlet.multipart.max-file-size / max-request-size
            log.warn("[API-8][filter] multipart request rejected: {}", e.getMessage());
            writeError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                    "the upload exceeds the configured size limits");
            return;
        }
        if (filePart == null || filePart.getSubmittedFileName() != null) {
            // No file field, or already a binary file part: nothing to convert
            chain.doFilter(request, response);
            return;
        }

        String text = new String(filePart.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        String contentType = request.getParameter(CONTENT_TYPE_FIELD);
        // A data URL ("data:image/png;base64,....") carries its own content type in front of the data
        if (text.startsWith("data:") && text.contains(",")) {
            String meta = text.substring(5, text.indexOf(','));
            text = text.substring(text.indexOf(',') + 1);
            if (contentType == null && !meta.isBlank()) {
                contentType = meta.split(";")[0];
            }
        }

        byte[] bytes;
        try {
            // MIME decoder: tolerates the line breaks some encoders insert every 76 characters
            bytes = Base64.getMimeDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            log.warn("[API-8][filter] field '{}' is not valid Base64: {}", FILE_FIELD, e.getMessage());
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "BAD_REQUEST",
                    "field '" + FILE_FIELD + "' must be the file content encoded as Base64");
            return;
        }

        String fileName = request.getParameter(FILE_NAME_FIELD);
        Base64DecodedPart decoded = new Base64DecodedPart(FILE_FIELD,
                fileName != null && !fileName.isBlank() ? fileName : DEFAULT_FILE_NAME,
                contentType != null && !contentType.isBlank() ? contentType : MediaType.APPLICATION_OCTET_STREAM_VALUE,
                bytes);
        log.info("[API-8][filter] field '{}': {} Base64 chars -> {} ({}, {} bytes)",
                FILE_FIELD, text.length(), decoded.getSubmittedFileName(), decoded.getContentType(), bytes.length);

        chain.doFilter(new DecodedFileRequest(request, decoded), response);
    }

    private static void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    /**
     * The original request with the Base64 text part swapped for the decoded file part.
     * The text is also removed from the parameters - Tomcat lists every text part as a request
     * parameter, and Camel copies parameters into Exchange headers, so it would otherwise travel
     * through the route as a header holding the whole file in Base64.
     */
    private static final class DecodedFileRequest extends HttpServletRequestWrapper {

        private final Base64DecodedPart decoded;

        DecodedFileRequest(HttpServletRequest request, Base64DecodedPart decoded) {
            super(request);
            this.decoded = decoded;
        }

        @Override
        public Collection<Part> getParts() throws IOException, ServletException {
            List<Part> parts = new ArrayList<>();
            for (Part part : super.getParts()) {
                parts.add(decoded.getName().equals(part.getName()) ? decoded : part);
            }
            return parts;
        }

        @Override
        public Part getPart(String name) throws IOException, ServletException {
            return decoded.getName().equals(name) ? decoded : super.getPart(name);
        }

        @Override
        public String getParameter(String name) {
            return decoded.getName().equals(name) ? null : super.getParameter(name);
        }

        @Override
        public String[] getParameterValues(String name) {
            return decoded.getName().equals(name) ? null : super.getParameterValues(name);
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            Map<String, String[]> params = new LinkedHashMap<>(super.getParameterMap());
            params.remove(decoded.getName());
            return Collections.unmodifiableMap(params);
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.enumeration(getParameterMap().keySet());
        }
    }

    /** A file part backed by the decoded bytes, indistinguishable from one the container parsed. */
    private static final class Base64DecodedPart implements Part {

        private final String name;
        private final String fileName;
        private final String contentType;
        private final byte[] bytes;
        private final String contentDisposition;

        Base64DecodedPart(String name, String fileName, String contentType, byte[] bytes) {
            this.name = name;
            this.fileName = fileName;
            this.contentType = contentType;
            this.bytes = bytes;
            this.contentDisposition = ContentDisposition.formData()
                    .name(name).filename(fileName, StandardCharsets.UTF_8).build().toString();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getSubmittedFileName() {
            return fileName;
        }

        @Override
        public long getSize() {
            return bytes.length;
        }

        @Override
        public void write(String path) throws IOException {
            Files.write(Path.of(path), bytes);
        }

        @Override
        public void delete() {
            // in memory only, nothing to clean up
        }

        @Override
        public String getHeader(String header) {
            if (HttpHeaders.CONTENT_DISPOSITION.equalsIgnoreCase(header)) {
                return contentDisposition;
            }
            return HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(header) ? contentType : null;
        }

        @Override
        public Collection<String> getHeaders(String header) {
            String value = getHeader(header);
            return value == null ? List.of() : List.of(value);
        }

        @Override
        public Collection<String> getHeaderNames() {
            return List.of(HttpHeaders.CONTENT_DISPOSITION, HttpHeaders.CONTENT_TYPE);
        }
    }
}
