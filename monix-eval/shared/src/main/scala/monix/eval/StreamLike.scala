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

import monix.types.Deferrable
import monix.types.shims.MonadError
import scala.collection.{LinearSeq, immutable}

/** Common implementation between [[monix.eval.TaskStream]]
  * and [[monix.eval.CoevalStream]].
  */
abstract class StreamLike[+A, F[_], Self[+T] <: StreamLike[T, F, Self]]
  (implicit F: Deferrable[F] with MonadError[F,Throwable]) { self: Self[A] =>

  def source: Enumerator[F,A]
  protected def transform[B](f: Enumerator[F,A] => Enumerator[F,B]): Self[B]

  /** Filters the iterator by the given predicate function,
    * returning only those elements that match.
    */
  final def filter(p: A => Boolean): Self[A] =
    transform(_.filter(p))

  /** Returns a new iterable by mapping the supplied function
    * over the elements of the source.
    */
  final def map[B](f: A => B): Self[B] =
    transform(_.map(f))

  /** Applies the function to the elements of the source
    * and concatenates the results.
    */
  final def flatMap[B](f: A => Self[B]): Self[B] =
    transform(_.flatMap(a => f(a).source))

  /** If the source is an async iterable generator, then
    * concatenates the generated async iterables.
    */
  final def flatten[B](implicit ev: A <:< Self[B]): Self[B] =
    flatMap(x => x)

  /** Alias for [[flatMap]]. */
  final def concatMap[B](f: A => Self[B]): Self[B] =
    flatMap(f)

  /** Alias for [[concat]]. */
  final def concat[B](implicit ev: A <:< Self[B]): Self[B] =
    flatten

  /** Appends the given iterable to the end of the source,
    * effectively concatenating them.
    */
  final def ++[B >: A](rhs: Self[B]): Self[B] =
    transform(_ ++ rhs.source)

  /** Left associative fold using the function 'f'.
    *
    * On execution the iterable will be traversed from left to right,
    * and the given function will be called with the prior result,
    * accumulating state until the end, when the summary is returned.
    */
  final def foldLeftL[S](seed: => S)(f: (S,A) => S): F[S] =
    source.foldLeftL(seed)(f)

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
  final def foldWhileL[S](seed: => S)(f: (S, A) => (Boolean, S)): F[S] =
    source.foldWhileL(seed)(f)

  /** Right associative lazy fold on `Self` using the
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
  final def foldRightL[B](lb: F[B])(f: (A, F[B]) => F[B]): F[B] =
    source.foldRightL(lb)(f)

  /** Find the first element matching the predicate, if one exists. */
  final def findL[B >: A](p: B => Boolean): F[Option[B]] =
    source.findL(p)

  /** Count the total number of elements. */
  final def countL: F[Long] =
    source.countL

  /** Given a sequence of numbers, calculates a sum. */
  final def sumL[B >: A](implicit B: Numeric[B]): F[B] =
    source.sumL

  /** Check whether at least one element satisfies the predicate. */
  final def existsL(p: A => Boolean): F[Boolean] =
    source.existsL(p)

  /** Check whether all elements satisfy the predicate. */
  final def forallL(p: A => Boolean): F[Boolean] =
    source.forallL(p)

  /** Aggregates elements in a `List` and preserves order. */
  def toListL[B >: A]: F[List[B]] =
    source.toListL

  /** Returns true if there are no elements, false otherwise. */
  final def isEmptyL: F[Boolean] =
    source.isEmptyL

  /** Returns true if there are elements, false otherwise. */
  final def nonEmptyL: F[Boolean] =
    source.nonEmptyL

  /** Returns the first element in the iterable, as an option. */
  final def headL[B >: A]: F[Option[B]] =
    source.headL

  /** Alias for [[headL]]. */
  final def firstL[B >: A]: F[Option[B]] =
    source.firstL

  /** Returns a new sequence that will take a maximum of
    * `n` elements from the start of the source sequence.
    */
  final def take(n: Int): Self[A] =
    transform(_.take(n))

  /** Returns a new sequence that will take elements from
    * the start of the source sequence, for as long as the given
    * function `f` returns `true` and then stop.
    */
  final def takeWhile(p: A => Boolean): Self[A] =
    transform(_.takeWhile(p))

  /** Recovers from potential errors by mapping them to other
    * async iterators using the provided function.
    */
  final def onErrorHandleWith[B >: A](f: Throwable => Self[B]): Self[B] =
    transform(_.onErrorHandleWith(ex => f(ex).source))

  /** Recovers from potential errors by mapping them to other
    * async iterators using the provided function.
    */
  final def onErrorRecoverWith[B >: A](pf: PartialFunction[Throwable, Self[B]]): Self[B] =
    transform(_.onErrorRecoverWith { case ex if pf.isDefinedAt(ex) => pf(ex).source })

  /** Recovers from potential errors by mapping them to
    * a final element using the provided function.
    */
  final def onErrorHandle[B >: A](f: Throwable => B): Self[B] =
    transform(_.onErrorHandle(f))

  /** Recovers from potential errors by mapping them to
    * a final element using the provided function.
    */
  final def onErrorRecover[B >: A](pf: PartialFunction[Throwable, B]): Self[B] =
    transform(_.onErrorRecover(pf))

  /** Drops the first `n` elements, from left to right. */
  final def drop(n: Int): Self[A] =
    transform(_.drop(n))

  /** Triggers memoization of the iterable on the first traversal,
    * such that results will get reused on subsequent traversals.
    */
  final def memoize: Self[A] =
    transform(_.memoize)

  /** Creates a new iterator that will consume the
    * source iterator and upon completion of the source it will
    * complete with `Unit`.
    */
  final def completedL: F[Unit] =
    source.completedL

  /** On evaluation it consumes the stream and for each element
    * execute the given function.
    */
  final def foreachL(cb: A => Unit): F[Unit] =
    source.foreachL(cb)
}

