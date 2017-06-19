package spatial.codegen.pirgen

import argon.core._
import spatial.analysis.SpatialTraversal
import spatial.aliases._
import spatial.SpatialConfig

import scala.collection.mutable

trait Partitions extends SpatialTraversal { this: PIRTraversal =>
  var STAGES: Int = SpatialConfig.stages                   // Number of compute stages per CU
  def LANES = SpatialConfig.lanes                          // Number of SIMD lanes per CU
  def REDUCE_STAGES = (Math.log(LANES)/Math.log(2)).toInt  // Number of stages required to reduce across all lanes
  var READ_WRITE = SpatialConfig.readWrite

  abstract class Partition {
    var cchains: Set[CUCChain] = Set[CUCChain]()

    def nonEmpty: Boolean
    def allStages: Iterable[Stage]
    def isEdge: Boolean
    def ctrl: Option[CUCChain]

    protected def recomputeCChains(nowEdge: Boolean): Unit = recomputeOwnedCChains(this, ctrl, isEdge || nowEdge)

  }
  object Partition {
    def emptyCU(cc: Option[CUCChain], isEdge: Boolean) = new CUPartition(mutable.ArrayBuffer[Stage](), cc, isEdge)
    def emptyMU(cc: Option[CUCChain], isEdge: Boolean) = new MUPartition(Map.empty, Map.empty, cc, isEdge)
  }

  class CUPartition(compute: mutable.ArrayBuffer[Stage], cc: Option[CUCChain], edge: Boolean) extends Partition {
    var cstages: List[Stage] = compute.toList
    val isEdge: Boolean = edge
    val ctrl: Option[CUCChain] = cc
    recomputeCChains(false)

    def nonEmpty = cstages.nonEmpty
    def allStages = cstages

    def popHead(n: Int = 1) = {
      val stages = cstages.take(n)
      cstages = cstages.drop(n)
      recomputeCChains(false)
      (stages, cstages.isEmpty)
    }

    def popTail(n: Int = 1) = {
      val stages = cstages.takeRight(n)
      cstages = cstages.dropRight(n)
      recomputeCChains(false)
      (stages, cstages.isEmpty)
    }


    def addTail(drop: (List[Stage], Boolean)) {
      cstages = cstages ++ drop._1
      recomputeCChains(drop._2)
    }

    def addHead(drop: (List[Stage], Boolean)) {
      cstages = drop._1 ++ cstages
      recomputeCChains(drop._2)
    }
  }

  class MUPartition(write: Map[Seq[CUMemory],mutable.ArrayBuffer[Stage]], read: Map[Seq[CUMemory],mutable.ArrayBuffer[Stage]], cc: Option[CUCChain], edge: Boolean) extends Partition {
    var wstages = Map[Seq[CUMemory], mutable.ArrayBuffer[Stage]]()
    var rstages = Map[Seq[CUMemory], mutable.ArrayBuffer[Stage]]()
    val isEdge: Boolean = edge
    val ctrl: Option[CUCChain] = cc

    write.foreach{case (mems, stages) =>
      wstages += mems -> mutable.ArrayBuffer[Stage]()
      wstages(mems) ++= stages
    }
    read.foreach{case (mems, stages) =>
      rstages += mems -> mutable.ArrayBuffer[Stage]()
      rstages(mems) ++= stages
    }
    recomputeCChains(false)

    def nonEmpty = wstages.nonEmpty || rstages.nonEmpty

    def allStages: Iterable[Stage] = wstages.values.flatten ++ rstages.values.flatten
  }

  def recomputeOwnedCChains(p: Partition, ctrl: Option[CUCChain], isEdge: Boolean) = {
    p.cchains = usedCChains(p.allStages)

    if (isEdge && ctrl.isDefined) p.cchains += ctrl.get
  }

  /**
    * CUCost - resource utilization metrics for PCUs
    */
  abstract class PartitionCost

