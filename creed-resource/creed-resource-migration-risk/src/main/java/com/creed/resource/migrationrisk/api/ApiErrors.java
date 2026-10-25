package com.creed.resource.migrationrisk.api;

import com.creed.resource.migrationrisk.git.GitRepositories;
import com.creed.resource.migrationrisk.service.MigrationRiskService;
import com.creed.resource.migrationrisk.service.Progress;
import com.creed.resource.migrationrisk.service.ReleaseStore;
import com.creed.resource.migrationrisk.service.RepositoryGuard;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exception -> {@code {error, message, fields?, time}} in one place: the exception handler answers requests with
 * it, and a background job sends the same body in its {@code failed} event, so the page handles both alike.
 */
public final class ApiErrors {

    private ApiErrors() {
    }

    public record ApiError(HttpStatus status, Map<String, Object> body) {
    }

    public static ApiError of(Throwable e) {
        return switch (e) {
            case RepositoryGuard.PathNotAllowedException p ->
                    error(HttpStatus.FORBIDDEN, "path_not_allowed", p.getMessage(), field(p.field(), p.getMessage()));
            case GitRepositories.InvalidRepositoryException r ->
                    error(HttpStatus.BAD_REQUEST, "invalid_repository", r.getMessage(), field(r.field(), r.getMessage()));
            case MigrationRiskService.TooManyChangesException t ->
                    error(HttpStatus.UNPROCESSABLE_ENTITY, "too_many_changes", t.getMessage(), null);
            case ReleaseStore.NotFoundException n -> error(HttpStatus.NOT_FOUND, "not_found", n.getMessage(), null);
            case ObjectOptimisticLockingFailureException o -> error(HttpStatus.CONFLICT, "review_conflict",
                    "the item was reviewed by someone else meanwhile — reload", null);
            case Progress.CancelledException c -> error(HttpStatus.CONFLICT, "cancelled", c.getMessage(), null);
            default -> error(HttpStatus.INTERNAL_SERVER_ERROR, "analysis_failed", String.valueOf(e.getMessage()), null);
        };
    }

    public static ApiError error(HttpStatus status, String error, String message, List<Map<String, String>> fields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        if (fields != null) {
            body.put("fields", fields);
        }
        body.put("time", Instant.now().toString());
        return new ApiError(status, body);
    }

    /** The page marks the input the failure belongs to — a wrong baseline is not a wrong repository. */
    static List<Map<String, String>> field(String field, String message) {
        return field == null ? null : List.of(Map.of("field", field, "message", message));
    }
}
