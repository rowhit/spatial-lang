package top

import chisel3._
import chisel3.util._
import fringe._
import accel._
import axi4._
import templates.Utils.{log2Up, getFF}


// import AccelTop
abstract class TopInterface extends Bundle {
  // Host scalar interface
  var raddr = Input(UInt(1.W))
  var wen  = Input(Bool())
  var waddr = Input(UInt(1.W))
  var wdata = Input(Bits(1.W))
  var rdata = Output(Bits(1.W))
  val is_enabled = Output(Bool())

}

case class TopParams(
  val addrWidth: Int,
  val dataWidth: Int,
  val v: Int,
  val numArgIns: Int,
  val numArgOuts: Int,
  val numArgIOs: Int,
  val numChannels: Int,
  val numArgInstrs: Int,
  val argOutLoopbacksMap: scala.collection.immutable.Map[Int,Int],
  val loadStreamInfo: List[StreamParInfo],
  val storeStreamInfo: List[StreamParInfo],
  val streamInsInfo: List[StreamParInfo],
  val streamOutsInfo: List[StreamParInfo],
  target: String
)

class VerilatorInterface(p: TopParams) extends TopInterface {
  // Host scalar interface
  raddr = Input(UInt(p.addrWidth.W))
  wen  = Input(Bool())
  waddr = Input(UInt(p.addrWidth.W))
  wdata = Input(Bits(64.W))
  rdata = Output(Bits(64.W))

  // DRAM interface - currently only one stream
  val dram = Vec(p.numChannels, new DRAMStream(p.dataWidth, p.v)) // Bus is 64 elements of 8 bits
  val axiParams = new AXI4BundleParameters(p.dataWidth, 512, 32)

  // Input streams
//  val genericStreamIn = StreamIn(StreamParInfo(32,1))
//  val genericStreamOut = StreamOut(StreamParInfo(32,1))

  // Debug signals
//  val dbg = new DebugSignals
}

class ZynqInterface(p: TopParams) extends TopInterface {
  val axiLiteParams = new AXI4BundleParameters(p.addrWidth, p.dataWidth, 1)
  val axiParams = new AXI4BundleParameters(p.addrWidth, 512, 32)

  val S_AXI = Flipped(new AXI4Lite(axiLiteParams))
  val M_AXI = Vec(p.numChannels, new AXI4Inlined(axiParams))

  // AXI debugging loopbacks
  val TOP_AXI = new AXI4Probe(axiLiteParams)
  val DWIDTH_AXI = new AXI4Probe(axiLiteParams)
  val PROTOCOL_AXI = new AXI4Probe(axiLiteParams)
  val CLOCKCONVERT_AXI = new AXI4Probe(axiLiteParams)
}

class Arria10Interface(p: TopParams) extends TopInterface {
  // To fit the sysid interface, we only want to have 7 bits for 0x0000 ~ 0x01ff
  val axiLiteParams = new AXI4BundleParameters(7, p.dataWidth, 1)
  // TODO: This group of params is for memory
  val axiParams = new AXI4BundleParameters(p.dataWidth, 512, 6)
  val S_AVALON = new AvalonSlave(axiLiteParams) // scalars
  val M_AXI = Vec(p.numChannels, new AXI4Inlined(axiParams))
}


class DE1SoCInterface(p: TopParams) extends TopInterface {
  private val axiLiteParams = new AXI4BundleParameters(16, p.dataWidth, 1)
  val axiParams = new AXI4BundleParameters(p.dataWidth, 512, 6)

  val S_AVALON = new AvalonSlave(axiLiteParams)
  val S_STREAM = new AvalonStream(axiLiteParams)

  // For led output data
  val LEDR_STREAM_address = Output(UInt(4.W))
  val LEDR_STREAM_chipselect = Output(Wire(Bool()))
  val LEDR_STREAM_writedata = Output(UInt(32.W))
  val LEDR_STREAM_write_n = Output(Wire(Bool()))

  // For switch input data
  val SWITCHES_STREAM_address = Output(UInt(32.W))
  val SWITCHES_STREAM_readdata = Input(UInt(32.W))
  // This read control signal is not needed by the switches interface
  // By default this is a read_n signal
  // TODO: fix the naming of read control signal
  val SWITCHES_STREAM_read = Output(Wire(Bool()))

  // For BufferedOut data that goes to the pixel buffer
  val BUFFOUT_waitrequest  = Input(UInt(1.W))
  val BUFFOUT_address      = Output(UInt(32.W))
  val BUFFOUT_write        = Output(UInt(1.W))
  val BUFFOUT_writedata    = Output(UInt(16.W))

