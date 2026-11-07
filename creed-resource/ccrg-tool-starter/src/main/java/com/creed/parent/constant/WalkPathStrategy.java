package com.creed.parent.constant;

import com.creed.parent.dal.model.WalkPathInfo;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.assertj.core.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 28/5/25
 */
@FunctionalInterface
public interface WalkPathStrategy {
    Logger log = LoggerFactory.getLogger(WalkPathStrategy.class);

    List<Path> apply(Path rootPath, WalkPathInfo info);

    static WalkPathStrategy simple() {
        return (rootPath, info) -> {
            Path partialPath = resolvePath(info.getProjectName(), info.getReleaseName(), info.getCountryCode()).normalize();
            return listFiles(rootPath.resolve(partialPath));
        };
    }

    static Path resolvePath(String... paths) {
        if (!Arrays.isNullOrEmpty(paths)) {
            return Stream.of(paths)
                    .map(str -> Path.of(StringUtils.defaultIfBlank(str, StringUtils.EMPTY)))
                    .reduce((s1, s2) -> s1.resolve(s2).normalize())
                    .orElse(Path.of(StringUtils.EMPTY));
        }
        return Path.of(StringUtils.EMPTY);
    }
    static List<Path> listFiles(Path path) {
        try (Stream<Path> currentDir = Files.list(path);) {
            return currentDir.filter(Files::isDirectory)
                    .toList();
        } catch (IOException e) {
            log.error("list error:{}", ExceptionUtils.getMessage(e));
        }
        return Collections.emptyList();
    }

    static <T, V, R> Function<T, R> combine(Function<T, V> func1, Function<V, R> func2) {
        return func1.andThen(s -> Optional.ofNullable(s).map(func2).orElse(null));
    }
    static <A, B, C, D> Function<A, D> combine(Function<A, B> func1, Function<B, C> func2, Function<C, D> func3) {
        return combine(func1, func2).andThen(func3);
    }

    default Function<Path, List<Path>> listFilesFunc() {

        return path -> {
            try (Stream<Path> currentDir = Files.list(path)) {
                return currentDir.filter(Files::isDirectory)
                        .toList();
            } catch (IOException e) {
                log.error("read path:{} error:{}", path, ExceptionUtils.getMessage(e));
            }
            return Collections.emptyList();
        };
    }

}
