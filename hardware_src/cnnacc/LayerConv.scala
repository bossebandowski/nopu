package cnnacc

import chisel3._
import chisel3.util._

import ocp._
import patmos.Constants._
import cnnacc.Config._

class LayerConv() extends Layer {

    val ms = Reg(Vec(MAX_CONVOLUTIONS, UInt(32.W)))

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
    val bram_addr_reg = RegInit(0.U(DATA_WIDTH.W))

    val in_offset = Wire(UInt())
    val out_offset = Wire(UInt())
    val std_rd_addr = Wire(UInt())
    val dx_inc = Wire(SInt())
    val dy_inc = Wire(SInt())
    val std_rd_addr_inc = Wire(UInt())

    // calculate next dx and next dy in advance to set bram address early and avoid long combinational logic chains
    when (dx === 1.S) {
        dx_inc := -1.S
    }
    .otherwise {
        dx_inc := dx + 1.S
    }

    when (dy < 1.S && dx === 1.S) {
        dy_inc := dy + 1.S
    }
    .otherwise {
        dy_inc := dy
    }

    in_offset := ~even * layer_offset
    out_offset := even * layer_offset
    std_rd_addr := (in_addr.asSInt + dx * input_depth.asSInt + dy * w * input_depth.asSInt).asUInt
    std_rd_addr_inc := (in_addr.asSInt + dx_inc * input_depth.asSInt + dy_inc * w * input_depth.asSInt).asUInt

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

            count_a := 0.U
            count_b := 0.U
            count_c := 0.U

            x := 1.S
            y := 1.S
            z := 0.U
            dx := -1.S
            dy := -1.S

            state := conv_load_m

