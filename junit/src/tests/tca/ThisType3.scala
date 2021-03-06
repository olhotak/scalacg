package tests.tca

import ca.uwaterloo.scalacg.annotation.target
import ca.uwaterloo.scalacg.annotation.invocations

object ThisType3 {
    class A {
      @target("A.zip")
      def zip(x : this.type) : Unit = { { "A.zap"; x}.zap(); } 
       @target("A.zap") def zap() { println("A.zap"); }
    }
    class B extends A {
      @target("B.zip") override def zip(x : this.type) : Unit = { { "B.zap"; x}.zap(); } 
      @target("B.zap") override def zap()  { println("B.zap"); }
    }
    
    @invocations("21: A.zip")
    def main(args: Array[String]) = {
      val x : A = new B();
      val _ = new A();
      x.zip(x); // cannot place annotation here because the argument has type "this.type"
    }  
} 