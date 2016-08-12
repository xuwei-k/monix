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

import monix.execution.{CancelableFuture, Scheduler}
import scala.util.control.NonFatal

/** A `TaskStream` represents a [[Task]]-based stream, that
  * has potentially lazy behavior in the tail and that
  * support asynchronous behavior.
  *
  * A `TaskStream` has the following characteristics:
  *
  *  1. it can be infinite
  *  2. it can be lazy
  *  3. it can be asynchronous
  *
  * It's very similar to other lazy types in Scala's standard
  * library, like `Iterator`, however the execution model is more
  * flexible, as it is controlled by [[Task]]. This means that:
  *
  *  1. you can have the equivalent of an `Iterable` if the
  *     `Task` tails are built with [[Task.evalAlways]]
  *  2. you can have the equivalent of a Scala `Stream`, caching
  *     elements as the stream is getting traversed, if the
  *     `Task` tails are built with [[Task.evalOnce]]
  *  3. it can be completely strict and thus equivalent with
  *     `List`, if the tails are built with [[Task.now]]
  *  4. it supports asynchronous behavior and can also replace
  *     `Observable` for simple use-cases - for example the
  *     elements produced can be the result of asynchronous
  *     HTTP requests
  *
  * The implementation is practically wrapping the generic
  * [[Enumerator]], initialized with the [[Task]] type.
  */
final case class TaskStream[+A](enumerator: Enumerator[Task,A])
  extends StreamLike[A,Task,TaskStream]()(Task.typeClassInstances) {

  protected def transform[B](f: (Enumerator[Task,A]) => Enumerator[Task,B]): TaskStream[B] = {
    val next = try f(enumerator) catch { case NonFatal(ex) => Enumerator.raiseError[Task,B](ex) }
    TaskStream(next)
  }

  /** Consumes the stream and for each element
    * execute the given function.
    */
  def foreach(f: A => Unit)(implicit s: Scheduler): CancelableFuture[Unit] =
    foreachL(f).runAsync
}

object TaskStream extends StreamLikeBuilders[Task, TaskStream] {
  override def fromEnumerator[A](stream: Enumerator[Task,A]): TaskStream[A] =
    TaskStream(stream)
}