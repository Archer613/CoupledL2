/** *************************************************************************************
 * Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
 * Copyright (c) 2020-2021 Peng Cheng Laboratory
 *
 * XiangShan is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 * http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 *
 * See the Mulan PSL v2 for more details.
 * *************************************************************************************
 */

package coupledL2

import chisel3._
import chisel3.util._
import coupledL2.MetaData._
import coupledL2.utils.{ParallelLookUp, ParallelPriorityMux}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tilelink.TLMessages._
import freechips.rocketchip.tilelink.TLPermissions._
import chipsalliance.rocketchip.config.Parameters

class MSHRTasks(implicit p: Parameters) extends L2Bundle {
  // outer
  val source_a = DecoupledIO(new SourceAReq) // To AcquireUnit  // TODO: no need to use decoupled handshake
  val source_b = DecoupledIO(new SourceBReq)
  val mainpipe = DecoupledIO(new TaskBundle) // To Mainpipe (SourceC or SourceD)
}

class MSHRResps(implicit p: Parameters) extends L2Bundle {
  val sink_c = Flipped(ValidIO(new RespInfoBundle))
  val sink_d = Flipped(ValidIO(new RespInfoBundle))
  val sink_e = Flipped(ValidIO(new RespInfoBundle))
}

class MSHR(implicit p: Parameters) extends L2Module {
  val io = IO(new Bundle() {
    val id = Input(UInt(mshrBits.W))
    val status = ValidIO(new MSHRStatus)
    val alloc = Flipped(ValidIO(new MSHRRequest))
    val tasks = new MSHRTasks()
    val resps = new MSHRResps()
    val nestedwb = Input(new NestedWriteback)
    val nestedwbData = Output(Bool())
  })

  val initState = Wire(new FSMState())
  val state = RegInit(new FSMState(), initState)
  initState.elements.foreach(_._2 := true.B)
  val dirResult = RegInit(0.U.asTypeOf(new DirResult()))
  val gotT = RegInit(false.B) // TODO: L3 might return T even though L2 wants B
  val gotDirty = RegInit(false.B)
  val probeDirty = RegInit(false.B)
  val probeGotN = RegInit(false.B)

  /* MSHR Allocation */
  val status_reg = RegInit(0.U.asTypeOf(Valid(new MSHRStatus())))
  when(io.alloc.valid) {
    status_reg.valid := true.B
    state := io.alloc.bits.state
    dirResult := io.alloc.bits.dirResult
    val ms_task = io.alloc.bits.task
    status_reg.bits.channel := ms_task.channel
    status_reg.bits.tag := ms_task.tag
    status_reg.bits.set := ms_task.set
    status_reg.bits.off := ms_task.off
    status_reg.bits.way := ms_task.way
    status_reg.bits.opcode := ms_task.opcode
    status_reg.bits.param := ms_task.param
    status_reg.bits.size := ms_task.size
    status_reg.bits.source := ms_task.sourceId
    status_reg.bits.needProbeAckData := ms_task.needProbeAckData
    status_reg.bits.alias := ms_task.alias
    status_reg.bits.aliasTask := ms_task.aliasTask
    status_reg.bits.pbIdx := ms_task.pbIdx
    gotT := false.B
    gotDirty := false.B
    probeDirty := false.B
    probeGotN := false.B
  }

  /* Intermediate logic */
  val req = status_reg.bits
  val meta = dirResult.meta
  val meta_no_client = !meta.clients.orR

  val req_needT = needT(req.opcode, req.param)
  val req_acquire = req.opcode === AcquireBlock || req.opcode === AcquirePerm
  val req_put = req.opcode === PutFullData || req.opcode === PutPartialData
  val req_get = req.opcode === Get
  val req_promoteT = req_acquire && Mux(dirResult.hit, meta_no_client && meta.state === TIP, gotT)

  /* Task allocation */
  // Theoretically, data to be released is saved in ReleaseBuffer, so Acquire can be sent as soon as req enters mshr
  io.tasks.source_a.valid := !state.s_acquire
  io.tasks.source_b.valid := !state.s_pprobe || !state.s_rprobe
  val mp_release_valid = !state.s_release && state.w_rprobeacklast
  val mp_probeack_valid = !state.s_probeack && state.w_pprobeacklast
  val mp_grant_valid = !state.s_refill && state.w_grantlast && state.w_rprobeacklast && state.s_release // [Alias] grant after rprobe done
  io.tasks.mainpipe.valid := mp_release_valid || mp_probeack_valid || mp_grant_valid

  val oa = io.tasks.source_a.bits
  oa := DontCare
  oa.tag := req.tag
  oa.set := req.set
  oa.off := req.off
  oa.source := io.id
  oa.opcode := Mux(
    req_put,
    req.opcode,
    Mux(dirResult.hit, AcquirePerm, AcquireBlock)
  )
  oa.param := Mux(
    req_put,
    req.param,
    Mux(req_needT, Mux(dirResult.hit, BtoT, NtoT), NtoB)
  )
  oa.size := req.size
  oa.pbIdx := req.pbIdx

