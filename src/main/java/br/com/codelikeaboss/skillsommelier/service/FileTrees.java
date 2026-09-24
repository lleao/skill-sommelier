package br.com.codelikeaboss.skillsommelier.service;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.List;

/** Directory-tree operations that never follow symbolic links. */
final class FileTrees {

    static final String GIT_DIR = ".git";

    private FileTrees() {}

    /** Deletes a file, a link (not its target) or a directory tree. Does nothing if the path does not exist. */
    static void delete(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                deleteEvenIfReadOnly(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Windows refuses to delete read-only files, such as the pack files git writes; clear the flag and retry. */
    private static void deleteEvenIfReadOnly(Path file) throws IOException {
        try {
            Files.delete(file);
        } catch (AccessDeniedException e) {
            DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (dos == null || !dos.readAttributes().isReadOnly()) {
                throw e;
            }
            dos.setReadOnly(false);
            Files.delete(file);
        }
    }

    /**
     * Copies the regular files of {@code source} into {@code target}, skipping {@code .git}. Links and special
     * files are not copied; their paths, relative to {@code source}, are returned.
     */
    static List<String> copyRegularFiles(Path source, Path target) throws IOException {
        List<String> skipped = new ArrayList<>();
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.getFileName().toString().equals(GIT_DIR)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile()) {
                    skipped.add(relativePath(source, file));
                    return FileVisitResult.CONTINUE;
                }
                Files.copy(file, target.resolve(source.relativize(file)),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES,
                        LinkOption.NOFOLLOW_LINKS);
                return FileVisitResult.CONTINUE;
            }
        });
        return skipped;
    }

    /** {@code file} relative to {@code dir}, with {@code /} separators on every OS. */
    static String relativePath(Path dir, Path file) {
        return dir.relativize(file).toString().replace('\\', '/');
    }
}
