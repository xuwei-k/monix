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

/** An enumeration describing the possible evaluation
  * strategies for `Applicative[Task]`.
  */
sealed abstract class ApplicativeStrategy

object ApplicativeStrategy {
  /** The default [[ApplicativeStrategy]], currently used for getting
    * `Applicative` instances for [[monix.eval.Task Task]].
    *
    * When specified, the instance returned by
    * [[monix.eval.Task.catsTypeClassInstances Task.catsTypeClassInstances]]
    * should evaluate functions like `Applicative.map2`, `Applicative.ap` and
    * `Applicative.product` in sequence (with ordered effects, which means
    * no parallelism).
    */
  case object Serial extends ApplicativeStrategy

  /** Optional [[ApplicativeStrategy]] type that specifies parallel
    * execution for `Applicative` instances.
    *
    * When specified, the instance returned by
    * [[monix.eval.Task.catsTypeClassInstances Task.catsTypeClassInstances]]
    * should evaluate functions like `Applicative.map2`, `Applicative.ap` and
    * `Applicative.product` in parallel (ordered results, but nondeterministic
    * ordering of effects).
    */
  case object Parallel extends ApplicativeStrategy

  /** Specifies the default [[ApplicativeStrategy]]. */
  implicit val default: ApplicativeStrategy =
    ApplicativeStrategy.Serial
}