  case class MUCost(
    sIn:   Int = 0, // Scalar inputs
    sOut:  Int = 0, // Scalar outputs
    vIn:   Int = 0, // Vector inputs
    vOut:  Int = 0, // Vector outputs
    comp:  Int = 0, // Address computation
    regsMax: Int = 0, // Maximum live values at any given time
    regsUse: Int = 0  // Estimated number of registers used
  ) extends PartitionCost {
    def >(that: MUCost) = {
      this.sIn > that.sIn || this.sOut > that.sOut || this.vIn > that.vIn ||
      this.vOut > that.vOut || this.comp > that.comp || this.regsMax > that.regsMax
    }
    def +(that: MUCost) = MUCost(
      sIn   = this.sIn + that.sIn,
      sOut  = this.sOut + that.sOut,
      vIn   = this.vIn + that.vIn,
      vOut  = this.vOut + that.vOut,
      comp  = this.comp + that.comp,
      regsMax = Math.max(this.regsMax, that.regsMax),
      regsUse = this.regsUse + that.regsUse
    )
    //def toUtil = Utilization(alus = comp, sclIn = sIn, sclOut = sOut, vecIn = vIn, vecOut = vOut)
    override def toString = s"  sIn: $sIn, sOut: $sOut, vIn: $vIn, vOut: $vOut, comp: $comp, regsMax: $regsMax, regs: $regsUse"
  }

  case class CUCost(
    sIn:   Int = 0, // Scalar inputs
    sOut:  Int = 0, // Scalar outputs
    vIn:   Int = 0, // Vector inputs
    vOut:  Int = 0, // Vector outputs
    comp:  Int = 0, // Compute stages
    regsMax: Int = 0, // Maximum live values at any given time
    regsUse: Int = 0  // Estimated number of registers used
  ) extends PartitionCost {
    def >(that: CUCost) = {
      this.sIn > that.sIn || this.sOut > that.sOut || this.vIn > that.vIn ||
      this.vOut > that.vOut || this.comp > that.comp || this.regsMax > that.regsMax
    }

    //def toUtil = Utilization(alus = comp, sclIn = sIn, sclOut = sOut, vecIn = vIn, vecOut = vOut)
    override def toString = s"  sIn: $sIn, sOut: $sOut, vIn: $vIn, vOut: $vOut, comp: $comp, regsMax: $regsMax, regs: $regsUse"
  }

  /**
    * Calculate the total cost for a given partition
    */
  def getCost(p: Partition, prev: Seq[Partition], all: List[Stage], others: Seq[CU], isUnit: Boolean) = p match {
    case pmu: MUPartition => getMUCost(pmu, prev, all, others)
    case pcu: CUPartition => getCUCost(pcu, prev, all, others, isUnit){p => p.cstages}
  }

  def getMUCost(p: MUPartition, prev: Seq[Partition], all: List[Stage], others: Seq[CU]) = {
    val readCost = p.rstages.values.map{stages =>
      val cost = getCUCost(p, prev, all, others, isUnit=false){_ => stages}
      MUCost(sIn=cost.sIn,sOut=cost.sOut,vIn=cost.vIn,vOut=cost.vOut,comp=cost.comp)
    }.fold(MUCost()){_+_}
    val writeCost = p.wstages.values.map{stages =>
      val cost = getCUCost(p, prev, all, others, isUnit=false){_ => stages}
      MUCost(sIn=cost.sIn,sOut=cost.sOut,vIn=cost.vIn,vOut=cost.vOut,comp=cost.comp)
    }.fold(MUCost()){_+_}
    readCost + writeCost
  }

