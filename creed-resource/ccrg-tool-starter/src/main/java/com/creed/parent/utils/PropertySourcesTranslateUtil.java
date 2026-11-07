package com.creed.parent.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.javaprop.JavaPropsMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.util.StringQuotingChecker;
import com.creed.parent.model.DocumentInfo;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 11/6/25
 */
@UtilityClass
@Slf4j
public class PropertySourcesTranslateUtil {
    private static final Pattern PATTERN = Pattern.compile("^\"(?<content>.*)\"$");
    private static final ObjectMapper YAML_MAPPER;
    public static final YamlPropertySourceLoader YAML_LOADER = new YamlPropertySourceLoader();
    public static final PropertiesPropertySourceLoader PROPERTIES_LOADER = new PropertiesPropertySourceLoader();

    static {
        YAMLFactory yamlFactory = YAMLFactory.builder()
                .stringQuotingChecker(new StringQuotingChecker() {
                    @Override
                    public boolean needToQuoteName(String name) {
                        return isReservedKeyword(name) || looksLikeYAMLNumber(name)
                                // 31-Jan-2022, tatu: as per [dataformats-text#306] may also
                                //   have other characters requiring quoting...
                                || nameHasQuotableChar(name);
                    }

                    @Override
                    public boolean needToQuoteValue(String value) {
                        // Only consider reserved keywords but not numbers?
                        // {@link com.fasterxml.jackson.dataformat.yaml.util.StringQuotingChecker#valueHasQuotableChar}
                        // return isReservedKeyword(value) || valueHasQuotableChar(value);
                        return false;
                    }
                })
                .build()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)  // removes marker characters ---
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)

                .disable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)  // avoid numbers being quote
                .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR);  // list array elements by prefixing hifen
        YAML_MAPPER = new ObjectMapper(yamlFactory);
    }
    @SneakyThrows
    public String map2YamlConverter(Map<String, String> propertiesMap) {
        if (MapUtils.isEmpty(propertiesMap)) {
            return StringUtils.EMPTY;
        }
        JavaPropsMapper propsMapper = new JavaPropsMapper();
        JsonNode jsonNode = propsMapper.readMapAs(propertiesMap, JsonNode.class);
        List<JsonNode> emptyNode = jsonNode.findParents("");
        if (CollectionUtils.isNotEmpty(emptyNode)) {
            for (JsonNode node : emptyNode) {
                Set<Map.Entry<String, JsonNode>> properties = node.properties();
                Iterator<Map.Entry<String, JsonNode>> iterator = properties.iterator();
                JsonNode value = null;
                while (iterator.hasNext()) {
                    Map.Entry<String, JsonNode> entry = iterator.next();
                    if (StringUtils.isEmpty(entry.getKey()) && node.isObject()) {
                        value = entry.getValue();
                        iterator.remove();
                    }
                }
                if (Objects.nonNull(value)) {
                    ObjectNode objectNode = (ObjectNode) node;
                    objectNode.set("INVALID-KEY", value);
                }
            }
        }
        return YAML_MAPPER.writeValueAsString(jsonNode);
    }


    public Set<String> searchPropertyKey(List<DocumentInfo> documentInfos, String propertyKey) {
        Set<String> propertyValues = new HashSet<>();
        BiConsumer<String, DocumentInfo> consumer = (propValue, di) -> propertyValues.add(propValue);
        loadPropertyByKey(documentInfos, propertyKey, consumer);
        return propertyValues;
    }

    public List<Pair<String, DocumentInfo>> searchPropertyKeyWithMeta(List<DocumentInfo> documentInfos, String propertyKey) {
        return searchPropertyKeyWithMeta(documentInfos, propertyKey, str -> true);
    }
    public List<Pair<String, DocumentInfo>> searchPropertyKeyWithMeta(List<DocumentInfo> documentInfos, String propertyKey, Predicate<String> predicate) {
        List<Pair<String, DocumentInfo>> propertyValues = new ArrayList<>();
        BiConsumer<String, DocumentInfo> consumer = (propValue, di) -> {
            if (predicate.test(propValue))
                propertyValues.add(Pair.of(propValue, di));
        } ;
        loadPropertyByKey(documentInfos, propertyKey, consumer);
        return propertyValues;
    }

    void loadPropertyByKey(List<DocumentInfo> documentInfos, String propertyKey, BiConsumer<String, DocumentInfo> consumer) {
        for (DocumentInfo documentInfo : documentInfos) {
            String extension = documentInfo.getExtension();
            Consumer<Map<String, Object>> sourcePropertiesConsumer = sourceProperties ->
                consumer.accept(sourceProperties.getOrDefault(propertyKey, "").toString(), documentInfo);

            loadPropertySource(extension, new ByteArrayResource(documentInfo.getContent().getBytes(StandardCharsets.UTF_8)), sourcePropertiesConsumer, documentInfo::getPath);

        }
    }

    void loadPropertySource(String extension, Resource resource, Consumer<Map<String, Object>> consumer, Supplier<String> path) {
        try {
            List<PropertySource<?>> load = new ArrayList<>();
            if (StringUtils.containsAny(extension, YAML_LOADER.getFileExtensions())) {
                load = YAML_LOADER.load("application-yml", resource);
            }else if (StringUtils.containsAny(extension, PROPERTIES_LOADER.getFileExtensions())) {
                load = PROPERTIES_LOADER.load("application-properties", resource);
            }
            if (CollectionUtils.isEmpty(load)) {
                log.warn("LOADER is empty form:{}", path.get());
            }
            for (var propertySource : load) {
                if (propertySource instanceof MapPropertySource src) {
                    Map<String, Object> sourceProperties = src.getSource();
                    consumer.accept(sourceProperties);
                }
            }
        } catch (Exception e) {
            // continue log next file
            log.error("LOADER exception:{} form:{}", ExceptionUtils.getMessage(e), path.get());
        }
    }
}