            in_addr := ((io.shape_in(31, 24).asSInt + 1.S) * io.shape_in(15, 8)).asUInt + ~io.even * layer_offset
            out_addr := io.even * layer_offset
        }
        is(conv_load_m) {
            when (io.sram_idle) {
                io.sram_rd_req := true.B
                io.sram_addr := sram_addr_reg
            }
            .elsewhen (io.sram_done) {
                io.sram_free_up := true.B
                ms(count_a + 0.U) := io.sram_rd_buffer(0)
                ms(count_a + 1.U) := io.sram_rd_buffer(1)
                ms(count_a + 2.U) := io.sram_rd_buffer(2) 
                ms(count_a + 3.U) := io.sram_rd_buffer(3) 

                when (count_a < output_depth - 4.U) {
                    sram_addr_reg := sram_addr_reg + 16.U
                    count_a := count_a + 4.U
                }
                .otherwise {
                    count_a := 0.U
                    state := conv_load_bias
                }
            }
        }
        is(conv_load_bias) {
            when (io.sram_idle) {
                io.sram_rd_req := true.B
                io.sram_addr := bias_addr
            }
            .elsewhen (io.sram_done) {
                io.sram_free_up := true.B
                bs(0) := io.sram_rd_buffer(0).asSInt
                state := conv_load_filter
            }
        }
        is(conv_load_filter) {
            when (io.sram_idle) {
                io.sram_rd_req := true.B
                io.sram_addr := weight_addr
            }
            .elsewhen (io.sram_done) {
                io.sram_free_up := true.B
                ws(0) := io.sram_rd_buffer(0)(31, 24).asSInt
                ws(1) := io.sram_rd_buffer(0)(23, 16).asSInt
                ws(2) := io.sram_rd_buffer(0)(15, 8).asSInt
                ws(3) := io.sram_rd_buffer(1)(31, 24).asSInt
                ws(4) := io.sram_rd_buffer(1)(23, 16).asSInt
                ws(5) := io.sram_rd_buffer(1)(15, 8).asSInt
                ws(6) := io.sram_rd_buffer(2)(31, 24).asSInt
                ws(7) := io.sram_rd_buffer(2)(23, 16).asSInt
                ws(8) := io.sram_rd_buffer(2)(15, 8).asSInt
                
                io.bram_rd_req := true.B
                io.bram_rd_addr := std_rd_addr
                bram_addr_reg := std_rd_addr_inc
                state := conv_load_input
            }
        }
        is(conv_load_input) {
            in_map(((dx + 1.S) + (dy + 1.S) * filter_size).asUInt) := io.bram_rd_data
            bram_addr_reg := std_rd_addr_inc

            when(dx === 1.S && dy === 1.S) {
                state := conv_apply_filter
                io.bram_rd_req := true.B
                io.bram_rd_addr := out_addr
                dx := -1.S
                dy := -1.S
            }
            .elsewhen (dx === 1.S && dy < 1.S) {
                dx := -1.S
                dy := dy + 1.S
                
                io.bram_rd_req := true.B
                io.bram_rd_addr := (in_addr.asSInt + (-1.S) * input_depth.asSInt + (dy + 1.S) * w * input_depth.asSInt).asUInt
            }
            .otherwise {
                dx := dx + 1.S
                io.bram_rd_req := true.B
                io.bram_rd_addr := (in_addr.asSInt + (dx + 1.S) * input_depth.asSInt + dy * w * input_depth.asSInt).asUInt
            }
        }
        is(conv_apply_filter) {
            outs(0) := io.bram_rd_data
            outs(1) := ws(0) * in_map(0)
            outs(2) := ws(1) * in_map(1)
            outs(3) := ws(2) * in_map(2)
            outs(4) := ws(3) * in_map(3)
            outs(5) := ws(4) * in_map(4)
            outs(6) := ws(5) * in_map(5)
            outs(7) := ws(6) * in_map(6)
            outs(8) := ws(7) * in_map(7)
            outs(9) := ws(8) * in_map(8)
            state := conv_sum_output
        }
        is(conv_sum_output) {
            outs(0) := outs(0) + outs(1) + outs(2) + outs(3) + outs(4) + outs(5) + outs(6) + outs(7) + outs(8) + outs(9)

            when(z < input_depth - 1.U) {
                state := conv_write_output
            }
            .otherwise {
                state := conv_add_bias
            }
        }
        is(conv_add_bias) {
            outs(0) := bs(0) + outs(0)
            state := conv_requantize
        }
        is(conv_requantize) {
            tmp64(0) := (outs(0) * ms(count_a).asSInt) >> 32.U
            state := conv_apply_relu
        }
        is(conv_apply_relu) {
            when (tmp64(0)(31, 0).asSInt < 0.S) {
                outs(0) := 0.S
            }
            .elsewhen (tmp64(0)(31, 0).asSInt > 255.S) {
                outs(0) := 255.S
            }
            .otherwise {
                outs(0) := tmp64(0)(31, 0).asSInt
            }
            state := conv_write_output
        }
        is(conv_write_output) {
            io.bram_wr_req := true.B
            io.bram_wr_addr := out_addr
            io.bram_wr_data := outs(0)

            // transitions
            when (x === w - filter_size + 1.S && y === w - filter_size + 1.S) {
                // reset x and y
                x := 1.S
                y := 1.S
                weight_addr := weight_addr + (filter_size * 4.S).asUInt
                
                when (z < input_depth - 1.U) {
                    z := z + 1.U         
                    // next input channel, same filter (but different filter layer)
                    in_addr := ((w + 1.S) * input_depth.asSInt + z.asSInt + 1.S).asUInt + in_offset
                    out_addr := count_a + out_offset

                    state := conv_load_filter
                }
                .otherwise {
                    z := 0.U
                 
                    when (count_a < output_depth - 1.U) {
                        // when done with a filter
                        // get ready to load the next filter and corresponding bias
                        bias_addr := bias_addr + 4.U

                        // increment filter count
                        count_a := count_a + 1.U

                        // reset in address
                        in_addr := ((w + 1.S) * input_depth.asSInt).asUInt + in_offset
                        out_addr := count_a + 1.U + out_offset
                        
                        // state transition
                        state := conv_load_bias
                    }
                    .otherwise {
                        // when done with all filters, done with layer
                        state := conv_done
                    }
                }
            }
            // when done with a row
            .elsewhen (x === w - filter_size + 1.S && y < w - filter_size + 1.S) {
                // reset x
                x := 1.S
                // increment y
                y := y + 1.S
                // set center of next in map
                in_addr := ((y + 1.S) * w * input_depth.asSInt + input_depth.asSInt + z.asSInt).asUInt + in_offset

                // state transition
                state := conv_wr_addr_set
            }
            // standard case: done with an input map
            .otherwise {
                // increment x
                x := x + 1.S
                // set center of next in map
                in_addr := (y * w * input_depth.asSInt + (x + 1.S) * input_depth.asSInt + z.asSInt).asUInt + in_offset
                // state transition
                state := conv_wr_addr_set
            }
        }
        is(conv_wr_addr_set) {
            out_addr := ((y - 1.S) * output_depth.asSInt * (w - filter_size + 1.S) + (x - 1.S) * output_depth.asSInt + count_a.asSInt).asUInt + out_offset
            io.bram_rd_req := true.B
            io.bram_rd_addr := std_rd_addr
            bram_addr_reg := std_rd_addr_inc
            state := conv_load_input
        }
        is(conv_done) {
            when (io.ack) {
                state := conv_idle
            }
        }
    }
}