/*
 * Copyright (c) 2014-2016 by its authors. Some rights reserved.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.eval

import monix.eval.Enumerator._
import monix.types.{Deferrable, Evaluable, Streamable}
import monix.types.shims.{Applicative, Functor, Monad, MonadError}
import scala.collection.immutable
import scala.collection.{LinearSeq, mutable}
import scala.util.control.NonFatal

sealed abstract class Enumerator[F[_],+A] extends Product with Serializable {
  /** Filters the stream by the given predicate function,
    * returning only those elements that match.
    */
  final def filter(p: A => Boolean)(implicit F: Functor[F]): Enumerator[F,A] =
    this match {
      case ref @ NextEl(head, tail) =>
        try { if (p(head)) ref else Wait[F,A](F.map(tail)(_.filter(p))) }
        catch { case NonFatal(ex) => Error(ex) }

      case NextSeq(head, tail) =>
        val rest = F.map(tail)(_.filter(p))
        try head.filter(p) match {
          case Nil => Wait[F,A](rest)
          case filtered => NextSeq[F,A](filtered, rest)
        } catch {
          case NonFatal(ex) => Error(ex)
        }

      case Wait(rest) => Wait[F,A](F.map(rest)(_.filter(p)))
      case empty @ Empty() => empty
      case error @ Error(ex) => error
    }

  /** Returns a new iterable by mapping the supplied function
    * over the elements of the source.
    */
  final def map[B](f: A => B)(implicit F: Functor[F]): Enumerator[F,B] = {
    this match {
      case NextEl(head, tail) =>
        try { NextEl[F,B](f(head), F.map(tail)(_.map(f))) }
        catch { case NonFatal(ex) => Error(ex) }
      case NextSeq(head, rest) =>
        try { NextSeq[F,B](head.map(f), F.map(rest)(_.map(f))) }
        catch { case NonFatal(ex) => Error(ex) }

      case Wait(rest) => Wait[F,B](F.map(rest)(_.map(f)))
      case empty @ Empty() => empty
      case error @ Error(_) => error
    }
  }

  /** Applies the function to the elements of the source
    * and concatenates the results.
    */
  final def flatMap[B](f: A => Enumerator[F,B])
    (implicit F: Deferrable[F]): Enumerator[F,B] = {

    this match {
      case NextEl(head, tail) =>
        try { f(head) concatF F.map(tail)(_.flatMap(f)) }
        catch { case NonFatal(ex) => Error(ex) }

      case NextSeq(list, rest) =>
        try {
          if (list.isEmpty)
            Wait[F,B](F.map(rest)(_.flatMap(f)))
          else
            f(list.head) concatF F.evalAlways(NextSeq[F,A](list.tail, rest).flatMap(f))
        } catch {
          case NonFatal(ex) => Error(ex)
        }

      case Wait(rest) => Wait[F,B](F.map(rest)(_.flatMap(f)))
      case empty @ Empty() => empty
      case error @ Error(_) => error
    }
  }

  /** If the source is an async iterable generator, then
    * concatenates the generated async iterables.
    */
  final def flatten[B](implicit ev: A <:< Enumerator[F,B], F: Deferrable[F]): Enumerator[F,B] =
    flatMap(x => x)

  /** Alias for [[flatMap]]. */
  final def concatMap[B](f: A => Enumerator[F,B])(implicit F: Deferrable[F]): Enumerator[F,B] =
    flatMap(f)

  /** Alias for [[concat]]. */
  final def concat[B](implicit ev: A <:< Enumerator[F,B], F: Deferrable[F]): Enumerator[F,B] =
    flatten

  /** Appends the given iterable to the end of the source,
    * effectively concatenating them.
    */
  final def ++[B >: A](rhs: Enumerator[F,B])
    (implicit F: Functor[F]): Enumerator[F,B] = {

    this match {
      case Wait(task) =>
        Wait[F,B](F.map(task)(_ ++ rhs))
      case NextEl(a, lt) =>
        NextEl[F,B](a, F.map(lt)(_ ++ rhs))
      case NextSeq(head, lt) =>
        NextSeq[F,B](head, F.map(lt)(_ ++ rhs))
      case Empty() => rhs
      case error @ Error(_) => error
    }
  }

  private final def concatF[B >: A](rhs: F[Enumerator[F,B]])
    (implicit F: Functor[F]): Enumerator[F,B] = {

    this match {
      case Wait(task) =>
        Wait[F,B](F.map(task)(_ concatF rhs))
      case NextEl(a, lt) =>
        NextEl[F,B](a, F.map(lt)(_ concatF rhs))
      case NextSeq(head, lt) =>
        NextSeq[F,B](head, F.map(lt)(_ concatF rhs))
      case Empty() => Wait[F,B](rhs)
      case Error(ex) => Error(ex)
    }
  }

  /** Left associative fold using the function 'f'.
    *
    * On execution the iterable will be traversed from left to right,
    * and the given function will be called with the prior result,
    * accumulating state until the end, when the summary is returned.
    */
  final def foldLeftL[S](seed: => S)(f: (S,A) => S)
    (implicit F: MonadError[F, Throwable]): F[S] = {

    this match {
      case Empty() =>
        try F.pure(seed) catch { case NonFatal(ex) => F.raiseError(ex) }
      case Error(ex) => F.raiseError(ex)
      case Wait(next) =>
        F.flatMap(next)(_.foldLeftL(seed)(f))
      case NextEl(a, next) =>
        try {
          val state = f(seed, a)
          F.flatMap(next)(_.foldLeftL(state)(f))
        } catch {
          case NonFatal(ex) => F.raiseError(ex)
        }
      case NextSeq(list, next) =>
        try {
          val state = list.foldLeft(seed)(f)
          F.flatMap(next)(_.foldLeftL(state)(f))
        } catch {
          case NonFatal(ex) => F.raiseError(ex)
        }
    }
  }

  /** Left associative fold with the ability to short-circuit the process.
    *
    * This fold works for as long as the provided function keeps returning `true`
    * as the first member of its result and the streaming isn't completed.
    * If the provided fold function returns a `false` then the folding will
    * stop and the generated result will be the second member
    * of the resulting tuple.
    *
    * @param f is the folding function, returning `(true, state)` if the fold has
    *          to be continued, or `(false, state)` if the fold has to be stopped
    *          and the rest of the values to be ignored.
    */
  final def foldWhileL[S](seed: => S)(f: (S, A) => (Boolean, S))
    (implicit F: MonadError[F, Throwable]): F[S] = {

    this match {
      case Empty() =>
        try F.pure(seed) catch { case NonFatal(ex) => F.raiseError(ex) }
      case Error(ex) => F.raiseError(ex)
      case Wait(next) =>
        F.flatMap(next)(_.foldWhileL(seed)(f))
      case NextEl(a, next) =>
        try {
          val (continue, state) = f(seed, a)
          if (!continue) F.pure(state) else
            F.flatMap(next)(_.foldWhileL(state)(f))
        } catch {
          case NonFatal(ex) => F.raiseError(ex)
        }
      case NextSeq(list, next) =>
        try {
          var continue = true
          var state = seed
          val iter = list.iterator

          while (continue && iter.hasNext) {
            val (c,s) = f(state, iter.next())
            state = s
            continue = c
          }

          if (!continue) F.pure(state) else
            F.flatMap(next)(_.foldWhileL(state)(f))
        }
        catch {
          case NonFatal(ex) => F.raiseError(ex)
        }
    }
  }

  /** Right associative lazy fold on stream using the
    * folding function 'f'.
    *
    * This method evaluates `lb` lazily (in some cases it will not be
    * needed), and returns a lazy value. We are using `(A, Eval[B]) =>
    * Eval[B]` to support laziness in a stack-safe way. Chained
    * computation should be performed via .map and .flatMap.
    *
    * For more detailed information about how this method works see the
    * documentation for `Eval[_]`.
    */
  final def foldRightL[B](lb: F[B])(f: (A, F[B]) => F[B])
    (implicit F: MonadError[F, Throwable]): F[B] = {

    this match {
      case Empty() => lb
      case Error(ex) => F.raiseError(ex)
      case Wait(next) =>
        F.flatMap(next)(_.foldRightL(lb)(f))
      case NextEl(a, next) =>
        f(a, F.flatMap(next)(_.foldRightL(lb)(f)))
      case NextSeq(list, next) =>
        if (list.isEmpty) F.flatMap(next)(_.foldRightL(lb)(f))
        else {
          val a = list.head
          val tail = list.tail
          val rest = F.pure(NextSeq[F,A](tail, next))
          f(a, F.flatMap(rest)(_.foldRightL(lb)(f)))
        }
    }
  }

  /** Find the first element matching the predicate, if one exists. */
  final def findL[B >: A](p: B => Boolean)
    (implicit F: MonadError[F,Throwable]): F[Option[B]] =
    foldWhileL(Option.empty[B])((s,a) => if (p(a)) (false, Some(a)) else (true, s))

  /** Count the total number of elements. */
  final def countL(implicit F: MonadError[F,Throwable]): F[Long] =
    foldLeftL(0L)((acc,_) => acc + 1)

  /** Given a sequence of numbers, calculates a sum. */
  final def sumL[B >: A](implicit B: Numeric[B], F: MonadError[F, Throwable]): F[B] =
    foldLeftL(B.zero)(B.plus)

  /** Check whether at least one element satisfies the predicate. */
  final def existsL(p: A => Boolean)
    (implicit F: MonadError[F,Throwable]): F[Boolean] =
    foldWhileL(false)((s,a) => if (p(a)) (false, true) else (true, s))

  /** Check whether all elements satisfy the predicate. */
  final def forallL(p: A => Boolean)
    (implicit F: MonadError[F,Throwable]): F[Boolean] =
    foldWhileL(true)((s,a) => if (!p(a)) (false, false) else (true, s))

  /** Aggregates elements in a `List` and preserves order. */
  final def toListL[B >: A](implicit F: MonadError[F,Throwable]): F[List[B]] = {
    val folded = foldLeftL(mutable.ListBuffer.empty[A]) { (acc, a) => acc += a }
    F.map(folded)(_.toList)
  }

  /** Returns true if there are no elements, false otherwise. */
  final def isEmptyL(implicit F: MonadError[F,Throwable]): F[Boolean] =
    foldWhileL(true)((_,_) => (false, false))

  /** Returns true if there are elements, false otherwise. */
  final def nonEmptyL(implicit F: MonadError[F,Throwable]): F[Boolean] =
    foldWhileL(false)((_,_) => (false, true))

  /** Returns the first element in the iterable, as an option. */
  final def headL[B >: A](implicit F: MonadError[F,Throwable]): F[Option[B]] =
    this match {
      case Wait(next) => F.flatMap(next)(_.headL)
      case Empty() => F.pure(None)
      case Error(ex) => F.raiseError(ex)
      case NextEl(a, _) => F.pure(Some(a))
      case NextSeq(list, _) => F.pure(list.headOption)
    }

  /** Alias for [[headL]]. */
  final def firstL[B >: A](implicit F: MonadError[F,Throwable]): F[Option[B]] = headL

  /** Returns a new sequence that will take a maximum of
    * `n` elements from the start of the source sequence.
    */
  final def take(n: Int)(implicit F: Applicative[F]): Enumerator[F,A] =
    if (n <= 0) Empty() else this match {
      case Wait(next) => Wait[F,A](F.map(next)(_.take(n)))
      case empty @ Empty() => empty
      case error @ Error(_) => error
      case NextEl(a, next) =>
        if (n - 1 > 0)
          NextEl[F,A](a, F.map(next)(_.take(n-1)))
        else
          NextEl[F,A](a, F.pure(Empty()))

      case NextSeq(list, rest) =>
        val length = list.length
        if (length == n)
          NextSeq[F,A](list, F.pure(Empty()))
        else if (length < n)
          NextSeq[F,A](list, F.map(rest)(_.take(n-length)))
        else
          NextSeq[F,A](list.take(n), F.pure(Empty()))
    }

  /** Returns a new sequence that will take elements from
    * the start of the source sequence, for as long as the given
    * function `f` returns `true` and then stop.
    */
  final def takeWhile(p: A => Boolean)(implicit F: Applicative[F]): Enumerator[F,A] =
    this match {
      case Wait(next) => Wait[F,A](F.map(next)(_.takeWhile(p)))
      case empty @ Empty() => empty
      case error @ Error(_) => error
      case NextEl(a, next) =>
        try { if (p(a)) NextEl[F,A](a, F.map(next)(_.takeWhile(p))) else Empty() }
        catch { case NonFatal(ex) => Error(ex) }
      case NextSeq(list, rest) =>
        try {
          val filtered = list.takeWhile(p)
          if (filtered.length < list.length)
            NextSeq[F,A](filtered, F.pure(Empty()))
          else
            NextSeq[F,A](filtered, F.map(rest)(_.takeWhile(p)))
        } catch {
          case NonFatal(ex) => Error(ex)
        }
    }

  /** Recovers from potential errors by mapping them to other
    * async iterators using the provided function.
    */
  final def onErrorHandleWith[B >: A](f: Throwable => Enumerator[F,B])
    (implicit F: MonadError[F,Throwable]): Enumerator[F,B] = {

    this match {
      case empty @ Empty() => empty
      case Wait(next) => Wait[F,B](F.map(next)(_.onErrorHandleWith(f)))
      case NextEl(a, next) => NextEl[F,B](a, F.map(next)(_.onErrorHandleWith(f)))
      case NextSeq(seq, next) => NextSeq[F,B](seq, F.map(next)(_.onErrorHandleWith(f)))
      case Error(ex) => try f(ex) catch { case NonFatal(err) => Error(err) }
    }
  }

  /** Recovers from potential errors by mapping them to other
    * async iterators using the provided function.
    */
  final def onErrorRecoverWith[B >: A](pf: PartialFunction[Throwable, Enumerator[F,B]])
    (implicit F: MonadError[F,Throwable]): Enumerator[F,B] =
    onErrorHandleWith {
      case ex if pf.isDefinedAt(ex) => pf(ex)
      case other => Error(other)
    }

  /** Recovers from potential errors by mapping them to
    * a final element using the provided function.
    */
  final def onErrorHandle[B >: A](f: Throwable => B)
    (implicit F: MonadError[F,Throwable]): Enumerator[F,B] =
    onErrorHandleWith(ex => Enumerator.now(f(ex)))

  /** Recovers from potential errors by mapping them to
    * a final element using the provided function.
    */
  final def onErrorRecover[B >: A](pf: PartialFunction[Throwable, B])
    (implicit F: MonadError[F,Throwable]): Enumerator[F,B] =
    onErrorHandleWith {
      case ex if pf.isDefinedAt(ex) => Enumerator.now(pf(ex))
      case other => Enumerator.error(other)
    }

  /** Drops the first `n` elements, from left to right. */
  final def drop(n: Int)(implicit F: Functor[F]): Enumerator[F,A] =
    if (n <= 0) this else this match {
      case Wait(next) => Wait[F,A](F.map(next)(_.drop(n)))
      case empty @ Empty() => empty
      case error @ Error(_) => error
      case NextEl(a, next) => Wait[F,A](F.map(next)(_.drop(n-1)))
      case NextSeq(list, rest) =>
        val length = list.length
        if (length == n)
          Wait[F,A](rest)
        else if (length > n)
          NextSeq[F,A](list.drop(n), rest)
        else
          Wait[F,A](F.map(rest)(_.drop(n - length)))
    }

  /** Triggers memoization of the iterable on the first traversal,
    * such that results will get reused on subsequent traversals.
    */
  final def memoize(implicit F: Deferrable[F]): Enumerator[F,A] =
    this match {
      case Wait(next) => Wait[F,A](F.map(F.memoize(next))(_.memoize))
      case ref @ (Empty() | Error(_)) => ref
      case NextEl(a, rest) => NextEl[F,A](a, F.map(F.memoize(rest))(_.memoize))
      case NextSeq(list, rest) => NextSeq[F,A](list, F.map(F.memoize(rest))(_.memoize))
    }

  /** Creates a new evaluable that will consume the
    * source iterator and upon completion of the source it will
    * complete with `Unit`.
    */
  final def completedL(implicit F: MonadError[F,Throwable]): F[Unit] = {
    def loop(tail: F[Enumerator[F,A]]): F[Unit] = F.flatMap(tail) {
      case NextEl(elem, rest) => loop(rest)
      case NextSeq(elems, rest) => loop(rest)
      case Wait(rest) => loop(rest)
      case Empty() => F.pure(())
      case Error(ex) => F.raiseError(ex)
    }

    loop(F.pure(this))
  }

  /** On evaluation it consumes the stream and for each element
    * execute the given function.
    */
  final def foreachL(cb: A => Unit)
    (implicit D: Deferrable[F], M: MonadError[F,Throwable]): F[Unit] = {

    def loop(tail: F[Enumerator[F,A]]): F[Unit] = M.flatMap(tail) {
      case NextEl(elem, rest) =>
        try { cb(elem); loop(rest) }
        catch { case NonFatal(ex) => M.raiseError(ex) }

      case NextSeq(elems, rest) =>
        try { elems.foreach(cb); loop(rest) }
        catch { case NonFatal(ex) => M.raiseError(ex) }

      case Wait(rest) => loop(D.defer(rest))
      case Empty() => D.unit
      case Error(ex) => M.raiseError(ex)
    }

    loop(D.pure(this))
  }
}

