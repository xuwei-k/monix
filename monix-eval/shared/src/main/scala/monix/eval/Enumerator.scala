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
import monix.types.shims.{Applicative, Functor, MonadError}
import monix.types.{Deferrable, Evaluable, Streamable}
import scala.collection.{LinearSeq, immutable, mutable}
import scala.util.control.NonFatal

/** The `Enumerator` is a type that models lazy streaming.
  *
  * Consumption of `Enumerator` happens typically in a loop where
  * the current step represents either a signal that the stream
  * is over, or a head/tail pair.
  *
  * The type is a ADT that can describe the following states:
  *
  *  - [[monix.eval.Enumerator.Cons Cons]] which signals a
  *     single element and a tail
  *  - [[monix.eval.Enumerator.ConsSeq ConsSeq]] signaling a
  *    whole batch of elements, as an optimization, along
  *    with the tail that can be further traversed
  *  - [[monix.eval.Enumerator.Halt Halt]] represents an empty
  *    stream, signaling the end, either in success or in error
  *
  * The `Enumerator` accepts as type parameter an `F` monadic type that
  * is used to control how evaluation happens. For example you can
  * use [[Task]], in which case the streaming can have asynchronous
  * behavior, or you can use [[Coeval]] in which case it can behave
  * like a normal, synchronous `Iterable`.
  *
  * As restriction, the type used must be stack safe in `map` and `flatMap`.
  *
  * This library also exposes alternative [[StreamLike]] types, such as
  * [[TaskStream]] and [[CoevalStream]]. Underlying these types the
  * [[Enumerator]] is used. The purpose of these types is easier usage,
  * because they don't expose higher-kinded types and usage of
  * type-classes. Being more concrete, they can also expose more operations,
  * as for these types the evaluation model is known.
  * Therefore consider [[Enumerator]] as a building block and prefer
  * [[TaskStream]] and [[CoevalStream]] in normal usage.
  *
  * ATTRIBUTION: this type was inspired by the `Streaming` type in the
  * Typelevel Cats library (later moved to Typelevel's Dogs :)), originally
  * committed in Cats by Erik Osheim. Several operations from `Streaming`
  * were adapted for `Enumerator`, like `flatMap`, `foldRightL` and `zipMap`.
  *
  * @tparam F is the monadic type that controls evaluation; note that it
  *         must be stack-safe in its `map` and `flatMap` operations
  *
  * @tparam A is the type of the elements produced by this enumerator
  */
