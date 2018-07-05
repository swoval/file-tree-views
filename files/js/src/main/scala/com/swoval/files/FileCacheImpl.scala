// Do not edit this file manually. It is autogenerated.

package com.swoval.files

import com.swoval.files.EntryFilters.AllPass
import com.swoval.files.PathWatcher.Event.Create
import com.swoval.files.PathWatcher.Event.Delete
import com.swoval.files.PathWatcher.Event.Modify
import com.swoval.files.PathWatcher.Event.Overflow
import java.util.Map.Entry
import com.swoval.files.Directory.Converter
import com.swoval.files.Directory.EntryFilter
import com.swoval.files.Directory.Observer
import com.swoval.files.Directory.OnChange
import com.swoval.files.Directory.OnError
import com.swoval.files.PathWatcher.Event
import com.swoval.files.PathWatcher.Event.Kind
import com.swoval.functional.Consumer
import com.swoval.functional.Either
import com.swoval.runtime.ShutdownHooks
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.HashMap
import java.util.HashSet
import java.util.Iterator
import java.util.List
import java.util.Map
import java.util.Set
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean

private[files] class FileCacheImpl[T <: AnyRef](private val converter: Converter[T],
                                                factory: PathWatcher.Factory,
                                                executor: Executor,
                                                options: FileCaches.Option*)
    extends FileCache[T] {

  private val observers: Observers[T] = new Observers()

  private val directories: Map[Path, Directory[T]] = new HashMap()

  private val pendingFiles: Set[Path] = new HashSet()

  private val closed: AtomicBoolean = new AtomicBoolean(false)

  private val internalExecutor: Executor =
    if (executor == null)
      Executor.make("com.swoval.files.FileCache-callback-internalExecutor")
    else executor

  private val callbackExecutor: Executor =
    Executor.make("com.swoval.files.FileCache-callback-executor")

  private val symlinkWatcher: SymlinkWatcher =
    if (!ArrayOps.contains(options, FileCaches.Option.NOFOLLOW_LINKS))
      new SymlinkWatcher(
        new Consumer[Path]() {
          override def accept(path: Path): Unit = {
            handleEvent(path)
          }
        },
        factory,
        new OnError() {
          override def apply(symlink: Path, exception: IOException): Unit = {
            observers.onError(symlink, exception)
          }
        },
        this.internalExecutor.copy()
      )
    else null

  private val registry: DirectoryRegistry = new DirectoryRegistry()

  private def callback(executor: Executor): Consumer[Event] =
    new Consumer[Event]() {
      override def accept(event: Event): Unit = {
        executor.run(new Runnable() {
          override def run(): Unit = {
            val path: Path = event.path
            if (event.kind == Overflow) {
              handleOverflow(path)
            } else {
              handleEvent(path)
            }
          }
        })
      }
    }

  private val watcher: PathWatcher =
    factory.create(callback(this.internalExecutor.copy()), this.internalExecutor.copy(), registry)

  ShutdownHooks.addHook(1, new Runnable() {
    override def run(): Unit = {
      close()
    }
  })

  /**
 Cleans up the path watcher and clears the directory cache.
   */
  override def close(): Unit = {
    if (closed.compareAndSet(false, true)) {
      if (symlinkWatcher != null) symlinkWatcher.close()
      watcher.close()
      val directoryIterator: Iterator[Directory[T]] =
        directories.values.iterator()
      while (directoryIterator.hasNext) directoryIterator.next().close()
      directories.clear()
      internalExecutor.close()
      callbackExecutor.close()
    }
  }

  override def addObserver(observer: Observer[T]): Int =
    observers.addObserver(observer)

  override def addCallback(onChange: OnChange[T]): Int =
    addObserver(new Observer[T]() {
      override def onCreate(newEntry: Directory.Entry[T]): Unit = {
        onChange.apply(newEntry)
      }

      override def onDelete(oldEntry: Directory.Entry[T]): Unit = {
        onChange.apply(oldEntry)
      }

      override def onUpdate(oldEntry: Directory.Entry[T], newEntry: Directory.Entry[T]): Unit = {
        onChange.apply(newEntry)
      }

      override def onError(path: Path, exception: IOException): Unit = {}
    })

  override def removeObserver(handle: Int): Unit = {
    observers.removeObserver(handle)
  }

  override def list(path: Path,
                    maxDepth: Int,
                    filter: Directory.EntryFilter[_ >: T]): List[Directory.Entry[T]] =
    internalExecutor
      .block(new Callable[List[Directory.Entry[T]]]() {
        override def call(): List[Directory.Entry[T]] = {
          val dir: Directory[T] = find(path)
          if (dir == null) {
            new ArrayList()
          } else {
            if (dir.path == path && dir.getDepth == -1) {
              val result: List[Directory.Entry[T]] =
                new ArrayList[Directory.Entry[T]]()
              result.add(dir.entry())
              result
            } else {
              dir.list(path, maxDepth, filter)
            }
          }
        }
      })
      .get

  override def list(path: Path,
                    recursive: Boolean,
                    filter: EntryFilter[_ >: T]): List[Directory.Entry[T]] =
    list(path, if (recursive) java.lang.Integer.MAX_VALUE else 0, filter)

  override def list(path: Path, maxDepth: Int): List[Directory.Entry[T]] =
    list(path, maxDepth, AllPass)

  override def list(path: Path, recursive: Boolean): List[Directory.Entry[T]] =
    list(path, recursive, AllPass)

  override def list(path: Path): List[Directory.Entry[T]] =
    list(path, java.lang.Integer.MAX_VALUE, AllPass)

  override def register(path: Path, maxDepth: Int): Either[IOException, Boolean] = {
    var result: Either[IOException, Boolean] = watcher.register(path, maxDepth)
    if (result.isRight) {
      result = internalExecutor
        .block(new Callable[Boolean]() {
          override def call(): Boolean = doReg(path, maxDepth)
        })
        .castLeft(classOf[IOException])
    }
    result
  }

  override def register(path: Path, recursive: Boolean): Either[IOException, Boolean] =
    register(path, if (recursive) java.lang.Integer.MAX_VALUE else 0)

  override def register(path: Path): Either[IOException, Boolean] =
    register(path, java.lang.Integer.MAX_VALUE)

  override def unregister(path: Path): Unit = {
    internalExecutor.block(new Runnable() {
      override def run(): Unit = {
        registry.removeDirectory(path)
        watcher.unregister(path)
        if (!registry.accept(path)) {
          val dir: Directory[T] = find(path)
          if (dir != null) {
            if (dir.path == path) {
              directories.remove(path)
            } else {
              dir.remove(path)
            }
          }
        }
      }
    })
  }

  private def doReg(path: Path, maxDepth: Int): Boolean = {
    var result: Boolean = false
    registry.addDirectory(path, maxDepth)
    val dirs: List[Directory[T]] =
      new ArrayList[Directory[T]](directories.values)
    Collections.sort(dirs, new Comparator[Directory[T]]() {
      override def compare(left: Directory[T], right: Directory[T]): Int =
        left.path.compareTo(right.path)
    })
    val it: Iterator[Directory[T]] = dirs.iterator()
    var existing: Directory[T] = null
    while (it.hasNext && existing == null) {
      val dir: Directory[T] = it.next()
      if (path.startsWith(dir.path)) {
        val depth: Int =
          if (path == dir.path) 0
          else (dir.path.relativize(path).getNameCount - 1)
        if (dir.getDepth == java.lang.Integer.MAX_VALUE || maxDepth < dir.getDepth - depth) {
          existing = dir
        } else if (depth <= dir.getDepth) {
          result = true
          dir.close()
          try {
            val md: Int =
              if (maxDepth < java.lang.Integer.MAX_VALUE - depth - 1)
                maxDepth + depth + 1
              else java.lang.Integer.MAX_VALUE
            existing = Directory.cached(dir.path, converter, md)
            directories.put(dir.path, existing)
          } catch {
            case e: IOException => existing = null

          }
        }
      }
    }
    if (existing == null) {
      try {
        var dir: Directory[T] = null
        try dir = Directory.cached(path, converter, maxDepth)
        catch {
          case e: NotDirectoryException =>
            dir = Directory.cached(path, converter, -1)

        }
        directories.put(path, dir)
        val entryIterator: Iterator[Directory.Entry[T]] =
          dir.list(true, EntryFilters.AllPass).iterator()
        if (symlinkWatcher != null) {
          while (entryIterator.hasNext) {
            val entry: Directory.Entry[T] = entryIterator.next()
            if (entry.isSymbolicLink) {
              symlinkWatcher.addSymlink(entry.path,
                                        if (maxDepth == java.lang.Integer.MAX_VALUE) maxDepth
                                        else maxDepth - 1)
            }
          }
        }
        result = true
      } catch {
        case e: NoSuchFileException => result = pendingFiles.add(path)

      }
    }
    result
  }

  private def find(path: Path): Directory[T] = {
    var foundDir: Directory[T] = null
    val it: Iterator[Directory[T]] = directories.values.iterator()
    while (it.hasNext) {
      val dir: Directory[T] = it.next()
      if (path.startsWith(dir.path) &&
          (foundDir == null || dir.path.startsWith(foundDir.path))) {
        foundDir = dir
      }
    }
    foundDir
  }

  private def diff(left: Directory[T], right: Directory[T]): Boolean = {
    val oldEntries: List[Directory.Entry[T]] =
      left.list(left.recursive(), AllPass)
    val oldPaths: Set[Path] = new HashSet[Path]()
    val oldEntryIterator: Iterator[Directory.Entry[T]] = oldEntries.iterator()
    while (oldEntryIterator.hasNext) oldPaths.add(oldEntryIterator.next().path)
    val newEntries: List[Directory.Entry[T]] =
      right.list(left.recursive(), AllPass)
    val newPaths: Set[Path] = new HashSet[Path]()
    val newEntryIterator: Iterator[Directory.Entry[T]] = newEntries.iterator()
    while (newEntryIterator.hasNext) newPaths.add(newEntryIterator.next().path)
    var result: Boolean = oldPaths.size != newPaths.size
    val oldIterator: Iterator[Path] = oldPaths.iterator()
    while (oldIterator.hasNext && !result) if (newPaths.add(oldIterator.next()))
      result = true
    val newIterator: Iterator[Path] = newPaths.iterator()
    while (newIterator.hasNext && !result) if (oldPaths.add(newIterator.next()))
      result = true
    result
  }

  private def cachedOrNull(path: Path, maxDepth: Int): Directory[T] = {
    var res: Directory[T] = null
    try res = Directory.cached(path, converter, maxDepth)
    catch {
      case e: IOException => {}

    }
    res
  }

  private def handleOverflow(path: Path): Unit = {
    if (!closed.get) {
      val directoryIterator: Iterator[Directory[T]] =
        directories.values.iterator()
      val toReplace: List[Directory[T]] = new ArrayList[Directory[T]]()
      val creations: List[Directory.Entry[T]] =
        new ArrayList[Directory.Entry[T]]()
      val updates: List[Array[Directory.Entry[T]]] =
        new ArrayList[Array[Directory.Entry[T]]]()
      val deletions: List[Directory.Entry[T]] =
        new ArrayList[Directory.Entry[T]]()
      while (directoryIterator.hasNext) {
        val currentDir: Directory[T] = directoryIterator.next()
        if (path.startsWith(currentDir.path)) {
          var oldDir: Directory[T] = currentDir
          var newDir: Directory[T] = cachedOrNull(oldDir.path, oldDir.getDepth)
          while (newDir == null || diff(oldDir, newDir)) {
            if (newDir != null) oldDir = newDir
            newDir = cachedOrNull(oldDir.path, oldDir.getDepth)
          }
          val oldEntries: Map[Path, Directory.Entry[T]] =
            new HashMap[Path, Directory.Entry[T]]()
          val newEntries: Map[Path, Directory.Entry[T]] =
            new HashMap[Path, Directory.Entry[T]]()
          val oldEntryIterator: Iterator[Directory.Entry[T]] =
            currentDir.list(currentDir.recursive(), AllPass).iterator()
          while (oldEntryIterator.hasNext) {
            val entry: Directory.Entry[T] = oldEntryIterator.next()
            oldEntries.put(entry.path, entry)
          }
          val newEntryIterator: Iterator[Directory.Entry[T]] =
            newDir.list(currentDir.recursive(), AllPass).iterator()
          while (newEntryIterator.hasNext) {
            val entry: Directory.Entry[T] = newEntryIterator.next()
            newEntries.put(entry.path, entry)
          }
          val oldIterator: Iterator[Entry[Path, Directory.Entry[T]]] =
            oldEntries.entrySet().iterator()
          while (oldIterator.hasNext) {
            val mapEntry: Entry[Path, Directory.Entry[T]] = oldIterator.next()
            if (!newEntries.containsKey(mapEntry.getKey)) {
              deletions.add(mapEntry.getValue)
              watcher.unregister(mapEntry.getKey)
            }
          }
          val newIterator: Iterator[Entry[Path, Directory.Entry[T]]] =
            newEntries.entrySet().iterator()
          while (newIterator.hasNext) {
            val mapEntry: Entry[Path, Directory.Entry[T]] = newIterator.next()
            val oldEntry: Directory.Entry[T] = oldEntries.get(mapEntry.getKey)
            if (oldEntry == null) {
              if (registry.accept(mapEntry.getKey) && mapEntry.getValue.isDirectory) {
                if (registry.accept(mapEntry.getKey) && mapEntry.getValue.isDirectory) {
                  /*
                   * Using Integer.MIN_VALUE will ensure that we update the directory without changing
                   * the depth of the registration.
                   */

                  watcher.register(mapEntry.getKey, java.lang.Integer.MIN_VALUE)
                }
              }
              creations.add(mapEntry.getValue)
            } else if (oldEntry != mapEntry.getValue) {
              updates.add(Array(oldEntry, mapEntry.getValue))
            }
          }
          toReplace.add(newDir)
        }
      }
      val replacements: Iterator[Directory[T]] = toReplace.iterator()
      while (replacements.hasNext) {
        val replacement: Directory[T] = replacements.next()
        directories.put(replacement.path, replacement)
      }
      callbackExecutor.run(new Runnable() {
        override def run(): Unit = {
          val creationIterator: Iterator[Directory.Entry[T]] =
            creations.iterator()
          while (creationIterator.hasNext) observers.onCreate(creationIterator.next())
          val deletionIterator: Iterator[Directory.Entry[T]] =
            deletions.iterator()
          while (deletionIterator.hasNext) observers.onDelete(deletionIterator.next())
          val updateIterator: Iterator[Array[Directory.Entry[T]]] =
            updates.iterator()
          while (updateIterator.hasNext) {
            val update: Array[Directory.Entry[T]] = updateIterator.next()
            observers.onUpdate(update(0), update(1))
          }
        }
      })
    }
  }

  private abstract class Callback(private val path: Path, private val kind: Kind)
      extends Runnable
      with Comparable[Callback] {

    override def compareTo(that: Callback): Int = {
      val kindComparision: Int = this.kind.compareTo(that.kind)
      if (kindComparision == 0) this.path.compareTo(that.path)
      else kindComparision
    }

  }

  private def addCallback(callbacks: List[Callback],
                          path: Path,
                          oldEntry: Directory.Entry[T],
                          newEntry: Directory.Entry[T],
                          kind: Kind,
                          ioException: IOException): Unit = {
    callbacks.add(new Callback(path, kind) {
      override def run(): Unit = {
        if (ioException != null) {
          observers.onError(path, ioException)
        } else if (kind == Create) {
          observers.onCreate(newEntry)
        } else if (kind == Delete) {
          observers.onDelete(oldEntry)
        } else if (kind == Modify) {
          observers.onUpdate(oldEntry, newEntry)
        }
      }
    })
  }

  private def handleEvent(path: Path): Unit = {
    if (!closed.get) {
      var attrs: BasicFileAttributes = null
      val callbacks: List[Callback] = new ArrayList[Callback]()
      try attrs = NioWrappers.readAttributes(path, LinkOption.NOFOLLOW_LINKS)
      catch {
        case e: IOException => {}

      }
      if (attrs != null) {
        val dir: Directory[T] = find(path)
        if (dir != null) {
          val paths: List[Directory.Entry[T]] =
            dir.list(path, 0, new Directory.EntryFilter[T]() {
              override def accept(entry: Directory.Entry[_ <: T]): Boolean =
                path == entry.path
            })
          if (!paths.isEmpty || path != dir.path) {
            val toUpdate: Path = if (paths.isEmpty) path else paths.get(0).path
            try {
              if (attrs.isSymbolicLink && symlinkWatcher != null)
                symlinkWatcher.addSymlink(path,
                                          if (dir.getDepth == java.lang.Integer.MAX_VALUE)
                                            java.lang.Integer.MAX_VALUE
                                          else dir.getDepth - 1)
              val updates: Directory.Updates[T] =
                dir.update(toUpdate, Directory.Entry.getKind(toUpdate, attrs))
              updates.observe(callbackObserver(callbacks))
            } catch {
              case e: IOException =>
                addCallback(callbacks, path, null, null, Event.Error, e)

            }
          }
        } else if (pendingFiles.remove(path)) {
          try {
            var directory: Directory[T] = null
            try directory = Directory.cached(path, converter, registry.maxDepthFor(path))
            catch {
              case nde: NotDirectoryException =>
                directory = Directory.cached(path, converter, -1)

            }
            directories.put(path, directory)
            addCallback(callbacks, path, null, directory.entry(), Create, null)
            val it: Iterator[Directory.Entry[T]] =
              directory.list(true, AllPass).iterator()
            while (it.hasNext) {
              val entry: Directory.Entry[T] = it.next()
              addCallback(callbacks, entry.path, null, entry, Create, null)
            }
          } catch {
            case e: IOException => pendingFiles.add(path)

          }
        }
      } else {
        val removeIterators: List[Iterator[Directory.Entry[T]]] =
          new ArrayList[Iterator[Directory.Entry[T]]]()
        val directoryIterator: Iterator[Directory[T]] =
          new ArrayList(directories.values).iterator()
        while (directoryIterator.hasNext) {
          val dir: Directory[T] = directoryIterator.next()
          if (path.startsWith(dir.path)) {
            val updates: List[Directory.Entry[T]] = dir.remove(path)
            if (dir.path == path) {
              pendingFiles.add(path)
              updates.add(dir.entry())
              directories.remove(path)
            }
            removeIterators.add(updates.iterator())
          }
        }
        val it: Iterator[Iterator[Directory.Entry[T]]] =
          removeIterators.iterator()
        while (it.hasNext) {
          val removeIterator: Iterator[Directory.Entry[T]] = it.next()
          while (removeIterator.hasNext) {
            val entry: Directory.Entry[T] = removeIterator.next()
            addCallback(callbacks, entry.path, entry, null, Delete, null)
            if (symlinkWatcher != null) {
              symlinkWatcher.remove(entry.path)
            }
          }
        }
      }
      if (!callbacks.isEmpty) {
        callbackExecutor.run(new Runnable() {
          override def run(): Unit = {
            Collections.sort(callbacks)
            val callbackIterator: Iterator[Callback] = callbacks.iterator()
            while (callbackIterator.hasNext) callbackIterator.next().run()
          }
        })
      }
    }
  }

  private def callbackObserver(callbacks: List[Callback]): Observer[T] =
    new Observer[T]() {
      override def onCreate(newEntry: Directory.Entry[T]): Unit = {
        addCallback(callbacks, newEntry.path, null, newEntry, Create, null)
      }

      override def onDelete(oldEntry: Directory.Entry[T]): Unit = {
        addCallback(callbacks, oldEntry.path, oldEntry, null, Delete, null)
      }

      override def onUpdate(oldEntry: Directory.Entry[T], newEntry: Directory.Entry[T]): Unit = {
        addCallback(callbacks, oldEntry.path, oldEntry, newEntry, Modify, null)
      }

      override def onError(path: Path, exception: IOException): Unit = {
        addCallback(callbacks, path, null, null, Event.Error, exception)
      }
    }

}