abstract class StreamLikeBuilders[F[_], Self[+T] <: StreamLike[T, F, Self]]
  (implicit F: Deferrable[F] with MonadError[F,Throwable]) {

  /** Lifts a [[Enumerator]] into an iterator. */
  def fromEnumerator[A](stream: Enumerator[F,A]): Self[A]

  /** Lifts a strict value into an stream */
  def now[A](a: A): Self[A] =
    fromEnumerator(Enumerator.now[F,A](a))

  /** Builder for an error state. */
  def error[A](ex: Throwable): Self[A] =
    fromEnumerator(Enumerator.error[F,A](ex))

  /** Builder for an empty state. */
  def empty[A]: Self[A] =
    fromEnumerator(Enumerator.empty[F,A])

  /** Builder for a wait iterator state. */
  def wait[A](rest: F[Self[A]]): Self[A] =
    fromEnumerator(Enumerator.Wait[F,A](F.map(rest)(_.source)))

  /** Builds a next iterator state. */
  def nextEl[A](head: A, rest: F[Self[A]]): Self[A] =
    fromEnumerator(Enumerator.nextEl[F,A](head, F.map(rest)(_.source)))

  /** Builds a next iterator state. */
  def nextSeq[A](headSeq: LinearSeq[A], rest: F[Self[A]]): Self[A] =
    fromEnumerator(Enumerator.nextSeq[F,A](headSeq, F.map(rest)(_.source)))

  /** Lifts a strict value into an stream */
  def evalAlways[A](a: => A): Self[A] =
    fromEnumerator(Enumerator.evalAlways(a))

  /** Lifts a strict value into an stream and
    * memoizes the result for subsequent executions.
    */
  def evalOnce[A](a: => A): Self[A] =
    fromEnumerator(Enumerator.evalAlways[F,A](a))

  /** Promote a non-strict value representing a stream
    * to a stream of the same type.
    */
  def defer[A](fa: => Self[A]): Self[A] =
    fromEnumerator(Enumerator.defer[F,A](fa.source))

  /** Generates a range between `from` (inclusive) and `until` (exclusive),
    * with `step` as the increment.
    */
  def range(from: Long, until: Long, step: Long = 1L): Self[Long] =
    fromEnumerator(Enumerator.range[F](from,until,step))

  /** Converts any sequence into an async iterable.
    *
    * Because the list is a linear sequence that's known
    * (but not necessarily strict), we'll just return
    * a strict state.
    */
  def fromList[A](list: immutable.LinearSeq[A], batchSize: Int): F[Self[A]] =
    F.map(Enumerator.fromList[F,A](list, batchSize))(fromEnumerator)

  /** Converts an iterable into an async iterator. */
  def fromIterable[A](iterable: Iterable[A], batchSize: Int): F[Self[A]] =
    F.map(Enumerator.fromIterable[F,A](iterable, batchSize))(fromEnumerator)

  /** Converts an iterable into an async iterator. */
  def fromIterable[A](iterable: java.lang.Iterable[A], batchSize: Int): F[Self[A]] =
    F.map(Enumerator.fromIterable[F,A](iterable, batchSize))(fromEnumerator)

  /** Converts a `scala.collection.Iterator` into an async iterator. */
  def fromIterator[A](iterator: scala.collection.Iterator[A], batchSize: Int): F[Self[A]] =
    F.map(Enumerator.fromIterator[F,A](iterator, batchSize))(fromEnumerator)

  /** Converts a `java.util.Iterator` into an async iterator. */
  def fromIterator[A](iterator: java.util.Iterator[A], batchSize: Int): F[Self[A]] =
    F.map(Enumerator.fromIterator[F,A](iterator, batchSize))(fromEnumerator)
}