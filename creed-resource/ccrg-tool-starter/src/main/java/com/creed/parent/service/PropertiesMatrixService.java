package com.creed.parent.service;

import com.creed.parent.config.PropertiesMatrixConfig;
import com.creed.parent.constant.AppCodeEnum;
import com.creed.parent.constant.TemplateResolver;
import com.creed.parent.definition.RouteDefinition;
import com.creed.parent.service.walkpath.AppWalkPathStrategy;
import com.creed.parent.utils.StrUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RegExUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;


/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 8/28/24
 */
@Service
@Slf4j
public class PropertiesMatrixService {
    public static final String APP_CODE_NOT_EXISTING = "appCode does not exist: ";
    @Resource
    private PropertiesMatrixConfig propertiesMatrixConfig;
    @Resource(name = "txtSpringTemplateEngine")
    private SpringTemplateEngine springTemplateEngine;

    public String getRootPath() {
        return propertiesMatrixConfig.getRootPath();
    }


    public String getSpringConfigLocation(String subFolder, RouteDefinition routeDefinition) {
        String appCode = routeDefinition.getApp();
        Assert.notNull(appCode, APP_CODE_NOT_EXISTING + appCode);
        if (Strings.CI.equals(routeDefinition.getCountryCode(), "GST")) {
            appCode = "gst_" + appCode;
        }
        return templateResolve(subFolder, routeDefinition, TemplateResolver.resolveConfigLocation().apply(routeDefinition.getProjectName(), appCode));
    }
    public String getSpringConfigAdditionalLocation(String subFolder, RouteDefinition routeDefinition) {
        String appCode = routeDefinition.getApp();
        Assert.notNull(appCode, APP_CODE_NOT_EXISTING + appCode);
        if (Strings.CI.equals(routeDefinition.getCountryCode(), "GST")) {
            appCode = "gst_" + appCode;
        }
        return templateResolve(subFolder, routeDefinition, TemplateResolver.resolveConfigAdditionalLocation().apply(routeDefinition.getProjectName(), appCode));
    }

    protected String templateResolve(String subFolder, RouteDefinition routeDefinition, String fragmentTemplate) {
        Context context = new Context();
        String rootPath=  propertiesMatrixConfig.getRootPath();
        if (StringUtils.isNotBlank(subFolder)) {
            rootPath = rootPath + StrUtils.SEPARATOR + subFolder;
        }
        RouteDefinition routeDefinitionContext = routeDefinition;
        // resolve latest app suffix different with folder path issue
        if (!AppWalkPathStrategy.LEGACY_REPOS.contains(routeDefinition.getProjectName())) {
            String resolvedFileName = AppCodeEnum.toFileName(routeDefinition.getApp());
            log.info("resolve app value issue:{}", resolvedFileName);
            RouteDefinition routeDefinitionSnapshot = new RouteDefinition();
            BeanUtils.copyProperties(routeDefinition, routeDefinitionSnapshot);
            routeDefinitionSnapshot.setApp(resolvedFileName);
            routeDefinitionContext = routeDefinitionSnapshot;
        }

        log.info("rootPath:{} fragmentTemplate:{}", rootPath, fragmentTemplate);
        context.setVariable("routeDefinition", routeDefinitionContext);
        context.setVariable("fragmentTemplate", fragmentTemplate);
        context.setVariable("rootPath", rootPath);
        String result = springTemplateEngine.process("index", context);
        log.info("templateResolve :{}", result);
        return StringUtils.trim(result);
    }


    public String getSpringProfilesInclude(String subFolder, RouteDefinition routeDefinition) {
        String appCode = routeDefinition.getApp();
        Assert.notNull(appCode, APP_CODE_NOT_EXISTING + appCode);
        if (Strings.CI.equals(routeDefinition.getCountryCode(), "GST")) {
            appCode = "gst_" + appCode;
        }
        String result = templateResolve(subFolder, routeDefinition, TemplateResolver.resolveProfilesInclude().apply(routeDefinition.getProjectName(), appCode));
        result = RegExUtils.replaceAll(result, StringUtils.LF, "");
        log.info("getSpringProfilesInclude :{}", result);
        return result;
    }
}
