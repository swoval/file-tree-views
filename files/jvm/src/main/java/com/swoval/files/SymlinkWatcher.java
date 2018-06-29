package com.swoval.files;

import static java.util.Map.Entry;

import com.swoval.files.Directory.OnError;
import com.swoval.files.DirectoryWatcher.Event;
import com.swoval.functional.Consumer;
import com.swoval.functional.Either;
import java.io.IOException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Monitors symlink targets. The {@link SymlinkWatcher} maintains a mapping of symlink targets to
 * symlink. When the symlink target is modified, the watcher will detect the update and invoke a
 * provided {@link com.swoval.functional.Consumer} for the symlink.
 */
class SymlinkWatcher implements AutoCloseable {
  private final AtomicBoolean isClosed = new AtomicBoolean(false);
  private final Map<Path, RegisteredPath> watchedSymlinksByDirectory = new HashMap<>();
  private final Map<Path, RegisteredPath> watchedSymlinksByTarget = new HashMap<>();
  private final OnError onError;
  private final Executor internalExecutor;

  private static final class RegisteredPath {
    public final Path path;
    public final Set<Path> paths = new HashSet<>();
    public final boolean isDirectory;

    RegisteredPath(final Path path, final boolean isDirectory, final Path base) {
      this.path = path;
      this.isDirectory = isDirectory;
      paths.add(base);
    }
  }

  private RegisteredPath find(final Path path, final Map<Path, RegisteredPath> map) {
    final RegisteredPath result = map.get(path);
    if (result != null) return result;
    else if (path == null || path.getNameCount() == 0) return null;
    else {
      final Path parent = path.getParent();
      if (parent == null || parent.getNameCount() == 0) return null;
      else return find(parent, map);
    }
  }

  private boolean hasLoop(final Path path) {
    boolean result = false;
    final Path parent = path.getParent();
    Path realPath;
    try {
      realPath = parent.toRealPath();
      result = parent.startsWith(realPath) && !parent.equals(realPath);
    } catch (final IOException e) {
      realPath = path;
    }
    if (!result) {
      Path loop = path.getRoot();
      final Iterator<Path> it = path.getParent().iterator();
      while (!result && it.hasNext()) {
        loop = loop.resolve(it.next());
        if (loop.endsWith(path.getFileName())) {
          try {
            final Path loopRealPath = loop.toRealPath();
            result = loopRealPath.equals(realPath);
          } catch (final IOException e) {
          }
        }
      }
    }
    return result;
  }

  SymlinkWatcher(
      final Consumer<Path> handleEvent,
      final DirectoryWatcher.Factory factory,
      final OnError onError,
      final Executor executor)
      throws IOException, InterruptedException {
    this.onError = onError;
    this.internalExecutor =
        executor == null
            ? Executor.make("com.swoval.files.SymlinkWatcher-callback-internalExecutor")
            : executor;
    final Consumer<Event> callback =
        new Consumer<Event>() {
          @SuppressWarnings("unchecked")
          @Override
          public void accept(final Event event) {
            SymlinkWatcher.this.internalExecutor.run(
                new Runnable() {
                  @Override
                  public void run() {

                    final List<Runnable> callbacks = new ArrayList<>();
                    final Path path = event.path;
                    {
                      final RegisteredPath registeredPath = find(path, watchedSymlinksByTarget);
                      if (registeredPath != null) {
                        final Path relativized = registeredPath.path.relativize(path);
                        final Iterator<Path> it = registeredPath.paths.iterator();
                        while (it.hasNext()) {
                          final Path rawPath = it.next().resolve(relativized);
                          if (!hasLoop(rawPath)) {
                            callbacks.add(
                                new Runnable() {
                                  @Override
                                  public void run() {
                                    handleEvent.accept(rawPath);
                                  }
                                });
                          }
                        }
                      }
                    }
                    if (!Files.exists(event.path)) {
                      final Path parent = event.path.getParent();
                      watchedSymlinksByTarget.remove(event.path);
                      final RegisteredPath registeredPath = watchedSymlinksByDirectory.get(parent);
                      if (registeredPath != null) {
                        registeredPath.paths.remove(event.path);
                        if (registeredPath.paths.isEmpty()) {
                          watcher.unregister(parent);
                          watchedSymlinksByDirectory.remove(parent);
                        }
                      }
                    }
                    final Iterator<Runnable> it = callbacks.iterator();
                    while (it.hasNext()) {
                      it.next().run();
                    }
                  }
                });
          }
        };
    this.watcher = factory.create(callback, internalExecutor.copy());
  }

  /*
   * This declaration must go below the constructor for javascript codegen.
   */
  private final DirectoryWatcher watcher;

  @Override
  public void close() {
    internalExecutor.block(
        new Runnable() {
          @Override
          public void run() {
            if (isClosed.compareAndSet(false, true)) {
              watcher.close();
              watchedSymlinksByTarget.clear();
              watchedSymlinksByDirectory.clear();
            }
          }
        });
  }

