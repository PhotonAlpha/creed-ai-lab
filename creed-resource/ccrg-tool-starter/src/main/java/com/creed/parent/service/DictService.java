package com.creed.parent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.creed.parent.constant.CommonStatusEnum;
import com.creed.parent.controller.dict.vo.DictConfigReqVO;
import com.creed.parent.controller.dict.vo.DictConfigVO;
import com.creed.parent.controller.dict.vo.DictListReqVO;
import com.creed.parent.controller.dict.vo.DictSaveReqVO;
import com.creed.parent.controller.vo.SelectedOptionVo;
import com.creed.parent.convert.DictConvert;
import com.creed.parent.convert.PropertySourceConvert;
import com.creed.parent.dal.entity.SystemDicts;
import com.creed.parent.dal.entity.SystemExtraTypes;
import com.creed.parent.dal.repository.SystemDictsRepository;
import com.creed.parent.dal.repository.SystemExtraTypesRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.assertj.core.util.VisibleForTesting;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.creed.parent.constant.GlobalErrorCodeConstants.*;
import static com.creed.parent.utils.ServiceExceptionUtil.exception;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 9/5/24
 */
@Service
@Slf4j
public class DictService {
    private static final int RECURSION_THRESHOLD = Short.MAX_VALUE;
    @Resource
    private SystemDictsRepository systemDictsRepository;
    @Resource
    private SystemExtraTypesRepository systemExtraTypesRepository;
    public static final ObjectMapper mapper = new ObjectMapper();

    public Long createDict(DictSaveReqVO createReqVO) {
        if (createReqVO.getParentId() == null) {
            createReqVO.setParentId(SystemDicts.PARENT_ID_ROOT);
        }
        // validate parent id
        validateParentDict(null, createReqVO.getParentId());
        // validate name
        validateDictNameUnique(null, createReqVO.getParentId(), createReqVO.getName());


        // insert
        SystemDicts dict = DictConvert.INSTANCE.convert2WebRouteInfo(createReqVO);
        updateExtraTypes(createReqVO, dict);
        systemDictsRepository.save(dict);
        return dict.getId();
    }

    protected void updateExtraTypes(DictSaveReqVO vo, SystemDicts dict) {
        Long nodeId = vo.getNodeId();
        if (Objects.isNull(dict)) {
            return;
        }
        if (Objects.nonNull(nodeId) && nodeId > 0) {
            if (Optional.of(dict)
                 .map(SystemDicts::getNode).map(SystemExtraTypes::getId)
                  .filter(nodeId::equals).isEmpty()) {
                systemExtraTypesRepository.findById(nodeId).ifPresent(dict::setNode);
            }

        }
        Long zoneId = vo.getZoneId();
        if (Objects.nonNull(zoneId) && zoneId > 0) {
            if (Optional.of(dict)
                    .map(SystemDicts::getZone).map(SystemExtraTypes::getId)
                    .filter(zoneId::equals).isEmpty()) {
                systemExtraTypesRepository.findById(zoneId).ifPresent(dict::setZone);
            }
        }
    }

    @VisibleForTesting
    void validateParentDict(Long id, Long parentId) {
        if (parentId == null || SystemDicts.PARENT_ID_ROOT.equals(parentId)) {
            return;
        }
        // 1. can not set self as parent
        if (Objects.equals(id, parentId)) {
            throw exception(DICT_PARENT_ERROR);
        }
        // 2. check parent
        Optional<SystemDicts> parentDict = systemDictsRepository.findById(parentId);
        if (parentDict.isEmpty()) {
            throw exception(DICT_PARENT_NOT_EXITS);
        }
        // 3. Recursive verification of parent departments,
        // If the parent department is its own child department, an error will be reported to avoid a loop
        if (id == null) {
            return;
        }
        validateParentDictRecursion(parentDict.map(SystemDicts::getParentId).orElse(null), RECURSION_THRESHOLD);
    }
    void validateParentDictRecursion(Long id, int recursionThreshold) {
        // If the ID is the root directory, stop recursion
        if (id == null || SystemDicts.PARENT_ID_ROOT.equals(id) || recursionThreshold < 1) {
            return;
        }
        Optional<SystemDicts> parentDict = systemDictsRepository.findById(id);
        Long pId = parentDict.map(SystemDicts::getParentId).orElse(null);
        if (Objects.equals(id, pId)) {
            throw exception(DICT_PARENT_IS_CHILD);
        }
        // Continue recursively to the next level parent department
        validateParentDictRecursion(pId, recursionThreshold - 1);
    }


