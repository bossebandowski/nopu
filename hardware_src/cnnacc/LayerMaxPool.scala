package cnnacc

import chisel3._
import chisel3.util._

import ocp._
import patmos.Constants._
import cnnacc.Config._

class LayerMaxPool() extends Layer {

    // registers
    val filter3x3 = Reg(Vec(9, SInt(DATA_WIDTH.W)))
    val in_map = Reg(Vec(9, SInt(DATA_WIDTH.W)))

    val x = RegInit(1.S(DATA_WIDTH.W))
    val y = RegInit(1.S(DATA_WIDTH.W))
    val z = RegInit(1.U(DATA_WIDTH.W))
    val dx = RegInit(-1.S(8.W))
    val dy = RegInit(-1.S(8.W))
    val w = RegInit(0.S(8.W))
    val filter_size = RegInit(0.S(8.W))
    val input_depth = RegInit(0.U(8.W))
    val output_depth = RegInit(0.U(8.W))
    val stride_length = RegInit(0.S(8.W))
    val cur_max = RegInit(ABS_MIN.S(DATA_WIDTH.W))
    val addr_reg = RegInit(0.U(DATA_WIDTH.W))
    val timing_aux_regs = Reg(Vec(10, UInt(DATA_WIDTH.W)))

    // aux wires
    val in_offset = Wire(UInt())
    val out_offset = Wire(UInt())

    // wires for calculating read and write addresses ahead of time
    val x_inc = Wire(SInt())
    val y_inc = Wire(SInt())
    val dx_inc = Wire(SInt())
    val dy_inc = Wire(SInt())
    val wr_addr = Wire(UInt())
    val rd_addr_inc_mask = Wire(UInt())
    val rd_addr_inc_conv = Wire(UInt())

    // state wires
    val done_with_mask = Wire(Bool())
    val done_with_conv = Wire(Bool())
    val done_with_layer = Wire(Bool())
    val done_with_row = Wire(Bool())
    val done_with_dxs = Wire(Bool())

    done_with_mask := dx === filter_size - 1.S && dy === filter_size - 1.S
    done_with_conv := x === output_depth.asSInt && y === output_depth.asSInt
    done_with_layer := count_a === input_depth - 1.U
    done_with_row := x === output_depth.asSInt && y < output_depth.asSInt
    done_with_dxs := dx === filter_size - 1.S && dy < filter_size - 1.S


    // input and output locations in BRAM depend on whether the layer has an even index or not
    in_offset := ~even * layer_offset
    out_offset := even * layer_offset

    // calculate next dx and dy
    when (done_with_mask) {
        dx_inc := 0.S
        dy_inc := 0.S    
        rd_addr_inc_mask := timing_aux_regs(5) * timing_aux_regs(4) + timing_aux_regs(6) * input_depth + timing_aux_regs(1)
    }
    .elsewhen (done_with_dxs) {
        dx_inc := 0.S
        dy_inc := dy + 1.S
        rd_addr_inc_mask := timing_aux_regs(7) + dy.asUInt * timing_aux_regs(4) + timing_aux_regs(4) + timing_aux_regs(9) + timing_aux_regs(1)
    }
    .otherwise {
        dx_inc := dx + 1.S
        dy_inc := dy
        rd_addr_inc_mask := timing_aux_regs(5) * timing_aux_regs(4) + dy.asUInt * timing_aux_regs(4) + (timing_aux_regs(6) + dx.asUInt) * input_depth + input_depth + timing_aux_regs(1)
    }

    // calculate next x and y
    when (done_with_conv) {
        x_inc := 0.S
        y_inc := 0.S
        rd_addr_inc_conv := timing_aux_regs(1)
    }
    .elsewhen(done_with_row) {
        x_inc := 0.S
        y_inc := y + 1.S
        rd_addr_inc_conv := ((stride_length * y_inc) * timing_aux_regs(4).asSInt).asUInt + timing_aux_regs(1)
    }
    .otherwise {
        x_inc := x + 1.S
        y_inc := y
        rd_addr_inc_conv := timing_aux_regs(5) * timing_aux_regs(4) + x.asUInt * timing_aux_regs(8) + timing_aux_regs(8) + timing_aux_regs(1)
    }

