package cnnacc

import chisel3._
import chisel3.util._

import ocp._
import patmos.Constants._
import cnnacc.Config._

class LayerMaxPool() extends Layer {

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

    val in_offset = Wire(UInt())
    val out_offset = Wire(UInt())

    in_offset := ~even * layer_offset
    out_offset := even * layer_offset

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

            state := pool_in_addr_set

        }
        is(pool_in_addr_set) {
            addr_reg := ((stride_length * y + dy) * input_depth.asSInt * w + (stride_length * x + dx) * input_depth.asSInt + count_a.asSInt).asUInt + in_offset
            state := pool_rd_delay
        }
        is(pool_rd_delay) {
            io.bram_rd_req := true.B
            io.bram_rd_addr := addr_reg
            state := pool_find_max
        }
        is(pool_find_max) {
            when(io.bram_rd_data > cur_max) {
                cur_max := io.bram_rd_data
            }

            when (dx === filter_size - 1.S && dy === filter_size - 1.S) {
                dx := 0.S
                dy := 0.S                
                state := pool_wr_delay
            }
            .elsewhen (dx === filter_size - 1.S && dy < filter_size - 1.S) {
                dx := 0.S
                dy := dy + 1.S

                state := pool_in_addr_set
            }
            .otherwise {
                dx := dx + 1.S

                state := pool_in_addr_set
            }
        }
        is(pool_wr_delay) {
            state := pool_write_output
            addr_reg := (y * input_depth.asSInt * output_depth.asSInt + x * input_depth.asSInt + count_a.asSInt).asUInt + out_offset
        }
        is(pool_write_output) {

            io.bram_wr_req := true.B
            io.bram_wr_data := cur_max
            io.bram_wr_addr := addr_reg
            cur_max := ABS_MIN.S

            when (x === output_depth.asSInt && y === output_depth.asSInt) {
                when (count_a < input_depth - 1.U) {
                    x := 0.S
                    y := 0.S
                    count_a := count_a + 1.U
                    state := pool_in_addr_set
                }
                .otherwise {
                    state := pool_done
                }
            }
            .elsewhen(x === output_depth.asSInt && y < output_depth.asSInt) {
                x := 0.S
                y := y + 1.S
                state := pool_in_addr_set
            }
            .otherwise {
                x := x + 1.S
                state := pool_in_addr_set
            }
        }
        is(pool_done) {
            when (io.ack) {
                state := pool_idle
            }
        }
    }
}