  // For GPI1 interface that reads from the JP1
  val GPI1_STREAMIN_chipselect  = Output(UInt(1.W))
  val GPI1_STREAMIN_address     = Output(UInt(4.W))
  val GPI1_STREAMIN_readdata    = Input(UInt(32.W))
  val GPI1_STREAMIN_read        = Output(UInt(1.W))

  // For GPI1 interface that writes to the JP1
  val GPO1_STREAMOUT_chipselect = Output(UInt(1.W))
  val GPO1_STREAMOUT_address    = Output(UInt(4.W))
  val GPO1_STREAMOUT_writedata  = Output(UInt(32.W))
  val GPO1_STREAMOUT_writen     = Output(UInt(1.W))

  // For GPI2 interface that reads from the JP2
  val GPI2_STREAMIN_chipselect  = Output(UInt(1.W))
  val GPI2_STREAMIN_address     = Output(UInt(4.W))
  val GPI2_STREAMIN_readdata    = Input(UInt(32.W))
  val GPI2_STREAMIN_read        = Output(UInt(1.W))

  // For GPI2 interface that writes to the JP2
  val GPO2_STREAMOUT_chipselect = Output(UInt(1.W))
  val GPO2_STREAMOUT_address    = Output(UInt(4.W))
  val GPO2_STREAMOUT_writedata  = Output(UInt(32.W))
  val GPO2_STREAMOUT_writen     = Output(UInt(1.W))
}

class AWSInterface(p: TopParams) extends TopInterface {
  val axiLiteParams = new AXI4BundleParameters(p.dataWidth, p.dataWidth, 1)
  val axiParams = new AXI4BundleParameters(p.dataWidth, 512, 16)

  val enable = Input(UInt(p.dataWidth.W))
  val done = Output(UInt(p.dataWidth.W))
  val scalarIns = Input(Vec(p.numArgIns, UInt(64.W)))
  val scalarOuts = Output(Vec(p.numArgOuts, UInt(64.W)))

//  val dbg = new DebugSignals

  // DRAM interface - currently only one stream
//  val dram = new DRAMStream(p.dataWidth, p.v)
  val M_AXI = Vec(p.numChannels, new AXI4Inlined(axiParams))
}

/**
 * Top: Top module including Fringe and Accel
 * @param w: Word width
 * @param numArgIns: Number of input scalar arguments
 * @param numArgOuts: Number of output scalar arguments
 */
