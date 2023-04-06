package coupledL2

import chipsalliance.rocketchip.config.Parameters
import freechips.rocketchip.tilelink.TLMessages._
import chisel3._
import chisel3.util._
import coupledL2.utils._
import utility._

class RequestBuffer(flow: Boolean = true, entries: Int = 4)(implicit p: Parameters) extends L2Module {

  val io = IO(new Bundle() {
    val in          = Flipped(DecoupledIO(new TaskBundle))
    val out         = DecoupledIO(new TaskBundle)
    val mshrStatus  = Vec(mshrsAll, Flipped(ValidIO(new MSHRStatus)))
    val mainPipeBlock = Input(Vec(2, Bool()))

    val ATag        = Output(UInt(tagBits.W))
    val ASet        = Output(UInt(setBits.W))
  })

  /* ======== Data Structure ======== */
  val reqEntry = new L2Bundle(){
    val valid    = Bool()
    val rdy      = Bool()
    val task     = new TaskBundle()

    /* blocked by MainPipe
    * [3] by stage1, a same-set entry just fired
    * [2] by stage2
    * [1] by stage3
    * [0] block release flag
    */
    val waitMP  = UInt(4.W)

    /* which MSHR the entry is waiting for */
    val waitMS  = UInt(mshrsAll.W)

    /* buffer_dep_mask[i][j] => entry i should wait entry j
    *   this is used to make sure that same set requests will be sent
    *   to MSHR in order
    */
    val depMask = Vec(entries, Bool())

    /* ways in the set that are occupied by unfinished MSHR task */
    val occWays = UInt(cacheParams.ways.W)
  }

  io.ATag := io.in.bits.tag
  io.ASet := io.in.bits.set

  val buffer = RegInit(VecInit(Seq.fill(entries)(0.U.asTypeOf(reqEntry))))

  /* ======== Enchantment ======== */
  val NWay = cacheParams.ways
  def sameAddr(a: TaskBundle, b: TaskBundle): Bool = Cat(a.tag, a.set) === Cat(b.tag, b.set)
  def sameAddr(a: TaskBundle, b: MSHRStatus): Bool = Cat(a.tag, a.set) === Cat(b.tag, b.set)
  def sameSet (a: TaskBundle, b: TaskBundle): Bool = a.set === b.set
  def sameSet (a: TaskBundle, b: MSHRStatus): Bool = a.set === b.set
  def countWaysOH(cond: (MSHRStatus => Bool)): UInt = {
    VecInit(io.mshrStatus.map(s =>
      Mux(
        s.valid && cond(s.bits),
        UIntToOH(s.bits.way, NWay),
        0.U(NWay.W)
      )
    )).reduceTree(_ | _)
  }
  def occWays     (a: TaskBundle): UInt = countWaysOH(s => !s.will_free && sameSet(a, s))
  def willFreeWays(a: TaskBundle): UInt = countWaysOH(s =>  s.will_free && sameSet(a, s))

  def noFreeWay(a: TaskBundle): Bool = !Cat(~occWays(a)).orR
  def noFreeWay(occWays: UInt): Bool = !Cat(~occWays).orR

  val full         = Cat(buffer.map(_.valid)).andR
  val conflictMask = io.mshrStatus.map(s =>
    s.valid && sameAddr(io.in.bits, s.bits) && !s.bits.will_free
  )
  val conflict     = Cat(conflictMask).orR
  val noReadyEntry = Wire(Bool()) // TODO:rename

  val canFlow      = flow.B && noReadyEntry
  val doFlow       = canFlow && io.out.ready && !noFreeWay(io.in.bits)

  // TODO: remove depMatrix cuz not important
  val depMask    = buffer.map(e => e.valid && sameAddr(io.in.bits, e.task))
  val isPrefetch = io.in.bits.fromA && io.in.bits.opcode === Hint
  val dup        = io.in.valid && isPrefetch && Cat(depMask).orR // duplicate prefetch

  /* ======== Alloc ======== */
  io.in.ready   := !full || doFlow

