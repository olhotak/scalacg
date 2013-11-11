package ca.uwaterloo.scalacg.util

import scala.collection.immutable.{ Set => ImmutableSet }
import scala.collection.mutable.Map
import scala.collection.mutable.Set

import ca.uwaterloo.scalacg.analysis.CallSites
import ca.uwaterloo.scalacg.config.Global

/**
 * Holds any tree-related information
 */

trait Trees extends Global {
  import global._

  val trees: Set[Tree]
}

trait TraversalCollections extends Global with CallSites with TypesCollections with MethodsCollections {
  import global._

  val abstractCallSites: Set[AbstractCallSite]
  val abstractToCallSites: Map[AbstractCallSite, ImmutableSet[CallSite]]
  val callSitesInMethod: Map[Symbol, ImmutableSet[AbstractCallSite]]
  val instantiatedTypesInMethod: Map[Symbol, ImmutableSet[Type]]
  //  val modulesInType: Map[Type, ImmutableSet[Type]]
}

trait TreeTraversal extends Trees with TraversalCollections {
  import global._

  // Traversal
  val abstractCallSites = Set[AbstractCallSite]()
  val abstractToCallSites = Map[AbstractCallSite, ImmutableSet[CallSite]]().withDefaultValue(ImmutableSet.empty[CallSite])
  val callSitesInMethod = Map[Symbol, ImmutableSet[AbstractCallSite]]().withDefaultValue(ImmutableSet.empty[AbstractCallSite])
  val instantiatedTypesInMethod = Map[Symbol, ImmutableSet[Type]]().withDefaultValue(ImmutableSet.empty[Type])
  //  val modulesInType = Map[Type, ImmutableSet[Type]]().withDefaultValue(ImmutableSet.empty[Type])

  // Types
  val applicationTypes = Set[Type]()
  val types = Set[Type]()
  val mainModules = Set[Type]()

  // Methods
  val mainMethods = Set[Symbol]()
  val mainModulesPrimaryConstructors = Set[Symbol]()

  // Find receiver annotations
  private val annotationFilter: PartialFunction[Tree, String] = {
    case Literal(Constant(string: String)) => string
    // TODO: replace _ with a more specific check for the cha case class
    case Apply(_, List(Literal(Constant(string: String)))) => string
  }

  //  /**
  //   * Find all the modules defined in the given set of types (not recursive).
  //   */
  //  def modulesInTypes(types: Set[Type]) = {
  //    val ret = Set[Type]()
  //    types.foreach(cls => ret ++= modulesInType(cls))
  //    ret
  //  }

