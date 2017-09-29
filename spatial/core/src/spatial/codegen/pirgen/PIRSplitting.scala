package spatial.codegen.pirgen

import argon.core._

import scala.collection.mutable
import scala.util.control.NoStackTrace

trait PIRSplitting extends PIRTraversal {

  class SplitException(val msg: String) extends Exception("Unable to split!")

  /**
    CU splitting is graph partitioning, where each partition has a limited number of inputs, outputs, and nodes

    Given a set of stages, we want to find a set of minimum cuts where each partition satisfies these limits

    This is more restricted/complex than the standard graph partitioning problem as nodes and edges cannot be treated uniformly:
    - Reduction stages represent multiple real ALU stages in the architecture (logV + 1 ?)
    - Reduction stages cannot directly consume CU inputs (requires a bypass stage)
    - Reduction stages cannot directly produce CU outputs (requires a bypass stage)
    - Write address computation stages MUST be preserved/copied within consumer CUs
    - Local vector writes: address and data must be available in the same CU

    Nodes must be duplicated/created depending on how the graph is partitioned
    Could model this as conditional costs for nodes in context of their partition?
   **/
  def splitCU(cu: CU, archCU: CUCost, archMU: MUCost, others: Seq[CU]): List[CU] = cu.style match {
    case _:MemoryCU => splitPMU(cu, archMU, others)
    case _:FringeCU => List(cu)
    case _ if cu.computeStages.isEmpty => List(cu)
    case _ => splitPCU(cu, archCU, others)
  }


  // TODO: PMU splitting. For now just throws an exception if it doesn't fit the specified constraints
  def splitPMU(cu: CU, archMU: MUCost, others: Seq[CU]): List[CU] = {
    if (cu.lanes > LANES) {
      var errReport = s"Failed splitting in PMU $cu"
      errReport += s"\nCU had ${cu.lanes} lanes, greater than allowed $LANES"
      throw new SplitException(errReport) with NoStackTrace
    }

    val allStages = cu.allStages.toList
    val ctrl = cu.cchains.find{case _:UnitCChain | _:CChainInstance => true; case _ => false}

    val partitions = mutable.ArrayBuffer[Partition]()

    val current = new MUPartition(cu.writeStages, cu.readStages, ctrl, false)

    val cost = getMUCost(current, partitions, allStages, others)

    if (cost > archMU) {
      var errReport = s"Failed splitting in PMU $cu"
      errReport += s"\nRead Stages: "
      current.rstages.foreach{stage => errReport += s"\n  $stage" }
      errReport += s"\nWrite Stages: "
      current.wstages.foreach{stage => errReport += s"\n  $stage" }

      errReport += "\nCost for last split option: "
      errReport += s"\n$cost"
      throw new SplitException(errReport) with NoStackTrace
    }

    List(cu)
  }

