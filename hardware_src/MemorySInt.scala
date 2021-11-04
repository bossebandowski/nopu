package util

import chisel3._

class MemorySInt() extends Module {
    /*
    BRAM memory entity
    */
    val size = 11
    val dataWidth = 32

    val io = IO(new Bundle {
        val rdAddr = Input(UInt (size.W))
        val rdData = Output(SInt (dataWidth.W))
        val wrEna = Input(Bool ())
        val wrData = Input(SInt (dataWidth.W))
        val wrAddr = Input(UInt (size.W))
    })

    val mem = SyncReadMem((scala.math.pow(2,size)).toInt, SInt(dataWidth.W))

    io.rdData := mem.read(io.rdAddr)
    when(io.wrEna) {
        mem.write(io.wrAddr , io.wrData)
    }
}