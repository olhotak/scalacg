package tests.tca

import ca.uwaterloo.scalacg.annotation.target

object Generics9 {
  trait A[X] {
    @target("A.foo") def foo(x : X) = { }
  }
  
  trait B extends A[Int] {
     @target("B.foo") override def foo(x : Int) = { }
  }
  
  def main(args: Array[String]): Unit = {
    val b : A[Int] = new B{};
    val a = new A[Int]{};
    
   { "A.foo"; "B.foo"; b}.foo(3);
  }
}