  def splitPCU(cu: CU, arch: CUCost, others: Seq[CU]): List[CU] = dbgblk(s"splitCU($cu)"){
    dbgs(s"Splitting PCU: $cu")
    dbgs(s"Compute: ")
    cu.computeStages.foreach{stage => dbgs(s"  $stage")}
    val allStages = cu.allStages.toList
    val ctrl = cu.cchains.find{case _:UnitCChain | _:CChainInstance => true; case _ => false}
    val isUnit = cu.lanes == 1

    val partitions = mutable.ArrayBuffer[CUPartition]()
    var current: CUPartition = Partition.emptyCU(ctrl, true)
    val remote: CUPartition = new CUPartition(cu.computeStages, ctrl, false)

    def getCost(p: CUPartition): CUCost = getCUCost(p, partitions, allStages, others, isUnit){p => p.cstages}

    while (remote.nonEmpty) {
      dbgs(s"Computing partition ${partitions.length}")

      // Bite off a chunk to maximize compute usage
      current addTail remote.popHead(arch.comp)
      var cost = getCost(current)

      while (!(cost > arch) && remote.nonEmpty) {
        //dbgs(s"Adding stages until exceeds cost")
        current addTail remote.popHead()
        cost = getCost(current)
      }

      while (cost > arch && current.nonEmpty) {
        //dbgs(s"Removing stage")
        //TODO: This splitting stratagy highly depends on the linear schedule of the stages.
        //It's possible that different valid linear schedule can give a much better splitting
        //result. 
        remote addHead current.popTail()
        cost = getCost(current)
      }

      if (current.cstages.isEmpty) {
        // Find first SRAM owner which can still be spliced
        var errReport = s"Failed splitting in CU $cu"
        errReport += s"\nCompute stages: "
        remote.cstages.foreach{stage => errReport += s"\n  $stage" }

        errReport += "\nCost for last split option: "
        current addTail remote.popHead()
        current.cstages.foreach{stage => errReport += s"\n  $stage"}
        val cost = getCost(current)
        errReport += s"Arch: \n$arch"
        errReport += s"Cost: \n$cost"
        throw new SplitException(errReport) with NoStackTrace
      }
      else {
        dbgs(s"Partition ${partitions.length}")
        dbgs(getCost(current))
        dbgs(s"  Compute stages: ")
        current.cstages.foreach{stage => dbgs(s"  $stage") }

        partitions += current
        current = Partition.emptyCU(ctrl, false)
      }
    } // end while

    val parent = if (partitions.length > 1) {
      val parent = ComputeUnit(cu.name, cu.pipe, StreamCU)
      parent.parent = cu.parent
      parent.deps = cu.deps
      parent.cchains ++= cu.cchains
      val mems = usedMem(cu.cchains)
      parent.memMap ++= cu.memMap.filter { case (e, m) => mems.contains(m) } 
      Some(parent)
    }
    else None

    val cus = partitions.zipWithIndex.map{case (p,i) =>
      scheduleCUPartition(orig = cu, p, i, parent)
    }

    cus.zip(partitions).zipWithIndex.foreach{case ((cu,p), i) =>
      dbgs(s"Partition #$i: $cu")
      val cost = getCost(p)
      dbgs(s"Cost: ")
      dbgs(cost)
      dbgs(s"Util: ")
      reportUtil(getUtil(cu, cus.filterNot(_ == cu)++others))
      dbgs(s"Compute stages: ")
      cu.computeStages.foreach{stage => dbgs(s"  $stage") }
    }

    parent.toList ++ cus.toList

  }