  // Traverse the ASTs and collect information
  def traverse(tree: Tree, ancestors: List[Tree]): Unit = {
    tree match {
      case cls: ClassDef => findCallSitesInClassDef(cls)
      case module: ModuleDef => findType(module)
      case nw: New => findInstantiatedTypes(nw)
      case apply: Apply => findCallSitesInApply(apply)
      case _: Select | _: Ident => findCallSitesSelectOrIdent // TODO: This causes duplicate call sites (see foreach in Reachable1b) 
      case _ => processChildren
    }

    // Add a type encountered traversing the tree (this could be an instantiated or a defined type).
    def addType(tree: Tree) = tree match {
      case _: ClassDef | _: ModuleDef => {
        val cls = tree.symbol // NSC marks this as OPT, so just call it once
        val tpe = cls.tpe

        applicationTypes += tpe
        types += tpe

        // Which class/method is that module defined in?
        // If it's defined in a class, add it to modulesInClass.
        // If in a method, add to the instantiated types in that method.
        if (cls.isModuleOrModuleClass) {
          val parent = enclMethodOrClass(ancestors)

          //           If the module is not a top-level module (i.e., it is defined in some method or class/module.)
          // Look for main methods in top-level modules.
          if (parent.isEmpty) {
            //            val symbol = parent.get.symbol
            //            //            val parentTpe = parent.get.tpe
            //            parent.get match {
            //              case _: ClassDef | _: ModuleDef => modulesInType(symbol.tpe) += tpe
            //              case _: DefDef => instantiatedTypesInMethod(symbol) += tpe
            //              case _ =>
            //            }
            //          } else { 
            val main = tpe.members.filter { m =>
              m.isMethod && // consider only methods, not fields or other members
                !m.isDeferred && // filter out abstract methods
                definitions.isJavaMainMethod(m) // checks signature and name of method
            }.toList

            if (main.nonEmpty) {
              mainMethods += main.head
              mainModulesPrimaryConstructors += cls.primaryConstructor
              mainModules += tpe
            }
          }
        }
      }
      case New(tpt) => types += tpt.tpe.dealias // Some types are aliased (see CaseClass3)
      case _ => // don't care
    }

    /**
     * Traverse the children too, and look for modules in methods.
     * Modules can appear as one of the following trees:
     * - Select: e.g. val v = p(C.this.A) (so A is defined in Class/Module C)
     * - Ident: e.g. val v = p(A) (A is a top-level module)
     */
    def processChildren = {
      tree match {
        // case s: Select if s.symbol.isModuleOrModuleClass => println("found a module: " + s.symbol.tpe + " :: " + signature(enclMethod(ancestors)) + " :: " + s.symbol + s.getClass)
        // case i: Ident if i.symbol.isModuleOrModuleClass => println("found a module: " + i.symbol.tpe + " :: " + signature(enclMethod(ancestors)) + " :: " + i.symbol + i.getClass)
        case _: Select | _: Ident if tree.symbol.isModuleOrModuleClass && !tree.symbol.isPackage => { //ObjectOrClass && !tree.symbol.isPackage => {
          val encl = enclMethod(ancestors)
          if (encl != NoSymbol) {
            //            println("instantiating type <t> inside method <m>: " + tree.tpe + " :: " + signature(encl))
            instantiatedTypesInMethod(encl) += tree.tpe
            //            println("found a module: " + tree.symbol.tpe + " :: " + signature(encl) + " :: " + tree.symbol + tree.getClass)
          }
        }
        //        case New(tpt) if tpt.symbol.isModuleOrModuleClass => {
        //          val encl = enclMethod(ancestors)
        //          if (encl != NoSymbol) {
        //            val tpe = tpt.symbol.tpe.dealias
        //            instantiatedTypesInMethod(encl) += tpe
        //            println("found a module: " + tpe + " :: " + signature(encl) + " :: " + tpt.symbol + tree.getClass)
        //          }
        //        }
        case _ => //println(tree.tpe + " :: " + " :: " + signature(enclMethod(ancestors)) + " :: " + tree.getClass)
      }
      tree.children.foreach(traverse(_, tree :: ancestors))
    }

    // Karim: I think this method normalizes multiple parameter lists?
    def normalizeMultipleParameters(tree: Apply): Apply = tree.fun match {
      case a: Apply => normalizeMultipleParameters(a)
      case _ => tree
    }

    // Returns the receiver of an apply or unapply. Some don't have a receiver. This method never returns null.
    def getReceiver(tree: Tree): Option[Tree] = tree match {
      case a: Apply => getReceiver(a.fun)
      case s: Select => Some(s.qualifier)
      case t: TypeApply => getReceiver(t.fun)
      case i: Ident => Some(i)
      case _ => assert(assertion = false, message = "getReceiver on unexpected tree " + tree + " of type " + tree.getClass); null
    }

    // Filters out unwanted "method symbols" (e.g., labels)
    // Karim: we might not need all these checks now that we do the analysis after uncurry. 
    def isMethod(symbol: Symbol) = {
      symbol.isMethod
      symbol.isInstanceOf[MethodSymbol] &&
        symbol.name != nme.OUTER_SYNTH && // this is a fake method that never exists; such calls are
        // later replaced by calls to a synthetic accessor method
        !symbol.isLabel // gotos are implemented with a method-call-like syntax, but they do not call
      // actual methods, only labels
    }

    // look for an annotation on the receiver
    def findReceiverAnnotations(receiver: Tree) = {
      receiver match {
        case Block(annotations, plainRec) =>
          val annot = annotations.collect(annotationFilter)
          (annot.toSet, plainRec)
        case _ => (ImmutableSet.empty[String], receiver)
      }
    }

    // Add a new call site
    // TODO: the scala compiler sees String("A") of different type compared to String("B")
    def addCallSite(receiverTree: Tree, staticTarget: Symbol, enclMethod: Symbol, position: Position, annotations: ImmutableSet[String]) = {
      val abstractCallSite = CallSite(receiverTree, staticTarget)
      val callSite = CallSite(receiverTree, staticTarget, enclMethod, position, annotations)

      abstractCallSites += abstractCallSite
      callSitesInMethod(callSite.enclMethod) += abstractCallSite
      abstractToCallSites(abstractCallSite) += callSite

      if (abstractCallSite.hasModuleReceiver) instantiatedTypesInMethod(enclMethod) += abstractCallSite.receiver
    }

    /**
     * Find the enclosing method from the given list of ancestors. For call sites in methods, that's obvious. For call sites
     * that appear in class definition, the primary constructor of the defining class is the enclosing method.
     */
    def enclMethod(ancestors: List[Tree]) = {
      ancestors.collectFirst {
        case cd: ClassDef => cd.symbol.primaryConstructor
        case dd: DefDef => dd.symbol
      }.getOrElse(NoSymbol)
    }

    /**
     * Return the first enclosing class (or object) or method.
     */
    def enclMethodOrClass(ancestors: List[Tree]) = {
      ancestors.find { parent =>
        parent match {
          case _: ClassDef | _: ModuleDef | _: DefDef => true
          case _ => false
        }
      }
    }

    // Find call sites in a class definition.
    // Adds the calls from primary constructors of classes to mixin constructors (see AbstractTypes13).
    def findCallSitesInClassDef(cls: ClassDef) = {
      //      if (!cls.symbol.isTrait) {
      for {
        mixin <- cls.symbol.mixinClasses
        caller = cls.symbol.primaryConstructor
        callee = mixin.primaryConstructor
        if caller != NoSymbol && callee != NoSymbol
        receiver = Super(This(caller.thisSym), tpnme.EMPTY) // calls to super constructors should be made via super
      } {
        /*
         * TODO: receiver.tpe can be null here (see SuperCall1), this is not problematic for method
         * resolution as we just get the static target. However, for checks like isSuper we need to add another
         * check that receiver != null
         */
        addCallSite(receiver, callee, caller, cls.pos, ImmutableSet.empty[String])
      }
      //      }

      findType(cls)
    }

    // Find call sites resulting from Apply(_, _)
    def findCallSitesInApply(apply: Apply) = {
      val a = normalizeMultipleParameters(apply)
      val callee = a.fun
      val args = a.args
      val receiver = getReceiver(a)
      if (isMethod(callee.symbol)) {
        val (annotations, plainReceiver) = findReceiverAnnotations(receiver.get)
        val encl = enclMethod(ancestors)
        addCallSite(plainReceiver, apply.symbol, encl, apply.pos, annotations)
        //        println(callee + " :: " + receiver + " :: " + receiver.get.symbol.tpe + " :: " + receiver.get.getClass)

        // If this is a call to asInstanceOf, add the type to the set of instantiateTypesInMethod
        if (definitions.isCastSymbol(apply.symbol)) instantiatedTypesInMethod(encl) += apply.tpe
        //          println("found asInstanceOf inside : " + signature(enclMethod(ancestors)) + " :: " + apply.tpe)
        //        }
      }

      args foreach (traverse(_, apply :: ancestors))
      callee.children foreach (traverse(_, apply :: ancestors)) // TODO: This causes duplicate call sites (see foreach in Reachable1b)
    }

    // Find call sites resulting from Select(qual, name) or Ident(qual, name)
    def findCallSitesSelectOrIdent = {
      if (isMethod(tree.symbol)) {
        val receiver = getReceiver(tree)
        val (annotations, plainReceiver) = findReceiverAnnotations(receiver.get)
        addCallSite(plainReceiver, tree.symbol, enclMethod(ancestors), tree.pos, annotations)
      }

      processChildren
    }

    // Find instantiated types.
    def findInstantiatedTypes(nw: New) = {
      instantiatedTypesInMethod(enclMethod(ancestors)) += nw.tpt.tpe.dealias // Some types are aliased (see CaseClass3)
      findType(nw)
    }

    // Find types and process children.
    def findType(tree: Tree) = {
      addType(tree)
      processChildren
    }
  }
}