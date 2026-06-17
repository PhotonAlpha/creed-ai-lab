package com.creed.report.model;

import java.util.List;

/** Top-level view model for the commit comparison page. */
public class Commit {

    private String shortSha;
    private String title;
    private String description;
    private String author;
    private String date;
    private String branch;
    private String pullRequest;
    private String tag;
    private String version;
    private String parentSha;
    private int additions;
    private int deletions;
    private List<DiffFile> files;

    public int getFilesChanged() {
        return files == null ? 0 : files.size();
    }

    public String getShortSha() {
        return shortSha;
    }

    public void setShortSha(String shortSha) {
        this.shortSha = shortSha;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getPullRequest() {
        return pullRequest;
    }

    public void setPullRequest(String pullRequest) {
        this.pullRequest = pullRequest;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getParentSha() {
        return parentSha;
    }

    public void setParentSha(String parentSha) {
        this.parentSha = parentSha;
    }

    public int getAdditions() {
        return additions;
    }

    public void setAdditions(int additions) {
        this.additions = additions;
    }

    public int getDeletions() {
        return deletions;
    }

    public void setDeletions(int deletions) {
        this.deletions = deletions;
    }

    public List<DiffFile> getFiles() {
        return files;
    }

    public void setFiles(List<DiffFile> files) {
        this.files = files;
    }
}
