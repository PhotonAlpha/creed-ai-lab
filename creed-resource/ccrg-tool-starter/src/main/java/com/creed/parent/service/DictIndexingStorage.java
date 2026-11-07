package com.creed.parent.service;

import com.creed.parent.config.PropertiesMatrixConfig;
import com.creed.parent.constant.AppCodeEnum;
import com.creed.parent.constant.WalkPathStrategy;
import com.creed.parent.dal.model.WalkPathInfo;
import com.creed.parent.exception.ServiceException;
import com.creed.parent.model.DocumentInfo;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 29/5/25
 */
@Slf4j
@Service
public class DictIndexingStorage {
    public static final Pattern ENVIRONMENT_STORAGE_PATTERN = Pattern.compile("^(?<env>PDN|DR|QUA)\\S+");
    public static final String PROJECT_NAME = "projectName";
    public static final String RELEASE_NAME = "releaseName";
    public static final String COUNTRY_CODE = "countryCode";
    public static final String APP_NAME = "appName";
    public static final String INSTANCE = "instance";
    public static final String FILE_EXTENSION = "fileExtension";
    public static final String PATH = "path";
    public static final String FOLDER_NAME = "folderName";
    public static final String CONTENT = "content";
    @Resource
    private PropertiesMatrixConfig propertiesMatrixConfig;
    @Getter
    @Setter
    private String[] fileExtensions = new String[]{"yml", "properties"};
    public static final StandardAnalyzer DEFAULT_ANALYZER = new StandardAnalyzer();
    @Getter
    @Setter
    private StandardAnalyzer analyzer = DEFAULT_ANALYZER;
    // private Directory indexDirectory = new ByteBuffersDirectory();

    public boolean createIndex(Path scanPath, String projectName, String releaseName, String countryCode, String app) {
        String appName = AppCodeEnum.toFileName(app);
        Path partialPath = WalkPathStrategy.resolvePath(projectName, releaseName, countryCode, appName);
        Path finalPath = scanPath.resolve(partialPath);
        try (
                Stream<Path> stream = Files.walk(finalPath);
                Directory indexDirectory = FSDirectory.open(Path.of(propertiesMatrixConfig.getRootPath() + "/.lucene"))
        ) {

            List<Path> files = stream
                    .filter(file -> !Files.isDirectory(file))
                    .filter(file -> StringUtils.equalsAny(FilenameUtils.getExtension(file.getFileName().toString()), fileExtensions))
                    .toList();

            // 创建内存中的索引存储
            // Directory indexDirectory = new ByteBuffersDirectory();

            // 创建标准分词器
            StandardAnalyzer analyzer = new StandardAnalyzer();

            // 配置IndexWriter
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            try (IndexWriter writer = new IndexWriter(indexDirectory, config)) {
                // 添加文档到索引
                for (Path file : files) {
                    log.info("file:{}", file);
                    String fileExtension = FilenameUtils.getExtension(file.getFileName().toString());
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    Document doc = new Document();
                    doc.add(new StringField(PROJECT_NAME, projectName, Field.Store.YES));
                    doc.add(new StringField(RELEASE_NAME, releaseName, Field.Store.YES));
                    doc.add(new StringField(COUNTRY_CODE, countryCode, Field.Store.YES));
                    doc.add(new StringField(APP_NAME, appName, Field.Store.YES));
                    doc.add(new StringField(FILE_EXTENSION, fileExtension, Field.Store.YES));
                    doc.add(new TextField(PATH, file.toString(), Field.Store.YES));
                    doc.add(new TextField(CONTENT, content, Field.Store.YES));
                    writer.addDocument(doc);
                }
                writer.commit();
            }
            return true;
        } catch (Exception e) {
            throw new ServiceException(500, ExceptionUtils.getMessage(e));
        }
    }

