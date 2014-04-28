package org.ukenergywatch.db

import scala.slick.driver.JdbcProfile
import org.joda.time._
import org.ukenergywatch.utils.JodaTimeExtensions._


trait Mergeable[T] {
  def fromTime: Int
  def toTime: Int
  protected def fromValue: T
  protected def toValue: T

  def doesOverlap(other: Mergeable[_]): Boolean =
    this.toTime > other.fromTime && other.toTime > this.fromTime

  def canMerge(other: Mergeable[T]): Boolean =
    this.fromValue == this.toValue && this.fromValue == other.fromValue && this.fromValue == other.toValue

}

trait Merger[T <: Mergeable[U], U] {
  protected def merge(cur: Seq[T], item: T, insert: T => Unit, updateFrom: (T, Int) => Unit, updateTo: (T, Int) => Unit, delete: T => Unit): Unit = {
    cur match {
      case list if list.length > 2 =>
        throw new Exception
      case list if list.exists(x => item.doesOverlap(x)) =>
        throw new Exception
      case List(a, b) if a.toTime == item.fromTime && b.fromTime == item.toTime && item.canMerge(a) && item.canMerge(b) =>
        // new item is between two existing items, and is mergeable
        updateTo(a, b.toTime)
        delete(b)
      case List(a, _*) if a.toTime == item.fromTime && item.canMerge(a) =>
        // new item can merge with previous existing item
        updateTo(a, item.toTime)
      case List(a) if item.toTime == a.fromTime && item.canMerge(a) =>
        // new item can merge with following existing item
        updateFrom(a, item.fromTime)
      case List(_, a) if item.toTime == a.fromTime && item.canMerge(a) =>
        // new item can merge with following existing item
        updateFrom(a, item.fromTime)
      case _ =>
        // Cannot merge with anything, insert new item
        insert(item)
    }
  }
}

trait IntTimeRangeTable {
  val profile: JdbcProfile

  import profile.simple._

  trait IntTimeRange {
    def from: ReadableInstant
    def to: ReadableInstant
  }
}

trait MergeableTable extends IntTimeRangeTable {
  val profile: JdbcProfile

  import profile.simple._

  abstract class TimeMergeTable[T](tag: Tag, name: String) extends Table[T](tag, name) with IntTimeRange {
    def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
    def fromTime = column[Int]("fromTime")
    def toTime = column[Int]("toTime")

    def from: ReadableInstant = ???
    def to: ReadableInstant = ???
  }

}

trait DownloadTable extends MergeableTable {
  val profile: JdbcProfile

  import profile.simple._

  case class Download(downloadType: Int, fromTime: Int, toTime: Int, id: Int = 0) extends Mergeable[Unit] {
    protected def fromValue: Unit = Unit
    protected def toValue: Unit = Unit
  }

  class Downloads(tag: Tag) extends TimeMergeTable[Download](tag, "downloads") {
    def downloadType = column[Int]("downloadType")
    def * = (downloadType, fromTime, toTime, id) <> (Download.tupled, Download.unapply)
  }

  object Downloads extends TableQuery(new Downloads(_)) with Merger[Download, Unit] {

    val TYPE_BMRA = 1
    val TYPE_GAS_DATA = 2
    val TYPE_GAS_PUBLISHED = 3

    def mergeInsert(item: Download)(implicit session: Session): Unit = {
      val q = Downloads
        .filter(x => x.downloadType === item.downloadType && x.toTime >= item.fromTime && x.fromTime <= item.toTime)
        .sortBy(_.fromTime)
        .take(3)
      val result = q.list
      def insert(e: Download): Unit = Downloads += e
      def updateFrom(e: Download, from: Int): Unit = Downloads.filter(_.id === e.id).map(_.fromTime).update(from)
      def updateTo(e: Download, to: Int): Unit = Downloads.filter(_.id === e.id).map(_.toTime).update(to)
      def delete(e: Download): Unit = Downloads.filter(_.id === e.id).delete
      merge(result, item, insert, updateFrom, updateTo, delete)
    }

    def getLatest(downloadType: Int)(implicit session: Session): Option[ReadableInstant] = {
      Downloads.filter(_.downloadType === downloadType).sortBy(_.toTime.desc).firstOption.map(_.toTime.toInstant)
    }

    def getLastGap(downloadType: Int)(implicit session: Session): Option[ReadableInterval] = {
      val q = Downloads.filter(_.downloadType === downloadType).sortBy(_.toTime.desc).take(2)
      val res = q.list.reverse
      res match {
        case List() => None
        case List(a) => Some(new Interval(0.toInstant, a.fromTime.toInstant))
        case List(a, b) => Some(new Interval(a.toTime.toInstant, b.fromTime.toInstant))
      }
    }

  }
  
}

trait GasImportTable extends MergeableTable {
  val profile: JdbcProfile

  import profile.simple._

  case class GasImport(supplyType: String, location: String, fromTime: Int, toTime: Int, flowRate: Float, id: Int = 0) extends Mergeable[Float] {
    protected def fromValue: Float = flowRate
    protected def toValue: Float = flowRate
  }

