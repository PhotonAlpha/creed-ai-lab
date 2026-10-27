package com.creed.resource.migrationrisk.service;

import com.creed.resource.migrationrisk.domain.CodeLocation;
import com.creed.resource.migrationrisk.domain.DetectedRefactoring;
import gr.uom.java.xmi.diff.CodeRange;
import lombok.extern.slf4j.Slf4j;
import org.refactoringminer.api.GitHistoryRefactoringMiner;
import org.refactoringminer.api.Refactoring;
import org.refactoringminer.api.RefactoringHandler;
import org.refactoringminer.rm1.GitHistoryRefactoringMinerImpl;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RefactoringMiner's directory mode: two directories holding the "before" and "after" source trees, no
 * git history needed — which is what lets repo1 be compared with repo2, a repository that does not share repo1's
 * history at all.
 */
@Component
@Slf4j
public class RefactoringDetector {

    /** {@code label} names the run in the log ("repo1 -> repo2"). */
    public List<DetectedRefactoring> detect(String label, Path before, Path after) {
        long started = System.nanoTime();
        log.info("RefactoringMiner {}: {} -> {} ...", label, before.getFileName(), after.getFileName());
        List<DetectedRefactoring> found = new ArrayList<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        GitHistoryRefactoringMiner miner = new GitHistoryRefactoringMinerImpl();
        // Real absolute paths only: RefactoringMiner relativizes each file by cutting the directory string's
        // length off its absolute path, so a relative directory produced "b/sers/ethan/..." and no results.
        // toRealPath also resolves macOS's /var -> /private/var, which breaks the same cut.
        miner.detectAtDirectories(real(before).toFile(), real(after).toFile(), new RefactoringHandler() {
            @Override
            public void handle(String id, List<Refactoring> refactorings) {
                refactorings.forEach(r -> found.add(toDetected(r)));
            }

            @Override
            public void handleException(String id, Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw new IllegalStateException("RefactoringMiner failed on " + before.getFileName() + " -> "
                    + after.getFileName() + ": " + failure.get().getMessage(), failure.get());
        }
        log.info("RefactoringMiner {}: {} refactorings ({} structural) in {} ms", label, found.size(),
                found.stream().filter(DetectedRefactoring::structural).count(), (System.nanoTime() - started) / 1_000_000);
        return found;
    }

    private static DetectedRefactoring toDetected(Refactoring refactoring) {
        // toString() is "Move Class\tcom.a.X moved to com.b.X" — the tab separates the type's display name.
        String description = refactoring.toString().replace('\t', ' ').trim();
        String type = refactoring.getRefactoringType().name();
        return new DetectedRefactoring(type, description, RefactoringIndex.isStructural(type),
                refactoring.leftSide().stream().map(RefactoringDetector::location).toList(),
                refactoring.rightSide().stream().map(RefactoringDetector::location).toList());
    }

    private static CodeLocation location(CodeRange range) {
        return new CodeLocation(range.getFilePath(), range.getStartLine(), range.getEndLine(),
                range.getCodeElementType().name(), range.getCodeElement());
    }

    private static Path real(Path dir) {
        try {
            return dir.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
