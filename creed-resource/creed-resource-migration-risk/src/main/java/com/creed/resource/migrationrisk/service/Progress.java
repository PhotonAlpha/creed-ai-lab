package com.creed.resource.migrationrisk.service;

/**
 * Where a running analysis is. Each stage owns a fixed band of the overall percentage; inside a band only the
 * stages that loop over files ({@link Stage#JAVA}, {@link Stage#FILES}) report steps. The two RefactoringMiner
 * runs are one opaque call each — no callback inside — so their bands jump from start to end; a UI shows them
 * as "running" with elapsed time.
 *
 * <p>A job's implementation also cancels: {@link #stage} and {@link #step} throw {@link CancelledException}
 * once the job was cancelled, so an analysis stops at the next report instead of running to the end.
 */
public interface Progress {

    enum Stage {
        RESOLVE(0), DIFF(5), EXPORT(10), UPSTREAM(15), MIGRATION(45), JAVA(80), FILES(94), SAVE(97), DONE(100);

        private final int start;

        Stage(int start) {
            this.start = start;
        }

        public int start() {
            return start;
        }

        /** The next stage's start, i.e. the end of this band. */
        public int end() {
            return this == DONE ? 100 : values()[ordinal() + 1].start;
        }

        public int percent(int done, int total) {
            return total <= 0 ? start : start + (end() - start) * Math.min(done, total) / total;
        }
    }

    void stage(Stage stage, String detail);

    void step(Stage stage, int done, int total, String detail);

    /** For the synchronous endpoints and the diff view: nobody is listening. */
    Progress NONE = new Progress() {
        @Override
        public void stage(Stage stage, String detail) {
        }

        @Override
        public void step(Stage stage, int done, int total, String detail) {
        }
    };

    class CancelledException extends RuntimeException {
        public CancelledException() {
            super("analysis cancelled");
        }
    }
}