  def getCUCost[P<:Partition](p: P, prev: Seq[Partition], all: List[Stage], others: Seq[CU], isUnit: Boolean)(getStages: P => Seq[Stage]) = {
    val local = getStages(p)

    dbg(s"\n\n")
    dbg(s"  Stages: ")
    local.foreach{stage => dbg(s"    $stage") }

    dbg(s"  CChains: ")
    p.cchains.foreach{cc => dbg(s"    $cc :: " + globalInputs(cc).mkString(", ")) }

    val remote = all diff local

    val localIns: Set[LocalComponent] = local.flatMap(_.inputMems).toSet.filterNot(isControl)
    val localOuts: Set[LocalComponent] = local.flatMap(_.outputMems).toSet
    val remoteIns: Set[LocalComponent] = remote.flatMap(_.inputMems).toSet
    val remoteOuts: Set[LocalComponent] = remote.flatMap(_.outputMems).toSet

    // --- CU inputs and outputs
    val cuInBuses = globalInputs(localIns) ++ globalInputs(p.cchains)
    val cuOutBuses = globalOutputs(localOuts)

    val cuGrpsIn = groupBuses(cuInBuses)
    val cuGrpsOut = groupBuses(cuOutBuses)

    var vIns: Int   = cuGrpsIn.vectors.size
    var vOuts: Int  = cuGrpsOut.vectors.size

    var sIns = Map[Int, Int]()
    def addIn(part: Int) {
      if (!sIns.contains(part)) sIns += part -> 1
      else sIns += part -> (sIns(part) + 1)
    }
    var sOuts: Int = localOuts.count{case ScalarOut(_) => true; case _ => false}

    // --- Memory reads
    val readMems = localIns.collect{case MemLoadReg(mem) => mem }
    val vectorMems = readMems.filter{mem => mem.mode match {
      case SRAMMode        => true
      case FIFOOnWriteMode => true
      case VectorFIFOMode  => true
      case _ => false
    }}
    val scalarMems = readMems.filter{mem => mem.mode match {
      case ScalarFIFOMode => true
      case ScalarBufferMode => true
      case _ => false
    }}
    dbg(s"  Read Mems (Vectors): " + vectorMems.mkString(", "))
    dbg(s"  Read Mems (Scalars): " + scalarMems.mkString(", "))
    vIns += vectorMems.size
    scalarMems.foreach{ _ => addIn(-1) }

    // --- Memory writes
    // Handled by VectorOuts


    // --- Registers
    dbg(s"  Arg ins: " + cuGrpsIn.args.mkString(", "))

    val scalars = cuGrpsIn.scalars.map{bus => s"$bus [" + others.find{cu => scalarOutputs(cu) contains bus}.map(_.name).getOrElse("X") + "]" }

    dbg(s"  Scalar ins: " + scalars.mkString(", "))
    dbg(s"  Vector ins: " + cuGrpsIn.vectors.mkString(", "))
    dbg(s"  Scalar outs: " + cuGrpsOut.scalars.mkString(", "))
    dbg(s"  Vector outs: " + cuGrpsOut.vectors.mkString(", "))
    // Live inputs from other partitions
    val liveIns  = localIns intersect remoteOuts
    if (!isUnit) {
      vIns += liveIns.size
    }
    else {
      liveIns.foreach{in =>
        addIn(prev.indexWhere{part => localOutputs(part.allStages) contains in})
      }
    }

    dbg(s"  Live ins: " + liveIns.mkString(", "))

    // Live outputs to other partitions
    val liveOuts = remoteIns intersect localOuts
    if (!isUnit) vOuts += liveOuts.size
    else         sOuts += liveOuts.size

    dbg(s"  Live outs: " + liveOuts.mkString(", "))

    // --- Bypass stages
    val bypasses = local.map{
      case ReduceStage(_,_,in,acc) =>
        val bypassInCost  = if (localIns.contains(in.reg)) 0 else 1
        val bypassOutCost = if (remoteIns.contains(acc))   1 else 0
        bypassInCost + bypassOutCost
      case MapStage(PIRBypass,_,_) => 1
      case _ => 0
    }.sum

    // --- Compute
    val rawCompute = local.map{
      case MapStage(op,_,_) => if (op == PIRBypass) 0 else 1
      case _:ReduceStage    => REDUCE_STAGES
    }.sum

    // Scalars
    val sclIns = sIns.values.sum + cuGrpsIn.args.size + cuGrpsIn.scalars.size

    // Live values throughout CU
    val cuInputs  = liveIns ++ localIns.filter{case _:VectorIn | _:ScalarIn | _:MemLoadReg | _:CounterReg => true; case _ => false }
    val cuOutputs = liveOuts ++ localOuts.filter{case _:VectorOut | _:ScalarOut => true; case _ => false }

    val liveRegs = (1 until local.size).map{i =>
      val prevOut = local.take(i).flatMap(_.outputMems).toSet ++ cuInputs // Values currently available
      val aftIn   = local.drop(i).flatMap(_.inputMems).toSet  // Values needed locally
      val aftOut  = local.drop(i).flatMap(_.outputMems).toSet // Values not yet produced
      val lives = (cuOutputs diff aftOut) ++ (prevOut intersect aftIn) // Values that will be output + values to be used locally
      val reduceRegs = if (local(i-1).isInstanceOf[ReduceStage] || local(i).isInstanceOf[ReduceStage]) 1 else 0
      dbgs(s"$i: " + lives.mkString(", "))
      lives.size + reduceRegs
    } :+ cuOutputs.size

    val regsMax = if (liveRegs.isEmpty) 0 else liveRegs.max

    val cost = CUCost(
      sIn  = sclIns,
      sOut = sOuts,
      vIn  = vIns,
      vOut = vOuts,
      comp = rawCompute + bypasses,
      regsMax = regsMax
    )

    dbg(s"  $cost")

    cost
  }



