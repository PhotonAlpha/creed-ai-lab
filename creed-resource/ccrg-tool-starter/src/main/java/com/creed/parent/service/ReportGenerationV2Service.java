package com.creed.parent.service;

import com.itextpdf.html2pdf.ConverterProperties;
import com.itextpdf.html2pdf.HtmlConverter;
import com.itextpdf.html2pdf.attach.ITagWorker;
import com.itextpdf.html2pdf.attach.ProcessorContext;
import com.itextpdf.html2pdf.attach.impl.DefaultTagWorkerFactory;
import com.itextpdf.html2pdf.attach.impl.tags.HTagWorker;
import com.itextpdf.io.font.FontProgram;
import com.itextpdf.io.font.FontProgramFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.IPropertyContainer;
import com.itextpdf.layout.element.Div;
import com.itextpdf.layout.element.IElement;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.styledxmlparser.node.IElementNode;
import com.itextpdf.styledxmlparser.resolver.font.BasicFontProvider;
import com.creed.parent.client.vo.confluence.attachment.AttachmentContentVO;
import com.creed.parent.client.vo.confluence.attachment.SimpleAttachmentContentVO;
import com.creed.parent.constant.ReportTypeEnum;
import com.creed.parent.constant.WalkPathTypeEnum;
import com.creed.parent.context.TenantContextHolder;
import com.creed.parent.controller.pdf.dto.BootstrapPath;
import com.creed.parent.controller.prompt.vo.RouteInfoGroupingVO;
import com.creed.parent.controller.vo.BatchFormVo;
import com.creed.parent.controller.vo.EnvironmentConfigurationVo;
import com.creed.parent.controller.vo.PropertyReviewOverviewVo;
import com.creed.parent.controller.vo.RouteFormVo;
import com.creed.parent.controller.vo.RouteInfoVo;
import com.creed.parent.controller.vo.SelectedOptionVo;
import com.creed.parent.convert.ConfluenceConvert;
import com.creed.parent.convert.PropertySourceConvert;
import com.creed.parent.dal.model.WalkPathInfo;
import com.creed.parent.exception.ServiceException;
import com.creed.parent.listener.EmitterEvent;
import com.creed.parent.listener.EmitterEventEnum;
import com.creed.parent.repo.ConfluenceRepository;
import com.creed.parent.service.report.ReportGenerator;
import com.creed.parent.service.report.ReportMetadataInfo;
import com.creed.parent.utils.DateTimeUtil;
import com.creed.parent.utils.PdfUtils;
import com.creed.parent.utils.json.JacksonUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.assertj.core.util.VisibleForTesting;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;
import org.springframework.web.context.request.RequestContextHolder;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.creed.parent.config.ThreadPoolConfig.TASK_EXECUTOR;

@Service
@Slf4j
public class ReportGenerationV2Service {
    private static final Set<String> H_TAGS = new HashSet<>(Arrays.asList("h1", "h2", "h3", "h4", "h5", "h6", "h7"));

    private static final Semaphore SEMAPHORE = new Semaphore(30);
    private static final Pattern FILE_PATH_PATTERN = Pattern.compile(".*/(?<filePath>[^/]+/LX/[^]]+).*");
    @Getter
    @Setter
    private UnaryOperator<String> extractOriginFileFunc = (original) -> {
        if (StringUtils.isNotBlank(original)) {
            Matcher matcher = FILE_PATH_PATTERN.matcher(original);
            if (matcher.matches()) {
                return matcher.group("filePath");
            }
        }
        return StringUtils.EMPTY;
    };

    @Resource
    @Lazy
    private PropertiesMatrixService propertiesMatrixService;
    @Resource
    @Lazy
    private PropertiesService propertiesService;
    @Resource
    @Lazy
    private DictLoadService dictLoadService;

    @Resource
    private TemplateEngine templateEngine;
    @Resource
    @Lazy
    private ReportGenerationV2Service self;

    @Resource
    ApplicationEventPublisher applicationEventPublisher;
    @Resource(name = "springTemplateEngine")
    private SpringTemplateEngine springTemplateEngine;

    @Resource
    @Lazy
    ConfluenceRepository confluenceRepository;
    @Resource
    @Lazy
    ReportGenerator reportGenerator;