object Enumerator {
  /** Lifts a strict value into an stream */
  def now[F[_],A](a: A)(implicit F: Applicative[F]): Enumerator[F,A] =
    NextEl[F,A](a, F.pure(empty[F,A]))

  /** Builder for an [[Error]] state. */
  def error[F[_],A](ex: Throwable): Enumerator[F,A] = Error[F](ex)

  /** Builder for an [[Empty]] state. */
  def empty[F[_],A]: Enumerator[F,A] = Empty[F]()

  /** Builder for a [[Wait]] iterator state. */
  def wait[F[_],A](rest: F[Enumerator[F,A]]): Enumerator[F,A] = Wait[F,A](rest)

  /** Builds a [[NextEl]] iterator state. */
  def nextEl[F[_],A](head: A, rest: F[Enumerator[F,A]]): Enumerator[F,A] =
    NextEl[F,A](head, rest)

  /** Builds a [[NextSeq]] iterator state. */
  def nextSeq[F[_],A](headSeq: LinearSeq[A], rest: F[Enumerator[F,A]]): Enumerator[F,A] =
    NextSeq[F,A](headSeq, rest)

  /** Lifts a strict value into an stream */
  def evalAlways[F[_],A](a: => A)(implicit F: Deferrable[F]): Enumerator[F,A] =
    Wait[F,A](F.evalAlways {
      try NextEl[F,A](a, F.pure(Empty[F]())) catch {
        case NonFatal(ex) => Error[F](ex)
      }
    })

