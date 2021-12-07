package cnnacc

import chisel3._
import chisel3.util._

import ocp._
import patmos.Constants._

class LayerConv() extends Layer {
    // states CONV layer
    val conv_idle :: conv_done :: conv_init :: conv_load_filter :: conv_apply_filter :: conv_write_output :: conv_load_bias :: conv_add_bias :: conv_apply_relu :: conv_load_input :: conv_addr_set :: conv_out_address_set :: conv_load_output :: conv_load_m :: conv_requantize :: Nil = Enum(15)

    val maxIn = RegInit(0.U(DATA_WIDTH.W))
    val maxOut = RegInit(0.U(DATA_WIDTH.W))

    val ms = Reg(Vec(32, UInt(32.W)))
    val tmp64 = Reg(Vec(BURST_LENGTH, SInt(64.W)))


    val filter3x3 = Reg(Vec(9, SInt(DATA_WIDTH.W)))
    val in_map = Reg(Vec(9, SInt(DATA_WIDTH.W)))
    val conv_addr = RegInit(0.S(DATA_WIDTH.W))

    val x = RegInit(1.S(DATA_WIDTH.W))
    val y = RegInit(1.S(DATA_WIDTH.W))
    val z = RegInit(1.U(DATA_WIDTH.W))
    val dx = RegInit(-1.S(8.W))
    val dy = RegInit(-1.S(8.W))
    val w = RegInit(28.S(8.W))
    val filter_size = RegInit(0.S(8.W))
    val input_depth = RegInit(0.U(8.W))
    val output_depth = RegInit(0.U(8.W))
    val stride_length = RegInit(0.S(8.W))

    // counts
    val countA = RegInit(0.U(DATA_WIDTH.W))
    val countB = RegInit(0.U(DATA_WIDTH.W))
    val countC = RegInit(0.U(DATA_WIDTH.W))

    state := conv_idle

    /* ================================================= CMD HANDLING ============================================ */

    when (io.run && state === conv_idle) {
        state := conv_init
    }

    /* ================================================ STATE MACHINE ============================================ */ 

    switch(state) {
        is(conv_idle) {
            /*
            do nothing
            */
        }
        is(conv_init) {
            weight_addr := io.weight_addr
            bias_addr := io.bias_addr
            w := io.shape_in(31, 24).asSInt
            filter_size := io.shape_in(23, 16).asSInt
            input_depth := io.shape_in(15, 8)
            output_depth := io.shape_in(7, 0)
            sram_addr_reg := io.m_factor
            even := io.even

            countA := 0.U
            countB := 0.U
            countC := 0.U

            x := 1.S
            y := 1.S
            z := 0.U
            dx := -1.S
            dy := -1.S

            state := conv_load_m

            when (even) {
                out_addr := layer_offset
                in_addr := ((w + 1.S) * input_depth).asUInt
            }
            .otherwise {
                out_addr := 0.U
                in_addr := (layer_offset.asSInt + (w + 1.S) * input_depth).asUInt
            }
        }
        is(conv_load_m) {
            io.sram_addr := weight_addr
            state := conv_done
        }
        is(conv_done) {
            when (io.ack) {
                state := conv_idle
            }
        }

    }
}