    @Async(TASK_EXECUTOR)
    public CompletableFuture<Triple<String, File, List<RouteInfoVo>>> generatePdf(String connectionId, List<EnvironmentConfigurationVo> environmentConfiguration, RouteFormVo routeForm, Locale locale) throws IOException {
        log.info("generatePdf {} with Locale:{} reportType:{}...", JacksonUtils.toJsonString(routeForm), locale);
        try {
            boolean success = SEMAPHORE.tryAcquire(600, TimeUnit.SECONDS);
            if (!success) {
                throw new ServiceException(500, "tryAcquire failed");
            }
            ReportMetadataInfo metadataInfo = new ReportMetadataInfo(connectionId, routeForm);
            List<RouteInfoVo> routes = Optional.of(routeForm).map(RouteFormVo::getRoutes).orElse(Collections.emptyList());
            String generateFormattedFileName = generateFormattedFileName(routes, "pdf");
            generateFormattedFileName = generateFormattedFileName.replace("/", "-");
            Path tempFile = Files.createTempFile("tmp-", generateFormattedFileName);
            log.info("tempFile PDF file created: {}", tempFile);

            Triple<String, File, List<RouteInfoVo>> result;
            try (FileOutputStream fos = new FileOutputStream(tempFile.toFile())){
                reportGenerator.generateReport(environmentConfiguration, ReportTypeEnum.PDF, locale, metadataInfo, fos);
                result = Triple.of(generateFormattedFileName, tempFile.toFile(), routes);
            }
            applicationEventPublisher.publishEvent(new EmitterEvent(this, connectionId, EmitterEventEnum.REPORT_COUNTING));
            return CompletableFuture.completedFuture(result);
        } catch (InterruptedException e) {
            log.error("InterruptedException", e);
            /* Clean up whatever needs to be handled before interrupting  */
            Thread.currentThread().interrupt();
            throw new ServiceException(500, ExceptionUtils.getMessage(e));
        } finally {
            SEMAPHORE.release();
        }
    }
    /**
     * description upload from file
     * @author EthanCao
     * @since 2026-01-16T10
     */
    public SimpleAttachmentContentVO uploadFile2Cloud(String contentId, Path tmpFile, String generateFormattedFileName) throws IOException {
        ByteArrayResource resource = new ByteArrayResource(Files.readAllBytes(tmpFile)) {
            @Override
            public String getFilename() {
                return generateFormattedFileName;
            }
        };
        return uploadFile2Cloud(contentId, resource);
    }
    /**
     * description upload from ByteArrayResource
     * @author EthanCao
     * @since 2026-01-16T10
     */
    public SimpleAttachmentContentVO uploadFile2Cloud(String contentId, org.springframework.core.io.Resource resource) {
        log.info("upload attachment: {} with name:{}", contentId, resource.getFilename());
        AttachmentContentVO attachmentContentVO = confluenceRepository.uploadAttachment(contentId, resource);
        SimpleAttachmentContentVO simpleAttachmentContentVO = ConfluenceConvert.INSTANCE.mapContainers(attachmentContentVO);
        log.info("upload attachment: {} successful", JacksonUtils.toJsonString(simpleAttachmentContentVO));
        return simpleAttachmentContentVO;
    }
    @Async(TASK_EXECUTOR)
    @VisibleForTesting
    public CompletableFuture<SimpleAttachmentContentVO> generateAndUploadPdfMock(String contentId, String connectionId, PropertyReviewOverviewVo propertyReviewOverview, RouteFormVo routeForm, Locale locale) {
        SimpleAttachmentContentVO simpleAttachmentContent = new SimpleAttachmentContentVO();
        simpleAttachmentContent.setBase("xxxx");
        simpleAttachmentContent.setWebui("xxxx");
        simpleAttachmentContent.setBase("xxxx");

        List<RouteInfoVo> routes = routeForm.getRoutes();
        String generateFormattedFileName = generateFormattedFileName(routes, "pdf");
        generateFormattedFileName = generateFormattedFileName.replace("/", "-");

        simpleAttachmentContent.setTitle(generateFormattedFileName);
        routeForm.setSimpleAttachmentContent(simpleAttachmentContent);

        applicationEventPublisher.publishEvent(new EmitterEvent(this, connectionId, EmitterEventEnum.REPORT_COUNTING));
        return CompletableFuture.completedFuture(simpleAttachmentContent);

    }

