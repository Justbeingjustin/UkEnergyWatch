package org.ukenergywatch.db

import org.ukenergywatch.utils.{ RangeOf, SimpleRangeOf }
import org.ukenergywatch.utils.JavaTimeExtensions._
import java.time.Instant
import scala.util.Try
import scala.annotation.tailrec
import java.time.Duration

// searchIndex is a special int value that allows fast retreival of data
// within a specified time-range.
// A tree-structure is used.
// The first level has each time-period being 2 hours, overlapping by half (ie 1 hour)
// Each level multiplies the time-period by 8
// level 0 = 2 hours
// level 1 = 16 hours
// level 2 = 128 hours = ~5 days
// level 3 = 1024 hours = ~42 days
// level 4 = 8192 hours = ~1 year
// level 5 = 65536 hours = ~7.5 years
// level 6 = 524288 hours = ~59 years (so this will definitely last until 2029, can add another level then))
//
// Each level starts at a fixed offset = level << 24
// This allow ~16 million hours at level 0 = ~1900 years = plenty
//
// All values based on the unix epoch, so time from 1970
//
// Writes use a searchIndex value that completely covers the range of the value, in the lowest level possible.
// Reads must do a range lookup in every level that selects all ranges that may contain the required data
// E.g.
// select * from rawdata where (
//   (searchIndex >= 0 && searchIndex <= 1) OR
//   (searchIndex >= 10000000 && searchIndex <= 10000001) OR
//   (searchIndex >= 11000000 && searchIndex <= 11000001) OR
//   ...
// )

trait SearchableValue extends MergeableValue {
  def searchIndex: Int
  def withSearchIndex(searchIndex: Int): this.type

  def withSearchIndex: this.type = withSearchIndex(SearchableValue.searchIndex(this))
}

object SearchableValue {

  //val level0Duration = 2.hours
  val level0Duration = Duration.ofHours(2) // Above infinite loops, don't know why
  val levelCount = 7

  def levelDuration(level: Int): Duration = level0Duration * (1 << (level * 3)) // 8x per level
  def levelOffset(level: Int): Int = level << 24 // 16 million per level

  // Return the single integer search-index that is correct for the given time-range
  def searchIndex(itemRange: RangeOf[Instant]): Int = {
    @tailrec def singleLevel(level: Int): Int = {
      if (level >= levelCount) {
        throw new Exception("Level too high")
      }
      val duration = levelDuration(level)
      val halfDuration = duration * 0.5
      val levelStart = itemRange.from.alignTo(halfDuration)
      val levelEnd = levelStart + duration
      if (levelEnd >= itemRange.to) {
        // This range is good
        (levelStart.millis / halfDuration.millis).toInt + levelOffset(level)
      } else {
        // This range is not long enough, try again
        singleLevel(level + 1)
      }
    }
    singleLevel(0)
  }

  // Return the search-index ranges that need searching for items within the given time-range
  def searchRanges(searchRange: RangeOf[Instant]): Seq[RangeOf[Int]] = {
    @tailrec def singleLevel(level: Int, result: List[RangeOf[Int]]): Seq[RangeOf[Int]] = {
      if (level >= levelCount) {
        result
      } else {
        val duration = levelDuration(level)
        val halfDuration = duration * 0.5
        val levelFromInclusive = math.max(0L, (searchRange.from - halfDuration).millis / halfDuration.millis)
        // -1 milli to remove a spurious extra search range. This will fail if queries in sub-milli range
        val levelToInclusive = (searchRange.to - 1.milli).millis / halfDuration.millis
        val out = SimpleRangeOf(
          levelFromInclusive.toInt + levelOffset(level),
          levelToInclusive.toInt + levelOffset(level)
        )
        singleLevel(level + 1, out :: result)
      }
    }
    singleLevel(0, List.empty)
  }
}

trait Searchable extends Mergeable {
  val driver: slick.driver.JdbcDriver
  import driver.api._

  trait SearchableTable {
    def searchIndex: Rep[Int]
  }

  trait SearchQuery[TValue <: SearchableValue, TTable <: Table[TValue] with SearchableTable with MergeableTable]
      extends MergeQuery[TValue, TTable] {
    this: TableQuery[TTable] =>

    override def preSearch(fromTime: DbTime, toTime: DbTime): Query[TTable, TValue, Seq] = {
      val searchRanges = SearchableValue.searchRanges(SimpleRangeOf(fromTime.toInstant, toTime.toInstant))
      val filters = searchRanges.map { range =>
        x: TTable => x.searchIndex >= range.from && x.searchIndex <= range.to
      }.reduce { (a, b) =>
        x: TTable => a(x) || b(x)
      }
      this.filter(filters)
    }

    override def addItem(item: TValue): DBIO[_] = {
      this += item.withSearchIndex(SearchableValue.searchIndex(item))
    }

    override def updateItem(id: Int, range: RangeOf[DbTime]): DBIO[_] = {
      this.filter(_.id === id)
        .map(x => (x.fromTime, x.toTime, x.searchIndex))
        .update((range.from, range.to, SearchableValue.searchIndex(
          SimpleRangeOf(range.from.toInstant, range.to.toInstant))))
    }

    def search(from: Instant, to: Instant): Query[TTable, TValue, Seq] = {
      val fromDbTime = DbTime(from)
      val toDbTime = DbTime(to)
      preSearch(fromDbTime, toDbTime).filter { x =>
        x.fromTime < toDbTime && x.toTime > fromDbTime
      }
    }

    def search(range: RangeOf[Instant]): Query[TTable, TValue, Seq] = search(range.from, range.to)

  }
}
