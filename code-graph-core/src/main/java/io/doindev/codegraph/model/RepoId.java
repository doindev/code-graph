package io.doindev.codegraph.model;

/** Identity of the repository root node. */
public record RepoId(String repoName) implements NodeId {
    public RepoId {
        if (repoName == null || repoName.isBlank()) {
            throw new IllegalArgumentException("repoName must not be blank");
        }
    }

    @Override
    public String value() {
        return "repo:" + repoName;
    }
}
