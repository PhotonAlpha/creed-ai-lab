package com.creed.parent.controller.admin;

import com.creed.parent.controller.admin.vo.InstalledInfoVo;
import com.creed.parent.controller.admin.vo.InstalledRequestVo;
import com.creed.parent.controller.admin.vo.UninstallRequestVo;
import com.creed.parent.controller.vo.SelectedOptionVo;
import com.creed.parent.dal.model.WalkPathInfo;
import com.creed.parent.service.AdministrationService;
import com.creed.parent.utils.R;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RegExUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 7/16/25
 */
@RestController
@RequestMapping("/api/admin")
@Slf4j
public class AdministrationController {
    @Resource
    AdministrationService administrationService;

    @GetMapping("/install/list-all")
    public R<List<InstalledInfoVo>> getInstallFiles() {
        return administrationService.listInstallFiles();
    }

    @PostMapping("/install")
    public R<String> installFiles(@RequestBody InstalledRequestVo installRequest) {
        return administrationService.install(installRequest);
    }

    @PostMapping("/uninstall")
    public R<List<WalkPathInfo>> uninstallFiles(@RequestBody UninstallRequestVo uninstallRequest) {
        return administrationService.uninstallFiles(uninstallRequest);
    }

    @PostMapping("/artifactory/list-releases")
    public R<List<SelectedOptionVo>> listReleases(@RequestBody WalkPathInfo walkPathInfo) {
        return administrationService.listReleases(walkPathInfo);
    }
    @PostMapping("/artifactory/list-builds")
    public R<List<SelectedOptionVo>> listBuilds(@RequestBody WalkPathInfo walkPathInfo) {
        return administrationService.listBuilds(walkPathInfo);
    }
    /* @PostMapping("/artifactory/list-master-countries")
    public R<List<SelectedOptionVo>> listMasterCountries(@RequestBody InstalledRequestVo installedRequestVo) {
        return administrationService.listMasterCountries(installedRequestVo);
    } */


    @GetMapping("/artifactory/shortcut/{*segments}")
    public R<List<SelectedOptionVo>> getShortcutInfo(@PathVariable String segments) {
        log.info("getShortcutInfo:{}", segments);
        if (StringUtils.startsWith(segments, "/")) {
            segments = RegExUtils.replaceFirst(segments, "/", "");
        }
        String[] parts = StringUtils.split(segments, "/");
        String repo   = parts.length >= 1 ? parts[0] : null;
        String branch = parts.length >= 2 ? parts[1] : null;
        String release= parts.length >= 3 ? parts[2] : null;
        log.info("getShortcutInfo receiving repo:{} branch:{} release:{}", repo, branch, release);
        return administrationService.getShortcutInfo(repo, branch, release);
    }


}
