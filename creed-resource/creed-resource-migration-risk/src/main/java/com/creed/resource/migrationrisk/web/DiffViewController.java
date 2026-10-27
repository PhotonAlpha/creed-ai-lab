package com.creed.resource.migrationrisk.web;

import com.creed.resource.migrationrisk.service.DiffViewService;
import com.creed.resource.migrationrisk.service.DiffViewService.DiffSet;
import com.creed.resource.migrationrisk.service.MigrationRiskService.Inputs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The Thymeleaf diff page: RefactoringMiner's side-by-side Monaco view of one file pair, with Prev / Next
 * across the differing files. Under {@code /api/migration-risk/} on purpose — that prefix is what the frontend's
 * Vite proxy (and any reverse proxy in front of the API) already forwards to this service.
 *
 * <p>Inputs are the analysis inputs as query parameters (git: paths + commit ids; folders: paths), so
 * the page works for a throwaway analysis as well as a saved one. {@code file} picks the pair by path.
 */
@Controller
@Slf4j
public class DiffViewController {

    private final DiffViewService diffs;

    public DiffViewController(DiffViewService diffs) {
        this.diffs = diffs;
    }

    @GetMapping("/api/migration-risk/view/diff")
    public String diff(@RequestParam(defaultValue = "GIT") Inputs.Mode mode,
                       @RequestParam String repo1Path,
                       @RequestParam(defaultValue = "HEAD") String repo1Head,
                       @RequestParam String repo2Path,
                       @RequestParam(defaultValue = "HEAD") String repo2Head,
                       @RequestParam(required = false) String repo2bPath,
                       @RequestParam(required = false) String repo2bHead,
                       @RequestParam(defaultValue = "false") boolean skipTests,
                       @RequestParam(required = false) String file,
                       @RequestParam(required = false) Integer index,
                       @RequestParam(required = false) String target,
                       Model model) {
        Inputs in = mode == Inputs.Mode.DIRECTORY
                ? Inputs.folders(repo1Path, repo2Path, repo2bPath, skipTests)
                : Inputs.git(repo1Path, repo1Head, repo2Path, repo2Head, repo2bPath, repo2bHead, skipTests);
        model.addAttribute("mode", mode);
        try {
            DiffSet set = diffs.diff(in);
            if (set.files().isEmpty()) {
                model.addAttribute("error", "repo1 and repo2 have no differing file to show.");
                return "diff";
            }
            int current = index != null && index >= 0 && index < set.files().size() ? index : set.indexOf(file);
            if (current < 0) {
                // Falling back to the first file would show the wrong code under the requested name.
                model.addAttribute("error", file + " has no diff (a binary file, or identical in repo1 and repo2).");
                return "diff";
            }
            model.addAttribute("files", set.files());
            model.addAttribute("current", set.files().get(current));
            model.addAttribute("refactoringCount", set.refactoringCount());
            DiffViewService.FileDiff shownFile = set.files().get(current);
            if (shownFile.text() && target != null && !target.isBlank() && !target.equals(shownFile.right())) {
                // repo2 keeps this file's content elsewhere (a config key in repo2B's config/application.yml)
                DiffViewService.TextView view = diffs.text(in, shownFile.left() != null ? shownFile.left() : file, target, current);
                model.addAttribute("current", view.file());
                model.addAttribute("config", view.config());
            } else {
                model.addAttribute("config", set.config(current));
            }
            UriComponentsBuilder self = UriComponentsBuilder.fromPath("/api/migration-risk/view/diff")
                    .queryParam("mode", mode).queryParam("repo1Path", repo1Path).queryParam("repo2Path", repo2Path);
            if (mode == Inputs.Mode.GIT) {
                self.queryParam("repo1Head", repo1Head).queryParam("repo2Head", repo2Head);
            }
            if (in.repo2bPath() != null) {
                self.queryParam("repo2bPath", in.repo2bPath());
                if (in.repo2bHead() != null) {
                    self.queryParam("repo2bHead", in.repo2bHead());
                }
            }
            if (skipTests) {
                self.queryParam("skipTests", true);
            }
            model.addAttribute("prev", current > 0 ? self.cloneBuilder().queryParam("index", current - 1).encode().toUriString() : null);
            model.addAttribute("next", current < set.files().size() - 1 ? self.cloneBuilder().queryParam("index", current + 1).encode().toUriString() : null);
            model.addAttribute("remainingBefore", current);
            model.addAttribute("remainingAfter", set.files().size() - 1 - current);
            model.addAttribute("fileLinks", set.files().stream()
                    .map(f -> new FileLink(f, self.cloneBuilder().queryParam("index", f.index()).encode().toUriString()))
                    .toList());
        } catch (RuntimeException e) {
            // Same failures as the JSON API (bad path, unknown revision ...), shown on the page instead.
            log.warn("diff view failed: {}", e.getMessage());
            model.addAttribute("error", e.getMessage());
        }
        return "diff";
    }

    public record FileLink(DiffViewService.FileDiff file, String href) {
    }
}
