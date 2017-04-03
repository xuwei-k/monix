/*
 * Copyright (c) 2014-2017 by its authors. Some rights reserved.
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

package monix.types

import cats.{Applicative, Group}
import monix.eval.Task

/** Instance defined for all `A` for which a [[cats.Group]] is defined,
  * providing a `Group` implementation for all `Task[A]`.
  *
  * @param F is the [[cats.Applicative]] instance for our [[monix.eval.Task]]
  * @param A is the [[cats.Group]] restriction for our `A` type
  */
class CatsTaskGroupInstance[A](implicit F: Applicative[Task], A: Group[A])
  extends CatsTaskMonoidInstance[A] with CatsTaskGroupInstance.TaskGroup[A] {

  override final def inverse(a: Task[A]): Task[A] =
    a.map(A.inverse)
}

object CatsTaskGroupInstance {
  /** Indirection to avoid class loading issues. */
  trait TaskGroup[A] extends Group[Task[A]]
}