  /** Lifts a strict value into an stream and
    * memoizes the result for subsequent executions.
    */
  def evalOnce[F[_],A](a: => A)(implicit F: Deferrable[F]): Enumerator[F,A] =
    Wait[F,A](F.evalOnce {
      try NextEl[F,A](a, F.now(Empty[F]())) catch {
        case NonFatal(ex) => Error[F](ex)
      }
    })

  /** Promote a non-strict value representing a stream
    * to a stream of the same type.
    */
  def defer[F[_],A](fa: => Enumerator[F,A])(implicit F: Deferrable[F]): Wait[F,A] =
    Wait[F,A](F.defer(F.evalAlways(fa)))

  /** Generates a range between `from` (inclusive) and `until` (exclusive),
    * with `step` as the increment.
    */
  def range[F[_]](from: Long, until: Long, step: Long = 1L)
    (implicit F: Deferrable[F]): Enumerator[F,Long] = {

    def loop(cursor: Long): Enumerator[F,Long] = {
      val isInRange = (step > 0 && cursor < until) || (step < 0 && cursor > until)
      val nextCursor = cursor + step
      if (!isInRange) Empty[F]() else
        NextEl[F,Long](cursor, F.evalAlways(loop(nextCursor)))
    }

    Wait[F,Long](F.evalAlways(loop(from)))
  }

