/** Lane EXU: contains multiple lanes
 *  
 *  Mask/tail/prestart distribution to each lane
 *  Input vs1/vs2/oldVd rearrange for widen or narrow
 *  Output vd rearrange for narrow
 *  Output mask rearrange for compare/add-with-carry
 */

package darecreek

import chisel3._
import chisel3.util._

class VLaneExu extends Module {
  val io = IO(new Bundle {
    val in = new Bundle {
      val bits = Input(new VExuInput)
      val valid = Input(Bool())
      val readys = Output(Vec(NLaneExuFUs, Bool()))
    }
    val out = Decoupled(new VExuOutput)
  })

  require(NLanes > 1, s"Number of lanes: $NLanes must > 1 (XLEN >= 128)")
  val uop = io.in.bits.uop
  val narrow = uop.ctrl.narrow
  val destEew = SewOH(uop.info.destEew)
  val expdIdx = uop.expdIdx

  val lanes = Seq.fill(NLanes)(Module(new VLane))
  for (i <- 0 until NLanes) {
    lanes(i).io.idx := i.U
    lanes(i).io.in.valids(0) := io.in.valid && uop.ctrl.alu
    lanes(i).io.in.valids(1) := io.in.valid && uop.ctrl.mul
    lanes(i).io.in.valids(2) := io.in.valid && uop.ctrl.fp
    lanes(i).io.in.valids(3) := io.in.valid && uop.ctrl.div
    lanes(i).io.in.data.uop := uop
    lanes(i).io.in.data.rs1 := io.in.bits.rs1
    lanes(i).io.in.data.prestart := 0.U  // So far, set vstart = 0 for all arithmetic instructions
    lanes(i).io.out.ready := io.out.ready
  }
  io.in.readys := lanes(0).io.in.readys

  /** Input tail distribution
   */
  val tail = TailGen(uop.info.vl, expdIdx, destEew, narrow)
  // Tail of each lane. It occupies the lowest bits of lane mask_input.
  val laneTail = Wire(Vec(NLanes, UInt(NByteLane.W)))
  // Lane index:        3       2       1       0
  // sew=32 laneTail:  76      54      32      10
  for (i <- 0 until NLanes) {
    laneTail(i) :=  Mux1H(destEew.oneHot, Seq(1,2,4,8).map(bytes => UIntSplit(tail, NByteLane/bytes)(i)))
  }
  //---- Tail rearrange for narrow instruction ----
  // Lane index:      3       2       1       0
  // sew=32 input:   76      54      32      10 (input is laneTail)
  //       output:   73      62      51      40 (output is laneTailNarrow)
  // sew=16 input:                 7654    3210
  //       output:                 BA32    9810
  val laneTailNarrow = Wire(Vec(NLanes, UInt(NByteLane.W)))
  for (i <- 0 until NLanes) {
    laneTailNarrow(i) := Mux1H(destEew.oneHot(2, 0), Seq(2,4,8).map(bytes => 
            Cat(UIntSplit(tail, NByteLane/bytes)(i+NLanes), UIntSplit(tail, NByteLane/bytes)(i))))
    lanes(i).io.in.data.tail := Mux(uop.ctrl.narrow, laneTailNarrow(i), laneTail(i))
  }

  /** Input mask distribution (same as Tail)
   */
  val v0 = Cat(io.in.bits.vSrc(3).reverse) // vector mask reg is v0
  val expdIdxOH = Seq.tabulate(8)(i => Mux(uop.ctrl.narrow, Cat(false.B, uop.expdIdx >> 1) === i.U, 
                                                            uop.expdIdx === i.U))
  val mask = Wire(UInt(vlenb.W))
  // Effective bits: sew = 8: vlenb,  16: vlenb/2,  32: vlenb/4,  64: vlenb/8
  mask := Mux1H(expdIdxOH, Seq.tabulate(8)(i => 
                        Mux1H(destEew.oneHot, Seq(1,2,4,8).map(k => UIntSplit(v0, vlenb/k)(i)))))
  // Mask for each lane. It occupies the lowest bits of lane mask_input.
  val maskLane = Wire(Vec(NLanes, UInt(NByteLane.W)))
  // Mask rearrange for narrow instruction (same as tail)
  val maskLaneNarrow = Wire(Vec(NLanes, UInt(NByteLane.W)))
  for (i <- 0 until NLanes) {
    maskLane(i) := Mux1H(destEew.oneHot, Seq(1,2,4,8).map(k => UIntSplit(mask, NByteLane/k)(i)))
    maskLaneNarrow(i) := Mux1H(destEew.oneHot(2, 0), Seq(2,4,8).map(k => 
            Cat(UIntSplit(mask, NByteLane/k)(i+NLanes), UIntSplit(mask, NByteLane/k)(i))))
    lanes(i).io.in.data.mask := Mux(uop.ctrl.narrow, maskLaneNarrow(i), maskLane(i))
  }

