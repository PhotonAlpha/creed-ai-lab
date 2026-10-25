package com.creed.resource.migrationrisk.web;

import com.creed.resource.migrationrisk.service.DiffViewService;
import com.creed.resource.migrationrisk.service.DiffViewService.DiffSet;
import com.creed.resource.migrationrisk.service.DiffViewService.Side;
import com.creed.resource.migrationrisk.service.MigrationRiskService.Inputs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The Thymeleaf diff page: RefactoringMiner's side-by-side Monaco view of one file pair, with Prev / Next
 * across the side's files. Under {@code /api/migration-risk/} on purpose — that prefix is what the frontend's
 * Vite proxy (and any reverse proxy in front of the API) already forwards to this service.
 *
 * <p>Inputs are the analysis inputs as query parameters (git: paths + commit ids; folders: three paths), so
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
                       @RequestParam(required = false) String baselinePath,
                       @RequestParam String repo1Path,
                       @RequestParam(required = false) String baseline,
                       @RequestParam(defaultValue = "HEAD") String repo1Head,
                       @RequestParam String repo2Path,
                       @RequestParam(defaultValue = "HEAD") String repo2Head,
                       @RequestParam(defaultValue = "A") Side side,
                       @RequestParam(required = false) String file,
                       @RequestParam(required = false) Integer index,
                       Model model) {
        Inputs in = mode == Inputs.Mode.DIRECTORY
                ? Inputs.folders(baselinePath, repo1Path, repo2Path)
                : Inputs.git(repo1Path, baseline, repo1Head, repo2Path, repo2Head);
        model.addAttribute("side", side);
        model.addAttribute("mode", mode);
        try {
            DiffSet set = diffs.diff(in, side);
            if (set.files().isEmpty()) {
                model.addAttribute("error", side == Side.A
                        ? "repo1 changed no Java file since the baseline." : "No Java file to compare on this side.");
                return "diff";
            }
            int current = index != null && index >= 0 && index < set.files().size() ? index : set.indexOf(file);
            model.addAttribute("files", set.files());
            model.addAttribute("current", set.files().get(current));
            model.addAttribute("refactoringCount", set.refactoringCount());
            model.addAttribute("config", set.config(current));
            UriComponentsBuilder self = UriComponentsBuilder.fromPath("/api/migration-risk/view/diff")
                    .queryParam("mode", mode).queryParam("repo1Path", repo1Path).queryParam("repo2Path", repo2Path);
            if (mode == Inputs.Mode.DIRECTORY) {
                self.queryParam("baselinePath", baselinePath);
            } else {
                self.queryParam("baseline", baseline).queryParam("repo1Head", repo1Head).queryParam("repo2Head", repo2Head);
            }
            // Switching sides keeps the file: the baseline path is the left side in both, else the new file's path.
            DiffViewService.FileDiff shown = set.files().get(current);
            String keep = shown.left() != null ? shown.left() : shown.right();
            model.addAttribute("sideA", self.cloneBuilder().queryParam("side", Side.A).queryParam("file", keep).encode().toUriString());
            model.addAttribute("sideB", self.cloneBuilder().queryParam("side", Side.B).queryParam("file", keep).encode().toUriString());
            UriComponentsBuilder sameSide = self.cloneBuilder().queryParam("side", side);
            model.addAttribute("prev", current > 0 ? sameSide.cloneBuilder().queryParam("index", current - 1).encode().toUriString() : null);
            model.addAttribute("next", current < set.files().size() - 1 ? sameSide.cloneBuilder().queryParam("index", current + 1).encode().toUriString() : null);
            model.addAttribute("remainingBefore", current);
            model.addAttribute("remainingAfter", set.files().size() - 1 - current);
            model.addAttribute("fileLinks", set.files().stream()
                    .map(f -> new FileLink(f, sameSide.cloneBuilder().queryParam("index", f.index()).encode().toUriString()))
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