sealed abstract class Enumerator[F[_],+A]
  extends Product with Serializable { self =>

  /** Filters the stream by the given predicate function,
    * returning only those elements that match.
    */
  final def filter(p: A => Boolean)(implicit F: Functor[F]): Enumerator[F,A] = {
    try this match {
      case ref @ Cons(head, tail) =>
        val rest = F.map(tail)(_.filter(p))
        if (p(head)) Cons[F,A](head, rest) else ConsSeq[F,A](Nil, rest)
      case ConsSeq(head, tail) =>
        val rest = F.map(tail)(_.filter(p))
        head.filter(p) match {
          case Nil => ConsSeq[F,A](Nil, rest)
          case filtered => ConsSeq[F,A](filtered, rest)
        }
      case empty @ Halt(_) =>
        empty
    }
    catch {
      case NonFatal(ex) => Halt(Some(ex))
    }
  }

  /** Returns a new iterable by mapping the supplied function
    * over the elements of the source.
    */
  final def map[B](f: A => B)(implicit F: Functor[F]): Enumerator[F,B] = {
    try this match {
      case Cons(head, tail) =>
        Cons[F,B](f(head), F.map(tail)(_.map(f)))
      case ConsSeq(head, rest) =>
        ConsSeq[F,B](head.map(f), F.map(rest)(_.map(f)))
      case empty @ Halt(_) =>
        empty
    }
    catch {
      case NonFatal(ex) => Halt(Some(ex))
    }
  }

  /** Applies the function to the elements of the source
    * and concatenates the results.
    */
  final def flatMap[B](f: A => Enumerator[F,B])
    (implicit F: Deferrable[F]): Enumerator[F,B] = {

    try this match {
      case Cons(head, tail) =>
        f(head) concatF F.map(tail)(_.flatMap(f))

      case ConsSeq(list, rest) =>
        if (list.isEmpty)
          ConsSeq[F,B](Nil, F.map(rest)(_.flatMap(f)))
        else
          f(list.head) concatF F.evalAlways(ConsSeq[F,A](list.tail, rest).flatMap(f))

      case empty @ Halt(_) =>
        empty
    }
    catch {
      case NonFatal(ex) => Halt(Some(ex))
    }
  }

  /** If the source is an async iterable generator, then
    * concatenates the generated enumerators.
    */
  final def flatten[B](implicit ev: A <:< Enumerator[F,B], F: Deferrable[F]): Enumerator[F,B] =
    flatMap(x => x)

  /** Alias for [[flatMap]]. */
  final def concatMap[B](f: A => Enumerator[F,B])(implicit F: Deferrable[F]): Enumerator[F,B] =
    flatMap(f)

  /** Alias for [[concat]]. */
  final def concat[B](implicit ev: A <:< Enumerator[F,B], F: Deferrable[F]): Enumerator[F,B] =
    flatten

  /** Prepends an element to the enumerator. */
  final def #::[B >: A](head: B)(implicit F: Applicative[F]): Enumerator[F,B] =
    Enumerator.cons[F,B](head, F.pure(this))

  /** Appends the given iterable to the end of the source,
    * effectively concatenating them.
    */
  final def ++[B >: A](rhs: Enumerator[F,B])
    (implicit F: Functor[F]): Enumerator[F,B] = {

    this match {
      case Cons(a, lt) =>
        Cons[F,B](a, F.map(lt)(_ ++ rhs))
      case ConsSeq(head, lt) =>
        ConsSeq[F,B](head, F.map(lt)(_ ++ rhs))
      case Halt(None) => rhs
      case error @ Halt(Some(_)) => error
    }
  }

  private final def concatF[B >: A](rhs: F[Enumerator[F,B]])
    (implicit F: Functor[F]): Enumerator[F,B] = {

    this match {
      case Cons(a, lt) =>
        Cons[F,B](a, F.map(lt)(_ concatF rhs))
      case ConsSeq(head, lt) =>
        ConsSeq[F,B](head, F.map(lt)(_ concatF rhs))
      case Halt(None) =>
        ConsSeq[F,B](Nil, rhs)
      case error @ Halt(Some(_)) =>
        error
    }
  }

  /** Left associative fold using the function 'f'.
    *
    * On execution the iterable will be traversed from left to right,
    * and the given function will be called with the prior result,
    * accumulating state until the end, when the summary is returned.
    */
  final def foldLeftL[S](seed: => S)(f: (S,A) => S)
    (implicit F: Evaluable[F]): F[S] = {

    def loop(self: Enumerator[F,A], state: S): F[S] =
      try self match {
        case Halt(None) =>
          F.pure(state)
        case Halt(Some(ex)) =>
          F.raiseError(ex)
        case Cons(a, next) =>
          val newState = f(state, a)
          F.flatMap(next)(loop(_, newState))
        case ConsSeq(list, next) =>
          val newState = list.foldLeft(state)(f)
          F.flatMap(next)(loop(_, newState))
      }
      catch {
        case NonFatal(ex) => F.raiseError(ex)
      }

    F.flatMap(F.evalAlways(seed))(loop(self, _))
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
    (implicit F: Evaluable[F]): F[S] = {

    def loop(self: Enumerator[F,A], acc: S): F[S] =
      try self match {
        case Halt(None) => F.pure(acc)
        case Halt(Some(ex)) => F.raiseError(ex)

        case Cons(a, next) =>
          val (continue, newState) = f(acc, a)
          if (!continue) F.pure(newState) else
            F.flatMap(next)(loop(_, newState))

        case ConsSeq(list, next) =>
          var continue = true
          var newState = acc
          val iter = list.iterator

          while (continue && iter.hasNext) {
            val (c,s) = f(newState, iter.next())
            newState = s
            continue = c
          }

          if (!continue) F.pure(newState) else
            F.flatMap(next)(loop(_, newState))
      }
      catch {
        case NonFatal(ex) => F.raiseError(ex)
      }

    F.flatMap(F.evalAlways(seed))(loop(self, _))
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

    try this match {
      case Halt(None) => lb
      case Halt(Some(ex)) => F.raiseError(ex)
      case Cons(a, next) =>
        f(a, F.flatMap(next)(_.foldRightL(lb)(f)))

      case ConsSeq(list, next) =>
        if (list.isEmpty)
          F.flatMap(next)(_.foldRightL(lb)(f))
        else {
          val a = list.head
          val tail = list.tail
          val rest = F.pure(ConsSeq[F,A](tail, next))
          f(a, F.flatMap(rest)(_.foldRightL(lb)(f)))
        }
    }
    catch {
      case NonFatal(ex) => F.raiseError(ex)
    }
  }

  /** Find the first element matching the predicate, if one exists. */
  final def findL[B >: A](p: B => Boolean)
    (implicit F: Evaluable[F]): F[Option[B]] =
    foldWhileL(Option.empty[B])((s,a) => if (p(a)) (false, Some(a)) else (true, s))

  /** Count the total number of elements. */
  final def countL(implicit F: Evaluable[F]): F[Long] =
    foldLeftL(0L)((acc,_) => acc + 1)

  /** Given a sequence of numbers, calculates a sum. */
  final def sumL[B >: A](implicit B: Numeric[B], F: Evaluable[F]): F[B] =
    foldLeftL(B.zero)(B.plus)

  /** Check whether at least one element satisfies the predicate. */
  final def existsL(p: A => Boolean)
    (implicit F: Evaluable[F]): F[Boolean] =
    foldWhileL(false)((s,a) => if (p(a)) (false, true) else (true, s))

  /** Check whether all elements satisfy the predicate. */
  final def forallL(p: A => Boolean)
    (implicit F: Evaluable[F]): F[Boolean] =
    foldWhileL(true)((s,a) => if (!p(a)) (false, false) else (true, s))

  /** Aggregates elements in a `List` and preserves order. */
  final def toListL[B >: A](implicit F: Evaluable[F]): F[List[B]] = {
    val folded = foldLeftL(mutable.ListBuffer.empty[A]) { (acc, a) => acc += a }
    F.map(folded)(_.toList)
  }

  /** Returns true if there are no elements, false otherwise. */
  final def isEmptyL(implicit F: Evaluable[F]): F[Boolean] =
    foldWhileL(true)((_,_) => (false, false))

  /** Returns true if there are elements, false otherwise. */
  final def nonEmptyL(implicit F: Evaluable[F]): F[Boolean] =
    foldWhileL(false)((_,_) => (false, true))

  /** Returns the first element in the stream, as an option.
    *
    * Halts with a `NoSuchElemException` if the stream is empty.
    */
  final def headL[B >: A](implicit F: Evaluable[F]): F[B] = {
    def error = new NoSuchElementException("headL")

    this match {
      case Halt(None) => F.raiseError(error)
      case Halt(Some(ex)) => F.raiseError(ex)
      case Cons(a, _) => F.pure(a)
      case ConsSeq(list, _) =>
        list.headOption
          .map(a => F.pure[B](a))
          .getOrElse(F.raiseError(error))
    }
  }

  /** Returns the first element in the stream, as an option. */
  final def headOptionL[B >: A](implicit F: Evaluable[F]): F[Option[B]] =
    this match {
      case Halt(None) => F.pure(None)
      case Halt(Some(ex)) => F.raiseError(ex)
      case Cons(a, _) => F.pure(Some(a))
      case ConsSeq(list, _) => F.pure(list.headOption)
    }

  /** Alias for [[headOptionL]]. */
  final def firstL[B >: A](implicit F: Evaluable[F]): F[Option[B]] = headOptionL

  /** Returns a new sequence that will take a maximum of
    * `n` elements from the start of the source sequence.
    */
  final def take(n: Int)(implicit F: Applicative[F]): Enumerator[F,A] =
    if (n <= 0) Halt(None) else this match {
      case empty @ Halt(None) => empty
      case error @ Halt(_) => error
      case Cons(a, next) =>
        if (n - 1 > 0)
          Cons[F,A](a, F.map(next)(_.take(n-1)))
        else
          Cons[F,A](a, F.pure(Halt(None)))

      case ConsSeq(list, rest) =>
        val length = list.length
        if (length == n)
          ConsSeq[F,A](list, F.pure(Halt(None)))
        else if (length < n)
          ConsSeq[F,A](list, F.map(rest)(_.take(n-length)))
        else
          ConsSeq[F,A](list.take(n), F.pure(Halt(None)))
    }

  /** Returns a new sequence that will take elements from
    * the start of the source sequence, for as long as the given
    * function `f` returns `true` and then stop.
    */
  final def takeWhile(p: A => Boolean)(implicit F: Applicative[F]): Enumerator[F,A] = {
    try this match {
      case empty @ Halt(None) => empty
      case error @ Halt(Some(_)) => error
      case Cons(a, next) =>
        try { if (p(a)) Cons[F,A](a, F.map(next)(_.takeWhile(p))) else Halt(None) }
        catch { case NonFatal(ex) => Halt(Some(ex)) }
      case ConsSeq(list, rest) =>
        try {
          val filtered = list.takeWhile(p)
          if (filtered.length < list.length)
            ConsSeq[F,A](filtered, F.pure(Halt(None)))
          else
            ConsSeq[F,A](filtered, F.map(rest)(_.takeWhile(p)))
        } catch {
          case NonFatal(ex) => Halt(Some(ex))
        }
    }
    catch {
      case NonFatal(ex) => Halt(Some(ex))
    }
  }

  /** Recovers from potential errors by mapping them to other
    * async iterators using the provided function.
    */
  final def onErrorHandleWith[B >: A](f: Throwable => Enumerator[F,B])
    (implicit F: MonadError[F,Throwable]): Enumerator[F,B] = {

    def protectF(tailF: F[Enumerator[F, A]]): F[Enumerator[F, B]] = {
      // Protecting F itself; we have to force the type system
      // here, because F[_] is invariant
      val withHandler = F.handleError(tailF.asInstanceOf[F[Enumerator[F,B]]])(ex => f(ex))
      // Recursive call for handling the rest of the stream
      F.map(withHandler)(_.onErrorHandleWith(f))
    }

    this match {
      case empty @ Halt(None) =>
        empty
      case Cons(a, tailF) =>
        Cons[F,B](a, protectF(tailF))
      case ConsSeq(seq, tailF) =>
        ConsSeq[F,B](seq, protectF(tailF))
      case Halt(Some(ex)) =>
        try f(ex) catch { case NonFatal(err) => Halt(Some(err)) }
    }
  }

  /** Recovers from potential errors by mapping them to other
    * async iterators using the provided function.
    */
  final def onErrorRecoverWith[B >: A](pf: PartialFunction[Throwable, Enumerator[F,B]])
    (implicit F: MonadError[F,Throwable]): Enumerator[F,B] =
    onErrorHandleWith {
      case ex if pf.isDefinedAt(ex) => pf(ex)
      case other => Halt(Some(other))
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
      case other => Enumerator.raiseError(other)
    }

  /** Drops the first `n` elements, from left to right. */
  final def drop(n: Int)(implicit F: Functor[F]): Enumerator[F,A] =
    if (n <= 0) this else this match {
      case empty @ Halt(None) => empty
      case error @ Halt(Some(_)) => error
      case Cons(a, next) =>
        ConsSeq[F,A](Nil, F.map(next)(_.drop(n-1)))
      case ConsSeq(list, rest) =>
        val length = list.length
        if (length == n)
          ConsSeq[F,A](Nil, rest)
        else if (length > n)
          ConsSeq[F,A](list.drop(n), rest)
        else
          ConsSeq[F,A](Nil, F.map(rest)(_.drop(n - length)))
    }

  /** Triggers memoization of the iterable on the first traversal,
    * such that results will get reused on subsequent traversals.
    */
  final def memoize(implicit F: Deferrable[F]): Enumerator[F,A] =
    this match {
      case empty @ Halt(_) => empty
      case Cons(a, rest) => Cons[F,A](a, F.map(F.memoize(rest))(_.memoize))
      case ConsSeq(list, rest) => ConsSeq[F,A](list, F.map(F.memoize(rest))(_.memoize))
    }

  /** Creates a new evaluable that will consume the
    * source iterator and upon completion of the source it will
    * complete with `Unit`.
    */
  final def completedL(implicit F: MonadError[F,Throwable]): F[Unit] = {
    def loop(tail: F[Enumerator[F,A]]): F[Unit] = F.flatMap(tail) {
      case Cons(elem, rest) => loop(rest)
      case ConsSeq(elems, rest) => loop(rest)
      case Halt(None) => F.pure(())
      case Halt(Some(ex)) => F.raiseError(ex)
    }

    loop(F.pure(this))
  }

  /** On evaluation it consumes the stream and for each element
    * execute the given function.
    */
  final def foreachL(cb: A => Unit)
    (implicit F: Evaluable[F]): F[Unit] = {

    def loop(tail: F[Enumerator[F,A]]): F[Unit] = F.flatMap(tail) {
      case Cons(elem, rest) =>
        try { cb(elem); loop(rest) }
        catch { case NonFatal(ex) => F.raiseError(ex) }

      case ConsSeq(elems, rest) =>
        try { elems.foreach(cb); loop(rest) }
        catch { case NonFatal(ex) => F.raiseError(ex) }

      case Halt(None) => F.unit
      case Halt(Some(ex)) => F.raiseError(ex)
    }

    loop(F.pure(this))
  }

  /** Zips two enumerators together.
    *
    * The length of the result will be the shorter of the two
    * arguments.
    */
  final def zip[B](other: Enumerator[F,B])
    (implicit F: Deferrable[F]): Enumerator[F, (A,B)] =
    zipMap(other)((_,_))

  /** Lazily zip two enumerators together, using the given
    * mapping function `f` to produce output values.
    *
    * The length of the result will be the shorter of the two
    * arguments.
    */
  final def zipMap[B,R](other: Enumerator[F,B])(f: (A,B) => R)
    (implicit F: Deferrable[F]): Enumerator[F, R] = {

    try {
      (this, other) match {
        case (Halt(None), _) | (_, Halt(None)) => Halt[F](None)
        case (error @ Halt(Some(_)), _) => error
        case (_, error @ Halt(Some(_))) => error

        case (Cons(a, ta), Cons(b, tb)) =>
          Cons[F,R](f(a,b), F.map2(ta, tb)((ra, rb) => ra.zipMap(rb)(f)))
        case (ConsSeq(seqA, ta), Cons(b, tb)) =>
          if (seqA.isEmpty)
            ConsSeq[F,R](Nil, F.map(ta)(_.zipMap(other)(f)))
          else {
            val nextA = ConsSeq[F,A](seqA.tail, ta)
            Cons[F,R](f(seqA.head,b), F.map(tb)(rb => nextA.zipMap(rb)(f)))
          }
        case (lh @ Cons(a, ta), ConsSeq(seqB, tb)) =>
          if (seqB.isEmpty)
            ConsSeq[F,R](Nil, F.map(tb)(rb => lh.zipMap(rb)(f)))
          else {
            // Scala compiler has problems, so must do asInstanceOf
            val nextB = ConsSeq[F,B](seqB.tail, tb.asInstanceOf[F[Enumerator[F,B]]])
            Cons[F,R](f(a,seqB.head), F.map(ta)(_.zipMap(nextB)(f)))
          }
        case (lh @ ConsSeq(seqA, ta), rh @ ConsSeq(seqB, tb)) =>
          if (seqA.isEmpty)
            ConsSeq[F,R](Nil, F.map(ta)(ra => ra.zipMap(rh)(f)))
          else if (seqB.isEmpty)
            ConsSeq[F,R](Nil, F.map(tb)(rb => lh.zipMap(rb)(f)))
          else {
            // Scala compiler has problems, so must do asInstanceOf
            val ctb = tb.asInstanceOf[F[Enumerator[F,B]]]
            val nextA = ConsSeq[F,A](seqA.tail, ta)
            val nextB = ConsSeq[F,B](seqB.tail, ctb)
            Cons[F,R](f(seqA.head,seqB.head), F.evalAlways(nextA.zipMap(nextB)(f)))
          }
      }
    }
    catch {
      case NonFatal(ex) => Halt(Some(ex))
    }
  }
}

/** @define consSeqDesc The [[monix.eval.Enumerator.ConsSeq ConsSeq]] state
  *         of the [[Enumerator]] represents a `headSeq` / `tail` cons pair.
  *
  *         Like [[monix.eval.Enumerator.Cons Cons]] except that
  *         the head is a strict sequence of elements that don't need
  *         asynchronous execution. The `headSeq` sequence can also be
  *         empty.
  *
  *         Useful for doing buffering or, by giving it an empty `headSeq`,
  *         useful to postpone the evaluation of the next element.
  *
  * @define consDesc The [[monix.eval.Enumerator.Cons Cons]] state
  *         of the [[Enumerator]] represents a `head` / `tail` cons pair.
  *
  *         Note the `head` is a strict value, whereas the `tail` is
  *         meant to be lazy and can have asynchronous behavior as well,
  *         depending on `F`.
  *
  * @define haltDesc The [[monix.eval.Enumerator.Halt Halt]] state
  *         of the [[Enumerator]] represents the completion state
  *         of a stream, with an optional exception if an error happened.
  *
  *         `Halt` is received as a final state in the iteration process.
  *         This state cannot be followed by any other element and
  *         represents the end of the stream.
  */
object Enumerator {
  /** Lifts a strict value into an stream */
  def now[F[_],A](a: A)(implicit F: Applicative[F]): Enumerator[F,A] =
    Cons[F,A](a, F.pure(empty[F,A]))

  /** Builder for a stream ending in error. */
  def raiseError[F[_],A](ex: Throwable): Enumerator[F,A] = Halt[F](Some(ex))

  /** Builder for an empty enumerator. */
  def empty[F[_],A]: Enumerator[F,A] = Halt[F](None)

  /** Lifts a strict value into an stream */
  def evalAlways[F[_],A](a: => A)(implicit F: Deferrable[F]): Enumerator[F,A] =
    ConsSeq[F,A](Nil, F.evalAlways {
      try Cons[F,A](a, F.pure(empty[F,A])) catch {
        case NonFatal(ex) => raiseError(ex)
      }
    })

  /** Lifts a strict value into an stream and
    * memoizes the result for subsequent executions.
    */
  def evalOnce[F[_],A](a: => A)(implicit F: Deferrable[F]): Enumerator[F,A] =
    ConsSeq[F,A](Nil, F.evalOnce {
      try Cons[F,A](a, F.now(empty[F,A])) catch {
        case NonFatal(ex) => raiseError(ex)
      }
    })

  /** Builder for a [[Halt]] state.
    *
    * $haltDesc
    *
    * @param ex is an optional exception that, in case it is
    *        present, it means that the streaming ended in error.
    */
  def halt[F[_],A](ex: Option[Throwable]): Enumerator[F,A] =
    Halt(ex)

  /** Builds a [[Cons]] iterator state.
    *
    * $consDesc
    *
    * @param head is the current element to be signaled
    * @param tail is the next state in the sequence that will
    *        produce the rest of the stream
    */
  def cons[F[_],A](head: A, tail: F[Enumerator[F,A]]): Enumerator[F,A] =
    Cons[F,A](head, tail)

  /** Returns a [[ConsSeq]] iterator state.
    *
    * $consSeqDesc
    *
    * @param headSeq is a sequence of the next elements to
    *        be processed, can be empty
    * @param tail is the next state in the sequence that will
    *        produce the rest of the stream
    */
  def consSeq[F[_],A](headSeq: LinearSeq[A], tail: F[Enumerator[F,A]]): Enumerator[F,A] =
    ConsSeq[F,A](headSeq, tail)

  /** Promote a non-strict value representing a stream
    * to a stream of the same type.
    */
  def defer[F[_],A](fa: => Enumerator[F,A])(implicit F: Deferrable[F]): Enumerator[F,A] =
    ConsSeq[F,A](Nil, F.evalAlways(fa))

  /** Zips two enumerators together.
    *
    * The length of the result will be the shorter of the two
    * arguments.
    */
  def zip2[F[_],A1,A2,R](fa1: Enumerator[F,A1], fa2: Enumerator[F,A2])
    (implicit F: Deferrable[F]): Enumerator[F,(A1,A2)] =
    fa1.zip(fa2)

  /** Lazily zip two enumerators together, using the given
    * mapping function `f` to produce output values.
    *
    * The length of the result will be the shorter of the two
    * arguments.
    */
  def zipMap2[F[_],A1,A2,R](fa1: Enumerator[F,A1], fa2: Enumerator[F,A2])(f: (A1,A2) => R)
    (implicit F: Deferrable[F]): Enumerator[F,R] =
    fa1.zipMap(fa2)(f)

  /** Zips three enumerators together.
    *
    * The length of the result will be the shorter of the three
    * arguments.
    */
  def zip3[F[_],A1,A2,A3](fa1: Enumerator[F,A1], fa2: Enumerator[F,A2], fa3: Enumerator[F,A3])
    (implicit F: Deferrable[F]): Enumerator[F,(A1,A2,A3)] =
    zipMap3(fa1,fa2,fa3)((a1,a2,a3) => (a1,a2,a3))

  /** Zips four enumerators together.
    *
    * The length of the result will be the shorter of the fiyr
    * arguments.
    */
  def zip4[F[_],A1,A2,A3,A4](fa1: Enumerator[F,A1], fa2: Enumerator[F,A2], fa3: Enumerator[F,A3], fa4: Enumerator[F,A4])
    (implicit F: Deferrable[F]): Enumerator[F,(A1,A2,A3,A4)] =
    zipMap4(fa1,fa2,fa3,fa4)((a1,a2,a3,a4) => (a1,a2,a3,a4))

  /** Zips five enumerators together.
    *
    * The length of the result will be the shorter of the five
    * arguments.
    */
  def zip5[F[_],A1,A2,A3,A4,A5](fa1: Enumerator[F,A1], fa2: Enumerator[F,A2], fa3: Enumerator[F,A3], fa4: Enumerator[F,A4], fa5: Enumerator[F,A5])
    (implicit F: Deferrable[F]): Enumerator[F,(A1,A2,A3,A4,A5)] =
    zipMap5(fa1,fa2,fa3,fa4,fa5)((a1,a2,a3,a4,a5) => (a1,a2,a3,a4,a5))

  /** Zips six enumerators together.
    *
    * The length of the result will be the shorter of the six
    * arguments.
    */
  def zip6[F[_],A1,A2,A3,A4,A5,A6](fa1: Enumerator[F,A1], fa2: Enumerator[F,A2], fa3: Enumerator[F,A3], fa4: Enumerator[F,A4], fa5: Enumerator[F,A5], fa6: Enumerator[F,A6])
    (implicit F: Deferrable[F]): Enumerator[F,(A1,A2,A3,A4,A5,A6)] =
    zipMap6(fa1,fa2,fa3,fa4,fa5,fa6)((a1,a2,a3,a4,a5,a6) => (a1,a2,a3,a4,a5,a6))

  /** Lazily zip three enumerators together, using the given
    * mapping function `f` to produce output values.
    *
    * The length of the result will be the shorter of the three
    * arguments.
    */
  def zipMap3[F[_],A1,A2,A3,R](fa1: Enumerator[F,A1], fa2: Enumerator[F,A2], fa3: Enumerator[F,A3])
    (f: (A1,A2,A3) => R)
    (implicit F: Deferrable[F]): Enumerator[F,R] = {

    val fa12 = zip2(fa1, fa2)
    zipMap2(fa12, fa3) { case ((a1,a2), a3) => f(a1,a2,a3) }
  }

  /** Lazily zip four enumerators together, using the given
    * mapping function `f` to produce output values.
    *
    * The length of the result will be the shorter of the four
    * arguments.
    */
  def zipMap4[F[_],A1,A2,A3,A4,R](fa1: Enumerator[F,A1], fa2: Enumerator[F,A2], fa3: Enumerator[F,A3], fa4: Enumerator[F,A4])
    (f: (A1,A2,A3,A4) => R)
    (implicit F: Deferrable[F]): Enumerator[F,R] = {

    val fa123 = zip3(fa1, fa2, fa3)
    zipMap2(fa123, fa4) { case ((a1,a2,a3), a4) => f(a1,a2,a3,a4) }
  }

  /** Lazily zip five enumerators together, using the given
    * mapping function `f` to produce output values.
    *
    * The length of the result will be the shorter of the five
    * arguments.
    */
  def zipMap5[F[_],A1,A2,A3,A4,A5,R](fa1: Enumerator[F,A1], fa2: Enumerator[F,A2], fa3: Enumerator[F,A3], fa4: Enumerator[F,A4], fa5: Enumerator[F,A5])
    (f: (A1,A2,A3,A4,A5) => R)
    (implicit F: Deferrable[F]): Enumerator[F,R] = {

    val fa1234 = zip4(fa1, fa2, fa3, fa4)
    zipMap2(fa1234, fa5) { case ((a1,a2,a3,a4), a5) => f(a1,a2,a3,a4,a5) }
  }

  /** Lazily zip six enumerators together, using the given
    * mapping function `f` to produce output values.
    *
    * The length of the result will be the shorter of the six
    * arguments.
    */
  def zipMap6[F[_],A1,A2,A3,A4,A5,A6,R](fa1: Enumerator[F,A1], fa2: Enumerator[F,A2], fa3: Enumerator[F,A3], fa4: Enumerator[F,A4], fa5: Enumerator[F,A5], fa6: Enumerator[F,A6])
    (f: (A1,A2,A3,A4,A5,A6) => R)
    (implicit F: Deferrable[F]): Enumerator[F,R] = {

    val fa12345 = zip5(fa1, fa2, fa3, fa4, fa5)
    zipMap2(fa12345, fa6) { case ((a1,a2,a3,a4,a5), a6) => f(a1,a2,a3,a4,a5,a6) }
  }

  /** Creates a stream that on evaluation repeats the
    * given elements, ad infinitum.
    *
    * Alias for [[repeat]].
    */
  def continually[F[_],A](elems: A*)(implicit F: Deferrable[F]): Enumerator[F,A] =
    repeat(elems:_*)

  /** Creates a stream that on evaluation repeats the
    * given elements, ad infinitum.
    */
  def repeat[F[_],A](elems: A*)(implicit F: Deferrable[F]): Enumerator[F,A] = {
    def one(elem: A): Enumerator[F,A] =
      Cons[F,A](elem, F.evalAlways(one(elem)))
    def many(elems: List[A]): Enumerator[F,A] =
      ConsSeq[F,A](elems, F.evalAlways(many(elems)))

    elems.length match {
      case 0 => throw new IllegalArgumentException("elems is empty")
      case 1 => one(elems.head)
      case _ => many(elems.toList)
    }
  }

  /** Generates a range between `from` (inclusive) and `until` (exclusive),
    * with `step` as the increment.
    */
  def range[F[_]](from: Long, until: Long, step: Long = 1L)
    (implicit F: Deferrable[F]): Enumerator[F,Long] = {

    def loop(cursor: Long): Enumerator[F,Long] = {
      val isInRange = (step > 0 && cursor < until) || (step < 0 && cursor > until)
      val nextCursor = cursor + step
      if (!isInRange) Halt[F](None) else
        Cons[F,Long](cursor, F.evalAlways(loop(nextCursor)))
    }

    ConsSeq[F,Long](Nil, F.evalAlways(loop(from)))
  }

  /** Converts any immutable list into an enumerator.
    *
    * @param list is the list to convert to an enumerator
    */
  def fromList[F[_],A](list: immutable.LinearSeq[A])
    (implicit F: Deferrable[F]): Enumerator[F,A] = {

    if (list.isEmpty) empty[F,A] else
      Cons[F,A](list.head, F.evalAlways(fromList(list.tail)))
  }

  /** Converts any immutable list into an enumerator.
    *
    * This version of `fromList` produces batches of elements,
    * instead of single elements, potentially making it more efficient.
    *
    * @param list is the list to convert to a enumerator
    * @param batchSize specifies the maximum batch size
    */
  def fromList[F[_],A](list: immutable.LinearSeq[A], batchSize: Int)
    (implicit F: Deferrable[F]): Enumerator[F,A] = {

    if (list.isEmpty) empty[F,A] else {
      val (first, rest) = list.splitAt(batchSize)
      ConsSeq[F,A](first, F.evalAlways(fromList(rest, batchSize)))
    }
  }

  /** Converts an iterable into an async iterator. */
  def fromIterable[F[_],A](iterable: Iterable[A], batchSize: Int)
    (implicit F: Evaluable[F]): Enumerator[F,A] = {

    val init = F.evalAlways(iterable.iterator)
    ConsSeq[F,A](Nil, F.flatMap(init)(iterator => fromIterator(iterator, batchSize)))
  }


  /** Converts an iterable into an async iterator. */
  def fromIterable[F[_],A](iterable: java.lang.Iterable[A], batchSize: Int)
    (implicit F: Evaluable[F]): Enumerator[F,A] = {

    val init = F.evalAlways(iterable.iterator)
    ConsSeq[F,A](Nil, F.flatMap(init)(iterator => fromIterator(iterator, batchSize)))
  }

  /** Converts a `scala.collection.Iterator` into an async iterator. */
  def fromIterator[F[_],A](iterator: scala.collection.Iterator[A], batchSize: Int)
    (implicit F: Evaluable[F]): F[Enumerator[F,A]] = {

    F.evalOnce {
      try {
        val buffer = mutable.ListBuffer.empty[A]
        var processed = 0
        while (processed < batchSize && iterator.hasNext) {
          buffer += iterator.next()
          processed += 1
        }

        if (processed == 0) Halt(None)
        else if (processed == 1)
          Cons[F,A](buffer.head, fromIterator(iterator, batchSize))
        else
          ConsSeq[F,A](buffer.toList, fromIterator(iterator, batchSize))
      } catch {
        case NonFatal(ex) =>
          Halt(Some(ex))
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

        if (processed == 0) Halt(None)
        else if (processed == 1)
          Cons[F,A](buffer.head, fromIterator(iterator, batchSize))
        else
          ConsSeq[F,A](buffer.toList, fromIterator(iterator, batchSize))
      } catch {
        case NonFatal(ex) =>
          Halt(Some(ex))
      }
    }
  }

  /** $consDesc
    *
    * @param head is the current element to be signaled
    * @param tail is the next state in the sequence that will
    *        produce the rest of the stream
    */
  final case class Cons[F[_],A](head: A, tail: F[Enumerator[F,A]])
    extends Enumerator[F,A]

  /** $consSeqDesc
    *
    * @param headSeq is a sequence of the next elements to be processed, can be empty
    * @param tail is the next state in the sequence
    */
  final case class ConsSeq[F[_],A](headSeq: LinearSeq[A], tail: F[Enumerator[F,A]])
    extends Enumerator[F,A]

  /** $haltDesc
    *
    * @param ex is an optional exception that, in case it is
    *        present, it means that the streaming ended in error.
    */
  final case class Halt[F[_]](ex: Option[Throwable]) extends Enumerator[F,Nothing]

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
      Enumerator.raiseError[F,A](e)

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


