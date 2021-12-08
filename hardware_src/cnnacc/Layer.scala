package cnnacc

import chisel3._

import ocp._
import patmos.Constants._

class Layer() extends Module {
    val io = IO(new Bundle {
        // BRAM wires
        val bram_rd_addr = Output(UInt (32.W))
        val bram_rd_data = Input(SInt (32.W))
        val bram_wr_data = Output(SInt (32.W))
        val bram_wr_addr = Output(UInt (32.W))
        val bram_wr_req = Output(Bool())
        val bram_rd_req = Output(Bool())

        // SRAM wires
        val sram_state = Input(UInt(8.W))
        val sram_addr = Output(UInt(32.W))
        val sram_wr_buffer = Output(Vec(BURST_LENGTH, UInt(DATA_WIDTH.W)))
        val sram_rd_buffer = Input(Vec(BURST_LENGTH, UInt(DATA_WIDTH.W)))
        val sram_wr_req = Output(Bool())
        val sram_rd_req = Output(Bool())
        val sram_idle = Input(Bool())
        val sram_done = Input(Bool())
        val sram_free_up = Output(Bool())

        // CMD and status wires
        val state = Output(UInt (32.W))
        val run = Input(Bool())
        val ack = Input(Bool())

        // CONFIG wires
        val activation = Input(UInt (32.W))
        val weight_addr = Input(UInt (32.W))
        val bias_addr = Input(UInt (32.W))
        val shape_in = Input(UInt (32.W))
        val shape_out = Input(UInt (32.W))
        val m_factor = Input(UInt (32.W))
        val even = Input(Bool())
    })

    // default outputs
    io.bram_wr_addr := 0.U
    io.bram_wr_data := 0.S
    io.bram_rd_addr := 0.U
    io.bram_wr_req := false.B
    io.bram_rd_req := false.B
    
    io.sram_wr_buffer := Seq(0.U, 0.U, 0.U, 0.U)
    io.sram_wr_req := false.B
    io.sram_rd_req := false.B
    io.sram_addr := 0.U
    io.sram_free_up := false.B

    // registers
    val state = RegInit(0.U(8.W))
    val layer_offset = scala.math.pow(2,14).toInt.U
    val even = RegInit(false.B)
    val mem_w_buffer = Reg(Vec(BURST_LENGTH, UInt(DATA_WIDTH.W)))
    val mem_r_buffer = Reg(Vec(BURST_LENGTH, UInt(DATA_WIDTH.W)))
    val weight_addr = RegInit(0.U(32.W))
    val bias_addr = RegInit(0.U(32.W))
    val sram_addr_reg = RegInit(0.U(32.W))
    val in_addr = RegInit(0.U(32.W))
    val out_addr = RegInit(0.U(32.W))

    val ws = Reg(Vec(4, SInt(8.W)))
    val bs = Reg(Vec(BURST_LENGTH, SInt(DATA_WIDTH.W)))
    val outs = Reg(Vec(4, SInt(DATA_WIDTH.W)))
    val tmp64 = Reg(Vec(4, SInt(64.W)))

    // counts
    val count_a = RegInit(0.U(DATA_WIDTH.W))
    val count_b = RegInit(0.U(DATA_WIDTH.W))
    val count_c = RegInit(0.U(DATA_WIDTH.W))


    io.state := state


}