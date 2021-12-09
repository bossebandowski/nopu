package cnnacc

import chisel3._
import chisel3.util._

import ocp._
import patmos.Constants._
import cnnacc.Config._

class LayerFc() extends Layer {

    val max_in = RegInit(0.U(DATA_WIDTH.W))
    val max_out = RegInit(0.U(DATA_WIDTH.W))
    val doing_bias = RegInit(false.B)
    val M = RegInit(0.U(DATA_WIDTH.W))
    val in_val = RegInit(0.S(DATA_WIDTH.W))
    val activation = RegInit(0.U(DATA_WIDTH.W))

    val in_inc = Wire(UInt())
    val out_inc = Wire(UInt())

    val w_inc = 16.U
    val b_inc = 4.U

    in_inc := count_a + 1.U
    out_inc := count_b + w_inc
    /* ================================================= CMD HANDLING ============================================ */

    when (io.run && state === fc_idle) {
        state := fc_init
    }

    /* ================================================ STATE MACHINE ============================================ */ 

    switch(state) {
        is(fc_idle) {
            // do nothing
        }
        is(fc_init) {
            // get config
            weight_addr := io.weight_addr
            bias_addr := io.bias_addr
            max_in := io.shape_in
            max_out := io.shape_out
            sram_addr_reg := io.m_factor
            activation := io.activation
            even := io.even
            
            count_a := 0.U
            count_b := 0.U
            count_c := 0.U

            doing_bias := false.B
            state := fc_load_m

            when (io.even) {
                out_addr := layer_offset
                in_addr := 0.U
            }
            .otherwise {
                out_addr := 0.U
                in_addr := layer_offset
            }
        }
        is(fc_load_m) {
            when (io.sram_idle) {
                io.sram_rd_req := true.B
                io.sram_addr := sram_addr_reg
            }
            .elsewhen (io.sram_done) {
                io.sram_free_up := true.B
                M := io.sram_rd_buffer(0)
                state := fc_load_input
            }
        }
        is(fc_load_input) {
            in_val := io.bram_rd_data
            state := fc_load_weight
        }
        is(fc_load_weight) {
            when (io.sram_idle) {
                io.sram_rd_req := true.B
                io.sram_addr := weight_addr
            }
            .elsewhen (io.sram_done) {
                io.sram_free_up := true.B

                ws(0) := io.sram_rd_buffer(0)(31, 24).asSInt   
                ws(1) := io.sram_rd_buffer(0)(23, 16).asSInt
                ws(2) := io.sram_rd_buffer(0)(15, 8).asSInt
                ws(3) := io.sram_rd_buffer(0)(7, 0).asSInt
                ws(4) := io.sram_rd_buffer(1)(31, 24).asSInt   
                ws(5) := io.sram_rd_buffer(1)(23, 16).asSInt
                ws(6) := io.sram_rd_buffer(1)(15, 8).asSInt
                ws(7) := io.sram_rd_buffer(1)(7, 0).asSInt
                ws(8) := io.sram_rd_buffer(2)(31, 24).asSInt   
                ws(9) := io.sram_rd_buffer(2)(23, 16).asSInt
                ws(10) := io.sram_rd_buffer(2)(15, 8).asSInt
                ws(11) := io.sram_rd_buffer(2)(7, 0).asSInt
                ws(12) := io.sram_rd_buffer(3)(31, 24).asSInt   
                ws(13) := io.sram_rd_buffer(3)(23, 16).asSInt
                ws(14) := io.sram_rd_buffer(3)(15, 8).asSInt
                ws(15) := io.sram_rd_buffer(3)(7, 0).asSInt

                io.bram_rd_req := true.B
                io.bram_rd_addr := out_addr

                state := fc_load_output
            }
        }
        is(fc_load_output) {
            when (count_c === doing_bias * b_inc + ~doing_bias * w_inc) {
                when (doing_bias) {
                    state := fc_load_bias
                }
                .otherwise {
                    state := fc_mac
                }
                count_c := 0.U
            }
            .otherwise {
                outs(count_c) := io.bram_rd_data
                count_c := count_c + 1.U

                io.bram_rd_req := true.B
                io.bram_rd_addr := out_addr + count_c + 1.U
            }
        }
        is(fc_mac) {
            outs(0) := outs(0) + (in_val * ws(0))
            outs(1) := outs(1) + (in_val * ws(1))
            outs(2) := outs(2) + (in_val * ws(2))
            outs(3) := outs(3) + (in_val * ws(3))
            outs(4) := outs(4) + (in_val * ws(4))
            outs(5) := outs(5) + (in_val * ws(5))
            outs(6) := outs(6) + (in_val * ws(6))
            outs(7) := outs(7) + (in_val * ws(7))
            outs(8) := outs(8) + (in_val * ws(8))
            outs(9) := outs(9) + (in_val * ws(9))
            outs(10) := outs(10) + (in_val * ws(10))
            outs(11) := outs(11) + (in_val * ws(11))
            outs(12) := outs(12) + (in_val * ws(12))
            outs(13) := outs(13) + (in_val * ws(13))
            outs(14) := outs(14) + (in_val * ws(14))
            outs(15) := outs(15) + (in_val * ws(15))

            state := fc_write_output
        }
        is(fc_write_output) {
            when (count_c < w_inc && count_b + count_c < max_out) {
                io.bram_wr_req := true.B
                io.bram_wr_addr := out_addr + count_c
                io.bram_wr_data := outs(count_c)
                count_c := count_c + 1.U
            }
            .otherwise {
                count_c := 0.U

                when (in_inc === max_in && out_inc >= max_out) {
                    count_b := 0.U
                    state := fc_load_output
                    doing_bias := true.B
                    io.bram_rd_req := true.B

                    when (even) {
                        out_addr := layer_offset
                        io.bram_rd_addr := layer_offset
                    }
                    .otherwise {
                        out_addr := 0.U
                        io.bram_rd_addr := 0.U
                    }
                }
                .elsewhen(in_inc < max_in && out_inc >= max_out) {

                    count_a := count_a + 1.U
                    count_b := 0.U
                    state := fc_load_input

                    in_addr := in_addr + 1.U
                    io.bram_rd_req := true.B
                    io.bram_rd_addr := in_addr + 1.U


                    weight_addr := weight_addr + w_inc + max_out - out_inc

                    when (even) {
                        out_addr := layer_offset
                    }
                    .otherwise {
                        out_addr := 0.U
                    }
                }
                .otherwise {
                    count_b := count_b + w_inc
                    out_addr := out_addr + w_inc
                    weight_addr := weight_addr + w_inc
                    state := fc_load_weight
                }
            }
        }
        is(fc_load_bias) {
            when (io.sram_idle) {
                io.sram_rd_req := true.B
                io.sram_addr := bias_addr
            }
            .elsewhen (io.sram_done) {
                io.sram_free_up := true.B
                state := fc_add_bias

                bs(0) := io.sram_rd_buffer(0).asSInt
                bs(1) := io.sram_rd_buffer(1).asSInt
                bs(2) := io.sram_rd_buffer(2).asSInt
                bs(3) := io.sram_rd_buffer(3).asSInt
            }
        }
        is(fc_add_bias) {
            state := activation

            outs(0) := bs(0) + outs(0)
            outs(1) := bs(1) + outs(1)
            outs(2) := bs(2) + outs(2)
            outs(3) := bs(3) + outs(3)

            count_c := 0.U
        }
        is(fc_requantize) {
            state := fc_apply_relu
            tmp64(0) := (outs(0) * M.asSInt) >> 32.U
            tmp64(1) := (outs(1) * M.asSInt) >> 32.U
            tmp64(2) := (outs(2) * M.asSInt) >> 32.U
            tmp64(3) := (outs(3) * M.asSInt) >> 32.U
        }
        is(fc_apply_relu) {
            when (tmp64(0)(31, 0).asSInt < 0.S) {
                outs(0) := 0.S
            }
            .elsewhen(tmp64(0)(31, 0).asSInt > 255.S) {
                outs(0) := 255.S
            }
            .otherwise {
                outs(0) := tmp64(0)(31, 0).asSInt
            }

            when (tmp64(1)(31, 0).asSInt < 0.S) {
                outs(1) := 0.S
            }
            .elsewhen(tmp64(1)(31, 0).asSInt > 255.S) {
                outs(1) := 255.S
            }
            .otherwise {
                outs(1) := tmp64(1)(31, 0).asSInt
            }

            when (tmp64(2)(31, 0).asSInt < 0.S) {
                outs(2) := 0.S
            }
            .elsewhen(tmp64(2)(31, 0).asSInt > 255.S) {
                outs(2) := 255.S
            }
            .otherwise {
                outs(2) := tmp64(2)(31, 0).asSInt
            }

            when (tmp64(3)(31, 0).asSInt < 0.S) {
                outs(3) := 0.S
            }
            .elsewhen(tmp64(3)(31, 0).asSInt > 255.S) {
                outs(3) := 255.S
            }
            .otherwise {
                outs(3) := tmp64(3)(31, 0).asSInt
            }

            state := fc_write_bias
        }
        is(fc_write_bias) {
            when (count_c < BURST_LENGTH.U) {
                io.bram_wr_req := true.B
                io.bram_wr_addr := out_addr + count_c
                io.bram_wr_data := outs(count_c)
                count_c := count_c + 1.U
            }
            .otherwise {
                count_c := 0.U

                when (count_b + b_inc < max_out) {
                    count_b := count_b + b_inc
                    out_addr := out_addr + b_inc

                    io.bram_rd_req := true.B
                    io.bram_rd_addr := out_addr + b_inc 
                    bias_addr := bias_addr + 16.U
                    state := fc_load_output
                }
                .otherwise {
                    state := fc_done
                }
            }
        }
        is(fc_done) {
            when (io.ack) {
                state := fc_idle
            }
        }
    }
}