    @VisibleForTesting
    void validateDictNameUnique(Long id, Long parentId, String name) {
        Optional<SystemDicts> dept = systemDictsRepository.findByParentIdAndName(parentId, name);
        if (dept.isEmpty()) {
            return;
        }
        if (id == null) {
            throw exception(DICT_NAME_DUPLICATE);
        }
        if (dept.map(SystemDicts::getId).filter(id::equals).isEmpty()) {
            throw exception(DICT_NAME_DUPLICATE);
        }
    }

    public void updateDict(DictSaveReqVO updateReqVO) {
        if (updateReqVO.getParentId() == null) {
            updateReqVO.setParentId(SystemDicts.PARENT_ID_ROOT);
        }
        // check self is existing
        SystemDicts dicts = validateDictExists(updateReqVO.getId());

        validateParentDict(updateReqVO.getId(), updateReqVO.getParentId());

        validateDictNameUnique(updateReqVO.getId(), updateReqVO.getParentId(), updateReqVO.getName());

        DictConvert.INSTANCE.update(updateReqVO, dicts);
        updateExtraTypes(updateReqVO, dicts);
        systemDictsRepository.save(dicts);
    }

    @VisibleForTesting
    SystemDicts validateDictExists(Long id) {
        if (id == null) {
            return null;
        }
        return systemDictsRepository.findById(id).orElseThrow(() -> exception(DICT_NOT_FOUND));
    }

    public void deleteDict(Long id) {

        SystemDicts dicts = validateDictExists(id);
        // Check if there is a sub-department
        if (systemDictsRepository.existsByParentId(id)) {
            throw exception(DICT_EXITS_CHILDREN);
        }
        // delete
        systemDictsRepository.delete(dicts);
    }

    public List<SystemDicts> getDicts(DictListReqVO reqVO) {
        List<SystemDicts> list = systemDictsRepository.findByCondition(reqVO);
        list.sort(Comparator.comparing(SystemDicts::getId).thenComparing(SystemDicts::getSort));
        return list;
    }

    public List<SelectedOptionVo> findByCondition(DictListReqVO req) {
        log.info("findByCondition:{}", req);
        if (StringUtils.isNotBlank(req.getParentName())) {
            Optional<SystemDicts> parentDictsOptional = systemDictsRepository.findByName(req.getParentName());
            List<SystemDicts> list = parentDictsOptional.map(SystemDicts::getId).map(systemDictsRepository::findByParentId).orElse(Collections.emptyList());
            list.sort(Comparator.comparing(SystemDicts::getSort));
            return PropertySourceConvert.INSTANCE.convert0(list);
        } else if (StringUtils.isNumeric(req.getParentId())) {
            Optional<SystemDicts> parentDictsOptional = systemDictsRepository.findById(Long.parseLong(req.getParentId()));
            List<SystemDicts> list = parentDictsOptional.map(SystemDicts::getId).map(systemDictsRepository::findByParentId).orElse(Collections.emptyList());
            list.sort(Comparator.comparing(SystemDicts::getSort));
            return PropertySourceConvert.INSTANCE.convert0(list);
        } else if (StringUtils.isNumeric(req.getName())) {
            Optional<SystemDicts> parentDictsOptional = systemDictsRepository.findByName(req.getName());
            List<SystemDicts> list = parentDictsOptional.stream().toList();
            return PropertySourceConvert.INSTANCE.convert0(list);
        }
        return Collections.emptyList();
    }

