/*
 * RangedSeq.scala
 * (FingerTree)
 *
 * Copyright (c) 2011-2014 Hanns Holger Rutz. All rights reserved.
 *
 * This software is published under the GNU Lesser General Public License v2.1+
 *
 *
 * For further information, please contact Hanns Holger Rutz at
 * contact@sciss.de
 */

package de.sciss.fingertree

import scala.annotation.tailrec

object RangedSeq {
  def empty[Elem, P](implicit view: Elem => (P, P), ordering: Ordering[P]): RangedSeq[Elem, P] =
    new Impl(view, ordering) {
      protected val tree = FingerTree.empty[Anno[P], Elem]
    }

  def apply[Elem, P](xs: Elem*)(implicit view: Elem => (P, P), ordering: Ordering[P]): RangedSeq[Elem, P] = {
    xs.foldLeft(empty[Elem, P])(_ + _)
  }

  private type Anno[P]      = Option[(P, P)]
  private type FT[Elem, P]  = FingerTree[Anno[P], Elem]

  private abstract class Impl[Elem, P](view: Elem => (P, P), ordering: Ordering[P])
    extends RangedSeq[Elem, P] with Measure[Elem, Anno[P]] {
    seq =>

    protected val tree: FingerTree[Anno[P], Elem]   // making this a val helps in debugger

    // ---- measure ----

    protected implicit def m: Measure[Elem, Anno[P]] = this

    def zero          : Anno[P] = None
    def apply(c: Elem): Anno[P] = Some(view(c))

    def |+|(a: Anno[P], b: Anno[P]): Anno[P] = (a, b) match {
      case (_, None) => a
      case (None, _) => b
      case (Some((alo, ahi)), Some((blo, bhi))) => Some((blo, ordering.max(ahi, bhi)))

    }
//    def |+|(a: Anno[P], b: Anno[P], c: Anno[P]): Anno[P]  = |+|(|+|(a, b), c)

    // ---- fingertreelike ----

    protected def wrap(_tree: FT[Elem, P]): RangedSeq[Elem, P] = new Impl(view, ordering) {
      protected val tree = _tree
    }

    // ---- rangedseq ----

    def +(elem: Elem): RangedSeq[Elem, P] = {
      // Should have Interval wrapper that does this check...
      // require( ord.lteq( i._1, i._2 ), "Upper interval bound cannot be less than lower bound : " + i )
      val (l, r)  = splitTreeAt(view(elem))
      val res     = l ++ (elem +: r)
      wrap(res)
    }

    def -(elem: Elem): RangedSeq[Elem, P] = {
      val v       = view(elem)
      val elemLo  = v._1
      val (l, r)  = splitTreeAt(v)

      @tailrec def loop(left: FT[Elem, P], right: FT[Elem, P]): RangedSeq[Elem, P] =
        right.viewLeft match {
          case FingerTree.ViewLeftCons(head, tail) =>
            if (head == elem) {
              wrap(left ++ tail)
            } else if (ordering.gt(view(head)._1, elemLo)) {
              seq // not found
            } else {
              loop(left :+ head, tail)
            }
          case _ => seq // not found
        }

      loop(l, r)
    }

    def findOverlaps(interval: (P, P)): Option[Elem] = {
      val (iLo, iHi) = interval
      tree.measure match {
        case Some((_, tHi)) if (ordering.lt(iLo, tHi)) =>
          // if the search interval's low bound is smaller than the tree's total up bound...
          // "gives us the interval x with the smallest low endpoint
          //  whose high endpoint is at least the low endpoint of the query interval"
          //
          // Note: n <= MInfty is always false. Since MInfty is equivalent to None
          //     in our implementation, we can write _.map( ... ).getOrElse( false )
          //     for this test
          val x = tree.find1(isLtStop(iLo) _)._2
          // It then remains to check that low x <= high i
          val xLo = view(x)._1
          // println(s"FIND1 $x; has LO $xLo COMPARRE TO iHi $iHi")
          if (ordering.lt(xLo, iHi)) Some(x) else None

        case _ => None
      }
    }

    def find(point: P): Option[Elem] =
      tree.measure match {
        case Some((_, tHi)) if (ordering.lt(point, tHi)) =>
          val x   = tree.find1(isLtStop(point) _)._2
          val xLo = view(x)._1
          if (ordering.lteq(xLo, point)) Some(x) else None

        case _ => None
      }

    def filterIncludes(interval: (P, P)): Iterator[Elem] = {
      val (iLo, iHi) = interval
      val until      = tree.takeWhile(isGteqStart(iLo) _)
      new IncludesIterator(until, iHi)
    }

    def filterOverlaps(interval: (P, P)): Iterator[Elem] = {
      val (iLo, iHi) = interval

      // (1) keep only those elements whose start is < query_hi
      val until = tree.takeWhile(isGtStart(iHi) _)
      // (2) then we need to keep only those whose stop is > query_lo.
      new OverlapsIterator(until, iLo)
    }

    def intersect(point: P): Iterator[Elem] = {
      val until = tree.takeWhile(isGteqStart(point) _)
      new OverlapsIterator(until, point)
    }

    def includes(point: P): Boolean = find(point).nonEmpty
    def includes(interval: (P, P)): Boolean = filterIncludes(interval).nonEmpty
    def overlaps(interval: (P, P)): Boolean = filterOverlaps(interval).nonEmpty

    private final class OverlapsIterator(init: FingerTree[Anno[P], Elem], iLo: P) extends InRangeIterator(init) {
      protected def dropPred(v: Anno[P]): Boolean = isGteqStop(iLo)(v)
      protected def name = "overlaps"
    }

    private final class IncludesIterator(init: FingerTree[Anno[P], Elem], iHi: P) extends InRangeIterator(init) {
      protected def dropPred(v: Anno[P]): Boolean = isGtStop(iHi)(v)
      protected def name = "includes"
    }

    private sealed abstract class InRangeIterator(init: FingerTree[Anno[P], Elem]) extends Iterator[Elem] {
      override def toString() =
        if (hasNext) s"RangedSeq $name-iterator@${hashCode().toHexString}" else "empty iterator"

      protected def dropPred(v: Anno[P]): Boolean
      protected def name: String

      private def findNext(xs: FingerTree[Anno[P], Elem]): FingerTree.ViewLeft[Anno[P], Elem] =
        xs.dropWhile(dropPred).viewLeft

      private var nextValue = findNext(init)

      final def hasNext: Boolean = !nextValue.isEmpty

      final def next(): Elem = nextValue match {
        case FingerTree.ViewLeftCons(head, tail) =>
          nextValue = findNext(tail)
          head

        case FingerTree.ViewNil() => throw new NoSuchElementException(s"next on $this")
      }
    }

    def interval: Option[(P, P)] = {
      (tree.headOption, tree.measure) match {
        case (Some(headElem), Some((_, maxStop))) =>
          val minStart = view(headElem)._1
          Some((minStart, maxStop))
        case _ => None
      }
    }

    // is the argument less than an element's stop point?
    @inline private def isLtStop   (k: P)(v: Anno[P]) = v.map(tup => ordering.lt  (k, tup._2)).getOrElse(false)
    // is the argument greater than an element's start point?
    @inline private def isGtStart  (k: P)(v: Anno[P]) = v.map(tup => ordering.gt  (k, tup._1)).getOrElse(false)
    // is the argument greater than or equal to element's start point?
    @inline private def isGteqStart(k: P)(v: Anno[P]) = v.map(tup => ordering.gteq(k, tup._1)).getOrElse(false)
    // is the argument less than or equal to element's stop point?
    @inline private def isGtStop   (k: P)(v: Anno[P]) = v.map(tup => ordering.gt  (k, tup._2)).getOrElse(false)
    // is the argument less than or equal to element's stop point?
    @inline private def isGteqStop (k: P)(v: Anno[P]) = v.map(tup => ordering.gteq(k, tup._2)).getOrElse(false)

    // "We order the intervals by their low endpoints"
    private def splitTreeAt(interval: (P, P)) = {
      val iLo = interval._1
      tree.span(_.map(tup => ordering.lt(tup._1, iLo)).getOrElse(false))
    }

    override def toString = tree.iterator.mkString("RangedSeq(", ", ", ")")
  }
}
sealed trait RangedSeq[Elem, P] extends FingerTreeLike[Option[(P, P)], Elem, RangedSeq[Elem, P]] {
  /** Adds a new element to the tree. */
  def +(elem: Elem): RangedSeq[Elem, P]

