package tests

object Traits8 {
  trait A {
    def foo();
    def bar() { { "B.foo"; this }.foo(); } // can only call B.foo() because that is the only type with a def. of foo() that can have A.bar() as a member
  }

  class B extends A {
    @target("B.foo") def foo() {}
  }

  class C extends A {
    @target("C.foo") def foo() {}
    override def bar() {}
  }

  def main(args: Array[String]) = {
    (new B).bar
    (new C).bar
  }
} 