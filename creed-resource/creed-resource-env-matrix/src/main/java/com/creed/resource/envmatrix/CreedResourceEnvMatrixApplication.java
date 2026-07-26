package com.creed.resource.envmatrix;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Env Matrix Viewer backend — the environment host/ip/port mapping matrix.
 *
 * <p>Unlike the sibling resource modules (catalog / order / payment) this one is backed by a real
 * PostgreSQL schema ({@code env_matrix}), because the matrix is durable configuration data that the
 * UI edits rather than throwaway demo state.
 */
@SpringBootApplication
public class CreedResourceEnvMatrixApplication {

    public static void main(String[] args) {
        SpringApplication.run(CreedResourceEnvMatrixApplication.class, args);
    }
}