  val ob = io.tasks.source_b.bits
  ob := DontCare
  ob.tag := dirResult.tag
  ob.set := dirResult.set
  ob.off := 0.U
  ob.opcode := Probe
  // ob.param := Mux(!state.s_pprobe, req.param, toN)
  ob.param := Mux(
    !state.s_pprobe,
    req.param,
    Mux(
      req_get && dirResult.hit && meta.state === TRUNK,
      toB,
      toN
    )
  )
  ob.alias := meta.alias(0)

  val mp_release, mp_probeack, mp_grant = Wire(new TaskBundle)

  mp_release := DontCare
  mp_release.channel := req.channel
  mp_release.tag := dirResult.tag
  mp_release.set := req.set
  mp_release.off := 0.U
  mp_release.alias := 0.U
  mp_release.opcode := Mux(
    meta.dirty && meta.state =/= INVALID || probeDirty,
    ReleaseData,
    Release
  )
  mp_release.param := Mux(isT(meta.state), TtoN, BtoN)
  mp_release.mshrTask := true.B
  mp_release.mshrId := io.id
  mp_release.aliasTask := false.B
  mp_release.useProbeData := true.B // read ReleaseBuf when useProbeData && opcode(0) is true
  mp_release.way := req.way
  mp_release.metaWen := true.B
  mp_release.meta := MetaEntry(dirty = false.B, state = INVALID, clients = 0.U, alias = meta.alias)
  mp_release.tagWen := false.B
  mp_release.dsWen := false.B

  mp_probeack := DontCare
  mp_probeack.channel := req.channel
  mp_probeack.tag := req.tag
  mp_probeack.set := req.set
  mp_probeack.off := req.off
  mp_probeack.opcode := Mux(
    meta.dirty && isT(meta.state) || probeDirty || req.needProbeAckData,
    ProbeAckData,
    ProbeAck
  )
  mp_probeack.param := ParallelLookUp(
    Cat(isT(meta.state), req.param(bdWidth - 1, 0)),
    Seq(
      Cat(false.B, toN) -> BtoN,
      Cat(false.B, toB) -> BtoB, // TODO: make sure that this req will not enter mshr in this situation
      Cat(true.B,  toN) -> TtoN,
      Cat(true.B,  toB) -> TtoB
    )
  )
  mp_probeack.mshrTask := true.B
  mp_probeack.mshrId := io.id
  mp_probeack.aliasTask := false.B
  mp_probeack.useProbeData := true.B // read ReleaseBuf when useProbeData && opcode(0) is true
  mp_probeack.way := req.way
  mp_probeack.meta := MetaEntry(
    dirty = false.B,
    state = Mux(
      req.param === toN,
      INVALID,
      Mux(
        req.param === toB,
        BRANCH,
        meta.state
      )
    ),
    clients = Fill(clientBits, !probeGotN),
    alias = meta.alias //[Alias] TODO: Keep alias bits unchanged
  )
  mp_probeack.metaWen := true.B
  mp_probeack.tagWen := false.B
  mp_probeack.dsWen := req.param =/= toN && probeDirty

  mp_grant := DontCare
  mp_grant.channel := req.channel
  mp_grant.tag := req.tag
  mp_grant.set := req.set
  mp_grant.off := req.off
  mp_grant.sourceId := req.source
  mp_grant.opcode := odOpGen(req.opcode)
  mp_grant.param := Mux(
    req_get || req_put,
    0.U, // Get/Put -> AccessAckData/AccessAck
    MuxLookup( // Acquire -> Grant
      req.param,
      req.param,
      Seq(
        NtoB -> Mux(req_promoteT, toT, toB),
        BtoT -> toT,
        NtoT -> toT
      )
    )
  )
  mp_grant.mshrTask := true.B
  mp_grant.mshrId := io.id
  mp_grant.way := req.way
  mp_grant.alias := req.alias
  mp_grant.aliasTask := req.aliasTask
  // [Alias] write probeData into DS for alias-caused Probe,
  // but not replacement-cased Probe
  mp_grant.useProbeData := dirResult.hit && req_get || req.aliasTask

