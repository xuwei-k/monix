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

import cats.{Applicative, Semigroup}
import monix.eval.Coeval

/** Instance defined for all `A` for which a [[cats.Semigroup]] is defined,
  * providing a `Semigroup` implementation for all `Coeval[A]`.
  *
  * @param F is the [[cats.Applicative]] instance for our [[monix.eval.Coeval]]
  * @param A is the [[cats.Semigroup]] restriction for our `A` type
  */
class CatsCoevalSemigroupInstance[A](implicit F: Applicative[Coeval], A: Semigroup[A])
  extends CatsCoevalSemigroupInstance.CoevalSemigroup[A] {

  override final def combine(x: Coeval[A], y: Coeval[A]): Coeval[A] =
    F.map2(x, y)(A.combine)
}

object CatsCoevalSemigroupInstance {
  /** Indirection to avoid class loading issues. */
  trait CoevalSemigroup[A] extends Semigroup[Coeval[A]]
}
