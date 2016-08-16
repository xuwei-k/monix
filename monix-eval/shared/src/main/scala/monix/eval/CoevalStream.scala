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
import scala.annotation.unchecked.{uncheckedVariance => uV}
import scala.collection.generic.CanBuildFrom
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag
import scala.util.control.NonFatal

/** An `CoevalStream` represents a [[Coeval]]-based stream, that
  * has potentially lazy behavior in the tail and that can
  * be traversed synchronously.
  *
  * A `CoevalStream` is similar to other Scala collections:
  *
  *  1. it can be infinite
  *  2. it can be lazy
  *
  * It's very similar to other lazy types in Scala's standard
  * library, like `Iterator`, however the execution model is more
  * flexible, as it is controlled by [[Coeval]]. This means that:
  *
  *  1. you can have the equivalent of an `Iterable` if the
  *     `Coeval` tails are built with [[Coeval.evalAlways]]
  *  2. you can have the equivalent of a Scala `Stream`, caching
  *     elements as the stream is getting traversed, if the
  *     `Coeval` tails are built with [[Coeval.evalOnce]]
  *  3. it can be completely strict and thus equivalent with
  *     `List`, if the tails are built with [[Coeval.now]]
  *
  * The implementation is practically wrapping the generic
  * [[Enumerator]], initialized with the [[Coeval]] type.
  */
final case class CoevalStream[+A](enumerator: Enumerator[Coeval,A])
  extends StreamLike[A,Coeval,CoevalStream]()(Coeval.typeClassInstances) { self =>

  protected def transform[B](f: (Enumerator[Coeval,A]) => Enumerator[Coeval,B]): CoevalStream[B] = {
    val next = try f(enumerator) catch { case NonFatal(ex) => Enumerator.raiseError[Coeval,B](ex) }
    CoevalStream(next)
  }

  /** Converts this stream into another collection type,
    * by copying all elements.
    *
    * @tparam Col The collection type to build.
    * @return a new collection containing all elements of this stream.
    */
  def to[Col[_]](implicit cbf: CanBuildFrom[Nothing, A, Col[A @uV]]): Col[A @uV] = {
    val buffer = cbf()
    var cursor = self.enumerator

    while (cursor != null) cursor match {
      case NextEl(head, tail, _) =>
        buffer += head
        cursor = tail.value
      case NextSeq(seq, tail, _) =>
        buffer ++= seq
        cursor = tail.value
      case Halt(exOpt) =>
        for (ex <- exOpt) throw ex
        cursor = null
    }

    buffer.result
  }

  /** Converts this stream to a `Buffer`.
    *
    * NOTE: this operation is dangerous, because [[CoevalStream]]
    * can be very large or infinite, due to its lazy nature.
    */
  def toBuffer[B >: A]: mutable.Buffer[B] =
    to[ArrayBuffer].asInstanceOf[mutable.Buffer[B]]

  /** Converts this stream to a `List`.
    *
    * NOTE: this operation is dangerous, because [[CoevalStream]]
    * can be very large or infinite, due to its lazy nature.
    */
  def toList: List[A] = to[List]

  /** Converts this stream into a standard `Array`.
    *
    * NOTE: this operation is dangerous, because [[CoevalStream]]
    * can be very large or infinite, due to its lazy nature.
    */
  def toArray[B >: A : ClassTag]: Array[B] = toBuffer.toArray

  /** Converts this stream into a `Vector`.
    *
    * NOTE: this operation is dangerous, because [[CoevalStream]]
    * can be very large or infinite, due to its lazy nature.
    */
  def toVector: Vector[A] = to[Vector]

  /** Converts this stream to a `Traversable`.
    *
    * Operation is safe, as `Traversable` can describe
    * really large or infinite streams.
    */
  def toTraversable: Traversable[A] = toIterable

  /** Converts this stream to an `Iterable`.
    *
    * Operation is safe, as `Iterable` can describe
    * really large or infinite streams.
    */
  def toIterable: Iterable[A] =
    new Iterable[A] {
      def iterator: Iterator[A] = new Iterator[A] {
        private[this] var cursor = self.enumerator
        private[this] val queue = mutable.Queue.empty[A]

        private def advance(): Unit =
          if (queue.isEmpty) cursor match {
            case NextEl(head, tail, _) =>
              queue.enqueue(head)
              cursor = tail.value
            case NextSeq(seq, tail, _) =>
              queue.enqueue(seq:_*)
              cursor = tail.value
            case Halt(exOpt) =>
              for (ex <- exOpt) throw ex
          }

        def hasNext: Boolean = {
          advance()
          queue.nonEmpty
        }

        def next(): A =
          queue.dequeue()
      }
    }

  /** Converts this stream into a [[TaskStream]], that is capable
    * of asynchronous execution.
    */
  def toTaskStream: TaskStream[A] = {
    def convert(stream: Enumerator[Coeval,A]): Enumerator[Task,A] =
      stream match {
        case NextEl(elem, rest, cancel) =>
          NextEl(elem, rest.task.map(convert), cancel.task)
        case NextSeq(elems, rest, cancel) =>
          NextSeq(elems, rest.task.map(convert), cancel.task)
        case empty @ Halt(ex) =>
          Halt[Task](ex)
      }

    TaskStream(convert(enumerator))
  }

  /** Consumes the stream and for each element execute the given function. */
  def foreach(f: A => Unit): Unit =
    foreachL(f).value
}

object CoevalStream extends StreamLikeBuilders[Coeval, CoevalStream] {
  override def fromEnumerator[A](stream: Enumerator[Coeval,A]): CoevalStream[A] =
    CoevalStream(stream)
}