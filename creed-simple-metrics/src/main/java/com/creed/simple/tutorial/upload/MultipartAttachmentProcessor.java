package com.creed.simple.tutorial.upload;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.attachment.AttachmentMessage;
import org.apache.camel.http.common.HttpMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * API-8, first step: turns every file part of the multipart request into a Camel attachment named
 * after its form field, so {@link FileUploadProcessor} can read {@code getAttachment("file")}.
 *
 * <p>Why a step of its own: the original project ran on camel-platform-http, which does this while
 * binding the request. This module serves REST through the Camel servlet, and a REST DSL consumer
 * there always gets {@code ServletRestHttpBinding}, which never looks at multipart parts — the
 * component's {@code attachmentMultipartBinding} option only switches the binding of plain
 * {@code servlet:} endpoints, not of REST ones. Doing it in the route keeps the fix next to the one
 * API that needs it instead of changing the binding of every REST endpoint in the module.
 *
 * <p>Parts without a submitted file name are plain form fields; they already arrived as headers.
 */
@Component("multipartAttachmentProcessor")
public class MultipartAttachmentProcessor implements Processor {

    private static final Logger log = LoggerFactory.getLogger(MultipartAttachmentProcessor.class);

    @Override
    public void process(Exchange exchange) throws Exception {
        HttpServletRequest request = exchange.getIn(HttpMessage.class).getRequest();
        String contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("multipart/")) {
            return;
        }
        AttachmentMessage message = exchange.getIn(AttachmentMessage.class);
        // The request is Base64FileMultipartFilter's wrapper, so the decoded "file" part is the one seen here
        for (Part part : request.getParts()) {
            if (part.getSubmittedFileName() != null) {
                message.addAttachment(part.getName(), new DataHandler(new PartDataSource(part)));
                log.info("[API-8][attachment] part '{}' -> attachment ({}, {} bytes)",
                        part.getName(), part.getSubmittedFileName(), part.getSize());
            }
        }
    }

    /** A read-only view of one part: its bytes, submitted file name and media type. */
    record PartDataSource(Part part) implements DataSource {

        @Override
        public InputStream getInputStream() throws IOException {
            return part.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() {
            throw new UnsupportedOperationException("read-only");
        }

        @Override
        public String getContentType() {
            return part.getContentType() != null ? part.getContentType() : "application/octet-stream";
        }

        @Override
        public String getName() {
            return part.getSubmittedFileName();
        }
    }
}
