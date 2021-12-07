package cnnacc

import chisel3._

class LayerFc() extends Module {
    val io = IO(new Bundle {
        val inx = Input(SInt (32.W))
        val outx = Output(SInt (32.W))
    })
}