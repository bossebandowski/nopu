package cnnacc

import chisel3._
import chisel3.util._

import ocp._
import patmos.Constants._

class LayerConv() extends Layer {
    // states CONV layer
    val conv_idle :: conv_done :: conv_init :: conv_load_filter :: conv_apply_filter :: conv_write_output :: conv_load_bias :: conv_add_bias :: conv_apply_relu :: conv_load_input :: conv_addr_set :: conv_out_address_set :: conv_load_output :: conv_load_m :: conv_requantize :: Nil = Enum(15)

    val ms = Reg(Vec(32, UInt(32.W)))

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

            when (io.even) {
                out_addr := layer_offset
                in_addr := ((io.shape_in(31, 24).asSInt + 1.S) * io.shape_in(15, 8)).asUInt
            }
            .otherwise {
                out_addr := 0.U
                in_addr := (io.shape_in(31, 24).asSInt + (w + 1.S) * io.shape_in(15, 8)).asUInt
            }
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
                filter3x3(0) := io.sram_rd_buffer(0)(31, 24).asSInt
                filter3x3(1) := io.sram_rd_buffer(0)(23, 16).asSInt
                filter3x3(2) := io.sram_rd_buffer(0)(15, 8).asSInt
                filter3x3(3) := io.sram_rd_buffer(1)(31, 24).asSInt
                filter3x3(4) := io.sram_rd_buffer(1)(23, 16).asSInt
                filter3x3(5) := io.sram_rd_buffer(1)(15, 8).asSInt
                filter3x3(6) := io.sram_rd_buffer(2)(31, 24).asSInt
                filter3x3(7) := io.sram_rd_buffer(2)(23, 16).asSInt
                filter3x3(8) := io.sram_rd_buffer(2)(15, 8).asSInt
                state := conv_addr_set
            }
        }
        is(conv_addr_set) {
            io.bram_rd_req := true.B
            io.bram_rd_addr := (in_addr.asSInt + dx * input_depth.asSInt + dy * w * input_depth.asSInt).asUInt
            state := conv_load_input
        }
        is(conv_load_input) {
            in_map(((dx + 1.S) + (dy + 1.S) * filter_size).asUInt) := io.bram_rd_data

            when(dx === 1.S && dy === 1.S) {
                state := conv_load_output
                io.bram_rd_req := true.B
                io.bram_rd_addr := out_addr
                dx := -1.S
                dy := -1.S
            }
            .elsewhen (dx === 1.S && dy < 1.S) {
                dx := -1.S
                dy := dy + 1.S
                state := conv_addr_set
            }
            .otherwise {
                dx := dx + 1.S
                state := conv_addr_set
            }
        }
        is(conv_load_output) {
            outs(0) := io.bram_rd_data
            state := conv_apply_filter
        }
        is(conv_apply_filter) {
            outs(0) := outs(0) + filter3x3(((dx + 1.S) + (dy + 1.S) * filter_size).asUInt) * in_map(((dx + 1.S) + (dy + 1.S) * filter_size).asUInt)

            when(dx === 1.S && dy === 1.S) {
                dx := -1.S
                dy := -1.S
                when(z < input_depth - 1.U) {
                    state := conv_write_output
                }
                .otherwise {
                    state := conv_add_bias
                }
            }
            .otherwise {
                when (dx === 1.S && dy < 1.S) {
                    dx := -1.S
                    dy := dy + 1.S
                }
                .otherwise {
                    dx := dx + 1.S
                }
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
                    when (even) {
                        in_addr := ((w + 1.S) * input_depth.asSInt + z.asSInt + 1.S).asUInt
                        out_addr := layer_offset + count_a 
                    }
                    .otherwise {
                        in_addr := (layer_offset.asSInt + (w + 1.S) * input_depth.asSInt + z.asSInt + 1.S).asUInt
                        out_addr := count_a
                    }

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
                        when (even) {
                            in_addr := ((w + 1.S) * input_depth.asSInt).asUInt
                            out_addr := layer_offset + count_a + 1.U
                        }
                        .otherwise {
                            in_addr := (layer_offset.asSInt + (w + 1.S) * input_depth.asSInt).asUInt
                            out_addr := count_a + 1.U
                        }
                        
                        // state transition
                        state := conv_load_bias
                    }
                    .otherwise {
                        // when done with all filters
                        state := conv_done                  // done with layer
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
                when (even) {
                    in_addr := ((y + 1.S) * w * input_depth.asSInt + input_depth.asSInt + z.asSInt).asUInt
                }
                .otherwise {
                    in_addr := (layer_offset.asSInt + (y + 1.S) * w * input_depth.asSInt + input_depth.asSInt + z.asSInt).asUInt
                }
                // state transition
                state := conv_out_address_set
            }
            // standard case: done with an input map
            .otherwise {
                // increment x
                x := x + 1.S

                // set center of next in map
                when (even) {
                    in_addr := (y * w * input_depth.asSInt + (x + 1.S) * input_depth.asSInt + z.asSInt).asUInt
                }
                .otherwise {
                    in_addr := (layer_offset.asSInt + y * w * input_depth.asSInt + (x + 1.S) * input_depth.asSInt + z.asSInt).asUInt
                }
                // state transition
                state := conv_out_address_set
            }
        }
        is(conv_out_address_set) {
            when (even) {
                out_addr := ((y - 1.S) * output_depth.asSInt * (w - filter_size + 1.S) + (x - 1.S) * output_depth.asSInt + count_a.asSInt + layer_offset.asSInt).asUInt
            }
            .otherwise {
                out_addr := ((y - 1.S) * output_depth.asSInt * (w - filter_size + 1.S) + (x - 1.S) * output_depth.asSInt + count_a.asSInt).asUInt
            }
            state := conv_addr_set
        }
        is(conv_done) {
            when (io.ack) {
                state := conv_idle
            }
        }
    }
}