  /**
    *  Input vs1/vs2 rearrangement
    */
  // Integer extension instruction
  val ext = uop.ctrl.funct6 === "b010010".U && uop.ctrl.funct3 === "b010".U
  for (i <- 0 until NLanes) {
    // vs2
    val laneVs2f2 = Wire(Vec(2, UInt(32.W)))  // widen & ext=2
    for (k <- 0 until 2) {
      laneVs2f2(k) := io.in.bits.vSrc(1)(i/2 + k*NLanes/2)(32*(i%2)+31, 32*(i%2))
    }
    val laneVs2f4 = Wire(Vec(4, UInt(16.W)))  // ext=4
    for (k <- 0 until 4) {
      if (NLanes < 4) {
        laneVs2f4(k) := io.in.bits.vSrc(1)(k/2)(16*(2*(k%2)+i)+15, 16*(2*(k%2)+i))
      } else {
        laneVs2f4(k) := io.in.bits.vSrc(1)(i/4 + k*NLanes/4)(16*(i%4)+15, 16*(i%4))
      }
    }
    val laneVs2f8 = Wire(Vec(8, UInt(8.W)))  // ext=8
    for (k <- 0 until 8) {
      if (NLanes == 2) {
        laneVs2f8(k) := io.in.bits.vSrc(1)(k/4)(8*(2*(k%4)+i)+7, 8*(2*(k%4)+i))
      } else if (NLanes == 4) {
        laneVs2f8(k) := io.in.bits.vSrc(1)(k/2)(8*(4*(k%2)+i)+7, 8*(4*(k%2)+i))
      } else {
        laneVs2f8(k) := io.in.bits.vSrc(1)(i/8 + k*NLanes/8)(8*(i%8)+7, 8*(i%8))
      }
    }

    lanes(i).io.in.data.vs2 := Mux(uop.ctrl.widen || ext && uop.ctrl.lsrc(0)(2,1) === 3.U, Cat(laneVs2f2.reverse),
                               Mux(ext && uop.ctrl.lsrc(0)(2,1) === 2.U, Cat(laneVs2f4.reverse),
                               Mux(ext && uop.ctrl.lsrc(0)(2,1) === 1.U, Cat(laneVs2f8.reverse),
                                   io.in.bits.vSrc(1)(i))))
    // vs1
    val laneVs1H = Wire(UInt(32.W))
    val laneVs1L = Wire(UInt(32.W))
    when (uop.ctrl.widen || uop.ctrl.widen2 || uop.ctrl.narrow) {
      laneVs1L := io.in.bits.vSrc(0)(i/2)(32*(i%2)+31, 32*(i%2))
      laneVs1H := io.in.bits.vSrc(0)(i/2 + NLanes/2)(32*(i%2)+31, 32*(i%2))
    }.otherwise {
      laneVs1H := io.in.bits.vSrc(0)(i)(63, 32)
      laneVs1L := io.in.bits.vSrc(0)(i)(31, 0)
    }
    lanes(i).io.in.data.vs1 := Cat(laneVs1H, laneVs1L)
  }

  /** Input old_vd rearrangement
   */
  // -------- For narrow instrution ------ 
  // Lane index:           3       2       1       0 (sew = destEew = 16)
  // original old_vd:   FEDC     BA98   7654    3210
  // rearranged old_vd: FE76     DC54   BA32    9810
  //
  for (i <- 0 until NLanes) {
    val laneOldVdNarrow = Wire(Vec(2, UInt(32.W)))  // narrow: 76 54 32 10 --> 73 62 51 40
    for (k <- 0 until 2) {
      laneOldVdNarrow(k) := io.in.bits.vSrc(2)(i/2 + k*NLanes/2)(32*(i%2)+31, 32*(i%2))
    }
    lanes(i).io.in.data.old_vd := Mux(uop.ctrl.narrow_to_1, maskLane(i), 
             Mux(uop.ctrl.narrow, laneOldVdNarrow.asUInt, io.in.bits.vSrc(2)(i)))
  }
  
  //---------- Output --------------
  io.out.valid := lanes(0).io.out.valid
  io.out.bits.uop := lanes(0).io.out.bits.uop
  io.out.bits.fflags := lanes(0).io.out.bits.fflags
  io.out.bits.vxsat := lanes(0).io.out.bits.vxsat

  /**
    *  Output vd rearrangement
    */
  // common ouptut
  val vd = Wire(Vec(NLanes, UInt(64.W)))
  for (i <- 0 until NLanes) {
    when (io.out.bits.uop.ctrl.narrow) {
      if (i < NLanes/2) {
        vd(i) := Cat(lanes(2*i+1).io.out.bits.vd(31, 0), lanes(2*i).io.out.bits.vd(31, 0))
      } else {
        vd(i) := Cat(lanes(2*i+1-NLanes).io.out.bits.vd(63, 32), lanes(2*i-NLanes).io.out.bits.vd(63, 32))
      }
    }.otherwise {
      vd(i) := lanes(i).io.out.bits.vd
    }
  }
  // compare output (narrow-to-1)
  def cmpOutRearrange(sew: Int):UInt = {
    val result = Wire(Vec(VLEN, Bool()))
    for (i <- 0 until VLEN) {
      if (i >= VLEN/(sew/8)) {result(i) := true.B}
      else {
        val laneIdx = (i % (256/sew)) / (64/sew)
        val lmulIdx = i / (256/sew)
        val offset = (i % (256/sew)) - laneIdx * (64/sew)
        result(i) := lanes(laneIdx).io.out.bits.vd(lmulIdx*8 + offset)
      }
    }
    Cat(result.reverse)
  }
  val vdCmp = Mux1H(Seq(
    (io.out.bits.uop.info.vsew === 0.U) -> cmpOutRearrange(8), 
    (io.out.bits.uop.info.vsew === 1.U) -> cmpOutRearrange(16), 
    (io.out.bits.uop.info.vsew === 2.U) -> cmpOutRearrange(32), 
    (io.out.bits.uop.info.vsew === 3.U) -> cmpOutRearrange(64), 
  ))
  // Final output vd
  for (i <- 0 until NLanes) {
    io.out.bits.vd(i) := Mux(io.out.bits.uop.ctrl.narrow_to_1, (UIntSplit(vdCmp))(i), vd(i))
  }
}