  class GasImports(tag: Tag) extends TimeMergeTable[GasImport](tag, "gasimports") {
    def supplyType = column[String]("supplyType")
    def location = column[String]("location")
    def flowRate = column[Float]("flowRate")
    def * = (supplyType, location, fromTime, toTime, flowRate, id) <> (GasImport.tupled, GasImport.unapply)
  }

  object GasImports extends TableQuery(new GasImports(_)) with Merger[GasImport, Float] {
    def mergeInsert(item: GasImport)(implicit session: Session): Unit = {
      val q = GasImports
        .filter(x => x.supplyType === item.supplyType && x.location === item.location && x.toTime >= item.fromTime && x.fromTime <= item.toTime)
        .sortBy(_.fromTime)
        .take(3)
      val result = q.list
      def insert(e: GasImport): Unit = GasImports += e
      def updateFrom(e: GasImport, from: Int): Unit = GasImports.filter(_.id === e.id).map(_.fromTime).update(from)
      def updateTo(e: GasImport, to: Int): Unit = GasImports.filter(_.id === e.id).map(_.toTime).update(to)
      def delete(e: GasImport): Unit = GasImports.filter(_.id === e.id).delete
      merge(result, item, insert, updateFrom, updateTo, delete)
    }
  }

}

trait BmUnitFpnsTable extends MergeableTable {
  val profile: JdbcProfile

  import profile.simple._

  case class BmUnitFpn(bmUnitId: String, fromTime: Int, fromMw: Float, toTime: Int, toMw: Float, id: Int = 0) extends Mergeable[Float] {
    protected def fromValue: Float = fromMw
    protected def toValue: Float = toMw
  }

  class BmUnitFpns(tag: Tag) extends TimeMergeTable[BmUnitFpn](tag, "bmunitfpns") {
    def bmUnitId = column[String]("bmUnitId")
    def fromMw = column[Float]("fromMw")
    def toMw = column[Float]("toMw")
    def * = (bmUnitId, fromTime, fromMw, toTime, toMw, id) <> (BmUnitFpn.tupled, BmUnitFpn.unapply)
  }

  object BmUnitFpns extends TableQuery(new BmUnitFpns(_)) with Merger[BmUnitFpn, Float] {
    def mergeInsert(item: BmUnitFpn)(implicit session: Session): Unit = {
      val q = BmUnitFpns
        .filter(x => x.bmUnitId === item.bmUnitId && x.toTime >= item.fromTime && x.fromTime <= item.toTime)
        .sortBy(_.fromTime)
        .take(3)
      val result = q.list
      def insert(e: BmUnitFpn): Unit = BmUnitFpns += e
      def updateFrom(e: BmUnitFpn, from: Int): Unit = BmUnitFpns.filter(_.id === e.id).map(_.fromTime).update(from)
      def updateTo(e: BmUnitFpn, to: Int): Unit = BmUnitFpns.filter(_.id === e.id).map(_.toTime).update(to)
      def delete(e: BmUnitFpn): Unit = BmUnitFpns.filter(_.id === e.id).delete
      merge(result, item, insert, updateFrom, updateTo, delete)
    }
    def getSpot(bmUnitId: String, when: ReadableInstant)(implicit session: Session): Option[Double] = {
      val whenSeconds = (when.getMillis / 1000).toInt
      val q = BmUnitFpns.filter(x => x.bmUnitId === bmUnitId && x.fromTime <= whenSeconds && x.toTime > whenSeconds)
      val item = q.firstOption
      for (item <- item) yield {
        val itemRange = (item.toTime - item.fromTime).toDouble
        val tFraction = (whenSeconds.toDouble - item.fromTime.toDouble) / itemRange
        item.fromMw + (item.toMw - item.fromMw) * tFraction
      }
    }
    def getRange(bmUnitId: String, interval: ReadableInterval)(implicit session: Session): Seq[BmUnitFpn] = {
      val seconds0 = (interval.getStartMillis / 1000).toInt
      val seconds1 = (interval.getEndMillis / 1000).toInt
      val q = BmUnitFpns.filter(x => x.bmUnitId === bmUnitId && x.toTime > seconds0 && x.fromTime < seconds1).sortBy(_.fromTime)
      q.list
    }
  }

}

trait GenByFuelTable extends MergeableTable {
  val profile: JdbcProfile

  import profile.simple._

  case class GenByFuel(fuel: String, fromTime: Int, toTime: Int, mw: Float, id: Int = 0) extends Mergeable[Float] {
    protected def fromValue: Float = mw
    protected def toValue: Float = mw
  }

  abstract class GenByFuelsBase(tag: Tag, name: String) extends TimeMergeTable[GenByFuel](tag, name) {
    def fuel = column[String]("fuel")
    def mw = column[Float]("toMw")
    def * = (fuel, fromTime, toTime, mw, id) <> (GenByFuel.tupled, GenByFuel.unapply)
  }

  class GenByFuels(tag: Tag) extends GenByFuelsBase(tag, "genbyfuel")
  class GenByFuelsLive(tag: Tag) extends GenByFuelsBase(tag, "genbyfuellive")