  /**
   * Start monitoring a symlink. As long as the target exists, this method will check if the parent
   * directory of the target is being monitored. If the parent isn't being registered, we register
   * it with the watch service. We add the target symlink to the set of symlinks watched in the
   * parent directory. We also add the base symlink to the set of watched symlinks for this
   * particular target.
   *
   * @param path The symlink base file.
   */
  @SuppressWarnings("EmptyCatchBlock")
  void addSymlink(final Path path, final boolean isDirectory, final int maxDepth) {
    if (path.toString().endsWith("other/dir/other")) new Exception().printStackTrace();
    internalExecutor.run(
        new Runnable() {
          @Override
          public String toString() {
            return "Add symlink " + path;
          }

          @Override
          public void run() {
            if (!isClosed.get()) {
              try {
                final Path realPath = path.toRealPath();
                if (path.startsWith(realPath) && !path.equals(realPath)) {
                  throw new FileSystemLoopException(path.toString());
                }
                final RegisteredPath targetRegistrationPath = watchedSymlinksByTarget.get(realPath);
                if (targetRegistrationPath == null) {
                  final Path registrationPath = isDirectory ? realPath : realPath.getParent();
                  final RegisteredPath registeredPath =
                      watchedSymlinksByDirectory.get(registrationPath);
                  if (registeredPath != null) {
                    if (!isDirectory || registeredPath.isDirectory) {
                      registeredPath.paths.add(realPath);
                      final RegisteredPath symlinkChildren = watchedSymlinksByTarget.get(realPath);
                      if (symlinkChildren != null) {
                        symlinkChildren.paths.add(path);
                      }
                    } else {
                      final Either<IOException, Boolean> result =
                          watcher.register(registrationPath, maxDepth);
                      if (result.getOrElse(false)) {
                        final RegisteredPath parentPath =
                            new RegisteredPath(registrationPath, true, realPath);
                        parentPath.paths.addAll(registeredPath.paths);
                        watchedSymlinksByDirectory.put(registrationPath, parentPath);
                        final RegisteredPath symlinkPaths =
                            new RegisteredPath(realPath, true, path);
                        final RegisteredPath existingSymlinkPaths =
                            watchedSymlinksByTarget.get(realPath);
                        if (existingSymlinkPaths != null) {
                          symlinkPaths.paths.addAll(existingSymlinkPaths.paths);
                        }
                        watchedSymlinksByTarget.put(realPath, symlinkPaths);
                      } else if (result.isLeft()) {
                        onError.apply(registrationPath, result.left().getValue());
                      }
                    }
                  } else {
                    final Either<IOException, Boolean> result =
                        watcher.register(registrationPath, maxDepth);
                    if (result.getOrElse(false)) {
                      watchedSymlinksByDirectory.put(
                          registrationPath,
                          new RegisteredPath(registrationPath, isDirectory, realPath));
                      watchedSymlinksByTarget.put(
                          realPath, new RegisteredPath(realPath, isDirectory, path));
                    } else if (result.isLeft()) {
                      onError.apply(registrationPath, result.left().getValue());
                    }
                  }
                } else if (Files.isDirectory(realPath)) {
                  onError.apply(path, new FileSystemLoopException(path.toString()));
                } else {
                  targetRegistrationPath.paths.add(path);
                }
              } catch (IOException e) {
                onError.apply(path, e);
              }
            }
          }
        });
  }

  /**
   * Removes the symlink from monitoring. If there are no remaining targets in the parent directory,
   * then we remove the parent directory from monitoring.
   *
   * @param path The symlink base to stop monitoring
   */
  void remove(final Path path) {
    internalExecutor.block(
        new Runnable() {
          @Override
          public void run() {
            if (!isClosed.get()) {
              Path target = null;
              {
                final Iterator<Entry<Path, RegisteredPath>> it =
                    watchedSymlinksByTarget.entrySet().iterator();
                while (it.hasNext() && target == null) {
                  final Entry<Path, RegisteredPath> entry = it.next();
                  if (entry.getValue().paths.remove(path)) {
                    target = entry.getKey();
                  }
                }
              }
              if (target != null) {
                final Path removalPath = Files.isDirectory(target) ? target : target.getParent();
                final RegisteredPath targetRegisteredPath = watchedSymlinksByTarget.get(target);
                if (targetRegisteredPath != null) {
                  targetRegisteredPath.paths.remove(path);
                  if (targetRegisteredPath.paths.isEmpty()) {
                    watchedSymlinksByTarget.remove(target);
                    final RegisteredPath registeredPath =
                        watchedSymlinksByDirectory.get(removalPath);
                    if (registeredPath != null) {
                      registeredPath.paths.remove(target);
                      if (registeredPath.paths.isEmpty()) {
                        watcher.unregister(removalPath);
                        watchedSymlinksByDirectory.remove(removalPath);
                      }
                    }
                  }
                }
              }
            }
          }
        });
  }
}
