package com.creed.parent.service;

import com.creed.parent.config.MetadataConfigConfig;
import com.creed.parent.controller.dict.vo.DictListReqVO;
import com.creed.parent.dal.entity.SystemDicts;
import com.creed.parent.dal.entity.SystemExtraTypes;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.util.List;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 8/30/24
 */
@Service
@Slf4j
public class TemplateService {
    @Resource(name = "txtSpringTemplateEngine")
    private SpringTemplateEngine springTemplateEngine;
    @Resource
    private PropertiesMatrixService propertiesMatrixService;
    @Resource
    private MetadataConfigConfig metadataConfigConfig;
    @Resource
    private DictService deptService;

    public ByteArrayOutputStream generateDataSqlTemplate() {

        List< SystemDicts > systemDicts = deptService.getDicts(new DictListReqVO());
        List<SystemExtraTypes> extraTypes = deptService.getExtraTypes();
        log.info("generateDataSqlTemplate :{}", systemDicts.size());
        Context context = new Context();
        context.setVariable("systemDicts", systemDicts);
        context.setVariable("extraTypes", extraTypes);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        springTemplateEngine.process("system_dict_template", context, new OutputStreamWriter(output));

        // Path rootPath = Paths.get(propertiesMatrixService.getRootPath()).getParent();
        // Files.write(rootPath.resolve("data.sql"), result.getBytes());
        return output;
    }

    public void createFromTemplate() {
        
    }


}
