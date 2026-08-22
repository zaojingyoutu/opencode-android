package com.android.tools.build.apkzlib.bytestorage;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * musl JDK 补丁版 (上游 studio-8.5.2 同名类, API 保持一致):
 * 上游用 java.io.File.delete() 删目录, 而 musl libc 的 remove() 不像 glibc 那样
 * 对目录回落到 rmdir, 导致 Alpine/musl JDK 上 File.delete(dir) 恒失败,
 * AGP 清理 /tmp/tempdir_* 时抛 "Failed to delete ..." → mergeDebugJavaResource 挂。
 * 这里改用 NIO Files.walk + Files.delete, 各平台行为一致。
 */
public class TemporaryFile implements AutoCloseable {

    /** Whether the file was already deleted or not. */
    private boolean deleted;

    /** The temporary file. */
    private final File file;

    /**
     * Creates a new temporary file.
     *
     * @param file the file to wrap
     */
    public TemporaryFile(File file) {
        this.file = file;
    }

    /**
     * Obtains the file.
     *
     * @return the file
     */
    public File getFile() {
        if (deleted) {
            throw new IllegalStateException("File already deleted");
        }

        return file;
    }

    @Override
    public void close() throws IOException {
        if (deleted) {
            return;
        }

        deleted = true;
        deleteFile(file);
    }

    private void deleteFile(File file) throws IOException {
        if (!Files.exists(file.toPath())) {
            return;
        }

        Path path = file.toPath();
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