    // a few timing aux regs to prevent timing issues during bram address setting
    timing_aux_regs(0) := count_a + out_offset
    timing_aux_regs(1) := count_a + in_offset
    timing_aux_regs(2) := y.asUInt * input_depth * output_depth
    timing_aux_regs(3) := x.asUInt * input_depth
    timing_aux_regs(4) := input_depth * w.asUInt
    timing_aux_regs(5) := (stride_length * y).asUInt
    timing_aux_regs(6) := (stride_length * x).asUInt
    timing_aux_regs(7) := timing_aux_regs(5) * timing_aux_regs(4) 
    timing_aux_regs(8) := stride_length.asUInt * input_depth
    timing_aux_regs(9) := timing_aux_regs(6) * input_depth 



    // standard write and read addresses
    wr_addr := timing_aux_regs(0) + timing_aux_regs(2) + timing_aux_regs(3)
    // read address of next cycle

    /* ================================================= CMD HANDLING ============================================ */

    when (io.run && state === pool_idle) {
        state := pool_init
    }

    /* ================================================ STATE MACHINE ============================================ */ 

    switch(state) {
        is(pool_idle) {
            /*
            do nothing
            */
        }
        is(pool_init) {
            /*
            get config from patmos, reset counts, and set read address
            */
            w := io.shape_in(31, 24).asSInt
            filter_size := io.shape_in(23, 20).asSInt
            stride_length := io.shape_in(19, 16).asSInt
            output_depth := io.shape_in(15, 8)
            input_depth := io.shape_in(7, 0)
            even := io.even

            x := 0.S
            y := 0.S
            dx := 0.S
            dy := 0.S
            cur_max := ABS_MIN.S
            count_a := 0.U

            addr_reg := ~io.even * layer_offset
            state := pool_rd_addr_set
        }
        is(pool_rd_addr_set) {
            /*
            set bram address for read in next cycle
            */
            io.bram_rd_req := true.B
            io.bram_rd_addr := addr_reg
            state := pool_find_max
        }
        is(pool_find_max) {
            /*
            find the maximum value in a given 2x2 region (or other filter sizes).
            Iterate over all inputs and update the maximum if a larger value appears on the BRAM data line
            */
            when(io.bram_rd_data > cur_max) {
                cur_max := io.bram_rd_data
            }

            dx := dx_inc
            dy := dy_inc

            when (done_with_mask) {
                addr_reg := wr_addr
                state := pool_write_output
            }
            .otherwise {
                addr_reg := rd_addr_inc_mask
                state := pool_rd_addr_set
            }
        }
        is(pool_write_output) {
            /*
            write maximum value int bram.
            transitions:
                when done with the layer, transition to "done" state
                when done with a convolution, repeat the loop for the next input convolution
                when done with an xy pair, move on to the next coordinates in the current convolution
            */
            io.bram_wr_req := true.B
            io.bram_wr_data := cur_max
            io.bram_wr_addr := addr_reg
            cur_max := ABS_MIN.S

            x := x_inc
            y := y_inc

            when (done_with_conv) {
                when (done_with_layer) {
                    state := pool_done
                    count_a := 0.U
                }
                .otherwise {
                    state := pool_done
                    count_a := count_a + 1.U
                    addr_reg := count_a.asUInt + in_offset + 1.U
                    state := pool_rd_addr_set
                }
            }
            .otherwise {
                addr_reg := rd_addr_inc_conv
                state := pool_rd_addr_set
            }
        }
        is(pool_done) {
            /*
            done. wait for ack from patmos return to idle state
            */
            when (io.ack) {
                state := pool_idle
            }
        }
    }
}