  val insertIdx = PriorityEncoder(buffer.map(!_.valid))
  val alloc = !full && io.in.valid && !doFlow && !dup
  when(alloc){
    val entry = buffer(insertIdx)
    val mpBlock = Cat(io.mainPipeBlock).orR
    entry.valid   := true.B
    // when Addr-Conflict / Same-Addr-Dependent / MainPipe-Block / noFreeWay-in-Set, entry not ready
    entry.rdy     := !conflict && !Cat(depMask).orR && !mpBlock && !noFreeWay(io.in.bits)
    entry.task    := io.in.bits
    entry.waitMP  := Cat(
      io.out.valid && sameSet(io.in.bits, io.out.bits),
      io.mainPipeBlock(0),
      io.mainPipeBlock(1),
      0.U(1.W))
    entry.waitMS  := VecInit(conflictMask).asUInt
    entry.occWays := Mux(mpBlock, 0.U, occWays(io.in.bits))

    entry.depMask := depMask //TODO
    assert(PopCount(conflictMask) <= 2.U)
  }

  // for in-out-related depMask bug, consider add a cleaner to check it every few cycles
  // low power consuming
  // flow 的请求可能会越过 alloc 的项，即使它们是同 set 的？
  /* ======== Issue ======== */
  val issueArb = Module(new FastArbiter(reqEntry, entries))
  // once fired at issueArb, it is ok to enter MainPipe without conflict
  // however, it may be blocked for other reasons such as high-prior reqs or MSHRFull
  // in such case, we need a place to save it
  val outPipe = Queue(issueArb.io.out, entries = 1, pipe = true, flow = false)

  for (i <- 0 until entries) {
    issueArb.io.in(i).valid := buffer(i).valid && buffer(i).rdy
    issueArb.io.in(i).bits  := buffer(i)

    when(issueArb.io.in(i).fire) {
      buffer(i).valid := false.B
    }
  }

  //TODO: if i use occWays when update,
  // does this mean that every entry has occWays logic?
  // !TODO: do it for now, later consider using Queue2

  /* ======== Update rdy and masks ======== */
  for (e <- buffer) {
    when(e.valid) {
      val waitMSUpdate  = WireInit(e.waitMS)
      val depMaskUpdate = WireInit(e.depMask)
      val occWaysUpdate = WireInit(e.occWays)

      // when mshr will_free, clear it in other reqs' waitMS and occWays
      val willFreeMask = VecInit(io.mshrStatus.map(s => s.valid && s.bits.will_free)).asUInt
      waitMSUpdate  := e.waitMS  & (~willFreeMask).asUInt
      occWaysUpdate := e.occWays & (~willFreeWays(e.task)).asUInt

      // Initially,
      //    waitMP(2) = s2 blocking, wait 2 cycles
      //    waitMP(1) = s3 blocking, wait 1 cycle
      // Now that we shift right waitMP every cycle
      //    so when waitMP(1) is 0 and waitMP(0) is 1, reach desired cycleCnt
      //    we recalculate occWays to take new allocated MSHR into account
      e.waitMP := e.waitMP >> 1.U
      when(e.waitMP(1) === 0.U && e.waitMP(0) === 1.U) {
        occWaysUpdate := occWays(e.task)
      }

      // when issue fire, clear it in other reqs' depMask
      when(issueArb.io.out.fire) {
        depMaskUpdate(issueArb.io.chosen) := false.B
      }
      // if io.out is the same set, we also need to set waitMP
      when(io.out.fire && sameSet(e.task, io.out.bits)) {
        e.waitMP := e.waitMP | "b1000".U
      }

      // update info
      e.waitMS  := waitMSUpdate
      e.depMask := depMaskUpdate
      e.occWays := occWaysUpdate
      e.rdy     := !waitMSUpdate.orR && !Cat(depMaskUpdate).orR && !e.waitMP && !noFreeWay(occWaysUpdate)
    }
  }

  /* ======== Output ======== */
  outPipe.ready := io.out.ready
  noReadyEntry := !outPipe.valid

  io.out.valid := outPipe.valid || io.in.valid && canFlow
  io.out.bits  := Mux(canFlow, io.in.bits, outPipe.bits.task)

  // for Dir to choose a way not occupied by some unfinished MSHR task
  io.out.bits.wayMask := Mux(canFlow, ~occWays(io.in.bits), ~outPipe.bits.occWays)


  // TODO: add a XSPerf to see see the timecounter of cycles the req is held in Buffer

}
