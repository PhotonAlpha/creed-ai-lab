package com.creed.parent.controller.dict;

import com.creed.parent.config.PropertiesMatrixConfig;
import com.creed.parent.constant.CommonStatusEnum;
import com.creed.parent.controller.dict.vo.DictListReqVO;
import com.creed.parent.controller.dict.vo.DictRespVO;
import com.creed.parent.controller.dict.vo.DictSaveReqVO;
import com.creed.parent.controller.dict.vo.DictSimpleRespVO;
import com.creed.parent.controller.dict.vo.ExtraTypeRespVO;
import com.creed.parent.controller.vo.SelectedOptionVo;
import com.creed.parent.convert.DictConvert;
import com.creed.parent.dal.entity.SystemDicts;
import com.creed.parent.dal.entity.SystemExtraTypes;
import com.creed.parent.service.DictService;
import com.creed.parent.service.TemplateService;
import com.creed.parent.utils.R;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.creed.parent.utils.R.success;

@RestController
@RequestMapping("/api/system/dict")
@Validated
@Slf4j
@Deprecated
public class DictRestController {

    @Resource
    private DictService dictService;
    @Resource
    private TemplateService templateService;
    @Resource
    private PropertiesMatrixConfig propertiesMatrixConfig;

    @GetMapping("/list-all-types")
    public R<List<ExtraTypeRespVO>> getSimpleTypes(@RequestParam("param") String param) {
        List<SystemExtraTypes> list = dictService.getExtraTypes(param);
        return success(DictConvert.INSTANCE.convertSystemExtraTypes(list));
    }

    @PostMapping("copy")
    public R<Long> copyById(@RequestParam("id") Long id) {
        Long deptId = dictService.copyById(id);
        return success(deptId);
    }
    @PostMapping("create")
    public R<Long> createDept(@Valid @RequestBody DictSaveReqVO createReqVO) {
        Long deptId = dictService.createDict(createReqVO);
        return success(deptId);
    }

    @PutMapping("update")
    public R<Boolean> updateDept(@Valid @RequestBody DictSaveReqVO updateReqVO) {
        dictService.updateDict(updateReqVO);
        return success(true);
    }

    @DeleteMapping("delete")
    public R<Boolean> deleteDept(@RequestParam("id") Long id) {
        dictService.deleteDict(id);
        return success(true);
    }

    @GetMapping("/list")
    public R<List<DictRespVO>> getDeptList(DictListReqVO reqVO) {
        List<SystemDicts> list = dictService.getDicts(reqVO);
        return success(DictConvert.INSTANCE.convert2WebRouteInfo(list));
    }

    @GetMapping(value = {"/list-all-simple", "/simple-list"})
    public R<List<DictSimpleRespVO>> getSimpleDeptList() {
        List<SystemDicts> list = dictService.getDicts(
                new DictListReqVO().setStatus(CommonStatusEnum.ENABLE));
        return success(DictConvert.INSTANCE.convert0(list));
    }

    @GetMapping("/get")
    public R<DictRespVO> getDept(@RequestParam("id") Long id) {
        SystemDicts dept = dictService.getDict(id);
        return success(DictConvert.INSTANCE.convert2WebRouteInfo(dept));
    }

    @GetMapping("/download-sql")
    public HttpEntity<byte[]> downloadSql() throws IOException {
        try (ByteArrayOutputStream out = templateService.generateDataSqlTemplate()) {

            HttpHeaders header = new HttpHeaders();
            header.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            header.set(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=data.sql");
            // header.setContentLength(documentBody.length);
            return new HttpEntity<byte[]>(out.toByteArray(), header);
        }
    }

    @PostMapping("/import-json-config")
    public R<Boolean> importConfig(@RequestParam("file") MultipartFile file) throws IOException {
        log.info("importConfig");
        return success(dictService.importConfig(file));
    }

    @GetMapping("/list-select")
    public R<List<SelectedOptionVo>> listSelect(@RequestParam("param") String param, @RequestParam(value = "type", required = false) String type) throws IOException {
        List<SelectedOptionVo> projectList = new ArrayList<>();
        String rootPath = propertiesMatrixConfig.getRootPath();

        AtomicLong index = new AtomicLong(1);
        if ("release".equals(type)) {
            try {
                rootPath = rootPath + "/" + param;
                Files.list(Paths.get(rootPath))
                        .filter(Files::isDirectory)
                        .map(Path::getFileName)
                        .filter(name -> StringUtils.startsWith(name.toString(), "R"))
                        .forEach(path -> projectList.add(new SelectedOptionVo(index.get(), path.getFileName().toString(), index.getAndIncrement() == 1)));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return R.success(projectList);
        } else if ("project".equals(type)) {
            try {
                Files.list(Paths.get(rootPath))
                        .filter(Files::isDirectory)
                        .map(Path::getFileName)
                        .forEach(path -> projectList.add(new SelectedOptionVo(index.get(), path.getFileName().toString(), index.getAndIncrement() == 1)));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return R.success(projectList);
        } else {
            return R.success(dictService.getSelector(param, type));
        }
    }

    @GetMapping("/list-modules")
    public R<List<SelectedOptionVo>> listModules(@RequestParam("project") String project, @RequestParam(value = "countryCode") String countryCode) throws IOException {
        return R.success(dictService.getModules(project, countryCode));
    }

    @GetMapping("/getdict")
    public List<SystemDicts> saveCommit() {
        List<SystemDicts> systemDicts = dictService.getDicts(new DictListReqVO());
        return systemDicts;
    }
}
