package com.creed.resource.migrationrisk.service;

import com.creed.resource.migrationrisk.api.dto.IgnoreRuleView;
import com.creed.resource.migrationrisk.domain.MrIgnoreRule;
import com.creed.resource.migrationrisk.domain.MrIgnoreRuleRepository;
import com.creed.resource.migrationrisk.domain.RiskItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.AntPathMatcher;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Persistent "don't show me this" rules, applied to every analysis (saved or not) after its file records are built:
 * a rule without a category drops the matching files, a rule with one drops that category's findings from them (a
 * file left with no findings is dropped too). What was dropped is counted in a warning, never silently.
 */
@Service
public class IgnoreRules {

    private final MrIgnoreRuleRepository rules;
    private final Clock clock;
    private final AntPathMatcher matcher = new AntPathMatcher("/");

    public IgnoreRules(MrIgnoreRuleRepository rules, Clock clock) {
        this.rules = rules;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<IgnoreRuleView> list() {
        return rules.findAll().stream().sorted(Comparator.comparing(MrIgnoreRule::getId)).map(IgnoreRules::view).toList();
    }

    @Transactional
    public IgnoreRuleView add(IgnoreRuleView.Create request) {
        MrIgnoreRule rule = new MrIgnoreRule();
        rule.setPattern(request.pattern().trim());
        rule.setCategory(request.category());
        rule.setScope(request.scope() == null || request.scope().isBlank() ? null : request.scope().trim());
        rule.setNote(request.note() == null || request.note().isBlank() ? null : request.note().trim());
        rule.setCreatedAt(Instant.now(clock));
        return view(rules.save(rule));
    }

    @Transactional
    public void delete(long id) {
        if (!rules.existsById(id)) {
            throw new ReleaseStore.NotFoundException("no ignore rule #" + id);
        }
        rules.deleteById(id);
    }

    /** {@code repo1Path}: as the analysis source names it (path or URL), for scoped rules. */
    @Transactional(readOnly = true)
    public List<RiskItem> apply(List<RiskItem> files, String repo1Path, List<String> warnings) {
        List<MrIgnoreRule> active = rules.findAll().stream()
                .filter(r -> r.getScope() == null || r.getScope().equals(repo1Path)).toList();
        if (active.isEmpty()) {
            return files;
        }
        List<RiskItem> kept = new ArrayList<>();
        int droppedFiles = 0;
        int droppedFindings = 0;
        for (RiskItem file : files) {
            List<MrIgnoreRule> matching = active.stream().filter(r -> matcher.match(r.getPattern(), file.element())).toList();
            if (matching.isEmpty()) {
                kept.add(file);
                continue;
            }
            if (matching.stream().anyMatch(r -> r.getCategory() == null)) {
                droppedFiles++;
                continue;
            }
            List<RiskItem> parts = (file.parts().isEmpty() ? List.of(file) : file.parts()).stream()
                    .filter(p -> matching.stream().noneMatch(r -> r.getCategory() == p.category())).toList();
            int before = file.parts().isEmpty() ? 1 : file.parts().size();
            droppedFindings += before - parts.size();
            if (parts.isEmpty()) {
                droppedFiles++;
            } else if (parts.size() == before) {
                kept.add(file);
            } else {
                kept.add(FileRecords.regroup(file, parts));
            }
        }
        if (droppedFiles + droppedFindings > 0) {
            warnings.add("ignore rules left out " + droppedFiles + " file(s) and " + droppedFindings
                    + " finding(s) of kept files");
        }
        return kept;
    }

    private static IgnoreRuleView view(MrIgnoreRule r) {
        return new IgnoreRuleView(Objects.requireNonNull(r.getId()), r.getPattern(), r.getCategory(), r.getScope(),
                r.getNote(), r.getCreatedAt());
    }
}
