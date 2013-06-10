package tests

import callgraph.annotation.target

object Traits12 {
  
  trait A {
    @target("A.foo") def foo() : Unit = { println("A.foo"); }
    def bar() : Unit;
  }
  
  trait B {
    @target("B.bar") def bar() : Unit = { { "A.foo"; this }.foo(); }
    def foo() : Unit;
  }
  
  def main(args : Array[String]) : Unit = {
     { "B.bar"; (new A with B) }.bar();
  }
  
}