  /** Converts any sequence into an async iterable.
    *
    * Because the list is a linear sequence that's known
    * (but not necessarily strict), we'll just return
    * a strict state.
    */
  def fromList[F[_],A](list: immutable.LinearSeq[A], batchSize: Int)
    (implicit F: Deferrable[F]): F[Enumerator[F,A]] = {

    if (list.isEmpty) F.now(Empty()) else F.now {
      val (first, rest) = list.splitAt(batchSize)
      NextSeq[F,A](first, F.defer(fromList(rest, batchSize)))
    }
  }

  /** Converts an iterable into an async iterator. */
  def fromIterable[F[_],A](iterable: Iterable[A], batchSize: Int)
    (implicit F: Deferrable[F], M: Monad[F]): F[Enumerator[F,A]] =
    M.flatMap(F.now(iterable)) { iter => fromIterator(iter.iterator, batchSize) }

  /** Converts an iterable into an async iterator. */
  def fromIterable[F[_],A](iterable: java.lang.Iterable[A], batchSize: Int)
    (implicit F: Deferrable[F], M: Monad[F]): F[Enumerator[F,A]] =
    M.flatMap(F.now(iterable)) { iter => fromIterator(iter.iterator, batchSize) }

  /** Converts a `scala.collection.Iterator` into an async iterator. */
  def fromIterator[F[_],A](iterator: scala.collection.Iterator[A], batchSize: Int)
    (implicit F: Deferrable[F]): F[Enumerator[F,A]] = {

    F.evalOnce {
      try {
        val buffer = mutable.ListBuffer.empty[A]
        var processed = 0
        while (processed < batchSize && iterator.hasNext) {
          buffer += iterator.next()
          processed += 1
        }

        if (processed == 0) Empty()
        else if (processed == 1)
          NextEl[F,A](buffer.head, fromIterator(iterator, batchSize))
        else
          NextSeq[F,A](buffer.toList, fromIterator(iterator, batchSize))
      } catch {
        case NonFatal(ex) =>
          Error(ex)
      }
    }
  }

