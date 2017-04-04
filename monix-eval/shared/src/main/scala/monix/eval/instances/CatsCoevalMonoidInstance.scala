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

package monix.eval.instances

import cats.{Applicative, Monoid}
import monix.eval.Coeval

/** Instance defined for all `A` for which a [[cats.Monoid]] is defined,
  * providing a `Monoid` implementation for all `Coeval[A]`.
  *
  * @param F is the [[cats.Applicative]] instance for our [[monix.eval.Coeval]]
  * @param A is the [[cats.Monoid]] restriction for our `A` type
  */
class CatsCoevalMonoidInstance[A](implicit F: Applicative[Coeval], A: Monoid[A])
  extends CatsCoevalSemigroupInstance[A] with CatsCoevalMonoidInstance.CoevalMonoid[A] {

  override final def empty: Coeval[A] =
    Coeval.now(A.empty)
}

object CatsCoevalMonoidInstance {
  /** Indirection to avoid class loading issues. */
  trait CoevalMonoid[A] extends Monoid[Coeval[A]]
}
