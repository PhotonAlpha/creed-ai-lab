package com.creed.resource.migrationrisk.source;

import com.creed.resource.migrationrisk.git.ChangedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class TreeDiffTest {

    @TempDir
    Path tmp;

    private static void write(Path root, String path, String text) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void addedDeletedModifiedRenamedAndExcluded() throws IOException {
        Path a = tmp.resolve("a");
        Path b = tmp.resolve("b");
        write(a, "src/Keep.java", "class Keep {}\n");
        write(a, "src/Edit.java", "line1\nline2\n");
        write(a, "src/Gone.java", "gone\n");
        write(a, "old/Moved.java", "moved\n");
        // two deleted files with identical content: only one can pair with the single added copy
        write(a, "x/Empty1.txt", "");
        write(a, "x/Empty2.txt", "");
        write(a, "target/Build.class", "a");

        write(b, "src/Keep.java", "class Keep {}\n");
        write(b, "src/Edit.java", "line1\nchanged\nline3\n");
        write(b, "new/Moved.java", "moved\n");
        write(b, "src/Added.java", "a\nb\n");
        write(b, "y/Empty.txt", "");
        write(b, "target/Build.class", "b");

        List<ChangedFile> changes = TreeDiff.diff(new DirectoryTree(a, Set.of("target")),
                new DirectoryTree(b, Set.of("target")));

        assertThat(changes).extracting(ChangedFile::change, ChangedFile::oldPath, ChangedFile::newPath).containsExactly(
                // sorted by path (the new one, or the old one for a deletion)
                tuple(ChangedFile.Change.RENAMED, "old/Moved.java", "new/Moved.java"),
                tuple(ChangedFile.Change.ADDED, null, "src/Added.java"),
                tuple(ChangedFile.Change.MODIFIED, "src/Edit.java", "src/Edit.java"),
                tuple(ChangedFile.Change.DELETED, "src/Gone.java", null),
                tuple(ChangedFile.Change.DELETED, "x/Empty2.txt", null),
                tuple(ChangedFile.Change.RENAMED, "x/Empty1.txt", "y/Empty.txt"));
        assertThat(changes.get(1).addedLines()).containsExactly(1, 2);
        assertThat(changes.get(2).addedLines()).containsExactly(2, 3);
    }

    @Test
    void contentIdsMatchGitBlobIds() throws IOException {
        write(tmp, "hello.txt", "hello\n");
        // `printf 'hello\n' | git hash-object --stdin`
        assertThat(new DirectoryTree(tmp, Set.of()).contentId("hello.txt")).contains("ce013625030ba8dba906f756967f9e9ca394464a");
    }
}