  /**
    * Utilization - gives statistics about various Plasticine resource utilization
    */
  case class Utilization(
    pcus:   Int = 0,    // Compute units
    pmus:   Int = 0,    // Memory units
    ucus:   Int = 0,    // Unit compute
    switch: Int = 0,    // Parent controllers in switches
    addr:   Int = 0,    // Used address stages
    stages: Int = 0,    // Used compute stages (ignores parallelization)
    alus:   Int = 0,    // ALUs
    mems:   Int = 0,    // SRAMs
    sclIn:  Int = 0,    // Scalar Inputs
    sclOut: Int = 0,    // Scalar Outputs
    vecIn:  Int = 0,    // Vector Inputs
    vecOut: Int = 0,    // Vector Outputs
    regsMax: Int = 0,   // Maximum registers live at any given time
    regsUse: Int = 0    // Total number of used registers
  ) {
    def +(that: Utilization) = Utilization(
      pcus   = this.pcus + that.pcus,
      pmus   = this.pmus + that.pmus,
      ucus   = this.ucus + that.ucus,
      switch = this.switch + that.switch,
      addr   = this.addr + that.addr,
      stages = this.stages + that.stages,
      alus   = this.alus + that.alus,
      mems   = this.mems + that.mems,
      sclIn  = this.sclIn + that.sclIn,
      sclOut = this.sclOut + that.sclOut,
      vecIn  = this.vecIn + that.vecIn,
      vecOut = this.vecOut + that.vecOut,
      regsMax = Math.max(this.regsMax, that.regsMax),
      regsUse = this.regsUse + that.regsUse
    )

    override def toString = s"$pcus, $pmus, $ucus, $switch, $alus, $mems, $sclIn, $sclOut, $vecIn, $vecOut, $regsMax, $regsUse"
  }
  object Utilization {
    def header = "PCUs, PMUs, UCUs, Switch, ALUs, SRAMs, SclIn, SclOut, VecIn, VecOut, Regs"
  }

  def getUtil(cu: CU, cus: Seq[CU]): Utilization = cu.style match {
    case _:MemoryCU =>
      val vIn = nVectorIns(cu)
      val vOut = nVectorOuts(cu)
      val regs = regsPerStage(cu)
      Utilization(
        pmus   = 1,
        mems   = 1,
        addr   = nUsedStages(cu),
        alus   = nUsedALUs(cu),
        sclIn  = nScalarIn(cu),
        sclOut = nScalarOut(cu),
        vecIn  = Math.max(1, vIn),
        vecOut = Math.max(1, vOut),
        regsMax = if (regs.isEmpty) 2 else 2 + regs.max, // Need at least data and address
        regsUse = 2 + regs.sum
      )

    case _:FringeCU => Utilization(ucus = 1)  // TODO

    case _ =>
      val nChildren = cus.count{_.parent.contains(cu)}
      val isParent = nChildren > 0
      val isEmpty  = cu.allStages.isEmpty
      val parentIsStream = cu.allParents.exists(_.style == StreamCU)

      if (nChildren == 1 && !parentIsStream) Utilization() // Merged with child
      else if (isParent) Utilization(switch = 1)
      else if (isEmpty) Utilization()
      else {
        val regs = regsPerStage(cu)
        /*val reduceRegs = cu.allStages.map{
          case _:MapStage => 0
          case _:ReduceStage => cu.lanes*2 + 1
          case _ => 0
        }.sum*/
        Utilization(
          pcus   = 1,
          stages = nUsedStages(cu),
          alus   = nUsedALUs(cu),
          sclIn  = nScalarIn(cu),
          sclOut = nScalarOut(cu),
          vecIn  = nVectorIns(cu),
          vecOut = nVectorOuts(cu),
          regsMax = if (regs.isEmpty) 1 else Math.max(Math.ceil(regs.max.toDouble / cu.lanes).toInt, 1), // per lane
          regsUse = regs.sum
        )
      }
  }

