package com.creed.parent.controller.dict;

import com.creed.parent.config.PropertiesMatrixConfig;
import com.creed.parent.controller.dict.vo.*;
import com.creed.parent.controller.vo.SelectedOptionVo;
import com.creed.parent.convert.DictConvert;
import com.creed.parent.dal.entity.SystemDicts;
import com.creed.parent.dal.model.WalkPathInfo;
import com.creed.parent.service.DictLoadService;
import com.creed.parent.service.DictService;
import com.creed.parent.service.TemplateService;
import com.creed.parent.utils.R;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.creed.parent.utils.R.success;

@RestController
@RequestMapping("/api/v2/system/dict")
@Validated
@Slf4j
public class DictPathRestController {

    @Resource
    private DictLoadService dictV2Service;

    @Resource
    private DictService dictService;
    @Resource
    private TemplateService templateService;
    @Resource
    private PropertiesMatrixConfig propertiesMatrixConfig;

    /**
     * find by name, no child will list out
     * @param reqVO
     * @return
     */
    @GetMapping("/list")
    public R<List<DictRespVO>> getDictList(DictListReqVO reqVO) {
        List<SystemDicts> list = dictService.getDicts(reqVO);
        return success(DictConvert.INSTANCE.convert2WebRouteInfo(list));
    }

    @GetMapping("/list-select")
    public R<List<SelectedOptionVo>> listSelect(DictListReqVO req) {
        return R.success(dictService.findByCondition(req));
    }
    @PostMapping("/path-walk")
    public R<List<SelectedOptionVo>> listWalkPath(@RequestBody WalkPathInfo req) {
        return R.success(dictV2Service.pathWalk(req));
    }


}
