package com.creed.parent.utils;


import com.itextpdf.kernel.pdf.EncryptionConstants;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.WriterProperties;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@UtilityClass
@Slf4j
public class PdfUtils {
    private final ReentrantLock lock = new ReentrantLock();
    private static final Map<String, byte[]> fontBinaryMap = new ConcurrentHashMap<>();

    public static List<byte[]> getFontBinary(List<String> fontList) throws IOException {
        Set<String> sets = fontBinaryMap.keySet();
        if (sets.containsAll(fontList)) {
            return fontList.stream().map(fontBinaryMap::get).toList();
        } else {
            synchronized (PdfUtils.class) {
                log.info("FontBinary initializing...");
                if (!fontBinaryMap.keySet().containsAll(fontList)) {
                    for (String path : fontList) {
                        log.info("fontPath:{}", path);
                        if (!fontBinaryMap.containsKey(path)) {
                            byte[] byteArray = IOUtils.toByteArray(new ClassPathResource(path).getInputStream());
                            fontBinaryMap.put(path, byteArray);
                        }
                    }
                }
                return fontList.stream().map(fontBinaryMap::get).toList();
            }
        }
    }

    public static ByteArrayOutputStream merge(List<PdfDocument> pdfDocuments, String password, boolean needPwd){
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        merge(pdfDocuments, password, needPwd, outputStream);
        return outputStream;
    }

    /**
     * to solve the concurrency  java.lang.IllegalStateException: Cipher not initialized issue,
     * move the merge with lock if there is encryption.
     * <p>
     * 在并发场景，合并PDF并且添加密码的时候，由于Cipher不是线程安全的，会报异常。因此需要添加锁来处理
     *
     * @param pdfDocuments
     * @param password
     * @param needPwd
     * @return
     */
    public void merge(List<PdfDocument> pdfDocuments, String password, boolean needPwd, OutputStream outputStream) {
        if (!needPwd) {
            mergeWithoutPassword(pdfDocuments, outputStream);
        } else {
            mergeWithPassword(pdfDocuments, password, outputStream);
        }
        /** backup option
         try (PdfDocument pdfDocument = new PdfDocument(writer)) {
         PdfMerger merger = new PdfMerger(pdfDocument, false, false);
         for (PdfDocument document : pdfDocuments) {
         merger.merge(document, 1, document.getNumberOfPages());
         document.close();
         }
         } */
    }

    private void mergeWithoutPassword(List<PdfDocument> pdfDocuments, OutputStream outputStream) {
        try (PdfWriter writer = new PdfWriter(outputStream);
             PdfDocument pdfDocument = new PdfDocument(writer)) {
            writer.setSmartMode(true);
            for (PdfDocument document : pdfDocuments) {
                try (document) {
                    document.copyPagesTo(1, document.getNumberOfPages(), pdfDocument);
                }
            }
        } catch (IOException e) {
            log.error("Failed to acquire lock in InterruptedException:{}", Thread.currentThread().getName(), e);
        }
    }

    private void mergeWithPassword(List<PdfDocument> pdfDocuments, String password, OutputStream outputStream) {
        try {
            if (lock.tryLock(60, TimeUnit.SECONDS)) {
                try (PdfWriter writer = new PdfWriter(outputStream,
                        new WriterProperties()
                                .setStandardEncryption(
                                        password.getBytes(StandardCharsets.UTF_8),
                                        password.getBytes(StandardCharsets.UTF_8),
                                        EncryptionConstants.ALLOW_PRINTING,
                                        EncryptionConstants.ENCRYPTION_AES_256
                                ));
                     PdfDocument pdfDocument = new PdfDocument(writer)) {
                    // PdfWriter writer = new PdfWriter(Paths.get("/logs/dep", "thymeleaf_test.pdf").toString())
                    writer.setSmartMode(true);
                    for (PdfDocument document : pdfDocuments) {
                        try (document) {
                            document.copyPagesTo(1, document.getNumberOfPages(), pdfDocument);
                        }
                    }
                } catch (IOException e) {
                    log.error("Failed to acquire lock in InterruptedException:{}", Thread.currentThread().getName(), e);
                }
            } else {
                log.error("Failed to acquire lock in thread:{}", Thread.currentThread().getName());
            }
        } catch (InterruptedException e) {
            log.error("Failed to acquire lock in InterruptedException:{}", Thread.currentThread().getName(), e);
            /* Clean up whatever needs to be handled before interrupting  */
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
    }


    private void mergePdfX(PdfWriter writer, List<ByteArrayOutputStream> pdfStreams) {
        try (PdfDocument mergePdfDocument = new PdfDocument(writer)) {
            writer.setSmartMode(true);
            for (ByteArrayOutputStream os : pdfStreams) {
                try (PdfReader sourceReader = new PdfReader(new ByteArrayInputStream(os.toByteArray()));
                     PdfDocument sourceDocument = new PdfDocument(sourceReader)) {
                    sourceDocument.copyPagesTo(1, sourceDocument.getNumberOfPages(), mergePdfDocument);
                }
            }
        } catch (IOException e) {
            log.error("Failed to acquire lock in InterruptedException:{}", Thread.currentThread().getName(), e);
        }
    }
    private void mergePdfWithPasswordX(PdfWriter writer, List<ByteArrayOutputStream> pdfStreams) {
        try {
            if (lock.tryLock(60, TimeUnit.SECONDS)) {
                mergePdfX(writer, pdfStreams);
            } else {
                log.error("Failed to acquire lock in thread:{}", Thread.currentThread().getName());
            }
        } catch (InterruptedException e) {
            log.error("Failed to acquire lock in InterruptedException:{}", Thread.currentThread().getName(), e);
            /* Clean up whatever needs to be handled before interrupting  */
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
    }


    public void mergeX(List<ByteArrayOutputStream> pdfDocuments, String password, boolean needPwd, OutputStream outputStream) {
        if (!needPwd) {
            try (PdfWriter writer = new PdfWriter(outputStream);) {
                mergePdfX(writer, pdfDocuments);
            } catch (Exception e) {
                log.error("Failed to acquire lock in InterruptedException:{}", Thread.currentThread().getName(), e);
            }
        } else {

            try (PdfWriter writer = new PdfWriter(outputStream,
                    new WriterProperties()
                            .setStandardEncryption(
                                    password.getBytes(StandardCharsets.UTF_8),
                                    password.getBytes(StandardCharsets.UTF_8),
                                    EncryptionConstants.ALLOW_PRINTING,
                                    EncryptionConstants.ENCRYPTION_AES_256
                            ))) {
                mergePdfWithPasswordX(writer, pdfDocuments);
            } catch (Exception e) {
                log.error("Failed to acquire lock in InterruptedException:{}", Thread.currentThread().getName(), e);
            }
        }
    }


    public static String humanReadableByteCount(long bytes, boolean si) {
        int unit = si ? 1000 : 1024;
        if (bytes < unit) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(unit));
        String pre = (si ? "kMGTPE" : "KMGTPE").charAt(exp - 1) + (si ? "" : "i");
        return String.format("%.2f %sB", bytes / Math.pow(unit, exp), pre);
    }
    public static String humanReadableByteCount(long bytes) {
        return humanReadableByteCount(bytes, true);
    }


}
