package spatial.dse

import java.io.PrintStream
import java.util.concurrent.{Executors, LinkedBlockingQueue, TimeUnit}

import argon.core._
import spatial.Subproc
import spatial.aliases._
import spatial.metadata._

trait HyperMapperDSE { this: DSE =>

  def hyperMapperDSE(space: Seq[Domain[Int]], program: Block[_], file: String = config.name + "_data.csv"): Unit = {
    val N = space.size
    val T = spatialConfig.threads
    val dir =  config.cwd + "/results/"
    val filename = dir + file

    new java.io.File(dir).mkdirs()

    report("Space Statistics: ")
    report("-------------------------")
    report(s"  # of parameters: $N")
    report("")
    report(s"Using $T threads")
    report(s"Writing results to file $filename")

    val workQueue = new LinkedBlockingQueue[Seq[Int]](5000)  // Max capacity specified here
    val fileQueue = new LinkedBlockingQueue[String](5000)

    val workerIds = (0 until T - 1).toList

    val pool = Executors.newFixedThreadPool(T)
    val workers = workerIds.map{id =>
      val threadState = new State
      state.copyTo(threadState)
      HyperMapperThread(
        threadId  = id,
        space     = space,
        accel     = top,
        program   = program,
        localMems = localMems,
        workQueue = workQueue,
        outQueue  = fileQueue
      )(state)
    }

    report("Initializing models...")
    val pcsFile = config.name + ".pcs"
    val workDir = "dse_hm"
    val HEADER = space.map(_.name).mkString(",") + "," + workers.head.areaHeading.mkString(",") + ", Cycles, Valid"

    withLog(workDir, pcsFile){
      space.foreach{domain =>
        msg(s"""${domain.name} ${domain.tp} {${domain.options.mkString(", ")}}""", 100)
      }
    }
    val hm = Subproc(spatialConfig.HYPERMAPPER + "/hypermapper", pcsFile) { (cmd,reader) =>
      val parts = cmd.split(",").map(_.trim)
      val command = parts.head
      val nPoints = parts.last.toInt
      val header  = reader.readLine().split(",").map(_.trim)
      val order   = space.map{d => header.indexOf(d.name) }
      val points  = (0 until nPoints).map{_ => reader.readLine() }

      command match {
        case "Request" =>
          points.foreach{point =>
            val values = point.split(",").map(_.trim.toInt)
            workQueue.put(order.map{i => values(i) })
          }
          Some(HEADER + "\n" + points.indices.map { _ => fileQueue.take() }.mkString("\n"))

        case "Pareto" =>
          val data = new PrintStream(config.name + "_hm_data.csv")
          data.println(HEADER)
          points.foreach{pt => data.println(pt) }
          data.close()
          None
      }
    }

    // Initializiation may not be threadsafe - only creates 1 area model shared across all workers
    workers.foreach{worker => worker.init() }
    workers.foreach{worker => pool.submit(worker) }

    val startTime = System.currentTimeMillis
    hm.block(Some(workDir))

    println("Ending work queue.")

    // Poison the work queue (make sure to use enough to kill them all!)
    workerIds.foreach{_ => workQueue.put(Seq.empty[Int]) }

    println("Waiting for workers to complete...")
    pool.shutdown()
    pool.awaitTermination(10L, TimeUnit.HOURS)

    val endTime = System.currentTimeMillis()
    val totalTime = (endTime - startTime)/1000.0

    println(s"Completed space search in $totalTime seconds.")
  }

}