// Do not edit this file manually. It is autogenerated.

package com.swoval.files

import com.swoval.files.FileTreeViews.Observable
import com.swoval.functional.Either
import com.swoval.functional.Filters
import java.io.IOException
import java.nio.file.Path

object FileTreeDataViews {

  /**
   * Make a new [[DirectoryView]] that caches the file tree but has no data value associated
   * with each value.
   *
   * @param path the path to monitor
   * @param converter computes the data value for each path found in the directory
   * @param depth sets how the limit for how deep to traverse the children of this directory
   * @param followLinks sets whether or not to treat symbolic links whose targets as directories or
   *     files
   * @tparam T the data type for this view
   * @return a directory whose entries just contain the path itself.
   */
  def cached[T <: AnyRef](path: Path,
                          converter: Converter[T],
                          depth: Int,
                          followLinks: Boolean): DirectoryDataView[T] =
    new CachedDirectoryImpl(path,
                            path,
                            converter,
                            depth,
                            Filters.AllPass,
                            FileTreeViews.getDefault(followLinks)).init()

  /**
   * Container class for [[CachedDirectoryImpl]] entries. Contains both the path to which the
   * path corresponds along with a data value.
   *
   * @tparam T The value wrapped in the Entry
   */
  trait Entry[T] extends Comparable[Entry[T]] {

    /**
     * Returns the [[TypedPath]] associated with this entry.
     *
     * @return the [[TypedPath]].
     */
    def getTypedPath(): TypedPath

    /**
     * Return the value associated with this entry. jjj
     *
     * @return the value associated with this entry.
     */
    def getValue(): Either[IOException, T]

  }

  /**
   * Converts a Path into an arbitrary value to be cached.
   *
   * @tparam R the generic type generated from the path.
   */
  trait Converter[R] {

    /**
     * Convert the path to a value.
     *
     * @param path the path to convert
     * @return the converted value
     */
    def apply(path: TypedPath): R

  }

  /**
   * Provides callbacks to run when different types of file events are detected by the cache.
   *
   * @tparam T the type for the [[Entry]] data
   */
  trait CacheObserver[T] {

    /**
     * Callback to fire when a new path is created.
     *
     * @param newEntry the [[Entry]] for the newly created file
     */
    def onCreate(newEntry: Entry[T]): Unit

    /**
     * Callback to fire when a path is deleted.
     *
     * @param oldEntry the [[Entry]] for the deleted.
     */
    def onDelete(oldEntry: Entry[T]): Unit

    /**
     * Callback to fire when a path is modified.
     *
     * @param oldEntry the [[Entry]] for the updated path
     * @param newEntry the [[Entry]] for the deleted path
     */
    def onUpdate(oldEntry: Entry[T], newEntry: Entry[T]): Unit

    /**
     * Callback to fire when an error is encountered generating while updating a path.
     *
     * @param exception The exception thrown by the computation
     */
    def onError(exception: IOException): Unit

  }

  /**
   * A file tree cache that can be monitored for events.
   *
   * @tparam T the type of data stored in the cache.
   */
  trait ObservableCache[T] extends Observable[Entry[T]] {

    /**
     * Add an observer of cache events.
     *
     * @param observer the observer to add
     * @return the handle to the observer.
     */
    def addCacheObserver(observer: CacheObserver[T]): Int

  }

}
