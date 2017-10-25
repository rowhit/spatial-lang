package spatial.analysis

import argon.core._
import argon.traversal.CompilerPass
import spatial.aliases._
import spatial.metadata._

import scala.language.existentials

case class MemoryReporter(var IR: State, localMems: () => Seq[Exp[_]]) extends CompilerPass {
  override val name = "Memory Reporter"
  override def shouldRun: Boolean = config.verbosity > 0

  override protected def process[S:Type](block: Block[S]): Block[S] = {
    val target = spatialConfig.target
    val areaModel = target.areaModel

    withLog(config.logDir, "Memories.report") {
      localMems().map{case mem @ Def(d) =>
        val area = areaModel.areaOf(mem, d, inHwScope = true, inReduce = false)
        mem -> area
      }.sortWith((a,b) => a._2 < b._2).foreach{case (mem,area) =>
        dbg(u"${mem.ctx}: ${mem.tp}: $mem")
        dbg(mem.ctx.lineContent.getOrElse(""))
        dbg(c"  ${str(mem)}")
        val duplicates = duplicatesOf(mem)
        dbg(c"Duplicates: ${duplicates.length}")
        dbg(c"Area: " + area.toString)
        duplicates.zipWithIndex.foreach{
          case (BankedMemory(banking, depth, _), i) =>
            val banks = banking.map(_.banks).mkString(", ")
            dbg(c"  #$i: Banked. Banks: ($banks), Depth: $depth")
          case (DiagonalMemory(strides,banks,depth,_), i) =>
            dbg(c"  #$i: Diagonal. Banks: $banks, Depth: $depth")
        }
        dbg("")
        dbg("")
      }
    }

    block
  }

}
