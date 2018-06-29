package com.swoval.files;

import static com.swoval.files.QuickListerImpl.DIRECTORY;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

abstract class FileWithFileType extends File implements FileType {
  public FileWithFileType(final String name) {
    super(name);
  }
}

/**
 * Represents a file that will be returned by {@link QuickList#list}. Provides fast {@link
 * QuickFile#isDirectory} and {@link QuickFile#isFile} methods that should not call stat (or the
 * non-POSIX equivalent) on the * underlying file. Can be converted to a {@link java.io.File} or
 * {@link java.nio.file.Path} with {@link QuickFile#toFile} and {@link QuickFile#toPath}.
 */
public interface QuickFile {
  /**
   * Returns the fully resolved file name
   *
   * @return the fully resolved file name
   */
  String getFileName();

  /**
   * Returns true if this was a directory at the time time of listing. This may become inconsistent
   * if the QuickFile is cached
   *
   * @return true when the QuickFile is a directory
   */
  boolean isDirectory();
  /**
   * Returns true if this was a regular file at the time time of listing. This may become
   * inconsistent if the QuickFile is cached
   *
   * @return true when the QuickFile is a file
   */
  boolean isFile();

  /**
   * Returns true if this was a symbolic link at the time time of listing. This may become
   * inconsistent if the QuickFile is cached
   *
   * @return true when the QuickFile is a symbolic link
   */
  boolean isSymbolicLink();

  /**
   * Returns an instance of {@link FileWithFileType}. Typically the implementation of {@link
   * QuickFile} while extend {@link FileWithFileType}. This method will then just cast the instance
   * to {@link java.io.File}. Because the {@link QuickFile#isDirectory} and {@link QuickFile#isFile}
   * methods will generally cache the value of the native file result returned by readdir (posix) or
   * FindNextFile (windows) and use this value to compute {@link QuickFile#isDirectory} and {@link
   * QuickFile#isFile}, the returned {@link FileWithFileType} is generally unsuitable to be used as
   * a persistent value. Instead, use {@link QuickFile#toFile}.
   *
   * @return An instance of FileWithFileType. This may just be a cast.
   */
  FileWithFileType asFile();

  /**
   * Returns an instance of {@link java.io.File}. The instance should not override {@link
   * java.io.File#isDirectory} or {@link java.io.File#isFile} which makes it safe to persist.
   *
   * @return an instance of {@link java.io.File}
   */
  File toFile();

  /**
   * Returns an instance of {@link java.nio.file.Path}.
   *
   * @return an instance of {@link java.nio.file.Path}
   */
  Path toPath();
}

class QuickFileImpl extends FileWithFileType implements QuickFile {
  private final int kind;

  QuickFileImpl(final String name, final int kind) {
    super(name);
    this.kind = kind;
  }

  @Override
  public String getFileName() {
    return super.toString();
  }

  @Override
  public boolean isDirectory() {
    return is(QuickListerImpl.UNKNOWN) ? super.isDirectory() : is(DIRECTORY);
  }

  @Override
  public boolean isFile() {
    return is(QuickListerImpl.UNKNOWN) ? super.isFile() : is(QuickListerImpl.FILE);
  }

  @Override
  public boolean isSymbolicLink() {
    return is(QuickListerImpl.UNKNOWN) ? Files.isSymbolicLink(toPath()) : is(QuickListerImpl.LINK);
  }

  @Override
  public FileWithFileType asFile() {
    return this;
  }

  @Override
  public File toFile() {
    return new File(getFileName());
  }

  @Override
  public String toString() {
    return "QuickFile(" + getFileName() + ")";
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof QuickFile
        && this.getFileName().equals(((QuickFile) other).getFileName());
  }

  @Override
  public int hashCode() {
    return toString().hashCode();
  }

  private boolean is(final int kind) {
    return (this.kind & kind) != 0;
  }
}
