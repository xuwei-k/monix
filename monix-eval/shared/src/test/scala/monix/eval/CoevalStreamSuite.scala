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

import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Success}

object CoevalStreamSuite extends BaseTestSuite {
  test("CoevalStream.filter") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val expect = Coeval.now(numbers.filter(_ % 2 == 0))
      val stream = CoevalStream.fromList(numbers).filter(_ % 2 == 0).toListL
      expect === stream
    }
  }

  test("CoevalStream.filter(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val expect = Coeval.now(numbers.filter(_ % 2 == 0))
      val stream = CoevalStream.fromList(numbers, 4).filter(_ % 2 == 0).toListL
      expect === stream
    }
  }

  test("CoevalStream.filter should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    var cancelWasTriggered = false
    var endWasReached = false

    val f = CoevalStream.now(1)
      .doOnCancel(Coeval.evalAlways { cancelWasTriggered = true })
      .doOnHalt(_ => Coeval.evalAlways { endWasReached = true })
      .filter(_ => throw ex)
      .firstL.runTry

    assertEquals(f, Failure(ex))
    assert(cancelWasTriggered, "cancelWasTriggered")
    assert(!endWasReached, "!endWasReached")
  }

  test("CoevalStream.filter(batched) should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    var cancelWasTriggered = false
    var endWasReached = false

    val f = CoevalStream.consSeq(List(1), Coeval.now(CoevalStream.empty), Coeval.unit)
      .doOnCancel(Coeval.evalAlways { cancelWasTriggered = true })
      .doOnHalt(_ => Coeval.evalAlways { endWasReached = true })
      .filter(_ => throw ex)
      .firstL.runTry

    assertEquals(f, Failure(ex))
    assert(cancelWasTriggered, "cancelWasTriggered")
    assert(!endWasReached, "!endWasReached")
  }

  test("CoevalStream.map") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val expect = Coeval.now(numbers.map(_ + 1))
      val stream = CoevalStream.fromList(numbers).map(_ + 1).toListL
      expect === stream
    }
  }

  test("CoevalStream.map(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val expect = Coeval.now(numbers.map(_ + 1))
      val stream = CoevalStream.fromList(numbers, 4).map(_ + 1).toListL
      expect === stream
    }
  }

  test("CoevalStream.map should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    var cancelWasTriggered = false
    var endWasReached = false

    val f = CoevalStream.now(1)
      .doOnCancel(Coeval.evalAlways { cancelWasTriggered = true })
      .doOnHalt(_ => Coeval.evalAlways { endWasReached = true })
      .map(_ => throw ex)
      .firstL.runTry

    assertEquals(f, Failure(ex))
    assert(cancelWasTriggered, "cancelWasTriggered")
    assert(!endWasReached, "!endWasReached")
  }

  test("CoevalStream.map(batched) should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    var cancelWasTriggered = false
    var endWasReached = false

    val f = CoevalStream.consSeq(List(1), Coeval.now(CoevalStream.empty), Coeval.unit)
      .doOnCancel(Coeval.evalAlways { cancelWasTriggered = true })
      .doOnHalt(_ => Coeval.evalAlways { endWasReached = true })
      .map(_ => throw ex)
      .firstL.runTry

    assertEquals(f, Failure(ex))
    assert(cancelWasTriggered, "cancelWasTriggered")
    assert(!endWasReached, "!endWasReached")
  }

  test("CoevalStream.flatMap") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val expect = Coeval.now(numbers.flatMap(x => List(x,x,x)))
      val stream = CoevalStream.fromList(numbers).flatMap(x => CoevalStream.fromList(List(x,x,x))).toListL
      expect === stream
    }
  }

  test("CoevalStream.flatMap(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val expect = Coeval.now(numbers.flatMap(x => List(x,x,x)))
      val stream = CoevalStream.fromList(numbers, 4).flatMap(x => CoevalStream.fromList(List(x,x,x), 2)).toListL
      expect === stream
    }
  }

  test("CoevalStream.flatMap should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    var cancelWasTriggered = false
    var endWasReached = false

    val f = CoevalStream.now(1)
      .doOnCancel(Coeval.evalAlways { cancelWasTriggered = true })
      .doOnHalt(_ => Coeval.evalAlways { endWasReached = true })
      .flatMap(_ => throw ex)
      .firstL.runTry

    assertEquals(f, Failure(ex))
    assert(cancelWasTriggered, "cancelWasTriggered")
    assert(!endWasReached, "!endWasReached")
  }

  test("CoevalStream.flatMap(batched) should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    var cancelWasTriggered = false
    var endWasReached = false

    val f = CoevalStream.consSeq(List(1), Coeval.now(CoevalStream.empty), Coeval.unit)
      .doOnCancel(Coeval.evalAlways { cancelWasTriggered = true })
      .doOnHalt(_ => Coeval.evalAlways { endWasReached = true })
      .flatMap(_ => throw ex)
      .firstL.runTry

    assertEquals(f, Failure(ex))
    assert(cancelWasTriggered, "cancelWasTriggered")
    assert(!endWasReached, "!endWasReached")
  }

  test("CoevalStream.flatten == flatMap(x => x)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers).map(x => CoevalStream.fromList(List(x,x))).flatten.toListL
      val expect = CoevalStream.fromList(numbers).flatMap(x => CoevalStream.fromList(List(x,x))).toListL
      expect === stream
    }
  }

  test("CoevalStream.concat == flatMap(x => x)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers).map(x => CoevalStream.fromList(List(x,x))).concat.toListL
      val expect = CoevalStream.fromList(numbers).flatMap(x => CoevalStream.fromList(List(x,x))).toListL
      expect === stream
    }
  }

  test("CoevalStream.concatMap == flatMap") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers).concatMap(x => CoevalStream.fromList(List(x,x))).toListL
      val expect = CoevalStream.fromList(numbers).flatMap(x => CoevalStream.fromList(List(x,x))).toListL
      expect === stream
    }
  }

  test("CoevalStream #:: elem") { implicit s =>
    check2 { (numbers: List[Int], head: Int) =>
      val expect = Coeval.now(head :: numbers)
      val stream = (head #:: CoevalStream.fromList(numbers)).toListL
      expect === stream
    }
  }

  test("CoevalStream.foldLeftL") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val expect = Coeval.now(numbers.sum)
      val stream = CoevalStream.fromList(numbers).foldLeftL(0)(_+_)
      expect === stream
    }
  }

  test("CoevalStream.foldLeftL(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val expect = Coeval.now(numbers.sum)
      val stream = CoevalStream.fromList(numbers, 4).foldLeftL(0)(_+_)
      expect === stream
    }
  }

  test("CoevalStream.foldLeftL should protect against user code, test 1") { implicit s =>
    val ex = DummyException("dummy")
    var cancelWasTriggered = false
    var endWasReached = false

    val f = CoevalStream.now(1)
      .doOnCancel(Coeval.evalAlways { cancelWasTriggered = true })
      .doOnHalt(_ => Coeval.evalAlways { endWasReached = true })
      .foldLeftL(0)((a,e) => throw ex)
      .runTry

    assertEquals(f, Failure(ex))
    assert(cancelWasTriggered, "cancelWasTriggered")
    assert(!endWasReached, "!endWasReached")
  }

  test("CoevalStream.foldLeftL should protect against user code, test 2") { implicit s =>
    val ex = DummyException("dummy")
    var cancelWasTriggered = false
    var endWasReached = false

    val f = CoevalStream.now(1)
      .doOnCancel(Coeval.evalAlways { cancelWasTriggered = true })
      .doOnHalt(_ => Coeval.evalAlways { endWasReached = true })
      .foldLeftL((throw ex) : Int)(_ + _)
      .runTry

    assertEquals(f, Failure(ex))
    assert(cancelWasTriggered, "cancelWasTriggered")
    assert(!endWasReached, "!endWasReached")
  }

  test("CoevalStream.foldLeftL(batched) should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    var cancelWasTriggered = false
    var endWasReached = false

    val f = CoevalStream.consSeq(List(1), Coeval.now(CoevalStream.empty), Coeval.unit)
      .doOnCancel(Coeval.evalAlways { cancelWasTriggered = true })
      .doOnHalt(_ => Coeval.evalAlways { endWasReached = true })
      .foldLeftL(0)((a,e) => throw ex)
      .runTry

    assertEquals(f, Failure(ex))
    assert(cancelWasTriggered, "cancelWasTriggered")
    assert(!endWasReached, "!endWasReached")
  }

  test("CoevalStream.foldWhileL") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val expect = Coeval.now(numbers.sum)
      val stream = CoevalStream.fromList(numbers).foldWhileL(0)((acc,e) => (true, acc+e))
      expect === stream
    }
  }

  test("CoevalStream.foldWhileL(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val expect = Coeval.now(numbers.sum)
      val stream = CoevalStream.fromList(numbers, 4).foldWhileL(0)((acc,e) => (true, acc+e))
      expect === stream
    }
  }

  test("CoevalStream.foldWhileL should protect against user code, test 1") { implicit s =>
    val ex = DummyException("dummy")
    var cancelWasTriggered = false
    var endWasReached = false

    val f = CoevalStream.now(1)
      .doOnCancel(Coeval.evalAlways { cancelWasTriggered = true })
      .doOnHalt(_ => Coeval.evalAlways { endWasReached = true })
      .foldWhileL(0)((a,e) => throw ex)
      .runTry

    assertEquals(f, Failure(ex))
    assert(cancelWasTriggered, "cancelWasTriggered")
    assert(!endWasReached, "!endWasReached")
  }

  test("CoevalStream.foldWhileL should protect against user code, test 2") { implicit s =>
    val ex = DummyException("dummy")
    var cancelWasTriggered = false
    var endWasReached = false

    val f = CoevalStream.now(1)
      .doOnCancel(Coeval.evalAlways { cancelWasTriggered = true })
      .doOnHalt(_ => Coeval.evalAlways { endWasReached = true })
      .foldWhileL((throw ex) : Int)((_,_) => (true,0))
      .runTry

    assertEquals(f, Failure(ex))
    assert(cancelWasTriggered, "cancelWasTriggered")
    assert(!endWasReached, "!endWasReached")
  }

  test("CoevalStream.foldWhileL(batched) should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    var cancelWasTriggered = false
    var endWasReached = false

    val f = CoevalStream
      .consSeq(List(1), Coeval.now(CoevalStream.empty), Coeval.unit)
      .doOnCancel(Coeval.evalAlways { cancelWasTriggered = true })
      .doOnHalt(_ => Coeval.evalAlways { endWasReached = true })
      .foldWhileL(0)((a,e) => throw ex)
      .runTry

    assertEquals(f, Failure(ex))
    assert(cancelWasTriggered, "cancelWasTriggered")
    assert(!endWasReached, "!endWasReached")
  }

  test("CoevalStream.foldRightL") { implicit s =>
    check1 { (numbers: List[Boolean]) =>
      val expect = Coeval.now(numbers.forall(x => x))
      val stream = CoevalStream.fromList(numbers).foldRightL(Coeval.now(true)) {
        (elem, acc) => if (elem) acc else Coeval.now(elem)
      }

      expect === stream
    }
  }

  test("CoevalStream.foldRightL(batched)") { implicit s =>
    check1 { (numbers: List[Boolean]) =>
      val expect = Coeval.now(numbers.forall(x => x))
      val stream = CoevalStream.fromList(numbers,4).foldRightL(Coeval.now(true)) {
        (elem, acc) => if (elem) acc else Coeval.now(elem)
      }

      expect === stream
    }
  }

  test("CoevalStream.foldRightL should protect against user code - when given function throws") { implicit s =>
    val ex = DummyException("dummy")
    var cancelWasTriggered = false
    var endWasReached = false

    val f = CoevalStream.now(1)
      .doOnCancel(Coeval.evalAlways { cancelWasTriggered = true })
      .doOnHalt(_ => Coeval.evalAlways { endWasReached = true })
      .foldRightL(Coeval.now(true))((_,_) => throw ex)
      .runTry

    assertEquals(f, Failure(ex))
    assert(cancelWasTriggered, "cancelWasTriggered")
    assert(!endWasReached, "!endWasReached")
  }

  test("CoevalStream.foldRightL should protect against user code - when given function returns error") { implicit s =>
    val ex = DummyException("dummy")
    var cancelWasTriggered = false
    var endWasReached = false

    val f = CoevalStream.now(1)
      .doOnCancel(Coeval.evalAlways { cancelWasTriggered = true })
      .doOnHalt(_ => Coeval.evalAlways { endWasReached = true })
      .foldRightL(Coeval.now(true))((_,_) => Coeval.raiseError(ex))
      .runTry

    assertEquals(f, Failure(ex))
    assert(cancelWasTriggered, "cancelWasTriggered")
    assert(!endWasReached, "!endWasReached")
  }


  test("CoevalStream.foldRightL(batched) should protect against user code - when given function throws") { implicit s =>
    val ex = DummyException("dummy")
    var cancelWasTriggered = false
    var endWasReached = false

    val f = CoevalStream
      .consSeq(List(1), Coeval.now(CoevalStream.empty), Coeval.unit)
      .doOnCancel(Coeval.evalAlways { cancelWasTriggered = true })
      .doOnHalt(_ => Coeval.evalAlways { endWasReached = true })
      .foldRightL(Coeval.now(true))((_,_) => throw ex)
      .runTry

    assertEquals(f, Failure(ex))
    assert(cancelWasTriggered, "cancelWasTriggered")
    assert(!endWasReached, "!endWasReached")
  }

  test("CoevalStream.foldRightL(batched) should protect against user code - when given function returns error") { implicit s =>
    val ex = DummyException("dummy")
    var cancelWasTriggered = false
    var endWasReached = false

    val f = CoevalStream
      .consSeq(List(1), Coeval.now(CoevalStream.empty), Coeval.unit)
      .doOnCancel(Coeval.evalAlways { cancelWasTriggered = true })
      .doOnHalt(_ => Coeval.evalAlways { endWasReached = true })
      .foldRightL(Coeval.now(true))((_,_) => Coeval.raiseError(ex))
      .runTry

    assertEquals(f, Failure(ex))
    assert(cancelWasTriggered, "cancelWasTriggered")
    assert(!endWasReached, "!endWasReached")
  }


  test("CoevalStream.fromList ++ CoevalStream.fromList") { implicit s =>
    check2 { (seq1: List[Int], seq2: List[Int]) =>
      val expect = Coeval.now(seq1 ++ seq2)
      val stream = (CoevalStream.fromList(seq1) ++ CoevalStream.fromList(seq2)).toListL
      expect === stream
    }
  }

  test("CoevalStream.defer(CoevalStream.fromList) ++ CoevalStream.fromList") { implicit s =>
    check2 { (seq1: List[Int], seq2: List[Int]) =>
      val expect = Coeval.now(seq1 ++ seq2)
      val stream = (CoevalStream.defer(CoevalStream.fromList(seq1)) ++ CoevalStream.fromList(seq2)).toListL
      expect === stream
    }
  }

  test("CoevalStream.fromList ++ CoevalStream.defer(CoevalStream.fromList)") { implicit s =>
    check2 { (seq1: List[Int], seq2: List[Int]) =>
      val expect = Coeval.now(seq1 ++ seq2)
      val stream = (CoevalStream.defer(CoevalStream.fromList(seq1)) ++ CoevalStream.fromList(seq2)).toListL
      expect === stream
    }
  }

  test("CoevalStream.fromList(batched) ++ CoevalStream.fromList(batched)") { implicit s =>
    check2 { (seq1: List[Int], seq2: List[Int]) =>
      val expect = Coeval.now(seq1 ++ seq2)
      val stream = (CoevalStream.fromList(seq1,4) ++ CoevalStream.fromList(seq2,4)).toListL
      expect === stream
    }
  }

  test("CoevalStream.findL") { implicit s =>
    check2 { (numbers: List[Int], n: Int) =>
      val stream = CoevalStream.fromList(numbers)
      stream.findL(_ == n) === Coeval.now(numbers.find(_ == n))
    }
  }

  test("CoevalStream.findL(batched)") { implicit s =>
    check2 { (numbers: List[Int], n: Int) =>
      val stream = CoevalStream.fromList(numbers,4)
      stream.findL(_ == n) === Coeval.now(numbers.find(_ == n))
    }
  }

  test("CoevalStream.findL is true") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers)

      numbers.lastOption match {
        case Some(toFind) =>
          stream.findL(_ == toFind) === Coeval.now(numbers.find(_ == toFind))
        case None =>
          stream.findL(_ == 0) === Coeval.now(None)
      }
    }
  }

  test("CoevalStream.existsL") { implicit s =>
    check2 { (numbers: List[Int], n: Int) =>
      val stream = CoevalStream.fromList(numbers)
      stream.existsL(_ == n) === Coeval.now(numbers.contains(n))
    }
  }

  test("CoevalStream.existsL(batched)") { implicit s =>
    check2 { (numbers: List[Int], n: Int) =>
      val stream = CoevalStream.fromList(numbers,4)
      stream.existsL(_ == n) === Coeval.now(numbers.contains(n))
    }
  }

  test("CoevalStream.forallL") { implicit s =>
    check2 { (numbers: List[Int], n: Int) =>
      val stream = CoevalStream.fromList(numbers)
      stream.forallL(_ == n) === Coeval.now(numbers.forall(_ == n))
    }
  }

  test("CoevalStream.forallL(batched)") { implicit s =>
    check2 { (numbers: List[Int], n: Int) =>
      val stream = CoevalStream.fromList(numbers,4)
      stream.forallL(_ == n) === Coeval.now(numbers.forall(_ == n))
    }
  }

  test("CoevalStream.countL") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers)
      stream.countL === Coeval.now(numbers.length)
    }
  }

  test("CoevalStream.countL(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers,4)
      stream.countL === Coeval.now(numbers.length)
    }
  }

  test("CoevalStream.sumL") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers)
      stream.sumL === Coeval.now(numbers.sum)
    }
  }

  test("CoevalStream.sumL(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers,4)
      stream.sumL === Coeval.now(numbers.sum)
    }
  }

  test("CoevalStream.isEmptyL") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers)
      stream.isEmptyL === Coeval.now(numbers.isEmpty)
    }
  }

  test("CoevalStream.isEmptyL(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers,4)
      stream.isEmptyL === Coeval.now(numbers.isEmpty)
    }
  }

  test("CoevalStream.nonEmptyL") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers)
      stream.nonEmptyL === Coeval.now(numbers.nonEmpty)
    }
  }

  test("CoevalStream.nonEmptyL(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers,4)
      stream.nonEmptyL === Coeval.now(numbers.nonEmpty)
    }
  }

  test("CoevalStream.firstL") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers)
      stream.firstL === Coeval.now(numbers.headOption)
    }
  }

  test("CoevalStream.firstL(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers,4)
      stream.firstL === Coeval.now(numbers.headOption)
    }
  }

  test("CoevalStream.headOptionL") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers)
      stream.headOptionL === Coeval.now(numbers.headOption)
    }
  }

  test("CoevalStream.headOptionL(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers,4)
      stream.headOptionL === Coeval.now(numbers.headOption)
    }
  }

  test("CoevalStream.headL") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers)
      stream.headL === Coeval.evalAlways(numbers.head)
    }
  }

  test("CoevalStream.headL(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers,4)
      stream.headL === Coeval.evalAlways(numbers.head)
    }
  }

  test("CoevalStream.take") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers)
      stream.take(5).toListL === Coeval.now(numbers.take(5))
    }
  }

  test("CoevalStream.take(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers,4)
      stream.take(5).toListL === Coeval.now(numbers.take(5))
    }
  }

  test("CoevalStream.take should cancel when done") { implicit s =>
    var wasCanceled = false
    var wasFinished = false
    val result = CoevalStream.fromList(List(1,2,3,4,5,6))
      .doOnCancel(Coeval.evalAlways { wasCanceled = true })
      .doOnHalt(_ => Coeval.evalAlways { wasFinished = true })
      .take(4)
      .toListL
      .runTry

    assertEquals(result, Success(List(1,2,3,4)))
    assert(wasCanceled, "wasCanceled")
    assert(!wasFinished, "!wasFinished")
  }

  test("CoevalStream.take(batched) should cancel when done") { implicit s =>
    var wasCanceled = false
    var wasFinished = false
    val result = CoevalStream.fromList(List(1,2,3,4,5,6), 2)
      .doOnCancel(Coeval.evalAlways { wasCanceled = true })
      .doOnHalt(_ => Coeval.evalAlways { wasFinished = true })
      .take(4)
      .toListL
      .runTry

    assertEquals(result, Success(List(1,2,3,4)))
    assert(wasCanceled, "wasCanceled")
    assert(!wasFinished, "!wasFinished")
  }

  test("CoevalStream.takeWhile") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers)
      stream.takeWhile(_ >= 0).toListL === Coeval.now(numbers.takeWhile(_ >= 0))
    }
  }

  test("CoevalStream.takeWhile(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers,4)
      stream.takeWhile(_ >= 0).toListL === Coeval.now(numbers.takeWhile(_ >= 0))
    }
  }

  test("CoevalStream.takeWhile should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    var wasCanceled = false
    var wasFinished = false

    val result = CoevalStream.fromList(List(1,2,3,4,5,6))
      .doOnCancel(Coeval.evalAlways { wasCanceled = true })
      .doOnHalt(_ => Coeval.evalAlways { wasFinished = true })
      .takeWhile(_ => throw ex)
      .toListL
      .runTry

    assertEquals(result, Failure(ex))
    assert(wasCanceled, "wasCanceled")
    assert(!wasFinished, "!wasFinished")
  }

  test("CoevalStream.takeWhile should cancel when done") { implicit s =>
    var wasCanceled = false
    var wasFinished = false
    val result = CoevalStream.fromList(List(1,2,3,4,5,6))
      .doOnCancel(Coeval.evalAlways { wasCanceled = true })
      .doOnHalt(_ => Coeval.evalAlways { wasFinished = true })
      .takeWhile(_ <= 4)
      .toListL
      .runTry

    assertEquals(result, Success(List(1,2,3,4)))
    assert(wasCanceled, "wasCanceled")
    assert(!wasFinished, "!wasFinished")
  }

  test("CoevalStream.takeWhile(batched) should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    var wasCanceled = false
    var wasFinished = false

    val result = CoevalStream.fromList(List(1,2,3,4,5,6), 2)
      .doOnCancel(Coeval.evalAlways { wasCanceled = true })
      .doOnHalt(_ => Coeval.evalAlways { wasFinished = true })
      .takeWhile(_ => throw ex)
      .toListL
      .runTry

    assertEquals(result, Failure(ex))
    assert(wasCanceled, "wasCanceled")
    assert(!wasFinished, "!wasFinished")
  }

  test("CoevalStream.takeWhile(batched) should cancel when done") { implicit s =>
    var wasCanceled = false
    var wasFinished = false
    val result = CoevalStream.fromList(List(1,2,3,4,5,6), 2)
      .doOnCancel(Coeval.evalAlways { wasCanceled = true })
      .doOnHalt(_ => Coeval.evalAlways { wasFinished = true })
      .takeWhile(_ <= 4)
      .toListL
      .runTry

    assertEquals(result, Success(List(1,2,3,4)))
    assert(wasCanceled, "wasCanceled")
    assert(!wasFinished, "!wasFinished")
  }

  test("CoevalStream.drop") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers)
      stream.drop(5).toListL === Coeval.now(numbers.drop(5))
    }
  }

  test("CoevalStream.drop(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers,4)
      stream.drop(5).toListL === Coeval.now(numbers.drop(5))
    }
  }

  test("CoevalStream.dropWhile") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers)
      stream.dropWhile(_ % 2 == 0).toListL === Coeval.now(numbers.dropWhile(_ % 2 == 0))
    }
  }

  test("CoevalStream.dropWhile(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers,4)
      stream.dropWhile(_ % 2 == 0).toListL === Coeval.now(numbers.dropWhile(_ % 2 == 0))
    }
  }

  test("CoevalStream.dropWhile should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    var wasCanceled = false
    var wasFinished = false

    val result = CoevalStream.fromList(List(1,2,3,4,5,6))
      .doOnCancel(Coeval.evalAlways { wasCanceled = true })
      .doOnHalt(_ => Coeval.evalAlways { wasFinished = true })
      .dropWhile(_ => throw ex)
      .toListL
      .runTry

    assertEquals(result, Failure(ex))
    assert(wasCanceled, "wasCanceled")
    assert(!wasFinished, "!wasFinished")
  }

  test("CoevalStream.dropWhile(batched) should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    var wasCanceled = false
    var wasFinished = false

    val result = CoevalStream.fromList(List(1,2,3,4,5,6),2)
      .doOnCancel(Coeval.evalAlways { wasCanceled = true })
      .doOnHalt(_ => Coeval.evalAlways { wasFinished = true })
      .dropWhile(_ => throw ex)
      .toListL
      .runTry

    assertEquals(result, Failure(ex))
    assert(wasCanceled, "wasCanceled")
    assert(!wasFinished, "!wasFinished")
  }

  test("CoevalStream.memoize") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers)
      stream.memoize.toListL === Coeval.now(numbers)
    }
  }

  test("CoevalStream.memoize(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers,4)
      stream.memoize.toListL === Coeval.now(numbers)
    }
  }

  test("CoevalStream.onErrorHandleWith equivalence") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers).onErrorHandleWith(_ => CoevalStream.empty)
      stream.memoize.toListL === Coeval.now(numbers)
    }
  }

  test("CoevalStream.onErrorHandleWith(batched) equivalence") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers,4).onErrorHandleWith(_ => CoevalStream.empty)
      stream.memoize.toListL === Coeval.now(numbers)
    }
  }

  test("CoevalStream.onErrorHandleWith recovers from stream errors") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val ex = DummyException("dummy")
      val recovery = List(1,2,3)
      val stream = (CoevalStream.fromList(numbers) ++ CoevalStream.raiseError[Int](ex))
        .onErrorHandleWith { case `ex` => CoevalStream.fromList(recovery) }
      stream.memoize.toListL === Coeval.now(numbers ++ recovery)
    }
  }

  test("CoevalStream.onErrorHandleWith recovers from F errors") { implicit s =>
    val ex = DummyException("dummy")
    val stream = (1 #:: 2 #:: CoevalStream.cons(3, Coeval.raiseError(ex), Coeval.unit))
      .onErrorHandleWith { case `ex` => CoevalStream(4,5) }

    val f = stream.toListL.runTry
    assertEquals(f, Success(List(1,2,3,4,5)))
  }

  test("CoevalStream.onErrorHandle equivalence") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers).onErrorHandle(_ => 0)
      stream.memoize.toListL === Coeval.now(numbers)
    }
  }

  test("CoevalStream.onErrorHandle(batched) equivalence") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers,4).onErrorHandle(_ => 0)
      stream.memoize.toListL === Coeval.now(numbers)
    }
  }

  test("CoevalStream.onErrorHandle recovers") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val ex = DummyException("dummy")
      val stream = (CoevalStream.fromList(numbers) ++ CoevalStream.raiseError[Int](ex))
        .onErrorHandle { case `ex` => 1 }
      stream.memoize.toListL === Coeval.now(numbers :+ 1)
    }
  }

  test("CoevalStream.onErrorRecoverWith equivalence") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers).onErrorRecoverWith { case _ => CoevalStream.empty }
      stream.memoize.toListL === Coeval.now(numbers)
    }
  }

  test("CoevalStream.onErrorRecoverWith(batched) equivalence") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers,4).onErrorRecoverWith { case _ => CoevalStream.empty }
      stream.memoize.toListL === Coeval.now(numbers)
    }
  }

  test("CoevalStream.onErrorRecoverWith recovers") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val ex = DummyException("dummy")
      val recovery = List(1,2,3)
      val stream = (CoevalStream.fromList(numbers) ++ CoevalStream.raiseError[Int](ex))
        .onErrorRecoverWith { case `ex` => CoevalStream.fromList(recovery) }
      stream.memoize.toListL === Coeval.now(numbers ++ recovery)
    }
  }

  test("CoevalStream.onErrorRecover equivalence") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers).onErrorRecover { case _ => 0 }
      stream.memoize.toListL === Coeval.now(numbers)
    }
  }

  test("CoevalStream.onErrorRecover(batched) equivalence") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers,4).onErrorRecover { case _ => 0 }
      stream.memoize.toListL === Coeval.now(numbers)
    }
  }

  test("CoevalStream.onErrorRecover recovers") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val ex = DummyException("dummy")
      val stream = (CoevalStream.fromList(numbers) ++ CoevalStream.raiseError[Int](ex))
        .onErrorRecover { case `ex` => 1 }
      stream.memoize.toListL === Coeval.now(numbers :+ 1)
    }
  }

  test("CoevalStream.completedL") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers)
      stream.completedL === Coeval.now(())
    }
  }

  test("CoevalStream.completedL(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers)
      stream.completedL === Coeval.now(())
    }
  }

  test("CoevalStream.foreachL") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val task = Coeval.evalAlways(ListBuffer.empty[Int]).flatMap { buffer =>
        val f = CoevalStream.fromList(numbers).foreachL(n => buffer.append(n))
        f.map(_ => buffer.toList)
      }

      task === Coeval.now(numbers)
    }
  }

  test("CoevalStream.foreachL(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val task = Coeval.evalAlways(ListBuffer.empty[Int]).flatMap { buffer =>
        val f = CoevalStream.fromList(numbers,4).foreachL(n => buffer.append(n))
        f.map(_ => buffer.toList)
      }

      task === Coeval.now(numbers)
    }
  }

  test("CoevalStream.foreachL should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    var cancelWasTriggered = false
    var endWasReached = false

    val f = CoevalStream.now(1)
      .doOnCancel(Coeval.evalAlways { cancelWasTriggered = true })
      .doOnHalt(_ => Coeval.evalAlways { endWasReached = true })
      .foreachL(_ => throw ex)
      .runTry

    assertEquals(f, Failure(ex))
    assert(cancelWasTriggered, "cancelWasTriggered")
    assert(!endWasReached, "!endWasReached")
  }

  test("CoevalStream.foreach") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val task = Coeval.evalAlways(ListBuffer.empty[Int]).map { buffer =>
        CoevalStream.fromList(numbers).foreach(n => buffer.append(n))
        buffer.toList
      }

      task === Coeval.now(numbers)
    }
  }

  test("CoevalStream.foreach(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val task = Coeval.evalAlways(ListBuffer.empty[Int]).map { buffer =>
        CoevalStream.fromList(numbers,4).foreach(n => buffer.append(n))
        buffer.toList
      }

      task === Coeval.now(numbers)
    }
  }

  test("CoevalStream.evalAlways") { implicit s =>
    val f = CoevalStream.evalAlways(10).toListL.runTry
    assertEquals(f, Success(List(10)))
  }

  test("CoevalStream.evalAlways should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    val f = CoevalStream.evalAlways[Int](throw ex).toListL.runTry
    assertEquals(f, Failure(ex))
  }

  test("CoevalStream.evalOnce") { implicit s =>
    val f = CoevalStream.evalOnce(10).toListL.runTry
    assertEquals(f, Success(List(10)))
  }

  test("CoevalStream.evalOnce should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    val f = CoevalStream.evalOnce[Int](throw ex).toListL.runTry
    assertEquals(f, Failure(ex))
  }

  test("CoevalStream.fromIterable(batch=1)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val task = CoevalStream.fromIterable(numbers, 1).toListL
      val expect = CoevalStream.fromList(numbers, 100).toListL
      task === expect
    }
  }

  test("CoevalStream.fromIterable(batch=4)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val task = CoevalStream.fromIterable(numbers, 4).toListL
      val expect = CoevalStream.fromList(numbers, 100).toListL
      task === expect
    }
  }

  test("CoevalStream.fromIterable(batch=1) (Java)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      import collection.JavaConverters._
      val task = CoevalStream.fromIterable(numbers.asJava, 1).toListL
      val expect = CoevalStream.fromList(numbers, 100).toListL
      task === expect
    }
  }

  test("CoevalStream.fromIterable(batch=4) (Java)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      import collection.JavaConverters._
      val task = CoevalStream.fromIterable(numbers.asJava, 4).toListL
      val expect = CoevalStream.fromList(numbers, 100).toListL
      task === expect
    }
  }

  test("CoevalStream.zip2") { implicit s =>
    import CoevalStream._
    check2 { (nums1: List[Int], nums2: List[Int]) =>
      val stream = zip2(fromList(nums1), fromList(nums2)).toListL
      val expected = Coeval.now(nums1.zip(nums2))
      stream === expected
    }
  }

  test("CoevalStream.zip2(batched left)") { implicit s =>
    import CoevalStream._
    check2 { (nums1: List[Int], nums2: List[Int]) =>
      val stream = zip2(fromList(nums1,4), fromList(nums2)).toListL
      val expected = Coeval.now(nums1.zip(nums2))
      stream === expected
    }
  }

  test("CoevalStream.zip2(batched right)") { implicit s =>
    import CoevalStream._
    check2 { (nums1: List[Int], nums2: List[Int]) =>
      val stream = zip2(fromList(nums1), fromList(nums2,4)).toListL
      val expected = Coeval.now(nums1.zip(nums2))
      stream === expected
    }
  }

  test("CoevalStream.zip2(batched both)") { implicit s =>
    import CoevalStream._
    check2 { (nums1: List[Int], nums2: List[Int]) =>
      val stream = zip2(fromList(nums1,4), fromList(nums2,4)).toListL
      val expected = Coeval.now(nums1.zip(nums2))
      stream === expected
    }
  }

  test("CoevalStream.zipMap2 should protect against user code") { implicit s =>
    import CoevalStream._
    val ex = DummyException("dummy")

    var stream1Ended = false
    var stream1Canceled = false
    val stream1 = apply(1,2)
      .doOnCancel(Coeval.evalAlways { stream1Canceled = true })
      .doOnHalt(_ => Coeval.evalAlways { stream1Ended = true })

    var stream2Ended = false
    var stream2Canceled = false
    val stream2 = apply(3,4)
      .doOnCancel(Coeval.evalAlways { stream2Canceled = true })
      .doOnHalt(_ => Coeval.evalAlways { stream2Ended = true })

    val f = zipMap2[Int,Int,Int](stream1, stream2)((a,b) => throw ex)
      .toListL.runTry

    assertEquals(f, Failure(ex))
    assert(stream1Canceled, "stream1Canceled")
    assert(!stream1Ended, "!stream1Ended")
    assert(stream2Canceled, "stream2Canceled")
    assert(!stream2Ended, "!stream2Ended")
  }

  test("CoevalStream.zip2 ends in error if left ends in error") { implicit s =>
    import CoevalStream._
    val ex = DummyException("dummy")

    var stream1Ended = false
    var stream1Canceled = false
    val stream1 = apply(1,2)
      .doOnCancel(Coeval.evalAlways { stream1Canceled = true })
      .doOnHalt(_ => Coeval.evalAlways { stream1Ended = true })

    var stream2Ended = false
    var stream2Canceled = false
    val stream2 = apply(3,4,5)
      .doOnCancel(Coeval.evalAlways { stream2Canceled = true })
      .doOnHalt(_ => Coeval.evalAlways { stream2Ended = true })

    val f = zip2[Int,Int,Int](stream1 ++ raiseError(ex), stream2)
      .toListL.runTry

    assertEquals(f, Failure(ex))
    assert(!stream1Canceled, "!stream1Canceled")
    assert(stream1Ended, "stream1Ended")
    assert(stream2Canceled, "stream2Canceled")
    assert(!stream2Ended, "!stream2Ended")
  }

  test("CoevalStream.zip2 ends in error if right ends in error") { implicit s =>
    import CoevalStream._
    val ex = DummyException("dummy")

    var stream1Ended = false
    var stream1Canceled = false
    val stream1 = apply(1,2,3)
      .doOnCancel(Coeval.evalAlways { stream1Canceled = true })
      .doOnHalt(_ => Coeval.evalAlways { stream1Ended = true })

    var stream2Ended = false
    var stream2Canceled = false
    val stream2 = apply(3,4)
      .doOnCancel(Coeval.evalAlways { stream2Canceled = true })
      .doOnHalt(_ => Coeval.evalAlways { stream2Ended = true })

    val f = zip2[Int,Int,Int](stream1, stream2 ++ raiseError(ex))
      .toListL.runTry

    assertEquals(f, Failure(ex))
    assert(stream1Canceled, "stream1Canceled")
    assert(!stream1Ended, "!stream1Ended")
    assert(!stream2Canceled, "!stream2Canceled")
    assert(stream2Ended, "stream2Ended")
  }

  test("CoevalStream.zip3") { implicit s =>
    import CoevalStream._
    check2 { (nums1: List[Int], nums2: List[Int]) =>
      val stream = zip3(fromList(nums1,4), fromList(nums2,4), fromList(nums1,4)).toListL
      val expected = Coeval.now(nums1.zip(nums2).zip(nums1).map { case ((a,b), c) => (a,b,c) })
      stream === expected
    }
  }

  test("CoevalStream.zip4") { implicit s =>
    import CoevalStream._
    check2 { (nums1: List[Int], nums2: List[Int]) =>
      val stream = zip4(fromList(nums1,4), fromList(nums2,4), fromList(nums1,4), fromList(nums2,4)).toListL
      val expected = Coeval.now(nums1.zip(nums2).zip(nums1).zip(nums2).map { case (((a,b), c), d) => (a,b,c,d) })
      stream === expected
    }
  }

  test("CoevalStream.zip5") { implicit s =>
    import CoevalStream._
    check2 { (nums1: List[Int], nums2: List[Int]) =>
      val stream = zip5(fromList(nums1,4), fromList(nums2,4), fromList(nums1,4), fromList(nums2,4), fromList(nums1,4)).toListL
      val expected = Coeval.now(nums1.zip(nums2).zip(nums1).zip(nums2).zip(nums1).map { case ((((a,b), c), d), e) => (a,b,c,d,e) })
      stream === expected
    }
  }

  test("CoevalStream.zip6") { implicit s =>
    import CoevalStream._
    check2 { (nums1: List[Int], nums2: List[Int]) =>
      val stream = zip6(fromList(nums1,4), fromList(nums2,4), fromList(nums1,4), fromList(nums2,4), fromList(nums1,4), fromList(nums2, 4)).toListL
      val expected = Coeval.now(nums1.zip(nums2).zip(nums1).zip(nums2).zip(nums1).zip(nums2).map { case (((((a,b), c), d), e), f) => (a,b,c,d,e,f) })
      stream === expected
    }
  }
  // Coeval specific -----

  test("CoevalStream.toIterable") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val coeval = CoevalStream.fromList(numbers).toIterable.toList
      coeval == numbers
    }
  }

  test("CoevalStream.toIterable(batched)") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val coeval = CoevalStream.fromList(numbers,4).toIterable.toList
      coeval == numbers
    }
  }

  test("CoevalStream.toTraversable") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val coeval = CoevalStream.fromList(numbers).toTraversable.toList
      coeval == numbers
    }
  }

  test("CoevalStream.toVector") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val coeval = CoevalStream.fromList(numbers).toVector.toList
      coeval == numbers
    }
  }

  test("CoevalStream.toArray") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val coeval = CoevalStream.fromList(numbers).toArray.toList
      coeval == numbers
    }
  }

  test("CoevalStream.toList") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val coeval = CoevalStream.fromList(numbers).toList
      coeval == numbers
    }
  }

  test("CoevalStream.toTaskStream") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val stream = CoevalStream.fromList(numbers).toTaskStream.toListL
      stream === Task.now(numbers)
    }
  }
}
