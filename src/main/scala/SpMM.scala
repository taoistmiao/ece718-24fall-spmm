package spmm

import chisel3._
import chisel3.util._
import chisel3.experimental.IntParam
import org.chipsalliance.cde.config.{Config, Parameters}
import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tile.{BuildRoCC, OpcodeSet}
import freechips.rocketchip.rocket._
import freechips.rocketchip.tile._
import freechips.rocketchip.system._
import freechips.rocketchip.diplomacy._

class SpMM(opcodes: OpcodeSet, val n: Int = 10)(implicit p: Parameters) extends LazyRoCC(opcodes) {
  override lazy val module = new SpMMImp(this)
}

class SpMMImp(outer: SpMM)(implicit p: Parameters) extends LazyRoCCModuleImp(outer)
    with HasCoreParameters {
  val regfile = Mem(outer.n, UInt(xLen.W))
  val busy = RegInit(VecInit(Seq.fill(outer.n){false.B}))

  val cmd = Queue(io.cmd)
  val funct = cmd.bits.inst.funct
  val addr = cmd.bits.rs2(log2Up(outer.n)-1,0)
  val doWrite = funct === 0.U
  val doRead = funct === 1.U
  val doLoad = funct === 2.U
  val doStore = funct === 3.U
  val doAdd = funct === 4.U
  val doMAC = funct === 5.U
  val memRespTag = io.mem.resp.bits.tag(log2Up(outer.n)-1,0)

  // datapath
  val addend = cmd.bits.rs1
  val accum = regfile(addr)
  val product = Cat(
                    (regfile(1)(8*1-1,0)*regfile(2))(7,0),
                    (regfile(1)(8*2-1,8*1)*regfile(2))(7,0),
                    (regfile(1)(8*3-1,8*2)*regfile(2))(7,0),
                    (regfile(1)(8*4-1,8*3)*regfile(2))(7,0),
                    (regfile(1)(8*5-1,8*4)*regfile(2))(7,0)
                  )
  val wdata = Mux(doWrite, addend, Mux(doMAC, product, accum + addend))

  when (cmd.fire && (doWrite || doAdd || doMAC)) {
    regfile(addr) := wdata
  }

  when (io.mem.resp.valid) {

    regfile(memRespTag) := Mux(doLoad, io.mem.resp.bits.data, regfile(memRespTag))
    busy(memRespTag) := false.B
  }

  // control
  when (io.mem.req.fire) {
    busy(addr) := true.B
  }

  val doResp = cmd.bits.inst.xd
  val stallReg = busy(addr)
  val stallLoad = doLoad && !io.mem.req.ready
  val stallResp = doResp && !io.resp.ready

  cmd.ready := !stallReg && !stallLoad && !stallResp
    // command resolved if no stalls AND not issuing a load that will need a request

  // PROC RESPONSE INTERFACE
  io.resp.valid := cmd.valid && doResp && !stallReg && !stallLoad
    // valid response if valid command, need a response, and no stalls
  io.resp.bits.rd := cmd.bits.inst.rd
    // Must respond with the appropriate tag or undefined behavior
  io.resp.bits.data := accum
    // Semantics is to always send out prior accumulator register value

  io.busy := cmd.valid || busy.reduce(_||_)
    // Be busy when have pending memory requests or committed possibility of pending requests
  io.interrupt := false.B
    // Set this true to trigger an interrupt on the processor (please refer to supervisor documentation)

  // MEMORY REQUEST INTERFACE
  io.mem.req.valid := cmd.valid && (doLoad || doStore) && !stallReg && !stallResp
  io.mem.req.bits.addr := addend
  io.mem.req.bits.tag := addr
  io.mem.req.bits.cmd := Mux(doLoad, M_XRD, M_XWR) // perform a load (M_XWR for stores)
  io.mem.req.bits.size := log2Ceil(8).U
  io.mem.req.bits.signed := false.B
  io.mem.req.bits.data := Mux(doLoad, 0.U, regfile(0)) // we're not performing any stores...
  io.mem.req.bits.phys := false.B
  io.mem.req.bits.dprv := cmd.bits.status.dprv
  io.mem.req.bits.dv := cmd.bits.status.dv
  io.mem.req.bits.no_resp := false.B

}