  def nUsedALUs(cu: CU): Int = {
    cu.allStages.map {
      case MapStage(op, _, _) if op != PIRBypass => cu.lanes
      case _:ReduceStage => cu.lanes // ALUs used by reduction tree
      case _ => 0
    }.sum
  }
  def nUsedStages(cu: CU): Int = {
    cu.allStages.map {
      case MapStage(op, _, _) if op != PIRBypass => 1
      case _: ReduceStage => REDUCE_STAGES
      case _ => 0
    }.sum
  }
  def regsPerStage(cu: CU): Seq[Int] = {
    val local = cu.allStages.toList
    val cuInputs  = local.flatMap(_.inputMems).filter{case _:VectorIn | _:ScalarIn | _:MemLoadReg | _:CounterReg => true; case _ => false }.toSet
    val cuOutputs = local.flatMap(_.outputMems).filter{case _:VectorOut | _:ScalarOut => true; case _ => false }.toSet

    dbg(s"  Live values: ")
    (1 until local.size).flatMap{i =>

      val prevOut = local.take(i).flatMap(_.outputMems).toSet ++ cuInputs // Values currently available
      val aftIn   = local.drop(i).flatMap(_.inputMems).toSet  // Values needed locally
      val aftOut  = local.drop(i).flatMap(_.outputMems).toSet // Values not yet produced
      val lives = (cuOutputs diff aftOut) ++ (prevOut intersect aftIn) // Values that will be output + values to be used locally
      val reduceRegs = if (local(i-1).isInstanceOf[ReduceStage]) 1 else 0
      dbgs(s"    $i: " + lives.mkString(", "))
      local(i - 1) match {
        case _:ReduceStage => Seq.tabulate(REDUCE_STAGES){j => (lives.size * cu.lanes) + (reduceRegs * cu.lanes / math.pow(2,j).toInt) }
        case _ => Seq(lives.size * cu.lanes)
      }
    } :+ cuOutputs.map{
      case _:VectorOut => cu.lanes
      case _:ScalarOut => 1
      case _ => 0
    }.sum
  }

  def nScalarIn(cu: CU): Int = {
    val groups = groupBuses(globalInputs(cu))
    groups.args.size + groups.scalars.size
  }
  def nScalarOut(cu: CU): Int = groupBuses(globalOutputs(cu)).scalars.size
  def nVectorIns(cu: CU): Int = groupBuses(globalInputs(cu)).vectors.size
  def nVectorOuts(cu: CU): Int = groupBuses(globalOutputs(cu)).vectors.size

  def reportUtil(stats: Utilization) {
    val Utilization(pcus, pmus, ucus, switch, addr, stages, alus, mems, sclIn, sclOut, vecIn, vecOut, regsMax, regsUse) = stats
    dbg(s"  pcus: $pcus, pmus: $pmus, ucus: $ucus, switch: $switch, addr: $addr, stages: $stages, alus: $alus,")
    dbg(s"  mems: $mems, sclIn: $sclIn, sclOut: $sclOut, vecIn: $vecIn, vecOut: $vecOut, regsMax: $regsMax, regs: $regsUse")
  }

  /** TODO: These are actually pretty useful, may want to move elsewhere **/
  private def quotePercent(x: Any): String = x match {
    case f: Float  => if (f.isInfinite || f.isNaN) "-" else "%.1f".format(f * 100) + "%"
    case f: Double => if (f.isInfinite || f.isNaN) "-" else "%.1f".format(f.toFloat * 100) + "%"
    case _ => x.toString
  }

  private implicit class PercentReportHelper(sc: StringContext) {
    def p(args: Any*): String = sc.raw(args.map(quotePercent): _*).stripMargin
  }

  private def quoteNumber(x: Any): String = x match {
    case f: Float  => if (f.isInfinite || f.isNaN) "-" else "%.2f".format(f)
    case f: Double => if (f.isInfinite || f.isNaN) "-" else "%.2f".format(f.toFloat)
    case _ => x.toString
  }

  private implicit class NumberReportHelper(sc: StringContext) {
    def n(args: Any*): String = sc.raw(args.map(quoteNumber): _*).stripMargin
  }

