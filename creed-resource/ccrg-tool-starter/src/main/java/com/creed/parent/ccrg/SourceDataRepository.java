package com.creed.parent.ccrg;

import freemarker.template.Configuration;
import freemarker.template.TemplateException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Repository
@Slf4j
public class SourceDataRepository {

    @Autowired
    private Configuration freemarkerConfiguration;

    @Autowired
    private JdbcTemplate jdbc;

    public Collection<SourceData> getAllSourceData(String sqlTemplate, Map<String, Object> dataMap) throws TemplateException, IOException {
        String sql = generateSql(sqlTemplate, dataMap);
        log.info("getAllSourceData SQL: {}", sql);
        List<SourceData> data = jdbc.query(sql, new SourceDataRowMapper());
        log.info("Found {} SourceData", data.size());
        return data;
    }

    public int putSourceData(SourceData ... sourceData) throws TemplateException, IOException {
        List<String> sqls = new ArrayList<>();
        for (SourceData data : sourceData) {
            log.info("SourceData:{}", data);
            String sql = generateSql("sql/insert-source-data.ftl", Collections.singletonMap("data", data));
            log.debug("putSourceData SQL: {}", sql);
            sqls.add(sql);
        }

        int rows = Arrays.stream(jdbc.batchUpdate(sqls.toArray(new String[] {}))).reduce(0, Integer::sum);
        log.info("Rows Updated: {}", rows);
        return rows;
    }

    protected String generateSql(String templatePath, Map<String, Object> dataModel) throws TemplateException, IOException {
        final Map<String, Object> finalDataModel = Objects.isNull(dataModel) ? Collections.emptyMap() : dataModel;
        try {
            finalDataModel.forEach((key, value) -> log.info("Found DataModel key: {}, value: {}", key, value));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Writer writer = new OutputStreamWriter(out);
            freemarkerConfiguration.getTemplate(templatePath).process(finalDataModel, writer);
            out.flush();
            String convertedAsString = StringUtils.replace(out.toString(StandardCharsets.UTF_8), StringUtils.LF, " ");
            log.info("Rendered SQL: {}", convertedAsString);
            return convertedAsString;
        } catch (Exception e) {
            log.error("Template rendering failed", e);
            throw e;
        }
    }

    static class SourceDataRowMapper implements RowMapper<SourceData> {

        @Override
        public SourceData mapRow(ResultSet rs, int rowNum) throws SQLException {
            return SourceData.builder()
                .key(rs.getString("KEY"))
                .value(rs.getString("VALUE"))
                .source(rs.getInt("SOURCE"))
                .origin(rs.getString("ORIGIN"))
                .active(rs.getBoolean("IS_ACTIVE"))
                .build();
        }

    }

}