  trait GenByFuelsObjBase {
    val ccgt = "CCGT"
    val coal = "COAL"
    val intew = "INTEW"
    val intfr = "INTFR"
    val intirl = "INTIRL"
    val intned = "INTNED"
    val npshyd = "NPSHYD"
    val nuclear = "NUCLEAR"
    val ocgt = "OCGT"
    val oil = "OIL"
    val other = "OTHER"
    val ps = "PS"
    val wind = "WIND"
  }

  object GenByFuels extends TableQuery(new GenByFuels(_)) with Merger[GenByFuel, Float] with GenByFuelsObjBase {

    def mergeInsert(item: GenByFuel)(implicit session: Session): Unit = {
      val q = GenByFuels
        .filter(x => x.fuel === item.fuel && x.toTime >= item.fromTime && x.fromTime <= item.toTime)
        .sortBy(_.fromTime)
        .take(3)
      val result = q.list
      def insert(e: GenByFuel): Unit = GenByFuels += e
      def updateFrom(e: GenByFuel, from: Int): Unit = GenByFuels.filter(_.id === e.id).map(_.fromTime).update(from)
      def updateTo(e: GenByFuel, to: Int): Unit = GenByFuels.filter(_.id === e.id).map(_.toTime).update(to)
      def delete(e: GenByFuel): Unit = GenByFuels.filter(_.id === e.id).delete
      merge(result, item, insert, updateFrom, updateTo, delete)
    }
  }

  object GenByFuelsLive extends TableQuery(new GenByFuelsLive(_)) with Merger[GenByFuel, Float] with GenByFuelsObjBase {

    def getLatestTime()(implicit session: Session): Option[ReadableInstant] = {
      GenByFuelsLive.sortBy(_.toTime.desc).firstOption.map(_.toTime.toInstant)
    }

    def mergeInsert(item: GenByFuel)(implicit session: Session): Unit = {
      val q = GenByFuelsLive
        .filter(x => x.fuel === item.fuel && x.toTime >= item.fromTime && x.fromTime <= item.toTime)
        .sortBy(_.fromTime)
        .take(3)
      val result = q.list
      def insert(e: GenByFuel): Unit = GenByFuelsLive += e
      def updateFrom(e: GenByFuel, from: Int): Unit = GenByFuelsLive.filter(_.id === e.id).map(_.fromTime).update(from)
      def updateTo(e: GenByFuel, to: Int): Unit = GenByFuelsLive.filter(_.id === e.id).map(_.toTime).update(to)
      def delete(e: GenByFuel): Unit = GenByFuelsLive.filter(_.id === e.id).delete
      merge(result, item, insert, updateFrom, updateTo, delete)
    }
  }

}

trait GridFrequencyTable extends IntTimeRangeTable {
  val profile: JdbcProfile

  import profile.simple._

  case class GridFrequency(endTime: Int, frequency: Float)

  abstract class GridFrequenciesBase(tag: Tag, name: String) extends Table[GridFrequency](tag, name) with IntTimeRange {
    def endTime = column[Int]("endTime", O.PrimaryKey)
    def frequency = column[Float]("frequency")
    def * = (endTime, frequency) <> (GridFrequency.tupled, GridFrequency.unapply)

    def from: ReadableInstant = ???
    def to: ReadableInstant = ???
  }

  class GridFrequencies(tag: Tag) extends GridFrequenciesBase(tag, "gridfrequency")
  class GridFrequenciesLive(tag: Tag) extends GridFrequenciesBase(tag, "gridfrequencylive")

  object GridFrequencies extends TableQuery(new GridFrequencies(_)) {
    def insert(item: GridFrequency)(implicit session: Session): Unit = {
      val result = GridFrequencies.filter(_.endTime === item.endTime).firstOption
      result match {
        case Some(_) => // Do nothing, already inserted
        case None => GridFrequencies += item
      }
    }
  }

  object GridFrequenciesLive extends TableQuery(new GridFrequenciesLive(_)) {
    def getLatestTime()(implicit session: Session): Option[ReadableInstant] = {
      GridFrequenciesLive.sortBy(_.endTime.desc).firstOption.map(_.endTime.toInstant)
    }
    def insert(item: GridFrequency)(implicit session: Session): Unit = {
      val result = GridFrequenciesLive.filter(_.endTime === item.endTime).firstOption
      result match {
        case Some(_) => // Do nothing, already inserted
        case None => GridFrequenciesLive += item
      }
    }
  }

}

trait DalComp {
  val dal: Dal
  trait Dal
      extends DownloadTable
      with GasImportTable
      with BmUnitFpnsTable
      with GenByFuelTable
      with GridFrequencyTable
      with DataMerger {

    val profile: JdbcProfile
    import profile.simple._

    val database: Database

    def ddls: Seq[profile.DDL] = Seq(
      Downloads.ddl,
      GasImports.ddl,
      BmUnitFpns.ddl,
      GenByFuels.ddl,
      GenByFuelsLive.ddl,
      GridFrequencies.ddl,
      GridFrequenciesLive.ddl
    )

  }
}