  /** Converts a `java.util.Iterator` into an async iterator. */
  def fromIterator[F[_],A](iterator: java.util.Iterator[A], batchSize: Int)
    (implicit F: Deferrable[F]): F[Enumerator[F,A]] = {

    F.evalOnce {
      try {
        val buffer = mutable.ListBuffer.empty[A]
        var processed = 0
        while (processed < batchSize && iterator.hasNext) {
          buffer += iterator.next()
          processed += 1
        }

        if (processed == 0) Empty()
        else if (processed == 1)
          NextEl[F,A](buffer.head, fromIterator(iterator, batchSize))
        else
          NextSeq[F,A](buffer.toList, fromIterator(iterator, batchSize))
      } catch {
        case NonFatal(ex) =>
          Error(ex)
      }
    }
  }

  /** A state of the [[Enumerator]] representing a deferred iterator. */
  final case class Wait[F[_],A](next: F[Enumerator[F,A]])
    extends Enumerator[F,A]

  /** A state of the [[Enumerator]] representing a head/tail decomposition.
    *
    * @param head is the next element to be processed
    * @param tail is the next state in the sequence
    */
  final case class NextEl[F[_],A](head: A, tail: F[Enumerator[F,A]])
    extends Enumerator[F,A]

  /** A state of the [[Enumerator]] representing a head/tail decomposition.
    *
    * Like [[NextEl]] except that the head is a strict sequence
    * of elements that don't need asynchronous execution.
    * Meant for doing buffering.
    *
    * @param headSeq is a sequence of the next elements to be processed, can be empty
    * @param tail is the next state in the sequence
    */
  final case class NextSeq[F[_],A](headSeq: LinearSeq[A], tail: F[Enumerator[F,A]])
    extends Enumerator[F,A]

