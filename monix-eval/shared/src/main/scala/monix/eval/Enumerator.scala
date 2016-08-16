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
import monix.types.shims.{Applicative, Monad, MonadError}
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
  *  - [[monix.eval.Enumerator.NextEl Cons]] which signals a
  *     single element and a tail
  *  - [[monix.eval.Enumerator.NextSeq ConsSeq]] signaling a
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

  /** Returns a computation that should be evaluated in
    * case the streaming must be canceled before reaching
    * the end.
    *
    * This is useful to release any acquired resources,
    * like opened file handles or network sockets.
    */
  def onCancel(implicit F: Applicative[F]): F[Unit] =
    this match {
      case NextEl(_, _, ref) => ref
      case NextSeq(_, _, ref) => ref
      case Halt(_) => F.pure(())
    }

  /** Given a computation, make sure to evaluate it if the
    * stream gets interrupted before reaching the end.
    *
    * Note this operation is subsumed in [[doOnFinish]] and the
    * given computation will only be evaluated if the stream is
    * interrupted early and not when it is fully complete.
    */
  def doOnCancel(f: F[Unit])(implicit F: Monad[F]): Enumerator[F,A] =
    this match {
      case NextEl(head, tail, cancel) =>
        NextEl[F,A](head, F.map(tail)(_.doOnCancel(f)), F.flatMap(cancel)(_ => f))
      case NextSeq(seq, tail, cancel) =>
        NextSeq[F,A](seq, F.map(tail)(_.doOnCancel(f)), F.flatMap(cancel)(_ => f))
      case halt @ Halt(_) =>
        halt
    }

  /** Given a computation, evaluate it upon the stream reaching
    * the end.
    *
    * Note this operation is subsumed in [[doOnFinish]] and the
    * given computation will only be evaluated if the stream reaches
    * the end, but not when it is interrupted prematurely (canceled).
    */
  def doOnHalt(f: Option[Throwable] => F[Unit])(implicit F: Monad[F]): Enumerator[F,A] =
    this match {
      case NextEl(head, tail, cancel) =>
        NextEl[F,A](head, F.map(tail)(_.doOnHalt(f)), cancel)
      case NextSeq(seq, tail, cancel) =>
        NextSeq[F,A](seq, F.map(tail)(_.doOnHalt(f)), cancel)
      case halt @ Halt(ex) =>
        suspend[F,A](F.map(f(ex))(_ => halt), F.pure(()))
    }

  /** Returns a new enumerator in which `f` is scheduled to be run on completion.
    * This would typically be used to release any resources acquired by this
    * enumerator.
    *
    * Note that both [[doOnCancel]] and [[doOnHalt]] are subsumed under
    * this operation, the given `f` being evaluated on both reaching the
    * end or canceling early.
    */
  def doOnFinish(f: Option[Throwable] => F[Unit])(implicit F: Monad[F]): Enumerator[F,A] =
    this match {
      case NextEl(head, tail, cancel) =>
        NextEl[F,A](head, F.map(tail)(_.doOnFinish(f)), F.flatMap(cancel)(_ => f(None)))
      case NextSeq(seq, tail, cancel) =>
        NextSeq[F,A](seq, F.map(tail)(_.doOnFinish(f)), F.flatMap(cancel)(_ => f(None)))
      case halt @ Halt(ex) =>
        suspend[F,A](F.map(f(ex))(_ => halt), F.pure(()))
    }

  /** Filters the stream by the given predicate function,
    * returning only those elements that match.
    */
  final def filter(p: A => Boolean)(implicit F: Applicative[F]): Enumerator[F,A] =
    this match {
      case ref @ NextEl(head, tail, cancel) =>
        val rest = F.map(tail)(_.filter(p))
        try {
          if (p(head)) NextEl[F,A](head, rest, cancel)
          else NextSeq[F,A](Nil, rest, cancel)
        } catch {
          case NonFatal(ex) =>
            signalError(ex, cancel)
        }
      case NextSeq(head, tail, cancel) =>
        try {
          val rest = F.map(tail)(_.filter(p))
          NextSeq[F,A](head.filter(p), rest, cancel)
        } catch {
          case NonFatal(ex) =>
            signalError(ex, cancel)
        }
      case empty @ Halt(_) =>
        empty
    }

  /** Returns a new iterable by mapping the supplied function
    * over the elements of the source.
    */
  final def map[B](f: A => B)(implicit F: Applicative[F]): Enumerator[F,B] =
    this match {
      case NextEl(head, tail, cancel) =>
        try
          NextEl[F,B](f(head), F.map(tail)(_.map(f)), cancel)
        catch { case NonFatal(ex) =>
          signalError(ex, cancel)
        }
      case NextSeq(head, rest, cancel) =>
        try
          NextSeq[F,B](head.map(f), F.map(rest)(_.map(f)), cancel)
        catch { case NonFatal(ex) =>
          signalError(ex, cancel)
        }
      case empty @ Halt(_) =>
        empty
    }

  /** Applies the function to the elements of the source
    * and concatenates the results.
    */
  final def flatMap[B](f: A => Enumerator[F,B])
    (implicit F: Monad[F]): Enumerator[F,B] = {

    this match {
      case NextEl(head, tail, cancel) =>
        try f(head) concatF F.map(tail)(_.flatMap(f)) catch {
          case NonFatal(ex) => signalError(ex, cancel)
        }

      case NextSeq(list, rest, cancel) =>
        try if (list.isEmpty)
          suspend[F,B](F.map(rest)(_.flatMap(f)), cancel)
        else
          f(list.head) concatF F.pureEval {
            NextSeq[F,A](list.tail, rest, cancel).flatMap(f)
          }
        catch {
          case NonFatal(ex) => signalError(ex, cancel)
        }

      case empty @ Halt(_) =>
        empty
    }
  }

  /** If the source is an async iterable generator, then
    * concatenates the generated enumerators.
    */
  final def flatten[B](implicit ev: A <:< Enumerator[F,B], F: Monad[F]): Enumerator[F,B] =
    flatMap(x => x)

  /** Alias for [[flatMap]]. */
  final def concatMap[B](f: A => Enumerator[F,B])(implicit F: Monad[F]): Enumerator[F,B] =
    flatMap(f)

  /** Alias for [[concat]]. */
  final def concat[B](implicit ev: A <:< Enumerator[F,B], F: Monad[F]): Enumerator[F,B] =
    flatten

  /** Prepends an element to the enumerator. */
  final def #::[B >: A](head: B)(implicit F: Applicative[F]): Enumerator[F,B] =
    Enumerator.cons[F,B](head, F.pure(this), onCancel)

  /** Appends the given iterable to the end of the source,
    * effectively concatenating them.
    */
  final def ++[B >: A](rhs: Enumerator[F,B])
    (implicit F: Monad[F]): Enumerator[F,B] = {

    this match {
      case NextEl(a, lt, cancel) =>
        val composite = F.flatMap(cancel)(_ => rhs.onCancel)
        NextEl[F,B](a, F.map(lt)(_ ++ rhs), composite)
      case NextSeq(seq, lt, cancel) =>
        val composite = F.flatMap(cancel)(_ => rhs.onCancel)
        NextSeq[F,B](seq, F.map(lt)(_ ++ rhs), composite)
      case Halt(None) =>
        rhs
      case error @ Halt(Some(_)) =>
        suspend[F,B](F.map(rhs.onCancel)(_ => error), rhs.onCancel)
    }
  }

  private final def signalError[B](ex: Throwable, cancel: F[Unit])
    (implicit F: Applicative[F]): Enumerator[F,B] =
    suspend[F,B](F.map(cancel)(_ => Halt(Some(ex))), cancel)

  private final def concatF[B >: A](rhs: F[Enumerator[F,B]])
    (implicit F: Monad[F]): Enumerator[F,B] = {

    this match {
      case NextEl(a, lt, cancel) =>
        val composite = F.flatMap(cancel)(_ => F.flatMap(rhs)(_.onCancel))
        NextEl[F,B](a, F.map(lt)(_.concatF(rhs)), composite)
      case NextSeq(seq, lt, cancel) =>
        val composite = F.flatMap(cancel)(_ => F.flatMap(rhs)(_.onCancel))
        NextSeq[F,B](seq, F.map(lt)(_.concatF(rhs)), composite)
      case Halt(None) =>
        suspend[F,B](rhs, F.flatMap(rhs)(_.onCancel))
      case error @ Halt(Some(_)) =>
        val cancel = F.flatMap(rhs)(_.onCancel)
        suspend[F,B](F.map(cancel)(_ => error), cancel)
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

    def loop(self: Enumerator[F,A], state: S): F[S] = {
      self match {
        case Halt(None) =>
          F.pure(state)
        case Halt(Some(ex)) =>
          F.raiseError(ex)
        case NextEl(a, next, cancel) =>
          try {
            val newState = f(state, a)
            F.flatMap(next)(loop(_, newState))
          } catch {
            case NonFatal(ex) =>
              F.flatMap(cancel)(_ => F.raiseError(ex))
          }
        case NextSeq(list, next, _) =>
          if (list.isEmpty)
            F.flatMap(next)(loop(_, state))
          else try {
            val newState = list.foldLeft(state)(f)
            F.flatMap(next)(loop(_, newState))
          } catch {
            case NonFatal(ex) =>
              F.flatMap(self.onCancel)(_ => F.raiseError(ex))
          }
      }
    }

    val init = F.handleErrorWith(F.evalAlways(seed))(ex =>
      F.flatMap(onCancel)(_ => F.raiseError(ex)))
    F.flatMap(init)(loop(self, _))
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
      self match {
        case Halt(None) => F.pure(acc)
        case Halt(Some(ex)) => F.raiseError(ex)

        case NextEl(a, next, cancel) =>
          try {
            val (continue, newState) = f(acc, a)
            if (!continue) F.pure(newState) else
              F.flatMap(next)(loop(_, newState))
          } catch {
            case NonFatal(ex) =>
              F.flatMap(cancel)(_ => F.raiseError(ex))
          }

        case NextSeq(list, next, cancel) =>
          try {
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
          } catch {
            case NonFatal(ex) =>
              F.flatMap(cancel)(_ => F.raiseError(ex))
          }
      }

    val init = F.handleErrorWith(F.evalAlways(seed))(ex =>
      F.flatMap(onCancel)(_ => F.raiseError(ex)))
    F.flatMap(init)(loop(self, _))
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

    def loop(self: Enumerator[F,A], lb: F[B]): F[B] =
      self match {
        case Halt(None) => lb
        case Halt(Some(ex)) => F.raiseError(ex)

        case NextEl(a, next, cancel) =>
          try {
            val result = f(a, F.flatMap(next)(loop(_, lb)))
            // Making sure cancel is evaluated on error
            F.handleErrorWith(result)(ex =>
              F.flatMap(cancel)(_ => F.raiseError(ex)))
          } catch {
            case NonFatal(ex) =>
              F.flatMap(cancel)(_ => F.raiseError(ex))
          }

        case NextSeq(list, next, cancel) =>
          try if (list.isEmpty)
            F.flatMap(next)(loop(_, lb))
          else {
            val a = list.head
            val tail = list.tail
            val rest = F.pure(NextSeq[F,A](tail, next, cancel))
            val result = f(a, F.flatMap(rest)(loop(_, lb)))
            // Making sure cancel is evaluated on error
            F.handleErrorWith(result)(ex =>
              F.flatMap(cancel)(_ => F.raiseError(ex)))
          }
          catch {
            case NonFatal(ex) =>
              F.flatMap(cancel)(_ => F.raiseError(ex))
          }
      }

    val init = F.handleErrorWith(lb)(ex => F.flatMap(onCancel)(_ => F.raiseError(ex)))
    loop(this, init)
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
      case NextEl(a, _, cancel) =>
        F.map(cancel)(_ => a)
      case NextSeq(list, rest, cancel) =>
        if (list.isEmpty)
          F.flatMap(rest)(_.headL)
        else
          F.map(cancel)(_ => list.head)
    }
  }

  /** Returns the first element in the stream, as an option. */
  final def headOptionL[B >: A](implicit F: Evaluable[F]): F[Option[B]] =
    this match {
      case Halt(None) => F.pure(None)
      case Halt(Some(ex)) => F.raiseError(ex)
      case NextEl(a, _, cancel) =>
        F.map(cancel)(_ => Some(a))
      case NextSeq(list, rest, cancel) =>
        if (list.isEmpty)
          F.flatMap(rest)(_.headOptionL)
        else
          F.map(cancel)(_ => list.headOption)
    }

  /** Alias for [[headOptionL]]. */
  final def firstL[B >: A](implicit F: Evaluable[F]): F[Option[B]] = headOptionL

  /** Returns a new sequence that will take a maximum of
    * `n` elements from the start of the source sequence.
    */
  final def take(n: Int)(implicit F: Applicative[F]): Enumerator[F,A] =
    this match {
      case empty @ Halt(None) => empty
      case error @ Halt(_) => error

      case NextEl(a, next, cancel) =>
        if (n - 1 > 0)
          NextEl[F,A](a, F.map(next)(_.take(n-1)), cancel)
        else
          NextEl[F,A](a, F.map(cancel)(_ => Halt(None)), cancel)

      case NextSeq(list, rest, cancel) =>
        val length = list.length
        if (length == n)
          NextSeq[F,A](list, F.map(cancel)(_ => Halt(None)), cancel)
        else if (length < n)
          NextSeq[F,A](list, F.map(rest)(_.take(n-length)), cancel)
        else
          NextSeq[F,A](list.take(n), F.map(cancel)(_ => Halt(None)), cancel)
    }

  /** Returns a new sequence that will take elements from
    * the start of the source sequence, for as long as the given
    * predicate function `p` returns `true` and then stop.
    */
  final def takeWhile(p: A => Boolean)(implicit F: Applicative[F]): Enumerator[F,A] =
    this match {
      case empty @ Halt(None) => empty
      case error @ Halt(Some(_)) => error

      case NextEl(a, next, cancel) =>
        try {
          if (p(a))
            NextEl[F,A](a, F.map(next)(_.takeWhile(p)), cancel)
          else
            suspend[F,A](F.map(cancel)(_ => Halt(None)), cancel)
        } catch {
          case NonFatal(ex) => signalError(ex, cancel)
        }

      case NextSeq(list, rest, cancel) =>
        try {
          val filtered = list.takeWhile(p)
          if (filtered.length < list.length)
            NextSeq[F,A](filtered, F.map(cancel)(_ => Halt(None)), cancel)
          else
            NextSeq[F,A](filtered, F.map(rest)(_.takeWhile(p)), cancel)
        } catch {
          case NonFatal(ex) => signalError(ex, cancel)
        }
    }

  /** Drops the first `n` elements, from left to right. */
  final def drop(n: Int)(implicit F: Applicative[F]): Enumerator[F,A] =
    if (n <= 0) this else this match {
      case empty @ Halt(None) => empty
      case error @ Halt(Some(_)) => error
      case NextEl(a, next, cancel) =>
        NextSeq[F,A](Nil, F.map(next)(_.drop(n-1)), cancel)
      case NextSeq(list, rest, cancel) =>
        val length = list.length
        if (length == n)
          NextSeq[F,A](Nil, rest, cancel)
        else if (length > n)
          NextSeq[F,A](list.drop(n), rest, cancel)
        else
          NextSeq[F,A](Nil, F.map(rest)(_.drop(n - length)), cancel)
    }

  /** Returns a new sequence that will drop elements from
    * the start of the source sequence, for as long as the given
    * predicate function `p` returns `true`.
    *
    * Once the predicate returns false, the rest of the enumerator
    * is streamed from then on.
    */
  final def dropWhile(p: A => Boolean)(implicit F: Applicative[F]): Enumerator[F,A] =
    this match {
      case empty @ Halt(None) => empty
      case error @ Halt(Some(_)) => error
      case NextEl(a, next, cancel) =>
        try {
          if (p(a))
            suspend[F,A](F.map(next)(_ dropWhile p), cancel)
          else
            NextEl[F,A](a, next, cancel)
        } catch {
          case NonFatal(ex) => signalError(ex, cancel)
        }
      case NextSeq(list, rest, cancel) =>
        try {
          val newList = list.dropWhile(p)
          if (newList.isEmpty)
            NextSeq[F,A](Nil, F.map(rest)(_ dropWhile p), cancel)
          else
            NextSeq[F,A](newList, rest, cancel)
        }
        catch {
          case NonFatal(ex) => signalError(ex, cancel)
        }
    }

  /** Recovers from potential errors by mapping them to other
    * async iterators using the provided function.
    */
  final def onErrorHandleWith[B >: A](f: Throwable => Enumerator[F,B])
    (implicit F: MonadError[F,Throwable]): Enumerator[F,B] = {

    def protectF(tailF: F[Enumerator[F, A]], cancel: F[Unit]): F[Enumerator[F, B]] = {
      // Protecting F itself; we have to force the type system
      // here, because F[_] is invariant
      val tailFB = tailF.asInstanceOf[F[Enumerator[F,B]]]
      val withHandler = F.handleErrorWith(tailFB)(ex => F.map(cancel)(_ => f(ex)))
      // Recursive call for handling the rest of the stream
      F.map(withHandler)(_.onErrorHandleWith(f))
    }

    this match {
      case empty @ Halt(None) =>
        empty
      case NextEl(a, tailF, cancel) =>
        NextEl[F,B](a, protectF(tailF, cancel), cancel)
      case NextSeq(seq, tailF, cancel) =>
        NextSeq[F,B](seq, protectF(tailF, cancel), cancel)
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

  /** Triggers memoization of the iterable on the first traversal,
    * such that results will get reused on subsequent traversals.
    *
    * Memoized enumerators avoid duplicate work when the same
    * enumerator gets traversed multiple times, however a memoized
    * enumerator can no longer be canceled ahead of time. So if
    * the enumerator is keeping file handles or other resources
    * open, then it has to be fully traversed in order for the
    * cancellation logic to be materialized.
    */
  final def memoize(implicit F: Evaluable[F]): Enumerator[F,A] = {
    def loop(self: Enumerator[F,A]): Enumerator[F,A] =
      self match {
        case empty @ Halt(_) =>
          empty
        case NextEl(a, rest, cancel) =>
          NextEl[F,A](a, F.map(F.memoize(rest))(loop), F.unit)
        case NextSeq(list, rest, cancel) =>
          NextSeq[F,A](list, F.map(F.memoize(rest))(loop), F.unit)
      }

    loop(this)
  }

  /** Compact removes "pauses" in the stream
    * (represented as `NodeSeq(Nil,_,_)` nodes).
    *
    * Normally such values are used to defer tail computation in
    * cases where it is convenient to return a stream value where
    * neither the head or tail are computed yet. Or because the
    * values in the tail sequence have been filtered out.
    *
    * In some cases (particularly if the stream is to be memoized) it
    * may be desirable to ensure that these values are not retained.
    */
  final def compact(implicit F: Monad[F]): Enumerator[F,A] = {
    def loop(self: Enumerator[F,A]): F[Enumerator[F,A]] =
      self match {
        case NextEl(head, tail, c) =>
          F.pure(NextEl[F,A](head, F.flatMap(tail)(loop), c))
        case NextSeq(seq, tail, c) =>
          if (seq.nonEmpty)
            F.pure(NextSeq[F,A](seq, F.flatMap(tail)(loop), c))
          else
            F.flatMap(tail)(loop)
        case halt @ Halt(_) =>
          F.pure(halt)
      }

    suspend[F,A](loop(this), onCancel)
  }

  /** Creates a new evaluable that will consume the
    * source iterator and upon completion of the source it will
    * complete with `Unit`.
    */
  final def completedL(implicit F: MonadError[F,Throwable]): F[Unit] = {
    def loop(tail: F[Enumerator[F,A]]): F[Unit] = F.flatMap(tail) {
      case NextEl(elem, rest, cancel) => loop(rest)
      case NextSeq(elems, rest, cancel) => loop(rest)
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
      case NextEl(elem, rest, cancel) =>
        try { cb(elem); loop(rest) } catch {
          case NonFatal(ex) =>
            F.flatMap(cancel)(_ => F.raiseError(ex))
        }

      case NextSeq(elems, rest, cancel) =>
        try { elems.foreach(cb); loop(rest) } catch {
          case NonFatal(ex) =>
            F.flatMap(cancel)(_ => F.raiseError(ex))
        }

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
    (implicit F: Monad[F]): Enumerator[F, (A,B)] =
    zipMap(other)((_,_))

  /** Lazily zip two enumerators together, using the given
    * mapping function `f` to produce output values.
    *
    * The length of the result will be the shorter of the two
    * arguments.
    */
  final def zipMap[B,R](other: Enumerator[F,B])(f: (A,B) => R)
    (implicit F: Monad[F]): Enumerator[F, R] = {

    (this, other) match {
      case (Halt(ex), rh) =>
        val cancel = rh.onCancel
        suspend[F,R](F.map(cancel)(_ => Halt[F](ex)), cancel)

      case (lh, Halt(ex)) =>
        val cancel = lh.onCancel
        suspend[F,R](F.map(cancel)(_ => Halt[F](ex)), cancel)

      case (NextEl(a, ta, lhc), NextEl(b, tb, rhc)) =>
        val cancel = F.flatMap(lhc)(_ => rhc)
        try {
          NextEl[F,R](f(a,b), F.map2(ta, tb)((ra, rb) => ra.zipMap(rb)(f)), cancel)
        }
        catch {
          case NonFatal(ex) => signalError(ex, cancel)
        }

      case (NextSeq(seqA, ta, lhc), NextEl(b, tb, rhc)) =>
        val cancel = F.flatMap(lhc)(_ => rhc)
        try if (seqA.isEmpty)
          NextSeq[F,R](Nil, F.map(ta)(_.zipMap(other)(f)), cancel)
        else {
          val nextA = NextSeq[F,A](seqA.tail, ta, cancel)
          NextEl[F,R](f(seqA.head,b), F.map(tb)(rb => nextA.zipMap(rb)(f)), cancel)
        }
        catch {
          case NonFatal(ex) => signalError(ex, cancel)
        }

      case (lh @ NextEl(a, ta, lhc), NextSeq(seqB, tb, rhc)) =>
        val cancel = F.flatMap(lhc)(_ => rhc)
        try if (seqB.isEmpty)
          NextSeq[F,R](Nil, F.map(tb)(rb => lh.zipMap(rb)(f)), cancel)
        else {
          // Scala compiler has problems, so must do asInstanceOf
          val nextB = NextSeq[F,B](seqB.tail, tb.asInstanceOf[F[Enumerator[F,B]]], cancel)
          NextEl[F,R](f(a,seqB.head), F.map(ta)(_.zipMap(nextB)(f)), cancel)
        }
        catch {
          case NonFatal(ex) => signalError(ex, cancel)
        }

      case (lh @ NextSeq(seqA, ta, lhc), rh @ NextSeq(seqB, tb, rhc)) =>
        val cancel = F.flatMap(lhc)(_ => rhc)
        try if (seqA.isEmpty)
          NextSeq[F,R](Nil, F.map(ta)(ra => ra.zipMap(rh)(f)), cancel)
        else if (seqB.isEmpty)
          NextSeq[F,R](Nil, F.map(tb)(rb => lh.zipMap(rb)(f)), cancel)
        else {
          // Scala compiler has problems, so must do asInstanceOf
          val ctb = tb.asInstanceOf[F[Enumerator[F,B]]]
          val nextA = NextSeq[F,A](seqA.tail, ta, lhc)
          val nextB = NextSeq[F,B](seqB.tail, ctb, rhc)
          NextEl[F,R](f(seqA.head,seqB.head), F.pureEval(nextA.zipMap(nextB)(f)), cancel)
        }
        catch {
          case NonFatal(ex) => signalError(ex, cancel)
        }
    }
  }
}

/** @define consSeqDesc The [[monix.eval.Enumerator.NextSeq ConsSeq]] state
  *         of the [[Enumerator]] represents a `headSeq` / `tail` cons pair.
  *
  *         Like [[monix.eval.Enumerator.NextEl Cons]] except that
  *         the head is a strict sequence of elements that don't need
  *         asynchronous execution. The `headSeq` sequence can also be
  *         empty.
  *
  *         Useful for doing buffering or, by giving it an empty `headSeq`,
  *         useful to postpone the evaluation of the next element.
  *
  * @define consDesc The [[monix.eval.Enumerator.NextEl Cons]] state
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
  *
  * @define cancelDesc is a computation to be executed in case
  *         streaming is stopped prematurely, giving it a chance
  *         to do resource cleanup (e.g. close file handles)
  */
object Enumerator {
  /** Lifts a strict value into an stream */
  def now[F[_],A](a: A)(implicit F: Applicative[F]): Enumerator[F,A] =
    NextEl[F,A](a, F.pure(empty[F,A]), F.pure(()))

  /** Builder for a stream ending in error. */
  def raiseError[F[_],A](ex: Throwable): Enumerator[F,A] = Halt[F](Some(ex))

  /** Builder for an empty enumerator. */
  def empty[F[_],A]: Enumerator[F,A] = Halt[F](None)

  /** Lifts a strict value into an stream */
  def evalAlways[F[_],A](a: => A)(implicit F: Deferrable[F]): Enumerator[F,A] = {
    val eval = F.evalAlways[Enumerator[F,A]] {
      try NextEl[F,A](a, F.pure(empty[F,A]), F.unit) catch {
        case NonFatal(ex) => raiseError[F,A](ex)
      }
    }

    NextSeq[F,A](Nil, eval, F.unit)
  }

  /** Lifts a strict value into an stream and
    * memoizes the result for subsequent executions.
    */
  def evalOnce[F[_],A](a: => A)(implicit F: Deferrable[F]): Enumerator[F,A] = {
    val eval = F.evalOnce[Enumerator[F,A]] {
      try NextEl[F,A](a, F.pure(empty[F,A]), F.unit) catch {
        case NonFatal(ex) => raiseError[F,A](ex)
      }
    }

    NextSeq[F,A](Nil, eval, F.unit)
  }

  /** Builder for a [[Halt]] state.
    *
    * $haltDesc
    *
    * @param ex is an optional exception that, in case it is
    *        present, it means that the streaming ended in error.
    */
  def halt[F[_],A](ex: Option[Throwable]): Enumerator[F,A] =
    Halt(ex)

  /** Builds a [[NextEl]] iterator state.
    *
    * $consDesc
    *
    * @param head is the current element to be signaled
    * @param tail is the next state in the sequence that will
    *        produce the rest of the stream
    * @param cancel $cancelDesc
    */
  def cons[F[_],A](head: A, tail: F[Enumerator[F,A]], cancel: F[Unit]): Enumerator[F,A] =
    NextEl[F,A](head, tail, cancel)

  /** Returns a [[NextSeq]] iterator state.
    *
    * $consSeqDesc
    *
    * @param headSeq is a sequence of the next elements to
    *        be processed, can be empty
    * @param tail is the next state in the sequence that will
    *        produce the rest of the stream
    * @param cancel $cancelDesc
    */
  def consSeq[F[_],A](headSeq: LinearSeq[A], tail: F[Enumerator[F,A]], cancel: F[Unit]): Enumerator[F,A] =
    NextSeq[F,A](headSeq, tail, cancel)

  /** Builder for a suspended state.
    *
    * It returns a [[NextSeq]] state with an empty list, which
    * means that no element is available to be signaled presently,
    * but the run-loop can advance to the next state.
    *
    * @param rest is the reference to the next state in the sequence
    * @param cancel $cancelDesc
    */
  def suspend[F[_],A](rest: F[Enumerator[F,A]], cancel: F[Unit]): Enumerator[F,A] =
    consSeq[F,A](Nil, rest, cancel)

  /** Promote a non-strict value representing a stream
    * to a stream of the same type.
    */
  def defer[F[_],A](fa: => Enumerator[F,A])
    (implicit F: Monad[F]): Enumerator[F,A] = {
    val cancel = F.flatten(F.pureEval(fa.onCancel))
    suspend[F,A](F.pureEval(fa), cancel)
  }

  /** Zips two enumerators together.
    *
    * The length of the result will be the shorter of the two
    * arguments.
    */
  def zip2[F[_],A1,A2,R](fa1: Enumerator[F,A1], fa2: Enumerator[F,A2])
    (implicit F: Monad[F]): Enumerator[F,(A1,A2)] =
    fa1.zip(fa2)

  /** Lazily zip two enumerators together, using the given
    * mapping function `f` to produce output values.
    *
    * The length of the result will be the shorter of the two
    * arguments.
    */
  def zipMap2[F[_],A1,A2,R](fa1: Enumerator[F,A1], fa2: Enumerator[F,A2])(f: (A1,A2) => R)
    (implicit F: Monad[F]): Enumerator[F,R] =
    fa1.zipMap(fa2)(f)

  /** Zips three enumerators together.
    *
    * The length of the result will be the shorter of the three
    * arguments.
    */
  def zip3[F[_],A1,A2,A3](fa1: Enumerator[F,A1], fa2: Enumerator[F,A2], fa3: Enumerator[F,A3])
    (implicit F: Monad[F]): Enumerator[F,(A1,A2,A3)] =
    zipMap3(fa1,fa2,fa3)((a1,a2,a3) => (a1,a2,a3))

  /** Zips four enumerators together.
    *
    * The length of the result will be the shorter of the fiyr
    * arguments.
    */
  def zip4[F[_],A1,A2,A3,A4](fa1: Enumerator[F,A1], fa2: Enumerator[F,A2], fa3: Enumerator[F,A3], fa4: Enumerator[F,A4])
    (implicit F: Monad[F]): Enumerator[F,(A1,A2,A3,A4)] =
    zipMap4(fa1,fa2,fa3,fa4)((a1,a2,a3,a4) => (a1,a2,a3,a4))

  /** Zips five enumerators together.
    *
    * The length of the result will be the shorter of the five
    * arguments.
    */
  def zip5[F[_],A1,A2,A3,A4,A5](fa1: Enumerator[F,A1], fa2: Enumerator[F,A2], fa3: Enumerator[F,A3], fa4: Enumerator[F,A4], fa5: Enumerator[F,A5])
    (implicit F: Monad[F]): Enumerator[F,(A1,A2,A3,A4,A5)] =
    zipMap5(fa1,fa2,fa3,fa4,fa5)((a1,a2,a3,a4,a5) => (a1,a2,a3,a4,a5))

  /** Zips six enumerators together.
    *
    * The length of the result will be the shorter of the six
    * arguments.
    */
  def zip6[F[_],A1,A2,A3,A4,A5,A6](fa1: Enumerator[F,A1], fa2: Enumerator[F,A2], fa3: Enumerator[F,A3], fa4: Enumerator[F,A4], fa5: Enumerator[F,A5], fa6: Enumerator[F,A6])
    (implicit F: Monad[F]): Enumerator[F,(A1,A2,A3,A4,A5,A6)] =
    zipMap6(fa1,fa2,fa3,fa4,fa5,fa6)((a1,a2,a3,a4,a5,a6) => (a1,a2,a3,a4,a5,a6))

  /** Lazily zip three enumerators together, using the given
    * mapping function `f` to produce output values.
    *
    * The length of the result will be the shorter of the three
    * arguments.
    */
  def zipMap3[F[_],A1,A2,A3,R](fa1: Enumerator[F,A1], fa2: Enumerator[F,A2], fa3: Enumerator[F,A3])
    (f: (A1,A2,A3) => R)
    (implicit F: Monad[F]): Enumerator[F,R] = {

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
    (implicit F: Monad[F]): Enumerator[F,R] = {

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
    (implicit F: Monad[F]): Enumerator[F,R] = {

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
    (implicit F: Monad[F]): Enumerator[F,R] = {

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
      NextEl[F,A](elem, F.evalAlways(one(elem)), F.unit)
    def many(elems: List[A]): Enumerator[F,A] =
      NextSeq[F,A](elems, F.evalAlways(many(elems)), F.unit)

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
        NextEl[F,Long](cursor, F.evalAlways(loop(nextCursor)), F.unit)
    }

    NextSeq[F,Long](Nil, F.evalAlways(loop(from)), F.unit)
  }

  /** Converts any immutable list into an enumerator.
    *
    * @param list is the list to convert to an enumerator
    */
  def fromList[F[_],A](list: immutable.LinearSeq[A])
    (implicit F: Deferrable[F]): Enumerator[F,A] = {

    if (list.isEmpty) empty[F,A] else
      NextEl[F,A](list.head, F.evalAlways(fromList(list.tail)), F.unit)
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
      NextSeq[F,A](first, F.evalAlways(fromList(rest, batchSize)), F.unit)
    }
  }

  /** Converts an iterable into an async iterator. */
  def fromIterable[F[_],A](iterable: Iterable[A], batchSize: Int)
    (implicit F: Evaluable[F]): Enumerator[F,A] = {

    val init = F.evalAlways(iterable.iterator)
    NextSeq[F,A](Nil, F.flatMap(init)(iterator => fromIterator(iterator, batchSize)), F.unit)
  }

  /** Converts an iterable into an async iterator. */
  def fromIterable[F[_],A](iterable: java.lang.Iterable[A], batchSize: Int)
    (implicit F: Evaluable[F]): Enumerator[F,A] = {

    val init = F.evalAlways(iterable.iterator)
    NextSeq[F,A](Nil, F.flatMap(init)(iterator => fromIterator(iterator, batchSize)), F.unit)
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
          NextEl[F,A](buffer.head, fromIterator(iterator, batchSize), F.unit)
        else
          NextSeq[F,A](buffer.toList, fromIterator(iterator, batchSize), F.unit)
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
          NextEl[F,A](buffer.head, fromIterator(iterator, batchSize), F.unit)
        else
          NextSeq[F,A](buffer.toList, fromIterator(iterator, batchSize), F.unit)
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
    * @param cancel $cancelDesc
    */
  final case class NextEl[F[_],A](
    head: A,
    tail: F[Enumerator[F,A]],
    cancel: F[Unit])
    extends Enumerator[F,A]

  /** $consSeqDesc
    *
    * @param headSeq is a sequence of the next elements to be processed, can be empty
    * @param tail is the rest of the stream
    * @param cancel $cancelDesc
    */
  final case class NextSeq[F[_],A](
    headSeq: LinearSeq[A],
    tail: F[Enumerator[F,A]],
    cancel: F[Unit])
    extends Enumerator[F,A]

  /** $haltDesc
    *
    * @param ex is an optional exception that, in case it is
    *        present, it means that the streaming ended in error.
    */
  final case class Halt[F[_]](ex: Option[Throwable])
    extends Enumerator[F,Nothing]

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


