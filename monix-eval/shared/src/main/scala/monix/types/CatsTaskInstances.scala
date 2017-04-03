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

import cats.{CoflatMap, MonadError}
import monix.eval.Task

/** Defines the list of implemented type-classes from Cats for
  * [[monix.eval.Task Task]].
  */
trait CatsTaskInstances[F[+A] <: monix.eval.Task[A]] extends MonadError[F, Throwable] with CoflatMap[F]

/** Concrete [[monix.eval.Task Task]] integration with Cats Type-classes. */
class CatsSerialTaskInstances extends CatsTaskInstances[Task] {
  override def pure[A](a: A): Task[A] =
    Task.now(a)
  override def flatMap[A, B](fa: Task[A])(f: (A) => Task[B]): Task[B] =
    fa.flatMap(f)
  override def flatten[A](ffa: Task[Task[A]]): Task[A] =
    ffa.flatten
  override def tailRecM[A, B](a: A)(f: (A) => Task[Either[A, B]]): Task[B] =
    Task.tailRecM(a)(f)
  override def coflatMap[A, B](fa: Task[A])(f: (Task[A]) => B): Task[B] =
    Task.eval(f(fa))
  override def ap[A, B](ff: Task[(A) => B])(fa: Task[A]): Task[B] =
    for (f <- ff; a <- fa) yield f(a)
  override def map2[A, B, Z](fa: Task[A], fb: Task[B])(f: (A, B) => Z): Task[Z] =
    for (a <- fa; b <- fb) yield f(a,b)
  override def map[A, B](fa: Task[A])(f: (A) => B): Task[B] =
    fa.map(f)
  override def raiseError[A](e: Throwable): Task[A] =
    Task.raiseError(e)
  override def handleError[A](fa: Task[A])(f: (Throwable) => A): Task[A] =
    fa.onErrorHandle(f)
  override def handleErrorWith[A](fa: Task[A])(f: (Throwable) => Task[A]): Task[A] =
    fa.onErrorHandleWith(f)
  override def recover[A](fa: Task[A])(pf: PartialFunction[Throwable, A]): Task[A] =
    fa.onErrorRecover(pf)
  override def recoverWith[A](fa: Task[A])(pf: PartialFunction[Throwable, Task[A]]): Task[A] =
    fa.onErrorRecoverWith(pf)
}

/** Default Cats instances for [[monix.eval.Task Task]]. */
object CatsSerialTaskInstances extends CatsSerialTaskInstances

/** Type-class instances for [[monix.eval.Task Task]]
  * that have nondeterministic effects for [[cats.Applicative]].
  */
class CatsParallelTaskInstances extends CatsSerialTaskInstances {
  override def ap[A, B](ff: Task[(A) => B])(fa: Task[A]): Task[B] =
    Task.mapBoth(ff,fa)(_(_))
  override def map2[A, B, Z](fa: Task[A], fb: Task[B])(f: (A, B) => Z): Task[Z] =
    Task.mapBoth(fa, fb)(f)
  override def product[A, B](fa: Task[A], fb: Task[B]): Task[(A, B)] =
    Task.zip2(fa, fb)
  override def ap2[A, B, Z](ff: Task[(A, B) => Z])(fa: Task[A], fb: Task[B]): Task[Z] =
    Task.zipMap3(ff, fa, fb)(_(_,_))
}

/** Default Cats instances for [[monix.eval.Task Task]] that
  * have nondeterministic effects for [[cats.Applicative]].
  */
object CatsParallelTaskInstances extends CatsParallelTaskInstances