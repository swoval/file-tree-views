// Do not edit this file manually. It is autogenerated.

package com.swoval.files

import com.swoval.files.Directory.Observer
import com.swoval.files.Directory.OnChange
import com.swoval.functional.Either
import java.io.IOException
import java.nio.file.Path
import java.util.List

/**
 * Provides an in memory cache of portions of the file system. Directories are added to the cache
 * using the [[FileCache.register]] method. Once a Path is added the cache, its
 * contents may be retrieved using the [[FileCache.list]]
 * method. The cache stores the path information in [[Directory.Entry]] instances.
 *
 * <p>A default implementation is provided by [[FileCaches.get]]. The user may cache arbitrary
 * information in the cache by customizing the [[Directory.Converter]] that is passed into the
 * factory [[FileCaches.get]].
 *
 * @tparam T The type of data stored in the [[Directory.Entry]] instances for the cache
 */
trait FileCache[T] extends AutoCloseable {

  /**
   * Add observer of file events
   *
   * @param observer The new observer
   * @return handle that can be used to remove the callback using [[removeObserver]]
   */
  def addObserver(observer: Observer[T]): Int

  /**
   * Add callback to fire when a file event is detected by the monitor
   *
   * @param onChange The callback to fire on file events
   * @return handle that can be used to remove the callback using [[removeObserver]]
   */
  def addCallback(onChange: OnChange[T]): Int

  /**
   * Stop firing the previously registered callback where {@code handle} is returned by [[addObserver]]
   *
   * @param handle A handle to the observer added by [[addObserver]]
   */
  def removeObserver(handle: Int): Unit

  /**
   * Lists the cache elements in the particular path
   *
   * @param path The path to list. This may be a file in which case the result list contains only
   *     this path or the empty list if the path is not monitored by the cache.
   * @param maxDepth The maximum depth of children of the parent to traverse in the tree.
   * @param filter Only include cache entries that are accepted by the filter.
   * @return The list of cache elements. This will be empty if the path is not monitored in a
   *     monitored path. If the path is a file and the file is monitored by the cache, the returned
   *     list will contain just the cache entry for the path.
   */
  def list(path: Path,
           maxDepth: Int,
           filter: Directory.EntryFilter[_ >: T]): List[Directory.Entry[T]]

  /**
   * Lists the cache elements in the particular path
   *
   * @param path The path to list. This may be a file in which case the result list contains only
   *     this path or the empty list if the path is not monitored by the cache.
   * @param recursive Toggles whether or not to include paths in subdirectories. Even when the cache
   *     is recursively monitoring the input path, it will not return cache entries for children if
   *     this flag is false.
   * @param filter Only include cache entries that are accepted by the filter.
   * @return The list of cache elements. This will be empty if the path is not monitored in a
   *     monitored path. If the path is a file and the file is monitored by the cache, the returned
   *     list will contain just the cache entry for the path.
   */
  def list(path: Path,
           recursive: Boolean,
           filter: Directory.EntryFilter[_ >: T]): List[Directory.Entry[T]]

  /**
   * Lists the cache elements in the particular path without any filtering
   *
   * @param path The path to list. This may be a file in which case the result list contains only
   *     this path or the empty list if the path is not monitored by the cache.
   *     <p>is recursively monitoring the input path, it will not return cache entries for children
   *     if this flag is false.
   * @param maxDepth The maximum depth of children of the parent to traverse in the tree.
   * @return The list of cache elements. This will be empty if the path is not monitored in a
   *     monitored path. If the path is a file and the file is monitored by the cache, the returned
   *     list will contain just the cache entry for the path.
   */
  def list(path: Path, maxDepth: Int): List[Directory.Entry[T]]

  /**
   * Lists the cache elements in the particular path without any filtering
   *
   * @param path The path to list. This may be a file in which case the result list contains only
   *     this path or the empty list if the path is not monitored by the cache.
   *     <p>is recursively monitoring the input path, it will not return cache entries for children
   *     if this flag is false.
   * @param recursive Toggles whether or not to traverse the children of the path
   * @return The list of cache elements. This will be empty if the path is not monitored in a
   *     monitored path. If the path is a file and the file is monitored by the cache, the returned
   *     list will contain just the cache entry for the path.
   */
  def list(path: Path, recursive: Boolean): List[Directory.Entry[T]]

  /**
   * Lists the cache elements in the particular path recursively and with no filter.
   *
   * @param path The path to list. This may be a file in which case the result list contains only
   *     this path or the empty list if the path is not monitored by the cache.
   * @return The list of cache elements. This will be empty if the path is not monitored in a
   *     monitored path. If the path is a file and the file is monitored by the cache, the returned
   *     list will contain just the cache entry for the path.
   */
  def list(path: Path): List[Directory.Entry[T]]

  /**
   * Register the path for monitoring.
   *
   * @param path The path to monitor
   * @param maxDepth The maximum depth of subdirectories to include
   * @return an instance of [[com.swoval.functional.Either]] that contains a boolean flag
   *     indicating whether registration succeeds. If an IOException is thrown registering the path,
   *     it is returned as a [[com.swoval.functional.Either.Left]]. This method should be
   *     idempotent and returns false if the call was a no-op.
   */
  def register(path: Path, maxDepth: Int): Either[IOException, Boolean]

  /**
   * Register the path for monitoring.
   *
   * @param path The path to monitor
   * @param recursive Recursively monitor the path if true
   * @return an instance of [[com.swoval.functional.Either]] that contains a boolean flag
   *     indicating whether registration succeeds. If an IOException is thrown registering the path,
   *     it is returned as a [[com.swoval.functional.Either.Left]]. This method should be
   *     idempotent and returns false if the call was a no-op.
   */
  def register(path: Path, recursive: Boolean): Either[IOException, Boolean]

  /**
   * Register the path for monitoring recursively.
   *
   * @param path The path to monitor
   * @return an instance of [[com.swoval.functional.Either]] that contains a boolean flag
   *     indicating whether registration succeeds. If an IOException is thrown registering the path,
   *     it is returned as a [[com.swoval.functional.Either.Left]]. This method should be
   *     idempotent and returns false if the call was a no-op.
   */
  def register(path: Path): Either[IOException, Boolean]

  /**
   * Unregister a path from the cache. This removes the path from monitoring and from the cache so
   * long as the path isn't covered by another registered path. For example, if the path /foo was
   * previously registered, after removal, no changes to /foo or files in /foo should be detected by
   * the cache. Moreover, calling [[com.swoval.files.FileCache.list]] for /foo should
   * return an empty list. If, however, we register both /foo recursively and /foo/bar (recursively
   * or not), after unregistering /foo/bar, changes to /foo/bar should continue to be detected and
   * /foo/bar should be included in the list returned by [[com.swoval.files.FileCache.list]].
   *
   * @param path The path to unregister
   */
  def unregister(path: Path): Unit

}
