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

import monix.eval.Enumerator.Halt
import monix.execution.internal.Platform
import monix.types.{Evaluable, Streamable}

import scala.collection.{LinearSeq, immutable}

/** A template for stream-like types based on [[Enumerator]].
  *
  * Wraps an [[Enumerator]] instance into a class type that has
  * a backed-in evaluation model, no longer exposing higher-kinded
  * types or type-class usage.
  *
  * This type is not meant for providing polymorphic behavior, but
  * for sharing implementation between types such as
  * [[TaskStream]] and [[CoevalStream]].
  *
  * @tparam A is the type of the elements emitted by the stream
  * @tparam Self is the type of the inheriting subtype
  * @tparam F is the monadic type that handles evaluation in the
  *         [[Enumerator]] implementation (e.g. [[Task]], [[Coeval]])
  */
abstract class StreamLike[+A, F[_], Self[+T] <: StreamLike[T, F, Self]]
  (implicit F: Evaluable[F])
  extends Product with Serializable { self: Self[A] =>

  /** Returns the underlying [[Enumerator]] that handles this stream. */
  def enumerator: Enumerator[F,A]

  /** Given a mapping function from one [[Enumerator]] to another,
    * applies it and returns a new stream based on the result.
    *
    * Must be implemented by inheriting subtypes.
    */
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
    transform(_.flatMap(a => f(a).enumerator))

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

  /** Prepends an element to the stream. */
  final def #::[B >: A](head: B): Self[B] =
    transform(enum => head #:: enum)

  /** Appends the given iterable to the end of the source,
    * effectively concatenating them.
    */
  final def ++[B >: A](rhs: Self[B]): Self[B] =
    transform(_ ++ rhs.enumerator)

  /** Left associative fold using the function 'f'.
    *
    * On execution the iterable will be traversed from left to right,
    * and the given function will be called with the prior result,
    * accumulating state until the end, when the summary is returned.
    */
  final def foldLeftL[S](seed: => S)(f: (S,A) => S): F[S] =
    enumerator.foldLeftL(seed)(f)

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
    enumerator.foldWhileL(seed)(f)

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
    enumerator.foldRightL(lb)(f)

  /** Find the first element matching the predicate, if one exists. */
  final def findL[B >: A](p: B => Boolean): F[Option[B]] =
    enumerator.findL(p)

  /** Count the total number of elements. */
  final def countL: F[Long] =
    enumerator.countL

  /** Given a sequence of numbers, calculates a sum. */
  final def sumL[B >: A](implicit B: Numeric[B]): F[B] =
    enumerator.sumL

  /** Check whether at least one element satisfies the predicate. */
  final def existsL(p: A => Boolean): F[Boolean] =
    enumerator.existsL(p)

  /** Check whether all elements satisfy the predicate. */
  final def forallL(p: A => Boolean): F[Boolean] =
    enumerator.forallL(p)

  /** Aggregates elements in a `List` and preserves order. */
  def toListL[B >: A]: F[List[B]] =
    enumerator.toListL

  /** Returns true if there are no elements, false otherwise. */
  final def isEmptyL: F[Boolean] =
    enumerator.isEmptyL

  /** Returns true if there are elements, false otherwise. */
  final def nonEmptyL: F[Boolean] =
    enumerator.nonEmptyL

  /** Returns the first element in the iterable.
    *
    * Halts with a `NoSuchElemException` if the stream is empty.
    */
  final def headL[B >: A]: F[B] =
    enumerator.headL

  /** Returns the first element in the iterable, as an option. */
  final def headOptionL[B >: A]: F[Option[B]] =
    enumerator.headOptionL

  /** Alias for [[headOptionL]]. */
  final def firstL[B >: A]: F[Option[B]] =
    enumerator.firstL

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
    * streams using the provided function.
    */
  final def onErrorHandleWith[B >: A](f: Throwable => Self[B]): Self[B] =
    transform(_.onErrorHandleWith(ex => f(ex).enumerator))

  /** Recovers from potential errors by mapping them to other
    * streams using the provided function.
    */
  final def onErrorRecoverWith[B >: A](pf: PartialFunction[Throwable, Self[B]]): Self[B] =
    transform(_.onErrorRecoverWith { case ex if pf.isDefinedAt(ex) => pf(ex).enumerator })

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
    enumerator.completedL

  /** On evaluation it consumes the stream and for each element
    * execute the given function.
    */
  final def foreachL(cb: A => Unit): F[Unit] =
    enumerator.foreachL(cb)
}

/** A template for companion objects of [[StreamLike]] subtypes.
  *
  * This type is not meant for providing polymorphic behavior, but
  * for sharing implementation between types such as
  * [[TaskStream]] and [[CoevalStream]].
  *
  * @tparam Self is the type of the inheriting subtype
  * @tparam F is the monadic type that handles evaluation in the
  *         [[Enumerator]] implementation (e.g. [[Task]], [[Coeval]])
  */
abstract class StreamLikeBuilders[F[_], Self[+T] <: StreamLike[T, F, Self]]
  (implicit F: Evaluable[F]) { self =>

  /** Lifts an [[Enumerator]] into an iterator. */
  def fromEnumerator[A](stream: Enumerator[F,A]): Self[A]

  /** Builds a stream out of the given elements. */
  def apply[A](elems: A*): Self[A] =
    fromIterable(elems, Platform.recommendedBatchSize)

  /** Lifts a strict value into a stream */
  def now[A](a: A): Self[A] =
    fromEnumerator(Enumerator.now[F,A](a))

  /** Builder for an error state. */
  def raiseError[A](ex: Throwable): Self[A] =
    fromEnumerator(Enumerator.raiseError[F,A](ex))

  /** Reusable empty reference. */
  private[this] val emptyRef: Self[Nothing] =
    fromEnumerator(Enumerator.empty[F, Nothing])

  /** Builder for an empty state. */
  def empty[A]: Self[A] = emptyRef

  /** Lifts a strict value into a stream */
  def evalAlways[A](a: => A): Self[A] =
    fromEnumerator(Enumerator.evalAlways(a))

  /** Lifts a strict value into a stream and
    * memoizes the result for subsequent executions.
    */
  def evalOnce[A](a: => A): Self[A] =
    fromEnumerator(Enumerator.evalAlways[F,A](a))

  /** Promote a non-strict value representing a stream
    * to a stream of the same type.
    */
  def defer[A](fa: => Self[A]): Self[A] =
    fromEnumerator(Enumerator.defer[F,A](fa.enumerator))

  /** Returns a stream representing a cons pair between a
    * strict `head` and a possibly lazy `tail`.
    *
    * @param head is the current element to be signaled
    * @param tail is the rest of the stream
    */
  def cons[A](head: A, tail: F[Self[A]]): Self[A] =
    fromEnumerator(Enumerator.cons[F,A](head, F.map(tail)(_.enumerator)))

  /** Returns a stream representing a cons pair between a
    * strict `headSeq` sequence and a possibly lazy `tail`.
    *
    * @param headSeq is the current sequence of the elements to signaled
    * @param tail is the rest of the stream
    */
  def consSeq[A](headSeq: LinearSeq[A], tail: F[Self[A]]): Self[A] =
    fromEnumerator(Enumerator.consSeq[F,A](headSeq, F.map(tail)(_.enumerator)))

  /** Returns an empty stream that can optionally signal
    * an end error.
    *
    * @param ex is an optional exception that, in case it is
    *        present, it means that the streaming ended in error.
    */
  def halt[A](ex: Option[Throwable]): Self[A] =
    fromEnumerator(Halt(ex))

  /** Generates a range between `from` (inclusive) and `until` (exclusive),
    * with `step` as the increment.
    */
  def range(from: Long, until: Long, step: Long = 1L): Self[Long] =
    fromEnumerator(Enumerator.range[F](from,until,step))

  /** Converts any immutable list into a stream.
    *
    * @param list is the list to convert to a stream
    */
  def fromList[A](list: immutable.LinearSeq[A]): Self[A] =
    fromEnumerator(Enumerator.fromList[F,A](list))

  /** Converts any immutable list into a stream.
    *
    * This version of `fromList` produces batches of elements,
    * instead of single elements, potentially making it more efficient.
    *
    * @param list is the list to convert to a stream
    * @param batchSize specifies the maximum batch size
    */
  def fromList[A](list: immutable.LinearSeq[A], batchSize: Int): Self[A] =
    fromEnumerator(Enumerator.fromList[F,A](list, batchSize))

  /** Converts an iterable into a stream. */
  def fromIterable[A](iterable: Iterable[A], batchSize: Int): Self[A] =
    fromEnumerator(Enumerator.fromIterable[F,A](iterable, batchSize))

  /** Converts an iterable into a stream. */
  def fromIterable[A](iterable: java.lang.Iterable[A], batchSize: Int): Self[A] =
    fromEnumerator(Enumerator.fromIterable[F,A](iterable, batchSize))

  /** Converts a `scala.collection.Iterator` into a stream. */
  def fromIterator[A](iterator: scala.collection.Iterator[A], batchSize: Int): F[Self[A]] =
    F.map(Enumerator.fromIterator[F,A](iterator, batchSize))(fromEnumerator)

  /** Converts a `java.util.Iterator` into a stream. */
  def fromIterator[A](iterator: java.util.Iterator[A], batchSize: Int): F[Self[A]] =
    F.map(Enumerator.fromIterator[F,A](iterator, batchSize))(fromEnumerator)

  /** Creates a stream that on evaluation repeats the
    * given elements, ad infinitum.
    *
    * Alias for [[repeat]].
    */
  def continually[A](elems: A*): Self[A] =
    fromEnumerator(Enumerator.continually(elems:_*))

  /** Creates a stream that on evaluation repeats the
    * given elements, ad infinitum.
    */
  def repeat[A](elems: A*): Self[A] =
    fromEnumerator(Enumerator.repeat(elems:_*))

  /** Implicit type-class instances. */
  implicit val typeClassInstances: TypeClassInstances =
    new TypeClassInstances

  /** Type-class instances definition. */
  class TypeClassInstances extends Streamable[Self] {
    override def now[A](a: A) = self.now[A](a)
    override def raiseError[A](e: Throwable): Self[A] = self.raiseError[A](e)
    override def evalAlways[A](f: => A) = self.evalAlways[A](f)
    override def defer[A](fa: => Self[A]) = self.defer[A](fa)
    override def memoize[A](fa: Self[A]) = fa.memoize
    override def evalOnce[A](f: =>A) = self.evalOnce[A](f)
    override val unit = self.now[Unit](())

    override def combineK[A](x: Self[A], y: Self[A]): Self[A] =
      x ++ y
    override def filter[A](fa: Self[A])(f: (A) => Boolean): Self[A] =
      fa.filter(f)
    override def filterM[A](fa: Self[A])(f: (A) => Self[Boolean]): Self[A] =
      flatMap(fa)(a => flatMap(f(a))(b => if (b) pure(a) else empty[A]))
    override def flatten[A](ffa: Self[Self[A]]): Self[A] =
      ffa.flatten
    override def flatMap[A, B](fa: Self[A])(f: (A) => Self[B]): Self[B] =
      fa.flatMap(f)
    override def coflatMap[A, B](fa: Self[A])(f: (Self[A]) => B): Self[B] =
      self.evalAlways[B](f(fa))
    override def empty[A]: Self[A] =
      self.empty[A]
    override def handleError[A](fa: Self[A])(f: (Throwable) => A): Self[A] =
      fa.onErrorHandle(f)
    override def handleErrorWith[A](fa: Self[A])(f: (Throwable) => Self[A]): Self[A] =
      fa.onErrorHandleWith(f)
    override def recover[A](fa: Self[A])(pf: PartialFunction[Throwable, A]): Self[A] =
      fa.onErrorRecover(pf)
    override def recoverWith[A](fa: Self[A])(pf: PartialFunction[Throwable, Self[A]]): Self[A] =
      fa.onErrorRecoverWith(pf)
    override def map2[A, B, Z](fa: Self[A], fb: Self[B])(f: (A, B) => Z): Self[Z] =
      for (a <- fa; b <- fb) yield f(a,b)
    override def ap[A, B](fa: Self[A])(ff: Self[(A) => B]): Self[B] =
      for (f <- ff; a <- fa) yield f(a)
    override def map[A, B](fa: Self[A])(f: (A) => B): Self[B] =
      fa.map(f)
  }
}