  case class Statistics(
    /** Utilizations **/
    total:   Utilization,
    pcuOnly: Utilization,
    pmuOnly: Utilization,
    /** UCUs **/
    sIn_UCU: Int = SpatialConfig.sIn_UCU,       // TODO: These aren't used in splitting, ignored for now
    stages_UCU: Int = SpatialConfig.stages_UCU, // TODO: These aren't used in splitting, ignored for now
    /** PCUs **/
    sIn_PCU:  Int = SpatialConfig.sIn_PCU,
    sOut_PCU: Int = SpatialConfig.sOut_PCU,
    vIn_PCU:  Int = SpatialConfig.vIn_PCU,
    vOut_PCU: Int = SpatialConfig.vOut_PCU,
    stages:   Int = SpatialConfig.stages,
    regs_PCU: Int = SpatialConfig.regs_PCU,
    /** PMUs **/
    sIn_PMU:  Int = SpatialConfig.sIn_PMU,
    sOut_PMU: Int = SpatialConfig.sOut_PMU,
    vIn_PMU:  Int = SpatialConfig.vIn_PMU,
    vOut_PMU: Int = SpatialConfig.vOut_PMU,
    readWrite:Int = SpatialConfig.readWrite,
    regs_PMU: Int = SpatialConfig.regs_PMU
  ) {
    val nPCUs = total.pcus - total.ucus
    val nUCUs = total.ucus
    val nPMUs = total.pmus
    val nSwitch = total.switch
    val requiredRegs_PCU = pcuOnly.regsMax
    val requiredRegs_PMU = pmuOnly.regsMax

    // Total available
    val nALUs_PCU = (LANES * nPCUs * stages) + (nUCUs * stages)
    val nSIns_PCU = (sIn_PCU * nPCUs) + (nUCUs * sIn_PCU)
    val nSOut_PCU = (sOut_PCU * nPCUs) + (nUCUs * 2)
    val nVIns_PCU = (vIn_PCU * nPCUs) + (nUCUs * 0)
    val nVOut_PCU = (vOut_PCU * nPCUs) + (nUCUs * 0)
    val nRegs_PCU = (LANES * regs_PCU * nPCUs * stages) + (nUCUs * stages * regs_PCU)

    val nALUs_PMU = nPMUs * readWrite
    val nMems_PMU = nPMUs
    val nSIns_PMU = sIn_PMU * nPMUs
    val nSOut_PMU = sOut_PMU * nPMUs
    val nVIns_PMU = vIn_PMU * nPMUs
    val nVOut_PMU = vOut_PMU * nPMUs
    val nRegs_PMU = regs_PMU * nPMUs * readWrite

    val nALUs = nALUs_PCU + nALUs_PMU
    val nMems = nMems_PMU
    val nSIns = nSIns_PCU + nSIns_PMU
    val nSOut = nSOut_PCU + nSOut_PMU
    val nVIns = nVIns_PCU + nVIns_PMU
    val nVOut = nVOut_PCU + nVOut_PMU
    val nRegs = nRegs_PCU + nRegs_PMU

    // Percent used
    val aluUtil  = total.alus.toFloat / nALUs
    val memUtil  = total.mems.toFloat / nMems
    val sInUtil  = total.sclIn.toFloat / nSIns
    val sOutUtil = total.sclOut.toFloat / nSOut
    val vInUtil  = total.vecIn.toFloat / nVIns
    val vOutUtil = total.vecOut.toFloat / nVOut
    val regsUtil = total.regsUse.toFloat / nRegs

    /** PCU only **/
    val aluUtil_PCU  = pcuOnly.alus.toFloat / nALUs_PCU
    val sInUtil_PCU  = pcuOnly.sclIn.toFloat / nSIns_PCU
    val sOutUtil_PCU = pcuOnly.sclOut.toFloat / nSOut_PCU
    val vInUtil_PCU  = pcuOnly.vecIn.toFloat / nVIns_PCU
    val vOutUtil_PCU = pcuOnly.vecOut.toFloat / nVOut_PCU
    val regsUtil_PCU = pcuOnly.regsUse.toFloat / nRegs_PCU

    val avgSIn_PCU  = pcuOnly.sclIn.toFloat / (nPCUs + nUCUs)
    val avgSOut_PCU = pcuOnly.sclOut.toFloat / (nPCUs + nUCUs)
    val avgVIn_PCU  = pcuOnly.vecIn.toFloat / (nPCUs + nUCUs)
    val avgVOut_PCU = pcuOnly.vecOut.toFloat / (nPCUs + nUCUs)
    val avgRegs_PCU = pcuOnly.regsUse.toFloat / (nPCUs + nUCUs)

    val sInPerStage_PCU  = pcuOnly.sclIn.toFloat / (pcuOnly.stages + pcuOnly.addr)
    val sOutPerStage_PCU = pcuOnly.sclOut.toFloat / (pcuOnly.stages + pcuOnly.addr)
    val vInPerStage_PCU  = pcuOnly.vecIn.toFloat / (pcuOnly.stages + pcuOnly.addr)
    val vOutPerStage_PCU = pcuOnly.vecOut.toFloat / (pcuOnly.stages + pcuOnly.addr)
    val regsPerStage_PCU = pcuOnly.regsUse.toFloat / (pcuOnly.stages + pcuOnly.addr)

    /** PMU only **/
    val aluUtil_PMU  = pmuOnly.alus.toFloat / nALUs_PMU
    val sInUtil_PMU  = pmuOnly.sclIn.toFloat / nSIns_PMU
    val sOutUtil_PMU = pmuOnly.sclOut.toFloat / nSOut_PMU
    val vInUtil_PMU  = pmuOnly.vecIn.toFloat / nVIns_PMU
    val vOutUtil_PMU = pmuOnly.vecOut.toFloat / nVOut_PMU
    val regsUtil_PMU = pmuOnly.regsUse.toFloat / nRegs_PMU

    val avgSIn_PMU  = pmuOnly.sclIn.toFloat / nPMUs
    val avgSOut_PMU = pmuOnly.sclOut.toFloat / nPMUs
    val avgVIn_PMU  = pmuOnly.vecIn.toFloat / nPMUs
    val avgVOut_PMU = pmuOnly.vecOut.toFloat / nPMUs
    val avgRegs_PMU = pmuOnly.regsUse.toFloat / nPMUs

    val sInPerStage_PMU  = pmuOnly.sclIn.toFloat / (pmuOnly.stages + pmuOnly.addr)
    val sOutPerStage_PMU = pmuOnly.sclOut.toFloat / (pmuOnly.stages + pmuOnly.addr)
    val vInPerStage_PMU  = pmuOnly.vecIn.toFloat / (pmuOnly.stages + pmuOnly.addr)
    val vOutPerStage_PMU = pmuOnly.vecOut.toFloat / (pmuOnly.stages + pmuOnly.addr)
    val regsPerStage_PMU = pmuOnly.regsUse.toFloat / (pmuOnly.stages + pmuOnly.addr)

    def makeReport(): Unit = {
      report("Total Utilization:")
      report(n"PCUs:  $nPCUs")
      report(n"UCUs:  $nUCUs")
      report(n"PMUs:  $nPMUs")
      report(n"Regs (PCU): $requiredRegs_PCU")
      report(n"Regs (PMU): $requiredRegs_PMU")
      report("")
      report(n"   ALUs: ${total.alus}/$nALUs" + "\t" + p"($aluUtil)")
      report(n"  SRAMs: ${total.mems}/$nMems" + "\t" + p"($memUtil)")
      report(n"   SIns: ${total.sclIn}/$nSIns" + "\t" + p"($sInUtil)")
      report(n"  SOuts: ${total.sclOut}/$nSOut" + "\t" + p"($sOutUtil)")
      report(n"   VIns: ${total.vecIn}/$nVIns" + "\t" + p"($vInUtil)")
      report(n"  VOuts: ${total.vecOut}/$nVOut" + "\t" + p"($vOutUtil)")
      report(n"   Regs: ${total.regsUse}/$nRegs" + "\t" + p"($regsUtil)")
      report("")
      report("")
      report("PCU Statistics:")
      report(n"   ALUs: ${pcuOnly.alus}/$nALUs_PCU"+"\t" + p"($aluUtil_PCU)")
      report(n"   SIns: ${pcuOnly.sclIn}/$nSIns_PCU"+"\t" + p"($sInUtil_PCU)")
      report(n"  SOuts: ${pcuOnly.sclOut}/$nSOut_PCU"+"\t" + p"($sOutUtil_PCU)")
      report(n"   VIns: ${pcuOnly.vecIn}/$nVIns_PCU"+"\t" + p"($vInUtil_PCU)")
      report(n"  VOuts: ${pcuOnly.vecOut}/$nVOut_PCU"+"\t" + p"($vOutUtil_PCU)")
      report(n"   Regs: ${pcuOnly.regsUse}/$nRegs_PCU"+"\t" + p"($regsUtil_PCU)")
      report("")
      report(n"   SIn / PCU: $avgSIn_PCU")
      report(n"  SOut / PCU: $avgSOut_PCU")
      report(n"   VIn / PCU: $avgVIn_PCU")
      report(n"  VOut / PCU: $avgVOut_PCU")
      report(n"  Regs / PCU: $avgRegs_PCU")
      report("")
      report(n"   SIn / Stage: $sInPerStage_PCU")
      report(n"  SOut / Stage: $sOutPerStage_PCU")
      report(n"   VIn / Stage: $vInPerStage_PCU")
      report(n"  VOut / Stage: $vOutPerStage_PCU")
      report(n"  Regs / Stage: $regsPerStage_PCU")
      report("")
      report("")
      report("PMU Statistics:")
      report(n"   ALUs: ${pmuOnly.alus}/$nALUs_PMU" + "\t" + p"($aluUtil_PMU)")
      report(n"   SIns: ${pmuOnly.sclIn}/$nSIns_PMU" + "\t" + p"($sInUtil_PMU)")
      report(n"  SOuts: ${pmuOnly.sclOut}/$nSOut_PMU" + "\t" + p"($sOutUtil_PMU)")
      report(n"   VIns: ${pmuOnly.vecIn}/$nVIns_PMU" + "\t" + p"($vInUtil_PMU)")
      report(n"  VOuts: ${pmuOnly.vecOut}/$nVOut_PMU" + "\t" + p"($vOutUtil_PMU)")
      report(n"   Regs: ${pmuOnly.regsUse}/$nRegs_PMU" + "\t" + p"($regsUtil_PMU)")
      report("")
      report(n"   SIn / PMU: $avgSIn_PMU")
      report(n"  SOut / PMU: $avgSOut_PMU")
      report(n"   VIn / PMU: $avgVIn_PMU")
      report(n"  VOut / PMU: $avgVOut_PMU")
      report(n"  Regs / PMU: $avgRegs_PMU")
      report("")
      report(n"   SIn / Stage: $sInPerStage_PMU")
      report(n"  SOut / Stage: $sOutPerStage_PMU")
      report(n"   VIn / Stage: $vInPerStage_PMU")
      report(n"  VOut / Stage: $vOutPerStage_PMU")
      report(n"  Regs / Stage: $regsPerStage_PMU")
    }
    def toCSV = {
      s"$nPCUs, $nPMUs, $nUCUs, $nSwitch, $requiredRegs_PCU, $requiredRegs_PMU, " +
      s", ${total.alus}, ${total.mems}, ${total.sclIn}, ${total.sclOut}, ${total.vecIn}, ${total.vecOut}, ${total.regsUse}, " +
      s", $aluUtil, $memUtil, $sInUtil, $sOutUtil, $vInUtil, $vOutUtil, $regsUtil, " +
      s", $aluUtil_PCU, $sInUtil_PCU, $sOutUtil_PCU, $vInUtil_PCU, $vOutUtil_PCU, $regsUtil_PCU, " +
      s", $avgSIn_PCU, $avgSOut_PCU, $avgVIn_PCU, $avgVOut_PCU, $avgRegs_PCU, " +
      s", $sInPerStage_PCU, $sOutPerStage_PCU, $vInPerStage_PCU, $vOutPerStage_PCU, $regsPerStage_PCU, " +
      s", $aluUtil_PMU, $sInUtil_PMU, $sOutUtil_PMU, $vInUtil_PMU, $vOutUtil_PMU, $regsUtil_PMU, " +
      s", $avgSIn_PMU, $avgSOut_PMU, $avgVIn_PMU, $avgVOut_PMU, $avgRegs_PMU, " +
      s", $sInPerStage_PMU, $sOutPerStage_PMU, $vInPerStage_PMU, $vOutPerStage_PMU, $regsPerStage_PMU"
    }
  }
  object Statistics {
    def header = {
      "PCUs, PMUs, UCUs, Switch, Req Regs (PCU), Req Regs (PMU), " +
      ", #ALUs, #SRAMs, #SIns, #SOuts, #Vins, #Vouts, #Regs, " +
      ", ALU Util, SRAM Util, SIn Util, SOut Util, VecIn Util, VecOut Util, Reg Util, " +
      ", ALU Util (PCU), SIn Util (PCU), SOut Util (PCU), VIn Util (PCU), VOut Util (PCU), Reg Util (PCU), " +
      ", SIn/PCU, SOut/PCU, VIn/PCU, VOut/PCU, Reg/PCU, " +
      ", SIn/Stage (PCU), SOut/Stage (PCU), VIn/Stage (PCU), VOut/Stage (PCU), Reg/Stage (PCU), " +
      ", ALU Util (PMU), SIn Util (PMU), SOut Util (PMU), VIn Util (PMU), VOut Util (PMU), Reg Util (PMU), " +
      ", SIn/PMU, SOut/PMU, VIn/PMU, VOut/PMU, Reg/PMU, " +
      ", SIn/Stage (PMU), SOut/Stage (PMU), VIn/Stage (PMU), VOut/Stage (PMU), Reg/Stage (PMU)"
    }
  }


  def tallyCUs(cus: Seq[CU]): Unit = {
    val total = cus.map { cu => getUtil(cu, cus) }.fold(Utilization()){_+_}

    val pcuOnly = cus.filter(_.isPCU).map{cu =>
      val util = getUtil(cu, cus)
      dbg(s"$cu: ")
      reportUtil(util)
      util
    }.fold(Utilization()){_+_}

    val pmuOnly = cus.filter(_.isPMU).map{cu => getUtil(cu, cus) }.fold(Utilization()){_+_}

    val stats = Statistics(total, pcuOnly, pmuOnly)
    stats.makeReport()
  }


}