  val meta_alias = WireInit(meta.alias)
  meta_alias(0) := req.alias
  mp_grant.meta := MetaEntry(
    dirty = gotDirty || dirResult.hit && (meta.dirty || probeDirty),
    state = Mux(
      req_get,
      Mux(
        dirResult.hit,
        Mux(isT(meta.state), TIP, BRANCH),
        Mux(req_promoteT, TIP, BRANCH)
      ),
      Mux(
        req_promoteT || req_needT,
        TRUNK,
        BRANCH
      )
    ),
    clients = Fill(clientBits, !(req_get && (!dirResult.hit || meta_no_client || probeGotN))),
    alias = meta_alias //[Alias] TODO: consider one client for now
  )
  mp_grant.metaWen := !req_put
  mp_grant.tagWen := !dirResult.hit && !req_put
  mp_grant.dsWen := !dirResult.hit && !req_put || probeDirty && (req_get || req.aliasTask)

  io.tasks.mainpipe.bits := ParallelPriorityMux(
    Seq(
      mp_grant_valid    -> mp_grant,
      mp_release_valid  -> mp_release,
      mp_probeack_valid -> mp_probeack
    )
  )

  /* Task update */
  when (io.tasks.source_a.fire) {
    state.s_acquire := true.B
  }
  when (io.tasks.source_b.fire) {
    state.s_pprobe := true.B
    state.s_rprobe := true.B
  }
  when (io.tasks.mainpipe.ready) {
    when (mp_grant_valid) {
      state.s_refill := true.B
    }.elsewhen (mp_release_valid) {
      state.s_release := true.B
    }.elsewhen (mp_probeack_valid) {
      state.s_probeack := true.B
    }
  }

  /* Refill response */
  val c_resp = io.resps.sink_c
  val d_resp = io.resps.sink_d
  val e_resp = io.resps.sink_e
  when (c_resp.valid) {
    when (c_resp.bits.opcode === ProbeAck || c_resp.bits.opcode === ProbeAckData) {
      state.w_rprobeackfirst := true.B
      state.w_rprobeacklast := state.w_rprobeacklast || c_resp.bits.last
      state.w_pprobeackfirst := true.B
      state.w_pprobeacklast := state.w_pprobeacklast || c_resp.bits.last
      state.w_pprobeack := state.w_pprobeack || status_reg.bits.off === 0.U || c_resp.bits.last
    }
    when (c_resp.bits.opcode === ProbeAckData) {
      probeDirty := true.B
    }
    when (isToN(c_resp.bits.param)) {
      probeGotN := true.B
    }
  }

  when (d_resp.valid) {
    when(d_resp.bits.opcode === Grant || d_resp.bits.opcode === GrantData || d_resp.bits.opcode === AccessAck) {
      state.w_grantfirst := true.B
      state.w_grantlast := d_resp.bits.last
      state.w_grant := status_reg.bits.off === 0.U || d_resp.bits.last  // TODO? why offset?
    }
    when(d_resp.bits.opcode === Grant || d_resp.bits.opcode === GrantData) {
      gotT := d_resp.bits.param === toT
      gotDirty := gotDirty || d_resp.bits.dirty
    }
    when(d_resp.bits.opcode === ReleaseAck) {
      state.w_releaseack := true.B
    }
  }

  when (e_resp.valid) {
    state.w_grantack := true.B
  }

  val no_schedule = state.s_refill && state.s_probeack
  val no_wait = state.w_rprobeacklast && state.w_pprobeacklast && state.w_grantlast && state.w_releaseack && state.w_grantack
  when (no_schedule && no_wait && status_reg.valid) {
    status_reg.valid := false.B
  }

  io.status.valid := status_reg.valid
  io.status.bits <> status_reg.bits
  // For A reqs, we only concern about the tag to be replaced
  io.status.bits.tag := Mux(req.fromB, req.tag, dirResult.tag)
  io.status.bits.nestB := status_reg.valid && state.w_releaseack && state.w_rprobeacklast && state.w_pprobeacklast && !state.w_grantfirst
  io.status.bits.w_c_resp := !state.w_rprobeacklast || !state.w_pprobeacklast || !state.w_pprobeack
  io.status.bits.w_d_resp := !state.w_grantlast || !state.w_grant || !state.w_releaseack
  io.status.bits.w_e_resp := !state.w_grantack
  assert(io.status.bits.w_c_resp || !c_resp.valid)
  assert(io.status.bits.w_d_resp || !d_resp.valid)
  assert(io.status.bits.w_e_resp || !e_resp.valid)

  val nestedwb_match = status_reg.valid && meta.state =/= INVALID &&
    dirResult.set === io.nestedwb.set &&
    dirResult.tag === io.nestedwb.tag
  when (nestedwb_match) {
    when (io.nestedwb.b_toN) {
      dirResult.hit := false.B
    }
    when (io.nestedwb.b_toB) {
      meta.state := BRANCH
    }
    when (io.nestedwb.b_clr_dirty) {
      meta.dirty := false.B
    }
    when (io.nestedwb.c_set_dirty) {
      meta.dirty := true.B
    }
  }

  io.nestedwbData := nestedwb_match && io.nestedwb.c_set_dirty

  dontTouch(state)
}
