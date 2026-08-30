package com.qtsurfer.mcp.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadRootTest {

  @TempDir
  Path tempDir;

  @Test
  void resolvesRegularFileBeneathRoot() throws IOException {
    Path root = Files.createDirectory(tempDir.resolve("uploads"));
    Path file = Files.writeString(root.resolve("ticks.csv"), "timestamp,close\n1,2\n");

    assertThat(new UploadRoot(root).resolveFile(file.toString())).isEqualTo(file.toRealPath());
  }

  @Test
  void rejectsTraversalOutsideRoot() throws IOException {
    Path root = Files.createDirectory(tempDir.resolve("uploads"));
    Path outside = Files.writeString(tempDir.resolve("secret.csv"), "secret");

    assertThatThrownBy(() -> new UploadRoot(root).resolveFile(outside.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("beneath");
  }

  @Test
  void rejectsSymlinkThatEscapesRoot() throws IOException {
    Path root = Files.createDirectory(tempDir.resolve("uploads"));
    Path outside = Files.writeString(tempDir.resolve("secret.csv"), "secret");
    Path link = root.resolve("escape.csv");
    Files.createSymbolicLink(link, outside);

    assertThatThrownBy(() -> new UploadRoot(root).resolveFile(link.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("beneath");
  }

  @Test
  void resolvesNewRelativeOutputBeneathExistingRootDirectory() throws IOException {
    Path root = Files.createDirectory(tempDir.resolve("uploads"));
    Path exports = Files.createDirectory(root.resolve("exports"));

    assertThat(new UploadRoot(root).resolveOutputFile("exports/ticks.lastra", false))
        .isEqualTo(exports.toRealPath().resolve("ticks.lastra"));
  }

  @Test
  void rejectsOutputTraversalAndExistingFileWithoutOverwrite() throws IOException {
    Path root = Files.createDirectory(tempDir.resolve("uploads"));
    Path existing = Files.writeString(root.resolve("ticks.lastra"), "old");
    UploadRoot guard = new UploadRoot(root);

    assertThatThrownBy(() -> guard.resolveOutputFile("../secret.lastra", false))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("relative");
    assertThatThrownBy(() -> guard.resolveOutputFile("ticks.lastra", false))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already exists");
    assertThat(guard.resolveOutputFile("ticks.lastra", true)).isEqualTo(existing.toRealPath());
  }
}
