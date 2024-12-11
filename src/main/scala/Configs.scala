package spmm

import chisel3._
import org.chipsalliance.cde.config.{Config, Parameters}
import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tile.{BuildRoCC, OpcodeSet}
import freechips.rocketchip.rocket._
import freechips.rocketchip.tile._
import freechips.rocketchip.system._
import freechips.rocketchip.diplomacy._

class WithSpMM extends Config((site, here, up) => {
  case BuildRoCC => Seq((p: Parameters) => LazyModule(
    new SpMM(OpcodeSet.custom0)(p)))
})

// class SpMMConfig extends Config(
//   new WithCustomAccelerator ++
//   new RocketConfig)