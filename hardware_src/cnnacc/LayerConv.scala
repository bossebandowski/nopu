package cnnacc

import chisel3._
import chisel3.util._

import ocp._
import patmos.Constants._
import cnnacc.Config._

class LayerConv() extends Layer {

    // registers
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
    val input_center = RegInit(0.U(DATA_WIDTH.W))
    val input_center_inc_reg = RegInit(0.U(DATA_WIDTH.W))
    val timing_aux_regs = Reg(Vec(10, UInt(DATA_WIDTH.W)))

    // aux wires
    val in_offset = Wire(UInt())
    val out_offset = Wire(UInt())
    val idx = Wire(UInt())

    // wires for calculating read and write addresses ahead of time
    val dx_inc = Wire(SInt())
    val dy_inc = Wire(SInt())
    val x_inc = Wire(SInt())
    val y_inc = Wire(SInt())
    val dx_two_inc = Wire(SInt())
    val dy_two_inc = Wire(SInt())
    val wr_addr_std = Wire(UInt())
    val rd_addr_std = Wire(UInt())
    val rd_addr_inc_ds = Wire(UInt())
    val input_center_inc = Wire(UInt())
    val rd_addr_inc_region = Wire(UInt())
    val rd_addr_inc_z = Wire(UInt())
    val input_center_rst = Wire(UInt())
    val rd_addr_two_inc = Wire(UInt())
    val m_address = Wire(UInt())

    // state wires
    val done_with_mask = Wire(Bool())
    val done_with_input_conv = Wire(Bool())
    val last_conv = Wire(Bool())
    val done_with_row = Wire(Bool())
    val done_with_dxs = Wire(Bool())
    val done_with_layer = Wire(Bool())

    done_with_mask := dx === 1.S && dy === 1.S
    done_with_input_conv := x === w - filter_size + 1.S && y === w - filter_size + 1.S
    last_conv := z === input_depth - 1.U
    done_with_row := x === w - filter_size + 1.S && y < w - filter_size + 1.S
    done_with_dxs := dx === 1.S && dy < 1.S
    done_with_layer := count_a === output_depth - 1.U

    // calculate dx and dy one cycle ahead
    when(done_with_mask) {
        dx_inc := -1.S
        dy_inc := -1.S
    }
    .elsewhen (done_with_dxs) {
        dx_inc := -1.S
        dy_inc := dy + 1.S
    }
    .otherwise {
        dx_inc := dx + 1.S
        dy_inc := dy
    }

    // calculate dx and dy two cycles ahead
    when (dx === -1.S) {
        dx_two_inc := 1.S
    }
    .elsewhen (dx === 0.S) {
        dx_two_inc := -1.S
    }
    .otherwise {
        dx_two_inc := 0.S
    }

    when (dy < 1.S && dx >= 0.S) {
        dy_two_inc := dy + 1.S
    }
    .otherwise {
        dy_two_inc := dy
    }

    // calculate x and y one cycle ahead
    when (done_with_input_conv) {
        x_inc := 1.S
        y_inc := 1.S
        input_center_inc := timing_aux_regs(1) + input_depth + timing_aux_regs(3)
    }
    .elsewhen (done_with_row) {
        x_inc := 1.S
        y_inc := y + 1.S
        input_center_inc := timing_aux_regs(5) + timing_aux_regs(1) + timing_aux_regs(4)
    }
    .otherwise {
        x_inc := x + 1.S
        y_inc := y
        input_center_inc := timing_aux_regs(5) + x.asUInt * input_depth + timing_aux_regs(4)
    }

    // calculate in address (center of input map) one cycle ahead
    input_center_inc_reg := input_center_inc

    // input and output locations in BRAM depend on whether the layer has an even index or not
    in_offset := ~even * layer_offset
    out_offset := even * layer_offset

    // a few timing aux regs to prevent timing issues during bram address setting
    timing_aux_regs(0) := (output_depth.asSInt * (w - filter_size + 1.S)).asUInt
    timing_aux_regs(1) := w.asUInt * input_depth
    timing_aux_regs(2) := count_a + out_offset
    timing_aux_regs(3) := z + in_offset
    timing_aux_regs(4) := timing_aux_regs(3) + input_depth
    timing_aux_regs(5) := y.asUInt * timing_aux_regs(1)

    // write address
    wr_addr_std := ((y - 1.S) * timing_aux_regs(0).asUInt + (x - 1.S) * output_depth.asSInt).asUInt + timing_aux_regs(2)
    // read address
    rd_addr_std := (input_center.asSInt + dx * input_depth.asSInt + dy * timing_aux_regs(1).asSInt).asUInt
    // read address next cycle within region
    rd_addr_inc_ds := (input_center.asSInt + dx_inc * input_depth.asSInt + dy_inc * timing_aux_regs(1).asSInt).asUInt
    // read address next cycle in new region
    rd_addr_inc_region := (input_center_inc_reg.asSInt - input_depth.asSInt - timing_aux_regs(1).asSInt).asUInt
    // read address next cycle in new input convolution
    rd_addr_inc_z := ((w + 1.S) * input_depth.asSInt + z.asSInt + 1.S).asUInt + in_offset
    // read address after reset
    input_center_rst := ((w + 1.S) * input_depth.asSInt).asUInt + in_offset
    // read address in two cycles
    rd_addr_two_inc := (input_center.asSInt + dx_two_inc * input_depth.asSInt + dy_two_inc * timing_aux_regs(1).asSInt).asUInt
    // address of requantization factor m
    m_address := ((io.shape_in(31, 24).asSInt + 1.S) * io.shape_in(15, 8)).asUInt + ~io.even * layer_offset