  /** Removes an element from the tree. */
  def -(elem: Elem): RangedSeq[Elem, P]

  /** Finds an element that overlaps a given interval.
    * An overlap occurs if the intersection between query interval
    * and found interval is non-empty. In other words, found_start < query_stop && found_stop > query_start.
    * Of the candidates, returns the one with the lowest start point.
    *
    * @param interval the query interval
    * @return         the element which overlaps the query interval, or `None` if there is none.
    */
  def findOverlaps(interval: (P, P)): Option[Elem]

  /** Find an element that contains a given point.
    * A point is contained in if found_start <= point && found_stop > point.
    * Elements with empty intervals will thus not be detected (the `interval` version of this method can)
    *
    * @param point  the query point
    * @return       the element containing the point, or `None` if such an element does not exist.
    */
  def find(point: P): Option[Elem]

  /** Filters the tree to contain only those elements that overlap a given interval.
    * An overlap occurs if the intersection between query interval
    * and found interval is non-empty. In other words, found_start < query_stop && found_stop > query_start.
    *
    * @param interval the query interval
    * @return         the filtered tree whose elements overlap the query interval
    */
  def filterOverlaps(interval: (P, P)): Iterator[Elem]

  /** Filters the tree to contain only those elements that are completely contained within a given interval.
    * Containment means that found_start <= query_start &&
    *
    * @param interval the query interval
    * @return         the filtered tree whose elements are contained within the query interval
    */
  def filterIncludes(interval: (P, P)): Iterator[Elem]

  /** Filters the tree to contain only those elements that contain a given point.
    * An element contains the point if its interval start is less than or equal to that point
    * and its interval stop is greater than that point.
    *
    * @param point  the intersection point
    * @return       the filtered tree having only elements which contain the point
    */
  def intersect(point: P): Iterator[Elem]

  /** Returns the total interval covered by the sequence, or `None` if the range is empty */
  def interval: Option[(P, P)]

  def includes(point: P): Boolean
  def includes(interval: (P, P)): Boolean
  def overlaps(interval: (P, P)): Boolean
}