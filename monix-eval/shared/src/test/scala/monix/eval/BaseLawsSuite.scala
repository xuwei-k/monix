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

import cats.{Eq, Eval}
import minitest.SimpleTestSuite
import minitest.laws.Checkers
import monix.execution.internal.Platform
import monix.execution.schedulers.TestScheduler
import org.scalacheck.{Arbitrary, Cogen, Prop}
import org.scalacheck.Test.Parameters
import org.typelevel.discipline.Laws
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

trait BaseLawsSuite extends SimpleTestSuite with Checkers with ArbitraryInstances {
  override lazy val checkConfig: Parameters =
    Parameters.default
      .withMinSuccessfulTests(if (Platform.isJVM) 100 else 10)
      .withMaxDiscardRatio(if (Platform.isJVM) 5.0f else 50.0f)

  lazy val slowCheckConfig: Parameters =
    Parameters.default
      .withMinSuccessfulTests(10)
      .withMaxDiscardRatio(50.0f)
      .withMaxSize(6)

  /** Checks all given Discipline rules. */
  def checkAll(name: String, ruleSet: Laws#RuleSet, config: Parameters = checkConfig): Unit = {
    for ((id, prop: Prop) ← ruleSet.all.properties)
      test(name + "." + id) {
        check(prop)
      }
  }
}

trait ArbitraryInstances extends ArbitraryInstances0 {
  implicit def equalityCoevalCoeval[A : Eq]: Eq[Coeval[Coeval[A]]] =
    new Eq[Coeval[Coeval[A]]] {
      def eqv(x: Coeval[Coeval[A]], y: Coeval[Coeval[A]]): Boolean =
        equalityCoeval[A].eqv(x.flatten, y.flatten)
    }

  implicit def equalityTaskTask[A : Eq]: Eq[Task[Task[A]]] =
    new Eq[Task[Task[A]]] {
      def eqv(x: Task[Task[A]], y: Task[Task[A]]): Boolean =
        equalityTask[A].eqv(x.flatten, y.flatten)
    }
}

trait ArbitraryInstances0 extends cats.instances.AllInstances {
  implicit def arbitraryCoeval[A : Arbitrary]: Arbitrary[Coeval[A]] =
    Arbitrary {
      val int = implicitly[Arbitrary[Int]].arbitrary
      for (chance <- int; a <- implicitly[Arbitrary[A]].arbitrary) yield
        if (chance % 3 == 0)
          Coeval.now(a)
        else if (chance % 3 == 1)
          Coeval.eval(a)
        else
          Coeval.evalOnce(a)
    }

  implicit def arbitraryTask[A : Arbitrary]: Arbitrary[Task[A]] =
    Arbitrary {
      val aa = implicitly[Arbitrary[A]].arbitrary
      val ai = implicitly[Arbitrary[Int]].arbitrary
      for (a <- aa; i <- ai) yield {
        if (math.abs(i % 5) == 0) Task.now(a)
        else if (math.abs(i % 5) == 1 || math.abs(i % 5) == 2) Task.evalOnce(a)
        else Task.eval(a)
      }
    }

  implicit def arbitraryEval[A : Arbitrary]: Arbitrary[Eval[A]] =
    Arbitrary {
      val int = implicitly[Arbitrary[Int]].arbitrary
      val aa = implicitly[Arbitrary[A]].arbitrary

      int.flatMap(chance => aa.map(a =>
        if (chance % 3 == 0) Eval.now(a)
        else if (chance % 3 == 1) Eval.always(a)
        else Eval.later(a)))
    }

  implicit lazy val arbitraryThrowable: Arbitrary[Throwable] =
    Arbitrary {
      implicitly[Arbitrary[Int]].arbitrary
        .map(number => new RuntimeException(number.toString))
    }

  implicit def arbitrary[E : Arbitrary, A : Arbitrary]: Arbitrary[Either[E,A]] =
    Arbitrary {
      val int = implicitly[Arbitrary[Int]].arbitrary
      val aa = implicitly[Arbitrary[A]].arbitrary
      val ae = implicitly[Arbitrary[E]].arbitrary

      for (i <- int; a <- aa; e <- ae) yield
        if (i % 2 == 0) Left(e) else Right(a)
    }

  implicit lazy val throwableEq = new Eq[Throwable] {
    override def eqv(x: Throwable, y: Throwable): Boolean =
      x == y
  }

  implicit def tuple3Eq[A : Eq]: Eq[(A,A,A)] =
    new Eq[(A,A,A)] {
      val ev: Eq[A] = implicitly[Eq[A]]
      def eqv(x: (A, A, A), y: (A, A, A)): Boolean =
        ev.eqv(x._1, y._1) && ev.eqv(x._2, y._2) && ev.eqv(x._3, y._3)
    }

  implicit def tryEq[A : Eq]: Eq[Try[A]] =
    new Eq[Try[A]] {
      val optA: Eq[Option[A]] = implicitly[Eq[Option[A]]]
      val optT: Eq[Option[Throwable]] = implicitly[Eq[Option[Throwable]]]

      def eqv(x: Try[A], y: Try[A]): Boolean =
        if (x.isSuccess) optA.eqv(x.toOption, y.toOption)
        else optT.eqv(x.failed.toOption, y.failed.toOption)
    }

  implicit def equalityCoeval[A : Eq]: Eq[Coeval[A]] =
    new Eq[Coeval[A]] {
      val eqA: Eq[Try[A]] = implicitly[Eq[Try[A]]]
      def eqv(x: Coeval[A], y: Coeval[A]): Boolean =
        eqA.eqv(x.runAttempt.asScala, y.runAttempt.asScala)
    }

  implicit def equalityTask[A : Eq]: Eq[Task[A]] =
    new Eq[Task[A]] {
      def eqv(x: Task[A], y: Task[A]): Boolean = {
        implicit val scheduler = TestScheduler()

        var valueA = Option.empty[Try[A]]
        var valueB = Option.empty[Try[A]]

        x.runAsync(new Callback[A] {
          def onError(ex: Throwable): Unit =
            valueA = Some(Failure(ex))
          def onSuccess(value: A): Unit =
            valueA = Some(Success(value))
        })

        y.runAsync(new Callback[A] {
          def onError(ex: Throwable): Unit =
            valueB = Some(Failure(ex))
          def onSuccess(value: A): Unit =
            valueB = Some(Success(value))
        })

        // simulate synchronous execution
        scheduler.tick(1.hour)
        valueA == valueB
      }
    }

  implicit def cogenForThrowable: Cogen[Throwable] =
    Cogen[String].contramap(_.toString)
  implicit def cogenForTask[A]: Cogen[Task[A]] =
    Cogen[Unit].contramap(_ => ())
  implicit def cogenForCoeval[A](implicit A: Numeric[A]): Cogen[Coeval[A]] =
    Cogen((x: Coeval[A]) => A.toLong(x.value))
}