package com.qtsurfer.mcp.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;

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

  /**
   * Resolve a new output file beneath this root without allowing traversal, symlinks, or an
   * arbitrary absolute filesystem target. The parent directory must already exist so this guard
   * never creates a directory through a symlink supplied by a tool caller.
   *
   * @param requested relative output path supplied by an MCP caller
   * @param overwrite whether an existing regular file may be replaced atomically
   * @return a safe, not-yet-open output path
   */
  public Path resolveOutputFile(String requested, boolean overwrite) {
    if (requested == null || requested.isBlank()) {
      throw new IllegalArgumentException("outputPath is required");
    }
    Path relative = Path.of(requested);
    if (relative.isAbsolute() || relative.normalize().startsWith("..")) {
      throw new IllegalArgumentException("outputPath must be a relative path beneath the configured upload root");
    }
    Path candidate = root.resolve(relative).normalize();
    Path parent = candidate.getParent();
    if (parent == null) {
      throw new IllegalArgumentException("outputPath must name a file beneath the configured upload root");
    }
    try {
      Path realParent = parent.toRealPath();
      if (!realParent.startsWith(root)) {
        throw new IllegalArgumentException("outputPath must resolve beneath the configured upload root");
      }
      Path resolved = realParent.resolve(candidate.getFileName());
      if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
        if (Files.isSymbolicLink(resolved) || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
          throw new IllegalArgumentException("outputPath must not replace a symlink or non-regular file");
        }
        if (!overwrite) {
          throw new IllegalArgumentException("outputPath already exists; set overwrite=true to replace it");
        }
      }
      return resolved;
    } catch (IOException e) {
      throw new IllegalArgumentException("outputPath parent is not an accessible directory beneath the configured upload root");
    }
  }
}