    /**
     * description upload sequence
     * @author EthanCao
     * @since 2026-06-22T17
     */
    // @Async(TASK_EXECUTOR)
    public CompletableFuture<SimpleAttachmentContentVO> generateAndUpload2Cloud(String contentId, String connectionId, List<EnvironmentConfigurationVo> environmentConfiguration, RouteFormVo routeForm, Locale locale, ReportTypeEnum reportType) {
        log.info("generateAndUpload2Cloud {} with Locale:{} reportType:{}...", JacksonUtils.toJsonString(routeForm), locale, reportType);
        Path tempFile = null;
        try {
            // boolean success = SEMAPHORE.tryAcquire(600, TimeUnit.SECONDS);
            // if (!success) {
            //     throw new ServiceException(500, "tryAcquire failed");
            // }
/*
            tempFile = Files.createTempFile("tmp-", generateFormattedFileName);

            Files.write(tempFile, byteArrayResource.getByteArray());
            Triple<String, File, List<RouteInfoVo>> result = Triple.of(generateFormattedFileName, tempFile.toFile(), routes);
*/
            ReportMetadataInfo metadataInfo = new ReportMetadataInfo(connectionId, routeForm);

            List<RouteInfoVo> routes = Optional.of(routeForm).map(RouteFormVo::getRoutes).orElse(Collections.emptyList());
            String formattedFileName = generateFormattedFileName(routes, reportType.getSuffix());
            formattedFileName = formattedFileName.replace("/", "-");
            tempFile = Files.createTempFile("tmp-", formattedFileName);
            log.info("tempFile PDF file created: {}", tempFile);

            try (FileOutputStream fos = new FileOutputStream(tempFile.toFile())){
                reportGenerator.generateReport(environmentConfiguration, reportType, locale, metadataInfo, fos);
            }
            SimpleAttachmentContentVO simpleAttachmentContent;
            if (TenantContextHolder.isUploadConfluence()) {
                log.info("skil upload confluence");
                simpleAttachmentContent = uploadFile2Cloud(contentId, tempFile, formattedFileName);
                routeForm.setSimpleAttachmentContent(simpleAttachmentContent);
            } else {
                simpleAttachmentContent = new SimpleAttachmentContentVO();
            }
            applicationEventPublisher.publishEvent(new EmitterEvent(this, connectionId, EmitterEventEnum.REPORT_COUNTING));
            return CompletableFuture.completedFuture(simpleAttachmentContent);
        } catch (Exception e) {
            log.error("Upload Exception: ", e);
            // throw new ServiceException(500, ExceptionUtils.getMessage(e));
        } finally {
            // SEMAPHORE.release();
            try {
                long size = Files.size(tempFile);
                String filesize = PdfUtils.humanReadableByteCount(size);
                log.info("tempFile PDF file size: {} -- {}", size, filesize);
                if (Objects.nonNull(tempFile)) {
                    boolean result = Files.deleteIfExists(tempFile);
                    log.info("deleting tmp files:{} is success:{}", tempFile, result);
                }
            } catch (IOException e) {
                log.error("deleteIfExists Exception: {}", ExceptionUtils.getMessage(e));
            }
        }
        return CompletableFuture.completedFuture(null);
    }




