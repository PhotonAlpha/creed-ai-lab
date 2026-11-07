
package com.creed.parent.service;

import com.github.difflib.text.DiffRow;
import com.creed.child.ChildApplication;
import com.creed.parent.CCRGApplication;
import com.creed.parent.ccrg.SourceData;
import com.creed.parent.controller.vo.EnvironmentConfigurationVo;
import com.creed.parent.controller.vo.PropertiesVo;
import com.creed.parent.controller.vo.RouteInfoVo;
import com.creed.parent.convert.PropertySourceConvert;
import com.creed.parent.definition.RouteDefinition;
import com.creed.parent.utils.json.JacksonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.assertj.core.util.VisibleForTesting;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.actuate.autoconfigure.env.EnvironmentEndpointProperties;
import org.springframework.boot.actuate.endpoint.SanitizingFunction;
import org.springframework.boot.actuate.env.EnvironmentEndpoint;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.creed.parent.service.PropertiesMatrixService.APP_CODE_NOT_EXISTING;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 8/27/24
 */
@Service
@Slf4j
@Deprecated(forRemoval = true)
public class PropertiesService {
    private static final List<String> skipList = Arrays.asList("systemProperties", "systemEnvironment");
    public static final String[] LOCAL_RESOURCE = new String[]{"application-settings.yml",
            "application-matrix.yml",
            "application-local.yml"};
    @Resource
    private EnvironmentEndpointProperties environmentEndpointProperties;
    @Resource
    private ObjectProvider<SanitizingFunction> sanitizingFunctions;

    @Resource
    private ApplicationArguments applicationArguments;
    @Resource
    private PropertiesMatrixService propertiesMatrixService;

    protected Map<Pair<String, String>, String> loadAllActiveProperties(ConfigurableEnvironment configurableEnvironment) {
        log.info("loadingAllActiveProperties...{}", String.join(",", configurableEnvironment.getActiveProfiles()));

        MutablePropertySources propertySources = configurableEnvironment.getPropertySources();
        EnvironmentEndpoint ev = new EnvironmentEndpoint(configurableEnvironment, sanitizingFunctions, environmentEndpointProperties.getShowValues());
        Iterator<PropertySource<?>> iterator = propertySources.iterator();
        Map<Pair<String, String>, String> propMap = new TreeMap<>(Comparator.naturalOrder());

        while (iterator.hasNext()) {
            PropertySource<?> propertySource = iterator.next();
            if (propertySource instanceof EnumerablePropertySource<?> enumerablePropertySource) {
                String[] propertyNames = enumerablePropertySource.getPropertyNames();
                for (String propertyName : propertyNames) {
                    log.debug("propertyName:{}", propertyName);
                    EnvironmentEndpoint.EnvironmentEntryDescriptor descriptor = ev.environmentEntry(propertyName);
                    EnvironmentEndpoint.PropertySummaryDescriptor activeProperty = descriptor.getProperty();
                    log.debug(" activeProperty:{}", activeProperty);
                    propMap.put(Pair.of(propertyName, String.valueOf(activeProperty.getValue())), activeProperty.getSource());
                }
            }
        }
        log.info("loadingAllActiveProperties...size {}", propMap.size());
        return propMap;
    }

    protected EnvironmentConfigurationVo loadAllProperties(ConfigurableEnvironment configurableEnvironment, int sourceIndex) {
        log.info("loadAllProperties...{}", String.join(",", configurableEnvironment.getActiveProfiles()));

        Map<Pair<String, String>, String> pairStringMap = loadAllActiveProperties(configurableEnvironment);

        EnvironmentEndpoint ev = new EnvironmentEndpoint(configurableEnvironment, sanitizingFunctions, environmentEndpointProperties.getShowValues());
        EnvironmentEndpoint.EnvironmentDescriptor environment = ev.environment(null);

        List<EnvironmentEndpoint.PropertySourceDescriptor> propertySources = environment.getPropertySources();

        List<PropertiesVo> springPropertiesList = propertySources.stream()
                .filter(resource -> StringUtils.containsAnyIgnoreCase(resource.getName(), skipList.toArray(new String[0])))
                .map(resource -> {
                    String resourceName = resource.getName();
                    return resource.getProperties().entrySet().stream()
                            .filter(entry -> StringUtils.startsWith(entry.getKey(), "spring."))
                            .map(entry -> PropertySourceConvert.INSTANCE.convert(entry.getValue(), entry.getKey(), resourceName, sourceIndex)).toList();
                }).flatMap(Collection::stream).toList();

        List<PropertiesVo> propertiesVoList = propertySources.stream()
                .filter(resource -> !StringUtils.containsAnyIgnoreCase(resource.getName(), skipList.toArray(new String[0])))
                .map(resource -> {
                    String resourceName = resource.getName();
                    return resource.getProperties().entrySet().stream()
                            .map(entry -> PropertySourceConvert.INSTANCE.convert(entry.getValue(), entry.getKey(), resourceName, sourceIndex)).toList();
                }).flatMap(Collection::stream).toList();
        LinkedList<PropertiesVo> propertiesList = new LinkedList<>(springPropertiesList);
        propertiesList.addAll(propertiesVoList);
        // mark active properties
        propertiesList.forEach(pop -> {
            if (pairStringMap.containsKey(Pair.of(pop.getPropertyName(), String.valueOf(pop.getPropertyValue())))) {
                pop.setActive(StringUtils.equals(pairStringMap.get(Pair.of(pop.getPropertyName(), String.valueOf(pop.getPropertyValue()))), pop.getSourceName()));
                // pop.setActive(Boolean.TRUE);
            }
            log.debug("propertiesVo:{}", pop);
        });

        log.info("AllProperties size...{}", propertiesVoList.size());
        return new EnvironmentConfigurationVo(String.join(",", configurableEnvironment.getActiveProfiles()), propertiesVoList);
    }

