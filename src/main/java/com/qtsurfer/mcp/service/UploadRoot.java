package com.qtsurfer.mcp.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves MCP upload paths beneath one operator-approved local directory. */
public final class UploadRoot {

  private final Path root;

  /**
   * Create an upload guard for an existing directory.
   *
   * @param root operator-approved upload directory
   * @throws IllegalArgumentException when the directory cannot be resolved
   */
  public UploadRoot(Path root) {
    try {
      this.root = root.toRealPath();
    } catch (IOException e) {
      throw new IllegalArgumentException("Upload root is not an accessible directory", e);
    }
    if (!Files.isDirectory(this.root)) {
      throw new IllegalArgumentException("Upload root is not a directory");
    }
  }

  /**
   * Resolve a readable regular file without permitting traversal or a symlink escape.
   *
   * @param requested path supplied by an MCP caller
   * @return the canonical path safe to pass to the SDK uploader
   * @throws IllegalArgumentException when the requested path is unsafe or unreadable
   */
  public Path resolveFile(String requested) {
    if (requested == null || requested.isBlank()) {
      throw new IllegalArgumentException("filePath is required");
    }
    try {
      Path candidate = Path.of(requested).toRealPath();
      if (!candidate.startsWith(root)) {
        throw new IllegalArgumentException("filePath must resolve beneath the configured upload root");
      }
      if (!Files.isRegularFile(candidate) || !Files.isReadable(candidate)) {
        throw new IllegalArgumentException("filePath must be a readable regular file");
      }
      return candidate;
    } catch (IOException e) {
      throw new IllegalArgumentException("filePath is not an accessible regular file");
    }
  }
}