    public ByteArrayOutputStream constructPdfDocumentOS(Context context, String template, String fragmentTemplate, String fragmentName, Locale locale) throws IOException {
        // String baseUrl = getCurrentBaseUrl();
        String customCssContentPath;
        // see https://stackoverflow.com/questions/53203909/spring-boot-thymeleaf-css-is-not-applied-to-template
        if (org.thymeleaf.util.StringUtils.equals(locale.getLanguage(), "zh")) {
            customCssContentPath = "pdf/css/custom.SC.css";
        } else {
            customCssContentPath = "pdf/css/custom.css";
        }
        // String cssContentPath =  "/pdf/css/custom.css";
        String cssContentPath = "pdf/css/bootstrap.min.css";
        String profilePath = "pdf/img/logo-infinity.svg";
        BootstrapPath bootstrapPath = new BootstrapPath();
        bootstrapPath.setCustomUtilsCss("pdf/css/custom-utilities.css");
        bootstrapPath.setCustomCssContent(customCssContentPath);
        bootstrapPath.setCssContent(cssContentPath);
        bootstrapPath.setProfileImg(profilePath);

        context.setVariable("bootstrapPath", bootstrapPath);
        context.setVariable("fragmentTemplate", fragmentTemplate);
        context.setVariable("fragmentName", fragmentName);
        StopWatch stopWatch = new StopWatch();
        stopWatch.start("constructPdfDocumentOS-templateEngine process");
        String htmlContent = templateEngine.process(template, context);
        stopWatch.stop();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Document document;
        stopWatch.start("constructPdfDocumentOS-generatePDF process");
        try (PdfWriter writer = new PdfWriter(outputStream);
             PdfDocument pdfDocument = new PdfDocument(writer)) {

            // pdfDocument.setTagged();
            PageSize pageSize = PageSize.A4.rotate();
            pdfDocument.setDefaultPageSize(pageSize);
            pdfDocument.setTagged();
            // Configure ConverterProperties to ignore exceptions
            ConverterProperties converterProperties = new ConverterProperties();
            BasicFontProvider fontProvider = new BasicFontProvider(false, false, false);

            for (String fontPath : fontList(locale)) {
                FontProgram fontProgram = FontProgramFactory.createFont(new ClassPathResource(fontPath).getInputStream().readAllBytes(), true);
                log.info("fontPath:{}", fontPath);
                fontProvider.addFont(fontProgram);
            }
            converterProperties.setFontProvider(fontProvider);
            converterProperties.setBaseUri(getCurrentBaseUrl() + "/");
            // https://kb.itextpdf.com/itext/creating-well-tagged-pdf-documents-with-pdfhtml
            DefaultTagWorkerFactory factory = new DefaultTagWorkerFactory() {
                @Override
                public ITagWorker getCustomTagWorker(IElementNode tag, ProcessorContext context) {
                    if (H_TAGS.contains(tag.name())) {
                        return new HTagWorker(tag, context) {
                            @Override
                            public boolean processTagChild(ITagWorker childTagWorker, ProcessorContext context) {
                                return super.processTagChild(childTagWorker, context);
                            }

                            @Override
                            public IPropertyContainer getElementResult() {
                                IPropertyContainer elementResult = super.getElementResult();
                                if (elementResult instanceof Div) {
                                    for (IElement child : ((Div) elementResult).getChildren()) {
                                        if (child instanceof Paragraph) {
                                            ((Paragraph) child).setNeutralRole();
                                        }
                                    }
                                }
                                return elementResult;
                            }
                        };
                    }
                    /* use PDF comment instead
                    else if ("span".equals(tag.name()) && StringUtils.isNotBlank(tag.getAttribute("data-comment"))) {
                        return new AnnotatedSpanTagWorker(tag, context);
                    }*/
                    return super.getCustomTagWorker(tag, context);
                }
            };
            converterProperties.setTagWorkerFactory(factory);
            // converterProperties.setCssApplierFactory()

            /* document = HtmlConverter.convertToDocument(htmlContent, pdfDocument, converterProperties);
            document.close(); */


            HtmlConverter.convertToPdf(htmlContent, pdfDocument, converterProperties);

            // Document convertedDocument = HtmlConverter.convertToDocument(htmlContent, pdfDocument, converterProperties);
            // PdfAnnotation comment = new PdfTextAnnotation(new Rectangle(100, 600, 0, 0))
            //         .setContents("this is a comment")
            //         .setTitle(new PdfString("注释作者"))
            //         .setContents("Hello PDF");
            // PdfPage page = pdfDocument.getPage(1);
            // page.addAnnotation(comment);
            // convertedDocument.close();

        }
        stopWatch.stop();
        log.info(">>stopWatch for template PDF<<{}", stopWatch.prettyPrint(TimeUnit.MILLISECONDS));
        return outputStream;
        // Files.write(Paths.get("/logs/dep", "thymeleaf_test.pdf"), outputStream.toByteArray(), StandardOpenOption.CREATE);
    }
    public String getCurrentBaseUrl() {
        // ServletRequestAttributes sra = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        /* if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attr) {
            HttpServletRequest httpServletRequest = attr.getRequest();
            String baseUrl = httpServletRequest.getScheme() + "://" + httpServletRequest.getServerName() + ":" + httpServletRequest.getServerPort() + httpServletRequest.getContextPath();
            log.info("baseUrl:{}", baseUrl);
            return baseUrl;
        }
        log.info("looking baseUrl failed!");
        return StringUtils.EMPTY; */
        return propertiesMatrixService.getRootPath();
    }

    protected List<String> fontList(Locale locale) {
        if (org.thymeleaf.util.StringUtils.equals(locale.getLanguage(), "zh")) {
            return Arrays.asList(
                    "static/pdf/fonts/NotoSans/NotoSansSC-Regular.ttf",
                    "static/pdf/fonts/NotoSans/NotoSansSC-Bold.ttf",
                    "static/pdf/fonts/NotoSans/NotoSansSC-Light.ttf"
            );
        }
        return Arrays.asList(
                "static/pdf/fonts/OpenSans/OpenSans-Regular.ttf",
                "static/pdf/fonts/OpenSans/OpenSans-Bold.ttf",
                "static/pdf/fonts/OpenSans/OpenSans-Light.ttf"
        );
    }

    public Pair<String, ByteArrayOutputStream> generateReport(String connectionId, RouteFormVo routeForm, Locale locale) {
        List<RouteInfoVo> routes = Optional.of(routeForm).map(RouteFormVo::getRoutes).orElse(Collections.emptyList());
        List<EnvironmentConfigurationVo> environmentConfiguration = propertiesService.getAllProperties(routes);
        if (CollectionUtils.isEmpty(environmentConfiguration)) {
            log.warn("!!environmentConfiguration is empty!!");
            return null;
        }

        ReportMetadataInfo metadataInfo = new ReportMetadataInfo(connectionId, routeForm);
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()){
            reportGenerator.generateReport(environmentConfiguration, ReportTypeEnum.XLSX, locale, metadataInfo, outputStream);
            return Pair.of(DateTimeUtil.getTimeStr() + "_comparison_report.xlsx", outputStream);
        } catch (IOException e) {
            log.error("Upload Exception: ", e);
            throw new ServiceException(500, ExceptionUtils.getMessage(e));
        }
    }

    public Pair<String, ByteArrayOutputStream> generateReport(RouteFormVo routeForm) {
        List<RouteInfoVo> routes = Optional.of(routeForm).map(RouteFormVo::getRoutes).orElse(Collections.emptyList());
        List<EnvironmentConfigurationVo> environmentConfiguration = propertiesService.getAllProperties(routes);
        if (CollectionUtils.isEmpty(environmentConfiguration)) {
            log.warn("!!environmentConfiguration is empty!!");
            return null;
        }
        ReportMetadataInfo metadataInfo = new ReportMetadataInfo(UUID.randomUUID().toString(), routeForm);
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()){
            reportGenerator.generateReport(environmentConfiguration, ReportTypeEnum.XLSX, Locale.ENGLISH, metadataInfo, outputStream);
            return Pair.of(DateTimeUtil.getTimeStr() + "_comparison_report.xlsx", outputStream);
        } catch (IOException e) {
            log.error("Upload Exception: ", e);
            throw new ServiceException(500, ExceptionUtils.getMessage(e));
        }
    }

    public Pair<String, ByteArrayOutputStream> generateReportBatch(BatchFormVo routeForm) throws IOException {
        log.info("generateReportBatch starting...");
        List<RouteFormVo> routeInfo = routeForm.getBatch();
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             ZipArchiveOutputStream zipOutputStream = new ZipArchiveOutputStream(outputStream)
        ) {
            for (RouteFormVo vo : routeInfo) {
                List<RouteFormVo> enrichRoutes = enrichRouteFormVo(vo);
                if (CollectionUtils.isEmpty(enrichRoutes)) {
                    ZipArchiveEntry zipOut = new ZipArchiveEntry(DateTimeUtil.getTimeStr() + "_comparison_report.error");
                    zipOutputStream.putArchiveEntry(zipOut);
                    zipOutputStream.write(DictService.mapper.writeValueAsBytes(vo));
                    zipOutputStream.closeArchiveEntry();
                } else {
                    for (RouteFormVo enrichedVo : enrichRoutes) {
                        Pair<String, ByteArrayOutputStream> outputStreamPair;
                        outputStreamPair = generateReport(null, enrichedVo, Locale.ENGLISH);
                        if (Objects.isNull(outputStreamPair)) {
                            ZipArchiveEntry zipOut = new ZipArchiveEntry(DateTimeUtil.getTimeStr() + "_comparison_report.error");
                            zipOutputStream.putArchiveEntry(zipOut);
                            zipOutputStream.write(JacksonUtils.toJsonByte(enrichedVo));
                        } else {
                            ZipArchiveEntry zipOut = new ZipArchiveEntry(generateFormattedFileName(enrichedVo.getRoutes(), "xlsx"));
                            zipOutputStream.putArchiveEntry(zipOut);
                            zipOutputStream.write(outputStreamPair.getRight().toByteArray());
                        }
                        zipOutputStream.closeArchiveEntry();
                    }
                }
            }

            return Pair.of(DateTimeUtil.getTimeStr() + "_comparison_report.zip", outputStream);
        } catch (IOException e) {
            log.error("generateReportBatch", e);
            throw e;
        }
    }

    public void generatePdfBatch(BatchFormVo routeForm, Locale locale, HttpServletResponse response) throws IOException {
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);

        log.info("generatePdfBatch starting...");
        List<RouteFormVo> routeInfo = routeForm.getBatch();
        String fileName = routeInfo.stream().map(RouteFormVo::getRoutes)
                .flatMap(Collection::stream)
                .map(info -> info.getReleaseName() + "_" + info.getEnvironment())
                .distinct()
                .collect(Collectors.joining("_VS_"));
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=" + fileName + "_report.zip");
        // header.setContentLength(documentBody.length);

        String connectionId = routeForm.getConnectionId();
        applicationEventPublisher.publishEvent(new EmitterEvent(this, connectionId, EmitterEventEnum.INIT));

        // sove RequestContextHolder.getRequestAttributes() is null issue
        RequestContextHolder.setRequestAttributes(RequestContextHolder.getRequestAttributes(), true);

        StopWatch stopWatch = new StopWatch();
        stopWatch.start("generatePdfBatch");

        List<RouteFormVo> combinationList = routeInfo.stream()
                .map(this::enrichRouteFormVo)
                .flatMap(Collection::stream)
                .toList();
        applicationEventPublisher.publishEvent(new EmitterEvent(this, connectionId, EmitterEventEnum.SET_TOTAL, combinationList.size()));
        List<File> tmpFiles = new ArrayList<>();
        try (BufferedOutputStream outputStream = new BufferedOutputStream(response.getOutputStream());
             ZipArchiveOutputStream zipOutputStream = new ZipArchiveOutputStream(outputStream)
        ) {
            // TODO change to reactor
            List<CompletableFuture<Triple<String, File, List<RouteInfoVo>>>> futureList = new ArrayList<>();
            if (CollectionUtils.isEmpty(combinationList)) {
                ZipArchiveEntry zipOut = new ZipArchiveEntry(DateTimeUtil.getTimeStr() + "_comparison_report.error");
                zipOutputStream.putArchiveEntry(zipOut);
                zipOutputStream.write(JacksonUtils.toJsonByte(routeInfo));
                zipOutputStream.closeArchiveEntry();
            } else {

                for (RouteFormVo enrichedVo : combinationList) {
                    List<EnvironmentConfigurationVo> environmentConfiguration = propertiesService.getAllProperties(enrichedVo.getRoutes());
                    if (CollectionUtils.isEmpty(environmentConfiguration)) {
                        continue;
                    }
                    log.debug(">>propertyReviewOverview:{}", JacksonUtils.toJsonString(environmentConfiguration));
                    log.info(">>property results:{}", environmentConfiguration.size());
                    futureList.add(self.generatePdf(connectionId, environmentConfiguration, enrichedVo, locale));
                }
            }
            applicationEventPublisher.publishEvent(new EmitterEvent(this, connectionId, EmitterEventEnum.REPORT_INIT));
            CompletableFuture.allOf(futureList.toArray(new CompletableFuture[0])).join();
            futureList.stream()
                    .map(cf -> cf.exceptionally(e -> {
                        log.error("generatePdf encounter error:", e);
                        return null;
                    }))
                    .map(CompletableFuture::join)
                    .forEach(osp -> {
                        try {
                            if (Objects.isNull(osp)) {
                                ZipArchiveEntry zipOut = new ZipArchiveEntry(DateTimeUtil.getTimeStr() + "_comparison_report.error");
                                zipOutputStream.putArchiveEntry(zipOut);
                                zipOutputStream.write(JacksonUtils.toJsonByte("something went wrong!"));
                            } else {
                                ZipArchiveEntry zipOut = new ZipArchiveEntry(generateFormattedFileName(osp.getRight(), "pdf"));
                                zipOutputStream.putArchiveEntry(zipOut);
                                // zipOutputStream.write(osp.getMiddle().toByteArray());

                                tmpFiles.add(osp.getMiddle());
                                try (InputStream fis = new BufferedInputStream(new FileInputStream(osp.getMiddle()))) {
                                    byte[] buffer = new byte[8192];
                                    int len;
                                    while ((len = fis.read(buffer)) != -1) {
                                        zipOutputStream.write(buffer, 0, len);
                                    }
                                }
                            }
                            zipOutputStream.closeArchiveEntry();
                        } catch (IOException e) {
                            log.error("inside CompletableFuture generateReportBatch:{}", ExceptionUtils.getMessage(e));
                        }
                    });
            stopWatch.stop();

            log.info(">>stopWatch for generatePdfBatch aggregate<<{}", stopWatch.prettyPrint(TimeUnit.MILLISECONDS));
            // return Pair.of(fileName + "_report.zip", outputStream);
        } catch (IOException e) {
            log.error("generateReportBatch", e);
            throw e;
        } finally {
            applicationEventPublisher.publishEvent(new EmitterEvent(this, connectionId, EmitterEventEnum.COMPLETED));
            if (CollectionUtils.isNotEmpty(tmpFiles)) {
                for (File tmpFile : tmpFiles) {
                    boolean result = Files.deleteIfExists(tmpFile.toPath());
                    log.info("deleting tmp files:{} is success:{}", tmpFile.toPath(), result);
                }
            }
        }
    }

    public String generateFormattedFileName(List<RouteInfoVo> routeInfos, String suffix) {
        StringBuilder sb = new StringBuilder();
        Optional<RouteInfoVo> optionalRouteInfo = routeInfos.stream().findAny();
        String envConjunction = routeInfos.stream().map(RouteInfoVo::getEnvironment)
                .distinct().collect(Collectors.joining("_"));
        String insConjunction = routeInfos.stream().map(RouteInfoVo::getInstance)
                .distinct().collect(Collectors.joining("_"));
        fileNameConsumer(optionalRouteInfo, RouteInfoVo::getProjectName, sb::append);
        fileNameConsumer(optionalRouteInfo, RouteInfoVo::getCountryCode, sb::append);
        fileNameConsumer(optionalRouteInfo, RouteInfoVo::getApp, sb::append);
        fileNameConsumer(optionalRouteInfo, any -> envConjunction, sb::append);
        fileNameConsumer(optionalRouteInfo, any -> insConjunction, sb::append);
        fileNameConsumer(optionalRouteInfo, RouteInfoVo::getModule, sb::append);
        String module = optionalRouteInfo.map(RouteInfoVo::getModule).orElse(StringUtils.EMPTY);
        if (StringUtils.isNotBlank(module)) {
            return module + "/" + sb + "report_%s.%s".formatted(RandomStringUtils.randomAlphabetic(5), suffix);
        }
        return sb + "report_%s.%s".formatted(RandomStringUtils.randomAlphabetic(5), suffix);
    }

    protected void fileNameConsumer(Optional<RouteInfoVo> optionalRouteInfo, Function<RouteInfoVo, String> stringSupplier, Consumer<String> appendConsumer) {
        optionalRouteInfo.map(stringSupplier).map("%s_"::formatted).ifPresent(appendConsumer);
    }

    public List<RouteFormVo> enrichRouteFormVo(RouteFormVo vo) {
        log.info("enrichRouteForm starting...");
        Optional<RouteInfoVo> firstInfo = vo.getRoutes().stream().findFirst();
        String appCode = firstInfo.map(RouteInfoVo::getApp).orElse(StringUtils.EMPTY);
        // if MS, export all MS modules
        // Collection<String> matchingKeys = CollectionUtils.intersection(sourceUniqueKVMapping.keySet(), targetUniqueKVMapping.keySet());
        if (firstInfo.isPresent() && StringUtils.equalsIgnoreCase(appCode, "MS")) {
            // make sure left and right side module consistent, if there is diff, just ignore
            List<String> moduleList = vo.getRoutes().stream()
                    .map(this::findAllModuleList)
                    .reduce((pre, aft) -> CollectionUtils.intersection(pre, aft).stream().toList())
                    .orElse(Collections.emptyList());
            log.info("final moduleList in batch:{}", moduleList);
            return moduleList.stream().map(str -> {
                RouteFormVo routeFormVo = PropertySourceConvert.INSTANCE.copy0(vo);
                routeFormVo.getRoutes().forEach(r -> r.setModule(str));
                return routeFormVo;
            }).toList();
        }
        return Collections.singletonList(vo);
    }

    private List<String> findAllModuleList(RouteInfoVo infoVo) {
        WalkPathInfo walkPathInfo = new WalkPathInfo();
        BeanUtils.copyProperties(infoVo, walkPathInfo);
        walkPathInfo.setDictType(WalkPathTypeEnum.MODULE.name());
        List<SelectedOptionVo> modules = dictLoadService.pathWalk(walkPathInfo);
        if (CollectionUtils.isEmpty(modules)) {
            return Collections.emptyList();
        }

        List<String> moduleList = modules.stream().map(SelectedOptionVo::getName).toList();
        log.info("findAllModuleList meta info:{} result:{}", infoVo, moduleList);
        return moduleList;
    }

    public List<RouteInfoGroupingVO> groupingForTemplate(List<RouteFormVo> combinationList) {
        TreeMap<String, List<RouteFormVo>> releaseMappings = combinationList.stream()
                .collect(Collectors.groupingBy(rf -> rf.getRoutes().stream()
                                .map(RouteInfoVo::getReleaseName).reduce((o, n) -> String.join("::", o, n))
                                .orElse("NA"),
                        TreeMap::new,
                        Collectors.toList()
                ));
        log.info("releaseMappings::{}", JacksonUtils.toJsonString(releaseMappings));
        List<RouteInfoGroupingVO> groupingList = releaseMappings.entrySet().stream()
                .map(entry -> {
                    String[] strings = StringUtils.split(entry.getKey(), "::");
                    String projectName = findByProjectName(entry.getValue());
                    String sourceArtifactoryLink = getArtifactoryLink(projectName, strings[0]);
                    String comparisonArtifactoryLink = getArtifactoryLink(projectName, strings[1]);
                    RouteInfoGroupingVO routeInfoGrouping = new RouteInfoGroupingVO(projectName, strings[0], strings[1], groupingByApp(entry.getValue()));
                    routeInfoGrouping.setSourceArtifactoryUrl(sourceArtifactoryLink);
                    routeInfoGrouping.setComparisonArtifactoryUrl(comparisonArtifactoryLink);
                    return routeInfoGrouping;
                }).toList();
        log.info("groupingList::{}", JacksonUtils.toJsonString(groupingList));
        return groupingList;
    }
    protected TreeMap<String, TreeMap<String, List<RouteFormVo>>> groupingByApp(List<RouteFormVo> routeForms) {
        return routeForms.stream()
                .collect(Collectors.groupingBy(rf -> rf.getRoutes().stream().findFirst().map(
                        info -> "%s_%s".formatted(info.getProjectName(),info.getApp())
                                ).orElse(""),
                        TreeMap::new,
                        Collectors.groupingBy(r -> r.getRoutes().stream().findFirst().map(RouteInfoVo::getModule).orElse("NA"),
                                TreeMap::new,
                                Collectors.toList()
                        ))
                );
    }
    protected String findByProjectName(List<RouteFormVo> routeForms) {
        return routeForms.stream()
                .findAny()
                .map(RouteFormVo::getRoutes)
                .map(Collection::stream)
                .map(Stream::findAny)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(RouteInfoVo::getProjectName)
                .orElse("INVALID");
    }
    protected String getArtifactoryLink(String projectName, String args) {
        String releaseName = StringUtils.substringBeforeLast(args, "_");
        String buildNo = StringUtils.substringAfterLast(args, "_");
        return "https://artifactory.example.com/artifactory/creed/%s/release/%s/%s/".formatted(projectName, releaseName, buildNo);

    }

    public String renderHtmlTemplate(List<RouteInfoGroupingVO> groupingList) {
        Context context = new Context();
        context.setVariable("routes", groupingList);
        return springTemplateEngine.process("confluence-index", Set.of("content"), context);
    }
}
