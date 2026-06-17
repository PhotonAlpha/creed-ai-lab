package com.creed.report.model;

import java.util.List;

/** A single changed file within a commit, including its split-diff rows. */
public class DiffFile {

    private final String name;
    private final int additions;
    private final int deletions;
    private final List<DiffRow> rows;

    public DiffFile(String name, int additions, int deletions, List<DiffRow> rows) {
        this.name = name;
        this.additions = additions;
        this.deletions = deletions;
        this.rows = rows;
    }

    public String getName() {
        return name;
    }

    public int getAdditions() {
        return additions;
    }

    public int getDeletions() {
        return deletions;
    }

    public List<DiffRow> getRows() {
        return rows;
    }

    /** Five squares like GitHub: filled blue for additions, red for deletions, grey otherwise. */
    public List<String> getStatBlocks() {
        int total = additions + deletions;
        int green = 0;
        int red = 0;
        if (total > 0) {
            green = (int) Math.round(additions * 5.0 / total);
            green = Math.max(additions > 0 ? 1 : 0, Math.min(green, 5));
            red = (int) Math.round(deletions * 5.0 / total);
            red = Math.max(deletions > 0 ? 1 : 0, Math.min(red, 5 - green));
        }
        List<String> blocks = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            if (i < green) {
                blocks.add("add");
            } else if (i < green + red) {
                blocks.add("del");
            } else {
                blocks.add("neutral");
            }
        }
        return blocks;
    }
}
