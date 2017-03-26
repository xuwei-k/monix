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

package monix.reactive

import cats.Eq
import minitest.SimpleTestSuite
import minitest.laws.Checkers
import monix.eval.Callback
import monix.execution.internal.Platform
import monix.execution.schedulers.TestScheduler
import org.scalacheck.{Arbitrary, Cogen, Prop}
import org.scalacheck.Test.Parameters
import org.typelevel.discipline.Laws

import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._

trait BaseLawsSuite extends SimpleTestSuite with Checkers with ArbitraryInstances {
  def defaultParams: Parameters = {
    Parameters.default
      .withMinSuccessfulTests(if (Platform.isJVM) 100 else 10)
      .withMaxDiscardRatio(if (Platform.isJVM) 5.0f else 50.0f)
  }

  override lazy val checkConfig: Parameters =
    defaultParams
  lazy val slowCheckConfig: Parameters =
    defaultParams.withMaxSize(32)

  /** Checks all given Discipline rules. */
  def checkAll(name: String, ruleSet: Laws#RuleSet, config: Parameters = checkConfig): Unit = {
    for ((id, prop: Prop) ← ruleSet.all.properties)
      test(name + "." + id) {
        check(prop)
      }
  }
}

trait ArbitraryInstances extends ArbitraryInstances0 {
  implicit def equalityObsObs[A : Eq]: Eq[Observable[Observable[A]]] =
    new Eq[Observable[Observable[A]]] {
      def eqv(x: Observable[Observable[A]], y: Observable[Observable[A]]): Boolean =
        equalityObservable[A].eqv(x.flatten, y.flatten)
    }
}

trait ArbitraryInstances0 extends monix.eval.ArbitraryInstances {
  implicit def arbitraryObservable[A : Arbitrary]: Arbitrary[Observable[A]] =
    Arbitrary {
      implicitly[Arbitrary[List[A]]].arbitrary
        .map(Observable.fromIterable)
    }

  implicit def equalityObservable[A : Eq]: Eq[Observable[A]] =
    new Eq[Observable[A]] {
      def eqv(x: Observable[A], y: Observable[A]): Boolean = {
        implicit val scheduler = TestScheduler()

        var valueA = Option.empty[Try[Option[List[A]]]]
        var valueB = Option.empty[Try[Option[List[A]]]]

        x.foldLeftF(List.empty[A])((acc,e) => e :: acc).firstOptionL.runAsync(
          new Callback[Option[List[A]]] {
            def onError(ex: Throwable): Unit =
              valueA = Some(Failure(ex))
            def onSuccess(value: Option[List[A]]): Unit =
              valueA = Some(Success(value))
          })

        y.foldLeftF(List.empty[A])((acc,e) => e :: acc).firstOptionL.runAsync(
          new Callback[Option[List[A]]] {
            def onError(ex: Throwable): Unit =
              valueB = Some(Failure(ex))
            def onSuccess(value: Option[List[A]]): Unit =
              valueB = Some(Success(value))
          })

        // simulate synchronous execution
        scheduler.tick(1.hour)
        valueA == valueB
      }
    }

  implicit def cogenForObservable[A]: Cogen[Observable[A]] =
    Cogen[Unit].contramap(_ => ())
}