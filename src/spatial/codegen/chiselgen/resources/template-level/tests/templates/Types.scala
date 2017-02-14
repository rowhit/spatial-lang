// See LICENSE.txt for license details.
package types

import chisel3.iotesters.{PeekPokeTester, Driver, ChiselFlatSpec}


class FixedPointTesterTests(c: FixedPointTester) extends PeekPokeTester(c) {
  val num1s = List(9, 32.175, 99.13861)
  val num2s = List(1, 2, 3, 1.75, 8.15)
  num1s.foreach { a => 
  	num2s.foreach { b => 
  	  poke(c.io.num1.raw, (a*scala.math.pow(2,c.f)).toInt)
  	  poke(c.io.num2.raw, (b*scala.math.pow(2,c.f)).toInt)
  	  step(1)
  	  val a_rounded = (((a*scala.math.pow(2,c.f)).toInt)/scala.math.pow(2,c.f))
  	  val b_rounded = (((b*scala.math.pow(2,c.f)).toInt)/scala.math.pow(2,c.f))
  	  // val sum = peek(c.io.add_result.raw)
  	  // println(s"$a + $b = ${sum.toDouble/scala.math.pow(2,c.f)}, expect ${(a*scala.math.pow(2,c.f)).toInt + (b*scala.math.pow(2,c.f)).toInt}")
  	  // val prod = peek(c.io.product_result.raw)
  	  // println(s"$a * $b = ${prod}, expect ${(a*b*scala.math.pow(2,c.f)).toInt}")
  	  // val sub = peek(c.io.sub_result.raw)
  	  // println(s"$a - $b = ${sub}, expect ${((a_rounded-b_rounded)*scala.math.pow(2,c.f)).toInt}")
  	  val quotient = peek(c.io.quotient_result.raw)
  	  println(s"$a / $b = ${quotient}, expect ${((a_rounded/b_rounded)*scala.math.pow(2,c.f)).toInt}")
  	  expect(c.io.add_result.raw, ((a_rounded + b_rounded)*scala.math.pow(2,c.f)).toInt)
  	  expect(c.io.product_result.raw, (a_rounded*b_rounded*scala.math.pow(2,c.f)).toInt)
  	  // expect(c.io.quotient_result.raw, (a_rounded/b_rounded*scala.math.pow(2,c.f)).toInt)
  	  if (a - b >= 0 | c.s) expect(c.io.sub_result.raw, ((a_rounded - b_rounded)*scala.math.pow(2,c.f)).toInt)
  	}
  }
}