    public synchronized EnvironmentConfigurationVo getAllProperties(String subFolder, RouteDefinition routeDefinition, int sourceIndex) {
        Assert.notNull(routeDefinition, "routeDefinition can not be null");
        log.info("getAllProperties:{}", JacksonUtils.toJsonString(routeDefinition));
        Map<String, Object> initialProperties = new HashMap<>();
        String appCode = routeDefinition.getApp();
        Assert.notNull(appCode, APP_CODE_NOT_EXISTING + appCode);

        initialProperties.put("spring.profiles.active", routeDefinition.getProfiles());
           System.setProperty("spring.profiles.active", routeDefinition.getProfiles());
        log.info("spring.profiles.active={}", routeDefinition.getProfiles());
        String springProfilesInclude = propertiesMatrixService.getSpringProfilesInclude(subFolder, routeDefinition);
        initialProperties.put("spring.profiles.include", springProfilesInclude);
           System.setProperty("spring.profiles.include", springProfilesInclude);
        log.info("spring.profiles.include={}", springProfilesInclude);
        String springConfigLocation = propertiesMatrixService.getSpringConfigLocation(subFolder, routeDefinition);
        initialProperties.put("spring.config.location", springConfigLocation);
           System.setProperty("spring.config.location", springConfigLocation);
        log.info("spring.config.location={}", springConfigLocation);
        String springConfigAdditionalLocation = propertiesMatrixService.getSpringConfigAdditionalLocation(subFolder, routeDefinition);
        initialProperties.put("spring.config.import", springConfigAdditionalLocation);
           System.setProperty("spring.config.import", springConfigAdditionalLocation);
        log.info("spring.config.import={}", springConfigAdditionalLocation);
        initialProperties.put("country_code", routeDefinition.getCountryCode());
        initialProperties.put("zone", routeDefinition.getZone());
        initialProperties.put("logging.instance", routeDefinition.getInstance());
        System.setProperty("logging.instance", routeDefinition.getInstance());

        ConfigurableEnvironment configurableEnvironment;
        try (ConfigurableApplicationContext context = CCRGApplication.parentBuilder.child(ChildApplication.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .properties(initialProperties).run(new String[0])) {
            List<String> parentPropertySources = context.getEnvironment().getPropertySources().stream().map(PropertySource::getName)
                    .filter(name -> StringUtils.containsAny(name, LOCAL_RESOURCE)).toList();
            for (String sourceName : parentPropertySources) {
                context.getEnvironment().getPropertySources().remove(sourceName);
            }

            configurableEnvironment = context.getBean(ConfigurableEnvironment.class);
            // remove parent profiles
            // can not use in concurrency environment
            ConfigurableEnvironment parentEnvironment = CCRGApplication.context.getBean(ConfigurableEnvironment.class);
            Set<String> parentActiveProfiles = Stream.of(parentEnvironment.getActiveProfiles()).collect(Collectors.toSet());
            String[] childActiveProfile = Arrays.stream(configurableEnvironment.getActiveProfiles()).filter(p -> !parentActiveProfiles.contains(p)).toArray(String[]::new);
            context.getEnvironment().setActiveProfiles(childActiveProfile);
            // load all properties
            MutablePropertySources propertySources = configurableEnvironment.getPropertySources();
            PropertySource<?> defaultProperties = propertySources.get("systemInitLogging");
            if (Objects.nonNull(defaultProperties)) {
                propertySources.remove("systemInitLogging");
                propertySources.addLast(defaultProperties);
            }

            EnvironmentConfigurationVo environmentConfiguration = loadAllProperties(configurableEnvironment, sourceIndex);
            log.debug("environmentConfiguration:{}", JacksonUtils.toJsonString(environmentConfiguration));
            environmentConfiguration.setSource(sourceIndex);
            environmentConfiguration.setRouteDefinition(routeDefinition);
            return environmentConfiguration;
        }
    }

    @Value("${properties-matrix.root-path}")
    private String rootPath;

    /**
     * description getAllProperties not support concurrency
     * @author EthanCao
     * @since 2026-06-19T13
     */
    public List<EnvironmentConfigurationVo> getAllProperties(List<RouteInfoVo> routeInfos) {
        //validate args
        if (CollectionUtils.isEmpty(routeInfos) || CollectionUtils.size(routeInfos) < 2) {
            log.error("at least 2 args required:{}", routeInfos);
            return Collections.emptyList();
        }
        log.debug("translateRouteInfoVo:{}", JacksonUtils.toJsonString(routeInfos));
        AtomicInteger index = new AtomicInteger(1);
        Function<RouteInfoVo, String> subFolderFunc = info -> {
            if (StringUtils.isNotBlank(info.getReleaseName())) {
                return info.getProjectName() + "/" + info.getReleaseName();
            }
            return info.getProjectName();
        };

        return routeInfos.stream()
                .map(info -> {
                    try {
                        return getAllProperties(subFolderFunc.apply(info), PropertySourceConvert.INSTANCE.convert(info), index.getAndIncrement());
                    } catch (Exception e) {
                        log.error("getAllProperties Exception", e);
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .toList();

    }

    public void enrichWithMetadata(String ref, String projectName, Collection<List<SourceData>> sourceData) {

        Path basePath = Path.of(rootPath, projectName, ref);
        Set<String> sourceLocations = Optional.ofNullable(sourceData).orElse(Collections.emptySet())
            .stream()
            .flatMap(Collection::stream)
            .map(SourceData::getOrigin)
            .map(origin -> StringUtils.substringBetween(origin, "[file:", "]"))
            .filter(
                path -> StringUtils.isNotEmpty(path) &&
                    !StringUtils.containsAnyIgnoreCase(path, "application-settings.yml")
            )
            .collect(Collectors.toSet());

        for(String sourceLocation : sourceLocations) {
            try {
                Path target = Path.of(sourceLocation);
                String targetHash = DigestUtils.sha256Hex(new FileInputStream(target.toFile()));
                Path refPath = basePath.resolve(target.subpath(4, target.getNameCount()));
                String refHash = DigestUtils.sha256Hex(new FileInputStream(refPath.toFile()));

                if (StringUtils.equals(refHash, targetHash)) {
                    log.info("File {} and {} are same", refPath.toAbsolutePath(), target.toAbsolutePath());
                    continue;
                }
                String extension = FilenameUtils.getExtension(sourceLocation);
                PropertySourceLoader loader;
                YamlPropertySourceLoader yamlSourceLoader = new YamlPropertySourceLoader();
                PropertiesPropertySourceLoader propertySourceLoader = new PropertiesPropertySourceLoader();
                if (StringUtils.containsAny(extension, yamlSourceLoader.getFileExtensions())) {
                    loader = yamlSourceLoader;
                } else if (StringUtils.containsAny(extension, propertySourceLoader.getFileExtensions())) {
                    loader = propertySourceLoader;
                } else {
                    log.info("File Extension is not supported:{}", extension);
                    continue;
                }

                Map<String, Object> refProperties = loadPropertiesFile(loader, sourceLocation, refPath);
                Map<String, Object> targetProperties = loadPropertiesFile(loader, sourceLocation, target);
                Collection<String> intersectionPropKeys = CollectionUtils.intersection(refProperties.keySet(), targetProperties.keySet());
                Set<String> disjunctionRefkeys = new HashSet<>(CollectionUtils.disjunction(refProperties.keySet(), intersectionPropKeys)); // existing in ref only, means it is removed
                Set<String> disjunctionTarKeys = new HashSet<>(CollectionUtils.disjunction(targetProperties.keySet(), intersectionPropKeys));// existing in tar only, means it is added
                Set<String> modifiedKeys = intersectionPropKeys.stream().filter(key -> !Objects.equals(refProperties.get(key), targetProperties.get(key))).collect(Collectors.toSet());
                Set<String> unchangedKeys = intersectionPropKeys.stream().filter(key -> Objects.equals(refProperties.get(key), targetProperties.get(key))).collect(Collectors.toSet());
                sourceData.stream().flatMap(Collection::stream)
                        .filter(sd -> StringUtils.contains(sd.getOrigin(),  FilenameUtils.getName(sourceLocation)))
                        .forEach(sd -> {
                    if (disjunctionRefkeys.contains(sd.getKey())) {
                        sd.setMetadata(new SourceData.Metadata(DiffRow.Tag.DELETE.toString(), null));
                    } else if (disjunctionTarKeys.contains(sd.getKey())) {
                        sd.setMetadata(new SourceData.Metadata(DiffRow.Tag.INSERT.toString(), null));
                    } else if (modifiedKeys.contains(sd.getKey())) {
                        sd.setMetadata(new SourceData.Metadata(DiffRow.Tag.CHANGE.toString(), null));
                    } else if (unchangedKeys.contains(sd.getKey())) {
                        sd.setMetadata(new SourceData.Metadata(DiffRow.Tag.EQUAL.toString(), null));
                    }
                });
            } catch (IOException e) {
                log.error("Failed to process path: {}. Error: {}. Skipping ...", sourceLocation, e.getMessage());
            }
        }
    }

    private Map<String, Object> loadPropertiesFile(PropertySourceLoader loader, String name, Path path) throws IOException {
        List<PropertySource<?>> propertySources = loader.load(name, new FileSystemResource(path));
        var propertiesMapping = new HashMap<String, Object>();
        for (PropertySource<?> propertySource : propertySources) {
            if (propertySource instanceof EnumerablePropertySource<?> enumerablePropertySource) {
                String[] propertyNames = enumerablePropertySource.getPropertyNames();
                for (String propertyName : propertyNames) {
                    log.debug("propertyName:{}", propertyName);
                    log.debug(" propertyValue:{}", propertySource.getProperty(propertyName));
                    propertiesMapping.put(propertyName, propertySource.getProperty(propertyName));
                }
            }
        }
        return propertiesMapping;
    }

    @VisibleForTesting
    public EnvironmentConfigurationVo getAllProperties4Test(String... params) {
        Map<String, Object> initialProperties = new HashMap<>();
        // spring.profiles.active=uat,spring.profiles.include=${spring.profiles.active},${spring.profiles.active}-${country_code:SG},${zone:TDC},currency-pair-${country_code:SG},whitelist-customers-perf-${country_code:SG},api-loadbalancer-${spring.profiles.active}-${country_code:SG}

        initialProperties.put("spring.profiles.active", "uat");
        initialProperties.put("spring.profiles.include", "${spring.profiles.active},${spring.profiles.active}-${country_code:SG},${zone:ADC},currency-pair-${country_code:SG},whitelist-customers-perf-${country_code:SG},api-loadbalancer-${spring.profiles.active}-${country_code:SG}");
        if (params[0].equals("1")) {
            initialProperties.put("spring.config.location", "classpath:/,file:/Users/venojk/Desktop/workspace/sourcecode/idbconfigs/global/LX/config/,file:/Users/venojk/Desktop/workspace/sourcecode/idbconfigs/SG/LX/QUA/NFT1/application/config/,file:/Users/venojk/Desktop/workspace/sourcecode/idbconfigs/SG/LX/QUA001/NFT1/application/config/,file:/Users/venojk/Desktop/workspace/sourcecode/idbconfigs/SG/LX/QUA001/NFT1/application/config/group-a/");
        } else {
            initialProperties.put("spring.config.location", "classpath:/,file:/Users/venojk/Desktop/workspace/sourcecode/idbconfigs/global/LX/config/,file:/Users/venojk/Desktop/workspace/sourcecode/idbconfigs/SG/LX/QUA/NFT1/application/config/,file:/Users/venojk/Desktop/workspace/sourcecode/idbconfigs/SG/LX/QUA002/NFT1/application/config/,file:/Users/venojk/Desktop/workspace/sourcecode/idbconfigs/SG/LX/QUA002/NFT1/application/config/group-a/");
        }
        initialProperties.put("spring.config.additional-location", "file:/Users/venojk/Desktop/workspace/sourcecode/idbconfigs/SG/LX/QUA001/NFT1/config/ssl/gebngssl_ms.properties,file:/Users/venojk/Desktop/workspace/sourcecode/idbconfigs/SG/LX/QUA001/NFT1/config/jwt/gebng_jwt.properties");
        initialProperties.put("country_code", "SG");
        initialProperties.put("zone", "ADC");
        initialProperties.put("logging.instance", "GEBNGCUSG11");
        // initialProperties.put("logging.config", "");
        // System.setProperty("logging.config", "");

        ConfigurableApplicationContext context = CCRGApplication.parentBuilder.child(ChildApplication.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .properties(initialProperties).run(new String[0]);
        ConfigurableEnvironment configurableEnvironment = context.getBean(ConfigurableEnvironment.class);
        EnvironmentConfigurationVo environmentConfiguration = loadAllProperties(configurableEnvironment, Integer.parseInt(params[0]));
        // log.info("environmentConfiguration:{}", environmentConfiguration);
        return environmentConfiguration;
    }

}
