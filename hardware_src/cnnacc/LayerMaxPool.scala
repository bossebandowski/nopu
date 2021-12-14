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

    // aux wires
    val in_offset = Wire(UInt())
    val out_offset = Wire(UInt())

    // wires for calculating read and write addresses ahead of time
    val x_inc = Wire(SInt())
    val y_inc = Wire(SInt())
    val dx_inc = Wire(SInt())
    val dy_inc = Wire(SInt())
    val wr_addr = Wire(UInt())
    val rd_addr = Wire(UInt())
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
    }
    .elsewhen (done_with_dxs) {
        dx_inc := 0.S
        dy_inc := dy + 1.S
    }
    .otherwise {
        dx_inc := dx + 1.S
        dy_inc := dy
    }

    // calculate next x and y
    when (done_with_conv) {
        x_inc := 0.S
        y_inc := 0.S
    }
    .elsewhen(done_with_row) {
        x_inc := 0.S
        y_inc := y + 1.S
    }
    .otherwise {
        x_inc := x + 1.S
        y_inc := y
    }

    // standard write and read addresses
    wr_addr := (y * input_depth.asSInt * output_depth.asSInt + x * input_depth.asSInt + count_a.asSInt).asUInt + out_offset
    rd_addr := ((stride_length * y + dy) * input_depth.asSInt * w + (stride_length * x + dx) * input_depth.asSInt + count_a.asSInt).asUInt + in_offset
    // read address of next cycle
    rd_addr_inc_mask := ((stride_length * y + dy_inc) * input_depth.asSInt * w + (stride_length * x + dx_inc) * input_depth.asSInt + count_a.asSInt).asUInt + in_offset
    rd_addr_inc_conv := ((stride_length * y_inc) * input_depth.asSInt * w + (stride_length * x_inc) * input_depth.asSInt + count_a.asSInt).asUInt + in_offset

    /* ================================================= CMD HANDLING ============================================ */

    when (io.run && state === pool_idle) {
        state := pool_init
    }

    /* ================================================ STATE MACHINE ============================================ */ 

    switch(state) {
        is(pool_idle) {
            // do nothing
        }
        is(pool_init) {
            // get config
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

            addr_reg := rd_addr
            state := pool_rd_addr_set

        }
        is(pool_rd_addr_set) {
            io.bram_rd_req := true.B
            io.bram_rd_addr := addr_reg
            state := pool_find_max
        }
        is(pool_find_max) {
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
                    addr_reg := count_a.asUInt + in_offset
                    state := pool_rd_addr_set
                }
            }
            .otherwise {
                addr_reg := rd_addr_inc_conv
                state := pool_rd_addr_set
            }
        }
        is(pool_done) {
            when (io.ack) {
                state := pool_idle
            }
        }
    }
}