class Top(
  val w: Int,
  val numArgIns: Int,
  val numArgOuts: Int,
  val numArgIOs: Int,
  val numArgInstrs: Int,
  val argOutLoopbacksMap: scala.collection.immutable.Map[Int,Int],
  val loadStreamInfo: List[StreamParInfo],
  val storeStreamInfo: List[StreamParInfo],
  val streamInsInfo: List[StreamParInfo],
  val streamOutsInfo: List[StreamParInfo],
  target: String = "") extends Module {

  // Ensure that there is at least one argIn and argOut; this ensures that
  // the argIn/argOut interfaces do no become undirected null vectors
  val totalArgIns = math.max(1, numArgIns)
  val totalArgOuts = math.max(1, numArgOuts)
  val totalArgOutLoobacks = math.max(1, argOutLoopbacksMap.toList.length)
  val totalRegs = totalArgIns + totalArgOuts + 2  // (command, status registers)

  val addrWidth = if (target == "zcu") 40 else 32
  val dataWidth = if (target == "zcu") 64 else w
  val v = if (target == "vcs" || target == "asic") 64 else 16
  val totalLoadStreamInfo = loadStreamInfo ++ (if (loadStreamInfo.size == 0) List(StreamParInfo(w, v, 0, false)) else List[StreamParInfo]())
  val totalStoreStreamInfo = storeStreamInfo ++ (if (storeStreamInfo.size == 0) List(StreamParInfo(w, v, 0, false)) else List[StreamParInfo]())

  val numChannels = target match {
    case "zynq"             => 4
    case "zcu"              => 2
    case "aws" | "aws-sim"  => 1
    case _                  => 1
  }

  val topParams = TopParams(addrWidth, dataWidth, v, totalArgIns, totalArgOuts, numArgIOs, numChannels, numArgInstrs, argOutLoopbacksMap, totalLoadStreamInfo, totalStoreStreamInfo, streamInsInfo, streamOutsInfo, target)

  FringeGlobals.target = target

  val io = target match {
    case "verilator"  => IO(new VerilatorInterface(topParams))
    case "vcs"        => IO(new VerilatorInterface(topParams))
    case "xsim"       => IO(new VerilatorInterface(topParams))
    case "aws"        => IO(new AWSInterface(topParams))
    case "aws-sim"    => IO(new AWSInterface(topParams))
    case "zynq"       => IO(new ZynqInterface(topParams))
    case "zcu"        => IO(new ZynqInterface(topParams))
    case "de1soc"     => IO(new DE1SoCInterface(topParams))
    case "arria10"    => IO(new Arria10Interface(topParams))
    case "asic"       => IO(new VerilatorInterface(topParams))
    case _ => throw new Exception(s"Unknown target '$target'")
  }

  // Accel
  val accel = Module(new AccelTop(w, totalArgIns, totalArgOuts, numArgIOs, numArgInstrs, argOutLoopbacksMap, totalLoadStreamInfo, totalStoreStreamInfo, streamInsInfo, streamOutsInfo))

  target match {
    case "verilator" | "vcs" | "xsim" | "asic" =>
      // Simulation Fringe
      val blockingDRAMIssue = false
      val topIO = io.asInstanceOf[VerilatorInterface]
      val fringe = Module(new Fringe(w, totalArgIns, totalArgOuts, numArgIOs, numChannels, numArgInstrs, argOutLoopbacksMap, totalLoadStreamInfo, totalStoreStreamInfo, streamInsInfo, streamOutsInfo, blockingDRAMIssue, topIO.axiParams))

      // Fringe <-> Host connections
      fringe.io.raddr := topIO.raddr
      fringe.io.wen   := topIO.wen
      fringe.io.waddr := topIO.waddr
      fringe.io.wdata := topIO.wdata
      topIO.rdata := fringe.io.rdata

      // Fringe <-> DRAM connections
      topIO.dram <> fringe.io.dram

      if (accel.io.argIns.length > 0) {
        accel.io.argIns := fringe.io.argIns
      }

      if (accel.io.argOutLoopbacks.length > 0) {
        accel.io.argOutLoopbacks := fringe.io.argOutLoopbacks
      }

      if (accel.io.argOuts.length > 0) {
        fringe.io.argOuts.zip(accel.io.argOuts) foreach { case (fringeArgOut, accelArgOut) =>
            fringeArgOut.bits := accelArgOut.bits
            fringeArgOut.valid := accelArgOut.valid
        }
      }
      fringe.io.memStreams <> accel.io.memStreams
      accel.io.enable := fringe.io.enable
      fringe.io.done := accel.io.done
      accel.io.reset := fringe.io.reset


    case "arria10" =>
      val blockingDRAMIssue = false
      val topIO = io.asInstanceOf[Arria10Interface]
      val fringe = Module(new FringeArria10(w, totalArgIns, totalArgOuts,
                                            numArgIOs, numChannels, numArgInstrs, argOutLoopbacksMap,
                                            totalLoadStreamInfo, totalStoreStreamInfo,
                                            streamInsInfo, streamOutsInfo, blockingDRAMIssue,
                                            topIO.axiLiteParams, topIO.axiParams))
      // Fringe <-> Host Connections
      fringe.io.S_AVALON <> topIO.S_AVALON

      // Fringe <-> DRAM Connections
      topIO.M_AXI <> fringe.io.M_AXI

      // TODO: add memstream connections here
      if (accel.io.argIns.length > 0) {
        accel.io.argIns := fringe.io.argIns
      }

      if (accel.io.argOutLoopbacks.length > 0) {
        accel.io.argOutLoopbacks := fringe.io.argOutLoopbacks
      }

      if (accel.io.argOuts.length > 0) {
        fringe.io.argOuts.zip(accel.io.argOuts) foreach { case (fringeArgOut, accelArgOut) =>
          fringeArgOut.bits := accelArgOut.bits
          fringeArgOut.valid := accelArgOut.valid
        }
      }

      // memStream connections
      fringe.io.externalEnable := false.B
      fringe.io.memStreams <> accel.io.memStreams

      accel.io.enable := fringe.io.enable
      fringe.io.done := accel.io.done
      accel.reset := reset.toBool | fringe.io.reset


    case "de1soc" =>
      // DE1SoC Fringe
      val blockingDRAMIssue = false
      val topIO = io.asInstanceOf[DE1SoCInterface]
      val fringe = Module(new FringeDE1SoC(w, totalArgIns, totalArgOuts, numArgIOs, numChannels, numArgInstrs, argOutLoopbacksMap, totalLoadStreamInfo, totalStoreStreamInfo, streamInsInfo, streamOutsInfo, blockingDRAMIssue, topIO.axiParams))

      // Fringe <-> Host connections
      fringe.io.S_AVALON <> topIO.S_AVALON
      // Accel <-> Stream
      accel.io.stream_in_data                 := topIO.S_STREAM.stream_in_data
      accel.io.stream_in_startofpacket        := topIO.S_STREAM.stream_in_startofpacket
      accel.io.stream_in_endofpacket          := topIO.S_STREAM.stream_in_endofpacket
      accel.io.stream_in_empty                := topIO.S_STREAM.stream_in_empty
      accel.io.stream_in_valid                := topIO.S_STREAM.stream_in_valid
      accel.io.stream_out_ready               := topIO.S_STREAM.stream_out_ready

      // Video Stream Outputs
      topIO.S_STREAM.stream_in_ready          := accel.io.stream_in_ready
      topIO.S_STREAM.stream_out_data          := accel.io.stream_out_data
      topIO.S_STREAM.stream_out_startofpacket := accel.io.stream_out_startofpacket
      topIO.S_STREAM.stream_out_endofpacket   := accel.io.stream_out_endofpacket
      topIO.S_STREAM.stream_out_empty         := accel.io.stream_out_empty
      topIO.S_STREAM.stream_out_valid         := accel.io.stream_out_valid

      // LED Stream Outputs
      topIO.LEDR_STREAM_writedata             := accel.io.led_stream_out_data
      topIO.LEDR_STREAM_chipselect            := 1.U
      topIO.LEDR_STREAM_write_n               := 0.U
      topIO.LEDR_STREAM_address               := 0.U

      // Switch Stream Outputs
      topIO.SWITCHES_STREAM_address           := 0.U
      accel.io.switch_stream_in_data          := topIO.SWITCHES_STREAM_readdata
      topIO.SWITCHES_STREAM_read              := 0.U

      // BufferedOut Outputs
      accel.io.buffout_waitrequest            := topIO.BUFFOUT_waitrequest
      topIO.BUFFOUT_address                   := accel.io.buffout_address
      topIO.BUFFOUT_write                     := accel.io.buffout_write
      topIO.BUFFOUT_writedata                 := accel.io.buffout_writedata

      // GPI1 StreamIn
      topIO.GPI1_STREAMIN_chipselect          := 1.U
      topIO.GPI1_STREAMIN_address             := 0.U
      topIO.GPI1_STREAMIN_read                := 1.U
      accel.io.gpi1_streamin_readdata         := topIO.GPI1_STREAMIN_readdata

      // GPO1 StreamOut
      topIO.GPO1_STREAMOUT_chipselect         := 1.U
      topIO.GPO1_STREAMOUT_address            := 0.U
      topIO.GPO1_STREAMOUT_writen             := 0.U
      topIO.GPO1_STREAMOUT_writedata          := accel.io.gpo1_streamout_writedata

      // GPI2 StreamIn
      topIO.GPI2_STREAMIN_chipselect          := 1.U
      topIO.GPI2_STREAMIN_address             := 0.U
      topIO.GPI2_STREAMIN_read                := 1.U
      accel.io.gpi2_streamin_readdata         := topIO.GPI2_STREAMIN_readdata

      // GPO2 StreamOut
      topIO.GPO2_STREAMOUT_chipselect         := 1.U
      topIO.GPO2_STREAMOUT_address            := 0.U
      topIO.GPO2_STREAMOUT_writen             := 0.U
      topIO.GPO2_STREAMOUT_writedata          := accel.io.gpo2_streamout_writedata

      if (accel.io.argIns.length > 0) {
        accel.io.argIns := fringe.io.argIns
      }

      if (accel.io.argOuts.length > 0) {
        fringe.io.argOuts.zip(accel.io.argOuts) foreach { case (fringeArgOut, accelArgOut) =>
            fringeArgOut.bits := accelArgOut.bits
            fringeArgOut.valid := accelArgOut.valid
        }
      }

      accel.io.enable := fringe.io.enable
      fringe.io.done := accel.io.done
      // Top reset is connected to a rst controller on DE1SoC, which converts active low to active high
      accel.reset := reset.toBool | fringe.io.reset

    case "zynq" | "zcu" =>
      // Zynq Fringe
      val topIO = io.asInstanceOf[ZynqInterface]

      val blockingDRAMIssue = false // Allow only one in-flight request, block until response comes back
      val fringe = Module(new FringeZynq(w, totalArgIns, totalArgOuts, numArgIOs, numChannels, numArgInstrs, argOutLoopbacksMap, totalLoadStreamInfo, totalStoreStreamInfo, streamInsInfo, streamOutsInfo, blockingDRAMIssue, topIO.axiLiteParams, topIO.axiParams))

      // Fringe <-> Host connections
      fringe.io.S_AXI <> topIO.S_AXI

      // Fringe <-> DRAM connections
      topIO.M_AXI <> fringe.io.M_AXI

      topIO.TOP_AXI <> fringe.io.TOP_AXI
      topIO.DWIDTH_AXI <> fringe.io.DWIDTH_AXI
      topIO.PROTOCOL_AXI <> fringe.io.PROTOCOL_AXI
      topIO.CLOCKCONVERT_AXI <> fringe.io.CLOCKCONVERT_AXI

      accel.io.argIns := fringe.io.argIns
      accel.io.argOutLoopbacks := fringe.io.argOutLoopbacks
      fringe.io.argOuts.zip(accel.io.argOuts) foreach { case (fringeArgOut, accelArgOut) =>
          fringeArgOut.bits := accelArgOut.bits
          fringeArgOut.valid := accelArgOut.valid
      }
      // accel.io.argIOIns := fringe.io.argIOIns
      // fringe.io.argIOOuts.zip(accel.io.argIOOuts) foreach { case (fringeArgOut, accelArgOut) =>
      //     fringeArgOut.bits := accelArgOut.bits
      //     fringeArgOut.valid := 1.U
      // }
      fringe.io.externalEnable := false.B
      fringe.io.memStreams <> accel.io.memStreams
      accel.io.enable := fringe.io.enable
      fringe.io.done := accel.io.done
      fringe.reset := ~reset.toBool
      accel.reset := fringe.io.reset
      // accel.reset := ~reset.toBool
      io.is_enabled := ~accel.io.enable

    case "aws" | "aws-sim" =>
      val topIO = io.asInstanceOf[AWSInterface]
      val blockingDRAMIssue = false  // Allow only one in-flight request, block until response comes back
//      val fringe = Module(new Fringe(w, totalArgIns, totalArgOuts, numArgIOs, totalLoadStreamInfo, totalStoreStreamInfo, streamInsInfo, streamOutsInfo, blockingDRAMIssue))
      val fringe = Module(new FringeZynq(w, totalArgIns, totalArgOuts, numArgIOs, numChannels, numArgInstrs, argOutLoopbacksMap, totalLoadStreamInfo, totalStoreStreamInfo, streamInsInfo, streamOutsInfo, blockingDRAMIssue, topIO.axiLiteParams, topIO.axiParams))

      // Fringe <-> DRAM connections
//      topIO.dram <> fringe.io.dram
      topIO.M_AXI <> fringe.io.M_AXI
      fringe.io.memStreams <> accel.io.memStreams

      // Accel: Scalar and control connections
      accel.io.argIns := topIO.scalarIns
      topIO.scalarOuts.zip(accel.io.argOuts) foreach { case (ioOut, accelOut) => ioOut := getFF(accelOut.bits, accelOut.valid) }
      accel.io.enable := topIO.enable
      topIO.done := accel.io.done
      accel.io.reset := fringe.io.reset

      fringe.io.externalEnable := topIO.enable
//      topIO.dbg <> fringe.io.dbg

    case _ =>
      throw new Exception(s"Unknown target '$target'")
  }

  // TBD: Accel <-> Fringe Memory connections
//  for (i <- 0 until numMemoryStreams) {
//    fringe.io.memStreams(i).cmd.bits := accel.io.memStreams(i).cmd.bits
//    fringe.io.memStreams(i).cmd.valid := accel.io.memStreams(i).cmd.valid
//    fringe.io.memStreams(i).wdata.bits := accel.io.memStreams(i).wdata.bits
//    fringe.io.memStreams(i).wdata.valid := accel.io.memStreams(i).wdata.valid
//    accel.io.memStreams(i).rdata.bits := fringe.io.memStreams(i).rdata.bits
//    accel.io.memStreams(i).rdata.valid := fringe.io.memStreams(i).rdata.valid
//  }

}
