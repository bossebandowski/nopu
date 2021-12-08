package cnnacc

import chisel3._
import chisel3.util._

import ocp._
import patmos.Constants._

class LayerMaxPool() extends Layer {
    // states POOL layer
    val pool_idle :: pool_done :: pool_init :: pool_in_addr_set :: pool_find_max :: pool_write_output :: Nil = Enum(6)

    val filter3x3 = Reg(Vec(9, SInt(DATA_WIDTH.W)))
    val in_map = Reg(Vec(9, SInt(DATA_WIDTH.W)))

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
    val cur_max = RegInit(abs_min.S(DATA_WIDTH.W))
    val abs_min = -2147483646


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
            cur_max := abs_min.S
            count_a := 0.U

            state := pool_in_addr_set

        }
        is(pool_in_addr_set) {
            io.bram_rd_req := true.B

            when (even) {
                io.bram_rd_addr := ((stride_length * y + dy) * input_depth.asSInt * w + (stride_length * x + dx) * input_depth.asSInt + count_a.asSInt).asUInt
            }
            .otherwise {
                io.bram_rd_addr := (layer_offset.asSInt + (stride_length * y + dy) * input_depth.asSInt * w + (stride_length * x + dx) * input_depth.asSInt + count_a.asSInt).asUInt
            }
                
            state := pool_find_max
        }
        is(pool_find_max) {
            when(io.bram_rd_data > cur_max) {
                cur_max := io.bram_rd_data
            }

            when (dx === filter_size - 1.S && dy === filter_size - 1.S) {
                dx := 0.S
                dy := 0.S                
                state := pool_write_output
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
        is(pool_write_output) {

            io.bram_wr_req := true.B
            io.bram_wr_data := cur_max
            cur_max := abs_min.S

            when (even) {
                io.bram_wr_addr := (y * input_depth.asSInt * output_depth.asSInt + x * input_depth.asSInt + count_a.asSInt + layer_offset.asSInt).asUInt
            }
            .otherwise {
                io.bram_wr_addr := (y * input_depth.asSInt * output_depth.asSInt + x * input_depth.asSInt + count_a.asSInt).asUInt
            }

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