  def scheduleCUPartition(orig: CU, part: CUPartition, i: Int, parent: Option[CU]): CU = dbgblk(s"scheduleCUPartition(orig=$orig, part=$part, i=$i, parent=$parent)"){
    val isUnit = orig.lanes == 1

    val cu = ComputeUnit(orig.name+"_"+i, orig.pipe, orig.style)
    cu.parent = if (parent.isDefined) parent else orig.parent
    cu.innerPar = orig.innerPar
    cu.fringeGlobals ++= orig.fringeGlobals
    if (parent.isEmpty) cu.deps ++= orig.deps
    if (parent.isEmpty) cu.cchains ++= orig.cchains

    val local = part.cstages
    val remote = orig.allStages.toList diff part.allStages

    val localIns  = local.flatMap(_.inputMems).toSet ++ cu.cchains.flatMap(localInputs)
    val localOuts = local.flatMap(_.outputMems).toSet

    val readMems = localIns.collect{case MemLoadReg(mem) => mem }
    orig.memMap.foreach{case (k,mem) => if (readMems contains mem) cu.memMap += k -> mem }

    val remoteIns = remote.flatMap(_.inputMems).toSet
    val remoteOuts = remote.flatMap(_.outputMems).toSet

    val ctx = ComputeContext(cu)

    def globalBus(reg: LocalComponent, isScalar:Option[Boolean]): GlobalBus = {
      val bus = reg match {
        case ScalarIn(bus) => bus
        case VectorIn(bus) => bus
        case ScalarOut(bus) => bus
        case VectorOut(bus) => bus
        case MemLoadReg(mem) if List(SRAMMode, VectorFIFOMode).contains(mem.mode) =>
          val bus = CUVector(mem.name+"_sdata")
          globals += bus
          bus
        case MemLoadReg(mem) if List(ScalarFIFOMode, ScalarBufferMode).contains(mem.mode) =>
          val bus = CUScalar(mem.name+"_vdata")
          globals += bus
          bus
        case WriteAddrWire(mem) =>
          val bus = CUScalar(mem.name+"_waddr")
          globals += bus
          bus
        case ReadAddrWire(mem) =>
          val bus = CUScalar(mem.name+"_raddr")
          globals += bus
          bus
        case _                    =>
          val bus = if (isScalar.getOrElse(isUnit)) CUScalar("bus_" + reg.id)    else CUVector("bus_" + reg.id)
          globals += bus
          bus
      }
      dbgs(s"globalBus: reg=$reg ${scala.runtime.ScalaRunTime._toString(reg.asInstanceOf[Product])}, bus=$bus") 
      bus
    }

    def portOut(reg: LocalComponent, isScalar:Option[Boolean] = None) = globalBus(reg, isScalar) match {
      case bus:BitBus => BitOut(bus)
      case bus:ScalarBus => ScalarOut(bus)
      case bus:VectorBus => VectorOut(bus)
    }

    def portIn(reg: LocalComponent, isScalar:Option[Boolean] = None) = {
      val bus = globalBus(reg, isScalar)
      val fifo = allocateRetimingFIFO(reg, bus, cu)
      MemLoadReg(fifo)
    }

    def rerefIn(reg: LocalComponent): LocalRef = {
      val in = reg match {
        case _:ConstReg[_] | _:CounterReg => reg
        case _:ValidReg | _:ControlReg => reg
        case _:ReduceMem[_]   => if (localOuts.contains(reg)) reg else portIn(reg)
        case MemLoadReg(mem) => 
          assert(localIns.contains(reg), s"localIns=$localIns doesn't contains $reg")
          assert(cu.mems.contains(mem), s"cu.mems=${cu.mems} doesn't contains $mem")
          reg
        case _ if !remoteOuts.contains(reg) | cu.regs.contains(reg) => reg 
        case _ if remoteOuts.contains(reg) & !cu.regs.contains(reg) => portIn(reg)
      }
      cu.regs += in
      ctx.refIn(in)
    }

    def rerefOut(reg: LocalComponent): List[LocalRef] = {
      val outs = reg match {
        case _:ScalarOut => List(reg)
        case _:VectorOut => List(reg)
        case _ =>
          val local  = if (localIns.contains(reg)) List(reg) else Nil
          val global = if (remoteIns.contains(reg)) List(portOut(reg)) else Nil
          local ++ global
      }
      cu.regs ++= outs
      outs.map{out => ctx.refOut(out)}
    }

    // --- Reconnect remotely computed read addresses (after write stages, before compute)
    /*cu.mems.foreach{ sram =>
      val remoteAddr = remoteOuts.find{case ReadAddrWire(`sram`) => true; case _ => false}
      val localAddr  = localOuts.find{case ReadAddrWire(`sram`) => true; case _ => false}

      if (remoteAddr.isDefined && localAddr.isEmpty) {
        val reg = ReadAddrWire(sram)
        val addrIn = portIn(reg, isUnit)
        ctx.addStage(MapStage(PIRBypass, List(ctx.refIn(addrIn)), List(ctx.refOut(reg))))
        cu.regs += reg
        cu.regs += addrIn
      }
    }*/

    // --- Reschedule compute stages
    part.cstages.foreach{
      case MapStage(op, ins, outs) =>
        val inputs = ins.map{in => rerefIn(in.reg) }
        val outputs = outs.flatMap{out => rerefOut(out.reg) }
        ctx.addStage(MapStage(op, inputs, outputs))

      case ReduceStage(op, init, in, acc, accumParent) =>
        var input = rerefIn(in.reg)
        if (!input.reg.isInstanceOf[ReduceMem[_]]) {
          val redReg = ReduceReg()
          val reduce = ctx.refOut(redReg)
          cu.regs += redReg
          ctx.addStage(MapStage(PIRBypass, List(input), List(reduce)))
          input = ctx.refIn(redReg)
        }
        cu.regs += acc
        val newAccumParent = 
          if (accumParent==orig) parent.getOrElse(cu) // Take StreamController of the splitted cu
                                                      // Or current CU if single partition
          else accumParent // outer controller. Shouldn't be splitted 
        dbgs(s"accumParent==orig ${accumParent==orig}")
        dbgs(s"accumParent=${accumParent} orig=${orig} parent=${parent.map(_.name)} cu=${cu.name}")
        dbgs(s"newAccumParent=${newAccumParent.name}")
        ctx.addStage(ReduceStage(op, init, input, acc, newAccumParent))

        if (remoteIns.contains(acc)) {
          val bus = portOut(acc, Some(true))
          ctx.addStage(MapStage(PIRBypass, List(ctx.refIn(acc)), List(ctx.refOut(bus))))
        }
    }

    // --- Add bypass stages for locally hosted, remotely read SRAMs
    /*val remoteSRAMReads = remoteIns.collect{case MemLoadReg(sram) => sram}
    val localBypasses = remoteSRAMReads intersect cu.mems
    localBypasses.foreach{sram =>
      val reg = MemLoadReg(sram)
      val out = portOut(reg, isUnit)

      if (!cu.computeStages.flatMap(_.outputMems).contains(out)) {
        ctx.addStage(MapStage(PIRBypass, List(ctx.refIn(reg)), List(ctx.refOut(out))))
        cu.regs += reg
        cu.regs += out
      }
    }*/


    // --- Reconnect split feedback paths
    //val rescheduledOutputs = cu.computeStages.flatMap(_.outputMems).toSet
    //TODO: readPort?
    /*cu.mems.foreach{sram => sram.writePort match {
      case Some(LocalVectorBus) =>
        val dataReg = FeedbackDataReg(sram)
        val addrReg = FeedbackAddrReg(sram)

        // TODO: What is the correct thing to do here?
        // TODO: Will the timing still be correct?
        if (!rescheduledOutputs.contains(dataReg)) {
          //sram.vector = Some(globalBus(dataReg, cu.isUnit))
          val in = portIn(dataReg, cu.isUnit)
          ctx.addStage(MapStage(PIRBypass, List(ctx.refIn(in)), List(ctx.refOut(dataReg))))
          cu.regs += in
          cu.regs += dataReg
        }
        if (!rescheduledOutputs.contains(addrReg)) {
          val in = portIn(addrReg, cu.isUnit)
          ctx.addStage(MapStage(PIRBypass, List(ctx.refIn(in)), List(ctx.refOut(addrReg))))
          cu.regs += in
          cu.regs += dataReg
        }

      case _ =>
    }}*/

    // --- TODO: Control logic


    // --- Copy counters
    if (parent.isDefined) {
      val ctrl = parent.get
      val f = copyIterators(cu, ctrl)

      // Copy all, but only retain those in the partition
      cu.cchains = cu.cchains.filter{cc => part.cchains.exists{_.name == cc.name}}

      def tx(cc: CUCChain): CUCChain = f.getOrElse(cc, cc)
        /*val ins = localInputs(cc)
        val ins2 = ins.map(x => portIn(x,true))
        val swap = ins.zip(ins2).toMap*/

        /*cc2 match {
          case cc: CUChainInstance =>
          case _ => cc2
        }*/

        /*if (f.contains(cc)) f(cc)
        else if (f.values.toList.contains(cc)) cc  // HACK: DSE
        else {
          val mapping = f.map{case (k,v) => s"$k -> $v"}.mkString("\n")
          throw new Exception(s"Attempted to copy counter $cc in CU $ctrl, but no such counter exists.\nMapping:\n$mapping")
        }*/
      //}
      def swap_cchain_Reg(x: LocalComponent) = x match {
        case CounterReg(cc,cIdx,iter) => CounterReg(tx(cc), cIdx, iter)
        case ValidReg(cc,cIdx, valid) => ValidReg(tx(cc), cIdx, valid)
        case _ => x
      }
      def swap_cchains_Ref(x: LocalRef) = x match {
        case LocalRef(i, reg) => LocalRef(i, swap_cchain_Reg(reg))
      }

      cu.allStages.foreach{
        case stage@MapStage(_,ins,_) => stage.ins = ins.map{in => swap_cchains_Ref(in) }
        case _ =>
      }
      cu.mems.foreach{sram =>
        sram.readAddr = sram.readAddr.map{swap_cchain_Reg(_)} //TODO refactor this
        sram.writeAddr = sram.writeAddr.map{swap_cchain_Reg(_)}
      }
    }

    cu
  }

}