    public List<DocumentInfo> searchIndex(String projectName, String releaseName, String countryCode, String app, String env, String searchStr) {
        String appName = AppCodeEnum.toFileName(app);
        List<DocumentInfo> list = new ArrayList<>();
        try (
            Directory index = FSDirectory.open(Path.of(propertiesMatrixConfig.getRootPath() + "/.lucene"));
            // Directory index = new ByteBuffersDirectory();
            DirectoryReader reader = DirectoryReader.open(index)
        ) {

            IndexSearcher searcher = new IndexSearcher(reader);
            StoredFields storedFields = reader.storedFields();

            // QueryParser projectNameParser = new QueryParser(PROJECT_NAME, analyzer); //精确匹配，不需要分词器
            // Query projectNameQuery = projectNameParser.parse(projectName);
            TermQuery projectNameQuery = new TermQuery(new Term(PROJECT_NAME, projectName));

            // QueryParser releaseNameParser = new QueryParser(RELEASE_NAME, analyzer);
            // Query releaseNameQuery = releaseNameParser.parse(releaseName);
            TermQuery releaseNameQuery = new TermQuery(new Term(RELEASE_NAME, releaseName));

            // QueryParser countryCodeParser = new QueryParser(COUNTRY_CODE, analyzer);
            // Query countryCodeQuery = countryCodeParser.parse(countryCode);
            TermQuery countryCodeQuery = new TermQuery(new Term(COUNTRY_CODE, countryCode));

            // QueryParser appParser = new QueryParser(APP_NAME, analyzer);
            // Query appQuery = appParser.parse(appName);
            TermQuery appQuery = new TermQuery(new Term(APP_NAME, appName));

            QueryParser envParser = new QueryParser(PATH, analyzer);
            Query envQuery = envParser.parse(env);

            QueryParser contentParser = new QueryParser(CONTENT, analyzer);
            Query contentPQuery = contentParser.parse(searchStr);

            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            builder.add(projectNameQuery, BooleanClause.Occur.MUST);
            builder.add(releaseNameQuery, BooleanClause.Occur.MUST);
            builder.add(countryCodeQuery, BooleanClause.Occur.MUST);
            builder.add(appQuery, BooleanClause.Occur.MUST);
            builder.add(envQuery, BooleanClause.Occur.MUST);
            builder.add(contentPQuery, BooleanClause.Occur.MUST);

            TopDocs topDocs = searcher.search(builder.build(), IndexSearcher.getMaxClauseCount());
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document document = storedFields.document(scoreDoc.doc);
                list.add(DocumentInfo.builder()
                        .content(document.get(CONTENT))
                        .path(document.get(PATH))
                        .extension(document.get(FILE_EXTENSION)).build());
            }
            return list;
        } catch (Exception e) {
            throw new ServiceException(500, ExceptionUtils.getMessage(e));
        }
    }

    /**
     * clean index
     * @return
     */
    public void resetIndex() {
        try (Directory indexDirectory = FSDirectory.open(Path.of(propertiesMatrixConfig.getRootPath() + "/.lucene"))) {
            // 配置IndexWriter
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            try (IndexWriter writer = new IndexWriter(indexDirectory, config)) {
                writer.deleteAll();
            }
        } catch (IOException e) {
            throw new ServiceException(500, ExceptionUtils.getMessage(e));
        }
    }

    public List<DocumentInfo> searchZone4PathInMemory(Path scanPath, WalkPathInfo info, String searchStr) {
        Supplier<Query> pathSupplier = () -> {
            try {
                String appName = AppCodeEnum.toFileName(info.getApp());
                TermQuery projectNameQuery = new TermQuery(new Term(PROJECT_NAME, info.getProjectName()));
                TermQuery releaseNameQuery = new TermQuery(new Term(RELEASE_NAME, info.getReleaseName()));
                TermQuery countryCodeQuery = new TermQuery(new Term(COUNTRY_CODE, info.getCountryCode()));
                TermQuery appQuery = new TermQuery(new Term(APP_NAME, appName));
                Matcher matcher = ENVIRONMENT_STORAGE_PATTERN.matcher(info.getInstance());
                String environmentVariable = StringUtils.EMPTY;
                if (matcher.matches()) {
                    environmentVariable = matcher.group("env");
                }
                log.info("searchZone4Path environmentVariable:{}", environmentVariable);

                String pathQuerySearcher;
                if (StringUtils.isNotBlank(environmentVariable)) {
                    if (StringUtils.equals("QUA", environmentVariable)) {
                        pathQuerySearcher = MessageFormat.format("(({0} AND {1}) OR {2}) AND ({3})", environmentVariable, info.getEnvironment(), info.getInstance(), searchStr);
                    } else {
                        pathQuerySearcher = MessageFormat.format("({0} OR {1}) AND ({2})", info.getEnvironment(), info.getInstance(), searchStr);
                    }
                } else {
                    pathQuerySearcher = info.getInstance();
                }
                log.info("pathQuerySearcher:{} searchStr:{}", pathQuerySearcher, searchStr);

                QueryParser pathParser = new QueryParser(PATH, analyzer);
                Query pathQuery = pathParser.parse(pathQuerySearcher);

                QueryParser contentParser = new QueryParser(CONTENT, analyzer);
                Query contentPQuery = contentParser.parse(searchStr);

                BooleanQuery.Builder builder = new BooleanQuery.Builder();
                builder.add(projectNameQuery, BooleanClause.Occur.MUST);
                builder.add(releaseNameQuery, BooleanClause.Occur.MUST);
                builder.add(countryCodeQuery, BooleanClause.Occur.MUST);
                builder.add(appQuery, BooleanClause.Occur.MUST);
                builder.add(pathQuery, BooleanClause.Occur.MUST);
                builder.add(contentPQuery, BooleanClause.Occur.MUST);
                return builder.build();
            } catch (Exception e) {
                throw new ServiceException(500, ExceptionUtils.getMessage(e));
            }
        };
        return searchInMemory(scanPath, info, Collections.singletonList(pathSupplier));
    }

    public List<DocumentInfo> searchZoneInMemory(Path scanPath, WalkPathInfo info, String searchStr) {
        Supplier<Query> supplier = querySupplier4Zone(searchStr, info);
        return searchInMemory(scanPath, info, Collections.singletonList(supplier));
    }
    public List<DocumentInfo> searchInMemory(Path scanPath, WalkPathInfo info, Collection<Supplier<Query>> queryFunc) {
        List<List<DocumentInfo>> results = searchInMemoryMultiple(scanPath, info, queryFunc);
        return results.stream().flatMap(Collection::stream).toList();
    }

    public List<List<DocumentInfo>> searchInMemoryMultiple(Path scanPath, WalkPathInfo info, Collection<Supplier<Query>> queryFunc) {
        String appName = AppCodeEnum.toFileName(info.getApp());
        Path partialPath = WalkPathStrategy.resolvePath(info.getProjectName(), info.getReleaseName(), info.getCountryCode(), appName);
        Path finalPath = scanPath.resolve(partialPath);
        List<List<DocumentInfo>> result = new ArrayList<>();
        try (
                Stream<Path> stream = Files.walk(finalPath);
                Directory index = new ByteBuffersDirectory();
        ) {

            List<Path> files = stream
                    .filter(file -> !Files.isDirectory(file))
                    .filter(file -> Strings.CS.equalsAny(FilenameUtils.getExtension(file.getFileName().toString()), fileExtensions))
                    .toList();
            // PathHierarchyTokenizer
            // config IndexWriter
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            try (IndexWriter writer = new IndexWriter(index, config)) {
                // add document to index
                log.info("total file size:{}", files.size());
                for (Path file : files) {
                    String fileExtension = FilenameUtils.getExtension(file.getFileName().toString());
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    Document doc = new Document();
                    doc.add(new StringField(PROJECT_NAME, info.getProjectName(), Field.Store.NO));
                    doc.add(new StringField(RELEASE_NAME, info.getReleaseName(), Field.Store.NO));
                    doc.add(new StringField(COUNTRY_CODE, info.getCountryCode(), Field.Store.NO));
                    if (StringUtils.isNotBlank(appName)) {
                        doc.add(new StringField(APP_NAME, appName, Field.Store.NO));
                    }
                    if (StringUtils.isNotBlank(info.getInstance())) {
                        doc.add(new StringField(INSTANCE, info.getInstance(), Field.Store.NO));
                    }
                    doc.add(new StringField(FILE_EXTENSION, fileExtension, Field.Store.YES));
                    doc.add(new StringField(FOLDER_NAME, Optional.ofNullable(file.getParent()).map(Path::getFileName).map(Path::toString).orElse(StringUtils.EMPTY), Field.Store.YES));
                    doc.add(new TextField(PATH, file.toString(), Field.Store.YES));
                    doc.add(new TextField(CONTENT, content, Field.Store.YES));
                    writer.addDocument(doc);
                }
                writer.commit();
            }
            try (
                    DirectoryReader reader = DirectoryReader.open(index)
            ) {
                IndexSearcher searcher = new IndexSearcher(reader);
                StoredFields storedFields = reader.storedFields();

                for (Supplier<Query> querySupplier : queryFunc) {
                    Query query = querySupplier.get();
                    TopDocs topDocs = searcher.search(query, IndexSearcher.getMaxClauseCount());
                    List<DocumentInfo> list = new ArrayList<>();
                    for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                        Document document = storedFields.document(scoreDoc.doc);
                        list.add(DocumentInfo.builder()
                                .content(document.get(CONTENT))
                                .path(document.get(PATH))
                                .folderName(document.get(FOLDER_NAME))
                                .extension(document.get(FILE_EXTENSION)).build());
                    }
                    result.add(list);
                }
                return result;
            }
        } catch (Exception e) {
            throw new ServiceException(500, ExceptionUtils.getMessage(e));
        }
    }

    public Supplier<Query> querySupplier4Zone(String searchStr, WalkPathInfo info) {
        return () -> {
            try{
                String appName = AppCodeEnum.toFileName(info.getApp());
                TermQuery projectNameQuery = new TermQuery(new Term(PROJECT_NAME, info.getProjectName()));
                TermQuery releaseNameQuery = new TermQuery(new Term(RELEASE_NAME, info.getReleaseName()));
                TermQuery countryCodeQuery = new TermQuery(new Term(COUNTRY_CODE, info.getCountryCode()));
                TermQuery appQuery = new TermQuery(new Term(APP_NAME, appName));
                Matcher matcher = ENVIRONMENT_STORAGE_PATTERN.matcher(info.getInstance());
                String environmentVariable = StringUtils.EMPTY;
                if (matcher.matches()) {
                    environmentVariable = matcher.group("env");
                }
                log.info("environmentVariable:{}", environmentVariable);

                String pathQuerySearcher;
                if (StringUtils.isNotBlank(environmentVariable)) {
                    if (StringUtils.equals("QUA", environmentVariable)) {
                        pathQuerySearcher = MessageFormat.format("(({0} AND {1}) OR {2})", environmentVariable, info.getEnvironment(), info.getInstance());
                    } else {
                        pathQuerySearcher = MessageFormat.format("({0} OR {1})", info.getEnvironment(), info.getInstance());
                    }
                } else {
                    pathQuerySearcher = info.getInstance();
                }
                log.info("pathQuerySearcher:{} searchStr:{}", pathQuerySearcher, searchStr);
                QueryParser pathParser = new QueryParser(PATH, analyzer);
                Query pathQuery = pathParser.parse(pathQuerySearcher);

                QueryParser contentParser = new QueryParser(CONTENT, analyzer);
                Query contentPQuery = contentParser.parse(searchStr);

                BooleanQuery.Builder builder = new BooleanQuery.Builder();
                builder.add(projectNameQuery, BooleanClause.Occur.MUST);
                builder.add(releaseNameQuery, BooleanClause.Occur.MUST);
                builder.add(countryCodeQuery, BooleanClause.Occur.MUST);
                builder.add(appQuery, BooleanClause.Occur.MUST);
                builder.add(pathQuery, BooleanClause.Occur.MUST);
                builder.add(contentPQuery, BooleanClause.Occur.MUST);
                return builder.build();
            } catch (Exception e) {
                throw new ServiceException(500, ExceptionUtils.getMessage(e));
            }
        };
    }

    public Supplier<Query> querySupplier4GroupZone(String searchStr, WalkPathInfo info) {
        return () -> {
            try{
                String appName = AppCodeEnum.toFileName(info.getApp());
                TermQuery projectNameQuery = new TermQuery(new Term(PROJECT_NAME, info.getProjectName()));
                TermQuery releaseNameQuery = new TermQuery(new Term(RELEASE_NAME, info.getReleaseName()));
                TermQuery countryCodeQuery = new TermQuery(new Term(COUNTRY_CODE, info.getCountryCode()));
                TermQuery appQuery = new TermQuery(new Term(APP_NAME, appName));
                String environmentVariable = info.getEnvironment();

                log.info("pathQuerySearcher:{}", environmentVariable);
                QueryParser pathParser = new QueryParser(PATH, analyzer);
                Query pathQuery = pathParser.parse(environmentVariable);

                QueryParser contentParser = new QueryParser(CONTENT, analyzer);
                Query contentPQuery = contentParser.parse(searchStr);

                BooleanQuery.Builder builder = new BooleanQuery.Builder();
                builder.add(projectNameQuery, BooleanClause.Occur.MUST);
                builder.add(releaseNameQuery, BooleanClause.Occur.MUST);
                builder.add(countryCodeQuery, BooleanClause.Occur.MUST);
                builder.add(appQuery, BooleanClause.Occur.MUST);
                builder.add(pathQuery, BooleanClause.Occur.MUST);
                builder.add(contentPQuery, BooleanClause.Occur.MUST);
                return builder.build();
            } catch (Exception e) {
                throw new ServiceException(500, ExceptionUtils.getMessage(e));
            }
        };
    }

    public List<Supplier<Query>> querySupplier4App(WalkPathInfo info, String... searchArr) {
        return Stream.of(searchArr)
                .map(searchStr ->
                        querySupplier4App(info, searchStr)
                )
                .toList();
    }
    private Supplier<Query> querySupplier4App(WalkPathInfo info, String searchStr) {
        return () -> {
            try {
                TermQuery projectNameQuery = new TermQuery(new Term(PROJECT_NAME, info.getProjectName()));
                TermQuery releaseNameQuery = new TermQuery(new Term(RELEASE_NAME, info.getReleaseName()));
                TermQuery countryCodeQuery = new TermQuery(new Term(COUNTRY_CODE, info.getCountryCode()));

                QueryParser pathParser = new QueryParser(PATH, analyzer);
                Query pathQuery = pathParser.parse(searchStr);

                BooleanQuery.Builder builder = new BooleanQuery.Builder();
                builder.add(projectNameQuery, BooleanClause.Occur.MUST);
                builder.add(releaseNameQuery, BooleanClause.Occur.MUST);
                builder.add(countryCodeQuery, BooleanClause.Occur.MUST);
                builder.add(pathQuery, BooleanClause.Occur.MUST);
                return builder.build();
            } catch (Exception e) {
                throw new ServiceException(500, ExceptionUtils.getMessage(e));
            }
        };
    }

    public Supplier<Query> querySupplier4Path(WalkPathInfo info, String searchStr) {
        return () -> {
            try {
                TermQuery projectNameQuery = new TermQuery(new Term(PROJECT_NAME, info.getProjectName()));
                TermQuery releaseNameQuery = new TermQuery(new Term(RELEASE_NAME, info.getReleaseName()));
                TermQuery countryCodeQuery = new TermQuery(new Term(COUNTRY_CODE, info.getCountryCode()));

                Query pathQuery = new PrefixQuery(new Term(FOLDER_NAME, searchStr));

                BooleanQuery.Builder builder = new BooleanQuery.Builder();
                builder.add(projectNameQuery, BooleanClause.Occur.MUST);
                builder.add(releaseNameQuery, BooleanClause.Occur.MUST);
                builder.add(countryCodeQuery, BooleanClause.Occur.MUST);
                builder.add(pathQuery, BooleanClause.Occur.MUST);
                return builder.build();
            } catch (Exception e) {
                throw new ServiceException(500, ExceptionUtils.getMessage(e));
            }
        };
    }

    public Supplier<Query> querySupplier4AppHome(String searchStr, WalkPathInfo info) {
        return () -> {
            try{
                String appName = AppCodeEnum.toFileName(info.getApp());
                TermQuery projectNameQuery = new TermQuery(new Term(PROJECT_NAME, info.getProjectName()));
                TermQuery releaseNameQuery = new TermQuery(new Term(RELEASE_NAME, info.getReleaseName()));
                TermQuery countryCodeQuery = new TermQuery(new Term(COUNTRY_CODE, info.getCountryCode()));
                TermQuery appQuery = new TermQuery(new Term(APP_NAME, appName));
                String environmentVariable = info.getEnvironment();
                log.info("environment query:{}", environmentVariable);
                QueryParser pathParser = new QueryParser(PATH, analyzer);
                Query pathQuery = pathParser.parse(environmentVariable);

                QueryParser contentParser = new QueryParser(CONTENT, analyzer);
                Query contentPQuery = contentParser.parse(searchStr);

                BooleanQuery.Builder builder = new BooleanQuery.Builder();
                builder.add(projectNameQuery, BooleanClause.Occur.MUST);
                builder.add(releaseNameQuery, BooleanClause.Occur.MUST);
                builder.add(countryCodeQuery, BooleanClause.Occur.MUST);
                builder.add(appQuery, BooleanClause.Occur.MUST);
                builder.add(pathQuery, BooleanClause.Occur.MUST);
                builder.add(contentPQuery, BooleanClause.Occur.MUST);
                return builder.build();
            } catch (Exception e) {
                throw new ServiceException(500, ExceptionUtils.getMessage(e));
            }
        };
    }

    public Supplier<Query> querySupplier4Common(String searchStr, WalkPathInfo info) {
        return () -> {
            try{
                String appName = AppCodeEnum.toFileName(info.getApp());
                TermQuery projectNameQuery = new TermQuery(new Term(PROJECT_NAME, info.getProjectName()));
                TermQuery releaseNameQuery = new TermQuery(new Term(RELEASE_NAME, info.getReleaseName()));
                TermQuery countryCodeQuery = new TermQuery(new Term(COUNTRY_CODE, info.getCountryCode()));
                TermQuery appQuery = new TermQuery(new Term(APP_NAME, appName));
                String environmentVariable = info.getEnvironment();
                log.info("environment query:{}", environmentVariable);
                QueryParser pathParser = new QueryParser(PATH, analyzer);
                Query pathQuery = pathParser.parse(environmentVariable);

                BooleanQuery.Builder builder = new BooleanQuery.Builder();
                builder.add(projectNameQuery, BooleanClause.Occur.MUST);
                builder.add(releaseNameQuery, BooleanClause.Occur.MUST);
                builder.add(countryCodeQuery, BooleanClause.Occur.MUST);
                builder.add(appQuery, BooleanClause.Occur.MUST);
                builder.add(pathQuery, BooleanClause.Occur.MUST);

                if (StringUtils.isNotBlank(searchStr)) {
                    QueryParser contentParser = new QueryParser(CONTENT, analyzer);
                    Query contentPQuery = contentParser.parse(searchStr);
                    builder.add(contentPQuery, BooleanClause.Occur.MUST);
                }
                return builder.build();
            } catch (Exception e) {
                throw new ServiceException(500, ExceptionUtils.getMessage(e));
            }
        };
    }
}