    // index wire for input map
    idx := ((dx + 1.S) + (dy + 1.S) * filter_size).asUInt

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
            /*
            get input parameters from patmos, reset counts, set in and out addresses
            */
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

            input_center := m_address
            out_addr := io.even * layer_offset
        }
        is(conv_load_m) {
            /*
            load the requantization parameter m. Every filter has its own arrays of input ms with the length of the input dimension.
            We can't load the entire array in one sram burst, so this state will be repeated a few times
            */
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
            /*
            load the bias for the current filter from sram
            */
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
            /*
            load the filter, i.e. the weights, from sram.
            Set input address for input load in next state
            */
            bram_addr_reg := rd_addr_std

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
                io.bram_rd_addr := bram_addr_reg
                bram_addr_reg := rd_addr_inc_ds
                state := conv_load_input
            }
        }
        is(conv_load_input) {
            /*
            the in_map matches a single 2d layer of the filter in dimension (standard: 3x3).
            We will hence load 9 values from bram into the corresponding reg vec.
            x and y denote the center of the region, and dx and dy the difference from the center. 
            */
            in_map(idx) := io.bram_rd_data
            bram_addr_reg := rd_addr_two_inc

            dx := dx_inc
            dy := dy_inc

            when(done_with_mask) {
                state := conv_apply_filter
                io.bram_rd_req := true.B
                io.bram_rd_addr := out_addr
            }
            .otherwise {
                io.bram_rd_req := true.B
                io.bram_rd_addr := bram_addr_reg
            }
        }
        is(conv_apply_filter) {
            /*
            load output (this is an accumulate operation) and multiply the weights with the input activations
            */
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
            /*
            sum up the previously calculated products. When doing the last convolution of a filter, transition
            to the add bias state sequence
            */
            outs(0) := outs(0) + outs(1) + outs(2) + outs(3) + outs(4) + outs(5) + outs(6) + outs(7) + outs(8) + outs(9)

            when (last_conv) {
                state := conv_add_bias
            }
            .otherwise {
                state := conv_write_output
            }
        }
        is(conv_add_bias) {
            /*
            just adds bias
            */
            outs(0) := bs(0) + outs(0)
            state := conv_requantize
        }
        is(conv_requantize) {
            /*
            requantize the output activation to avoid overflows.
            Multiply the output with the correct m factor and perform a 32 bit shift.
            This approximates a division
            */
            tmp64(0) := (outs(0) * ms(count_a).asSInt) >> 32.U
            state := conv_apply_relu
        }
        is(conv_apply_relu) {
            /*
            cap the output activation to 8-bit unsigned int range
            */
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
            /*
            write the output into bram. Also handle most transitions. Refer to state machine and report for details
            */
            io.bram_wr_req := true.B
            io.bram_wr_addr := out_addr
            io.bram_wr_data := outs(0)

            x := x_inc
            y := y_inc

            when (done_with_input_conv) {
                weight_addr := weight_addr + (filter_size * 4.S).asUInt         // increment weight address
                
                when (last_conv) {
                    z := 0.U                                                    // reset z, we are done with filter

                    when (done_with_layer) {                                    // finish when done with all filters
                        state := conv_done
                    }
                    .otherwise {
                        bias_addr := bias_addr + 4.U                            // otherwise increment bias address
                        count_a := count_a + 1.U                                // increment filter count
                        input_center := input_center_rst                        // reset read address
                        out_addr := count_a + 1.U + out_offset                  // set out address
                        state := conv_load_bias
                    }
                }
                .otherwise {                                                    // not done with filter, just get ready for next convolution
                    z := z + 1.U                                                // increment convolution id
                    input_center := rd_addr_inc_z                               // set read address
                    out_addr := count_a + out_offset                            // set write address
                    state := conv_load_filter                                   // load next filter layer
                }                    
            }
            .otherwise {                                                        // just continue with current 2d slice of input
                input_center := input_center_inc                                // set read address
                bram_addr_reg := rd_addr_inc_region                             // prepare read address for next cycle
                state := conv_wr_addr_set
            }
        }
        is(conv_wr_addr_set) {
            /*
            set write address, set read address for next inputs, and prepare read address for next cycle
            */
            out_addr := wr_addr_std
            io.bram_rd_req := true.B
            io.bram_rd_addr := bram_addr_reg
            bram_addr_reg := rd_addr_inc_ds
            state := conv_load_input
        }
        is(conv_done) {
            /*
            done. wait for ack from patmos return to idle state
            */
            when (io.ack) {
                state := conv_idle
            }
        }
    }
}