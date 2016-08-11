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
import scala.util.control.NonFatal

/** An `CoevalStream` represents a [[Coeval]]-based lazy stream.
  *
  * The implementation is practically wrapping
  * an [[Enumerator]] of [[Coeval]], provided for convenience.
  */
final case class CoevalStream[+A](stream: Enumerator[A,Coeval])
  extends StreamLike[A,Coeval,CoevalStream]()(Coeval.typeClassInstances) {

  protected def transform[B](f: (Enumerator[A, Coeval]) => Enumerator[B, Coeval]): CoevalStream[B] = {
    val next = try f(stream) catch { case NonFatal(ex) => Enumerator.Error[Coeval](ex) }
    CoevalStream(next)
  }

  /** Converts this lazy iterator into an async iterator. */
  def toAsyncIterator: TaskStream[A] = {
    def convert(stream: Enumerator[A, Coeval]): Enumerator[A, Task] =
      stream match {
        case NextEl(elem, rest) =>
          NextEl(elem, rest.task.map(convert))

        case NextSeq(elems, rest) =>
          NextSeq(elems, rest.task.map(convert))

        case Wait(rest) => Wait(rest.task.map(convert))
        case Empty() => Empty[Task]()
        case Error(ex) => Error[Task](ex)
      }

    TaskStream(convert(stream))
  }

  /** Consumes the stream and for each element execute the given function. */
  def foreach(f: A => Unit): Unit =
    foreachL(f).value
}

object CoevalStream extends StreamLikeBuilders[Coeval, CoevalStream] {
  override def fromEnumerator[A](stream: Enumerator[A, Coeval]): CoevalStream[A] =
    CoevalStream(stream)
}