  /** Represents an error state in the iterator.
    *
    * This is a final state. When this state is received, the data-source
    * should have been canceled already.
    *
    * @param ex is an error that was thrown.
    */
  final case class Error[F[_]](ex: Throwable) extends Enumerator[F,Nothing]

  /** Represents an empty iterator.
    *
    * Received as a final state in the iteration process.
    * When this state is received, the data-source should have
    * been canceled already.
    */
  final case class Empty[F[_]]() extends Enumerator[F,Nothing]

  /** Implicit type-class instances for [[Enumerator]]. */
  implicit def typeClassInstances[F[_] : Evaluable]: TypeClassInstances[F] =
    new TypeClassInstances

  /** Type-class instances for [[Enumerator]]. */
  class TypeClassInstances[F[_]](implicit F: Evaluable[F])
    extends Streamable[({type λ[+α] = Enumerator[F,α]})#λ] {

    override def now[A](a: A) = Enumerator.now[F,A](a)
    override def evalAlways[A](f: => A) = Enumerator.evalAlways[F,A](f)
    override def defer[A](fa: => Enumerator[F,A]) = Enumerator.defer[F,A](fa)
    override def memoize[A](fa: Enumerator[F,A]) = fa.memoize
    override def evalOnce[A](f: =>A) = Enumerator.evalOnce[F,A](f)
    override val unit = Enumerator.now[F,Unit](())
    override def raiseError[A](e: Throwable): Enumerator[F,A] =
      Enumerator.error[F,A](e)

    override def combineK[A](x: Enumerator[F,A], y: Enumerator[F,A]): Enumerator[F,A] =
      x ++ y
    override def filter[A](fa: Enumerator[F,A])(f: (A) => Boolean): Enumerator[F,A] =
      fa.filter(f)
    override def filterM[A](fa: Enumerator[F,A])(f: (A) => Enumerator[F,Boolean]): Enumerator[F,A] =
      flatMap(fa)(a => flatMap(f(a))(b => if (b) pure(a) else empty[A]))
    override def flatten[A](ffa: Enumerator[F,Enumerator[F,A]]): Enumerator[F,A] =
      ffa.flatten
    override def flatMap[A, B](fa: Enumerator[F,A])(f: (A) => Enumerator[F,B]): Enumerator[F,B] =
      fa.flatMap(f)
    override def coflatMap[A, B](fa: Enumerator[F,A])(f: (Enumerator[F,A]) => B): Enumerator[F,B] =
      Enumerator.evalAlways[F,B](f(fa))
    override def empty[A]: Enumerator[F,A] =
      Enumerator.empty[F,A]
    override def handleError[A](fa: Enumerator[F,A])(f: (Throwable) => A): Enumerator[F,A] =
      fa.onErrorHandle(f)
    override def handleErrorWith[A](fa: Enumerator[F,A])(f: (Throwable) => Enumerator[F,A]): Enumerator[F,A] =
      fa.onErrorHandleWith(f)
    override def recover[A](fa: Enumerator[F,A])(pf: PartialFunction[Throwable, A]): Enumerator[F,A] =
      fa.onErrorRecover(pf)
    override def recoverWith[A](fa: Enumerator[F,A])(pf: PartialFunction[Throwable, Enumerator[F,A]]): Enumerator[F,A] =
      fa.onErrorRecoverWith(pf)
    override def map2[A, B, Z](fa: Enumerator[F,A], fb: Enumerator[F,B])(f: (A, B) => Z): Enumerator[F,Z] =
      for (a <- fa; b <- fb) yield f(a,b)
    override def ap[A, B](fa: Enumerator[F,A])(ff: Enumerator[F,(A) => B]): Enumerator[F,B] =
      for (f <- ff; a <- fa) yield f(a)
    override def map[A, B](fa: Enumerator[F,A])(f: (A) => B): Enumerator[F,B] =
      fa.map(f)
  }
}