    public SystemDicts getDict(Long id) {
        return systemDictsRepository.findById(id).orElse(null);
    }

    public List<SystemExtraTypes> getExtraTypes(String param) {
        return systemExtraTypesRepository.findByType(param);
    }
    public List<SystemExtraTypes> getExtraTypes() {
        return systemExtraTypesRepository.findAll();
    }

    @Transactional(rollbackFor = Exception.class)
    public Boolean importConfig(MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            DictConfigReqVO req = mapper.readValue(in, DictConfigReqVO.class);
            Integer triggeredLevel = req.getTriggeredLevel();
            List<DictConfigVO> configs = req.getTreeData();
            uploadConfig(configs, triggeredLevel);
        }
        return true;
    }
    private Long uploadConfig(List<DictConfigVO> configs, Integer triggeredLevel) {
        return removeAndCreateDictConfig(configs, 0L, triggeredLevel, 0, RECURSION_THRESHOLD);
    }

    private Long createDictConfig(List<DictConfigVO> dictConfigs, Long parentId, int recursionThreshold) {
        if (recursionThreshold < 1 || CollectionUtils.isEmpty(dictConfigs)) {
            return -1L;
        }
        for (DictConfigVO dictConfig : dictConfigs) {
            dictConfig.setParentId(parentId);
            DictSaveReqVO dictSaveReq = DictConvert.INSTANCE.convertVo(dictConfig);
            dictSaveReq.setId(null);
            log.info("::going to insert name:{} parentId:{}", dictConfig.getName(), dictConfig.getParentId());
            Long id = createDict(dictSaveReq);
            List<DictConfigVO> children = dictConfig.getChildren();
            createDictConfig(children, id, recursionThreshold - 1);
        }
        return 0L;
    }

    private Long removeAndCreateDictConfig(List<DictConfigVO> dictConfigs, Long parentId, Integer triggeredLevel, Integer currentLevel, int recursionThreshold) {
        if (recursionThreshold < 1 || CollectionUtils.isEmpty(dictConfigs)) {
            return -1L;
        }
        if (currentLevel > triggeredLevel) {
            log.warn("currentLevel:{} over then triggeredLevel:{}", currentLevel, triggeredLevel);
            return -1L;
        }
        if (triggeredLevel.equals(currentLevel)) {
            // Recursively delete the specified level

            List<SystemDicts> childDicts = systemDictsRepository.findByParentId(parentId);
            // remove all child elements
            if (CollectionUtils.isNotEmpty(childDicts)) {
                removeDictConfig(childDicts, RECURSION_THRESHOLD);
            }
            // create all child elements
            createDictConfig(dictConfigs, parentId, RECURSION_THRESHOLD);
            return 0L;
        } else {
            // if not the dictConfigs, parent DictConfigVO always find the first element
            DictConfigVO dictConfigVO = dictConfigs.stream().findFirst().orElse(null);
            if (Objects.isNull(dictConfigVO)) {
                log.warn("currentLevel:{} over then triggeredLevel:{}, no record found", currentLevel, triggeredLevel);
                return -1L;
            }
            Optional<SystemDicts> childDict = systemDictsRepository.findByParentIdAndName(parentId, dictConfigVO.getName());
            Long pId = childDict.map(SystemDicts::getId).orElse(-1L);
            if (childDict.isEmpty()) {
                SystemDicts dicts = new SystemDicts();
                dicts.setParentId(parentId);
                dicts.setName(dictConfigVO.getName());
                dicts.setSort(0);
                dicts.setStatus(CommonStatusEnum.ENABLE);
                systemDictsRepository.save(dicts);
                pId = dicts.getId();
            }
            if (pId < 0) {
                log.warn("currentLevel:{} over then triggeredLevel:{}, no record found", currentLevel, triggeredLevel);
                return pId;
            }
            return removeAndCreateDictConfig(dictConfigVO.getChildren(), pId, triggeredLevel, currentLevel + 1, recursionThreshold - 1);
        }

    }


    private Long removeDictConfig(List<SystemDicts> childDicts, int recursionThreshold) {
        if (recursionThreshold < 1 || CollectionUtils.isEmpty(childDicts)) {
            return -1L;
        }
        for (SystemDicts childDict : childDicts) {
            // has child-child element
            List<SystemDicts> childElements = systemDictsRepository.findByParentId(childDict.getId());
            removeDictConfig(childElements, recursionThreshold - 1);
            // delete self after child cleaned
            systemDictsRepository.delete(childDict);
        }
        return 0L;
    }

    public List<SelectedOptionVo> getSelector(String param, String type) {

        if (StringUtils.equalsIgnoreCase(type, "country")) {
            //country is the first level, verify and get the parentId
            Optional<SystemDicts> systemDictsOptional = systemDictsRepository.findByParentIdAndName(0L, param);
            List<SystemDicts> countryList = systemDictsOptional.map(SystemDicts::getId).map(systemDictsRepository::findByParentId).orElse(Collections.emptyList());
            return PropertySourceConvert.INSTANCE.convert0(countryList);
        } else if (StringUtils.equalsIgnoreCase(type, "instance")) {
            Optional<SystemDicts> systemDictsOptional = systemDictsRepository.findById(Long.parseLong(param));
            SelectedOptionVo selectedOptionVo = systemDictsOptional.map(SystemDicts::getZone)
                    .map(zo -> new SelectedOptionVo(zo.getId(), zo.getVal()))
                    .orElse(null);
            return Collections.singletonList(selectedOptionVo);
        } else {
                List<SystemDicts> resultList = systemDictsRepository.findByParentId(Long.parseLong(param));
                return PropertySourceConvert.INSTANCE.convert0(resultList);
            }
        }

    public Long copyById(Long id) {
        SystemDicts dict = getDict(id);
        SystemDicts copiedDict = PropertySourceConvert.INSTANCE.copy(dict);
        copiedDict.setName(dict.getName() + "-" + RandomStringUtils.randomAlphabetic(5));
        copiedDict.setId(null);
        systemDictsRepository.save(copiedDict);
        //copy child level elements
        List<SystemDicts> childElements = systemDictsRepository.findByParentId(id);
        if (CollectionUtils.isNotEmpty(childElements)) {
            List<SystemDicts> copiedChildElements = PropertySourceConvert.INSTANCE.copy(childElements);
            copiedChildElements.forEach(elem -> {
                elem.setName(elem.getName() + "-" + RandomStringUtils.randomAlphabetic(5));
                elem.setParentId(copiedDict.getId());
                elem.setId(null);
            });
            systemDictsRepository.saveAll(copiedChildElements);
        }
        return copiedDict.getId();
    }
    @Deprecated
    public List<SelectedOptionVo> getModules(String project, String countryCode) {
        if (StringUtils.isBlank(project) || StringUtils.isBlank(countryCode)) {
            throw exception(DICT_NOT_FOUND);
        }
        Optional<SystemDicts> idbmsmodules = systemDictsRepository.findByName("idbmsmodules");
        if (idbmsmodules.isEmpty()) {
            throw exception(DICT_NOT_FOUND);
        }
        Long pId = idbmsmodules.get().getId();
        Optional<SystemDicts> projectDict = systemDictsRepository.findByParentIdAndName(pId, project);
        if (projectDict.isEmpty()) {
            throw exception(DICT_NOT_FOUND);
        }
        Long projectPId = projectDict.get().getId();
        Optional<SystemDicts> countryDict = systemDictsRepository.findByParentIdAndName(projectPId, countryCode);
        if (countryDict.isEmpty()) {
            throw exception(DICT_NOT_FOUND);
        }
        Long countryPId = countryDict.get().getId();
        List<SystemDicts> moduleList = systemDictsRepository.findByParentId(countryPId);
        return PropertySourceConvert.INSTANCE.convert0(moduleList);
    }
}
