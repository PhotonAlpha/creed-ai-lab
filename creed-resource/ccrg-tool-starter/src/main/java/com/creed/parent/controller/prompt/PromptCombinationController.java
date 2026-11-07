package com.creed.parent.controller.prompt;

import com.creed.parent.client.vo.confluence.content.ConfluenceContentVO;
import com.creed.parent.client.vo.confluence.content.PageResult;
import com.creed.parent.constant.ReportTypeEnum;
import com.creed.parent.context.TenantContextHolder;
import com.creed.parent.controller.prompt.vo.PageParam;
import com.creed.parent.controller.prompt.vo.PromptBatchForm;
import com.creed.parent.controller.prompt.vo.PromptCombinationRequest;
import com.creed.parent.controller.prompt.vo.PromptCombinationResponseVo;
import com.creed.parent.controller.prompt.vo.PromptMergeRequest;
import com.creed.parent.controller.prompt.vo.PromptMergeResponse;
import com.creed.parent.service.PromptCombinationGenerativeV2Service;
import com.creed.parent.utils.R;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 7/16/25
 */
@RestController
@RequestMapping("/api/prompt")
public class PromptCombinationController {
    @Resource
    PromptCombinationGenerativeV2Service promptCombinationGenerativeV2Service;

    @PostMapping("/list-instances")
    public R<PromptCombinationResponseVo> listInstances(@RequestBody PromptCombinationRequest request) {
        return R.success(promptCombinationGenerativeV2Service.listInstanceMatrix(request));
    }


    @PostMapping("/generative")
    public R<?> generative(@RequestBody PromptBatchForm request, @RequestParam("type") String type) {
        ReportTypeEnum typeEnum = ReportTypeEnum.findByType(type);
        TenantContextHolder.setUploadConfluence(true);
        return R.success(promptCombinationGenerativeV2Service.generateReportsInConfluence(request, typeEnum));
    }

    @GetMapping("/list-reports")
    public R<PageResult<ConfluenceContentVO>> listReports(PageParam pageParam) {
        return R.success(promptCombinationGenerativeV2Service.listReports(pageParam));
    }

    @PostMapping("/merge-reports")
    public R<PromptMergeResponse> mergeReports(@RequestBody PromptMergeRequest request) {
        return R.success(promptCombinationGenerativeV2Service.mergeReports(request));
    }
    @PostMapping("/delete-reports")
    public R<PromptMergeResponse> deleteReports(@RequestBody PromptMergeRequest request) {
        return R.success(promptCombinationGenerativeV2Service.deleteReports(request));
    }

}
