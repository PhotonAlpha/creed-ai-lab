package com.creed.parent.controller.upload;

import com.creed.parent.controller.upload.vo.UploadFileVO;
import com.creed.parent.service.FileUploadService;
import com.creed.parent.utils.R;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static com.creed.parent.utils.R.success;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 26/5/25
 */
@RestController
@RequestMapping("/api/system/upload")
@Validated
@Slf4j
public class FileUploadController {
    @Resource
    FileUploadService fileUploadService;

    @PostMapping(value = "install")
    // public R<String> copyById(@RequestPart("releaseId") String id, @RequestParam("file") MultipartFile file) {
    public R<String> copyById(UploadFileVO vo) {
        String msg = fileUploadService.fileInstall(vo);
        return success(msg);
    }
}
