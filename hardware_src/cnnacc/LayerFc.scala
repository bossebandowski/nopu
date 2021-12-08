package cnnacc

import chisel3._
import chisel3.util._

import ocp._
import patmos.Constants._
import cnnacc.Config._

class LayerFc() extends Layer {

    val max_in = RegInit(0.U(DATA_WIDTH.W))
    val max_out = RegInit(0.U(DATA_WIDTH.W))
    val transition = RegInit(0.U(8.W))
    val M = RegInit(0.U(DATA_WIDTH.W))
    val in_val = RegInit(0.S(DATA_WIDTH.W))
    val activation = RegInit(0.U(DATA_WIDTH.W))

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
            max_in := io.shape_in - 1.U
            max_out := io.shape_out - 4.U
            sram_addr_reg := io.m_factor
            activation := io.activation
            even := io.even
            
            count_a := 0.U
            count_b := 0.U
            count_c := 0.U

            transition := fc_mac
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

                io.bram_rd_req := true.B
                io.bram_rd_addr := out_addr

                state := fc_load_output
            }
        }
        is(fc_load_output) {
            when (count_c === BURST_LENGTH.U) {
                state := transition
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

            state := fc_write_output
        }
        is(fc_write_output) {
            when (count_c < BURST_LENGTH.U) {
                io.bram_wr_req := true.B
                io.bram_wr_addr := out_addr + count_c
                io.bram_wr_data := outs(count_c)
                count_c := count_c + 1.U
            }
            .otherwise {
                count_c := 0.U

                when (count_a === max_in && count_b === max_out) {
                    count_b := 0.U
                    state := fc_load_output
                    transition := fc_load_bias
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
                .elsewhen(count_a < max_in && count_b === max_out) {

                    count_a := count_a + 1.U
                    count_b := 0.U
                    state := fc_load_input

                    in_addr := in_addr + 1.U
                    io.bram_rd_req := true.B
                    io.bram_rd_addr := in_addr + 1.U
                    weight_addr := weight_addr + 4.U

                    when (even) {
                        out_addr := layer_offset
                    }
                    .otherwise {
                        out_addr := 0.U
                    }
                }
                .otherwise {
                    count_b := count_b + 4.U
                    out_addr := out_addr + 4.U
                    weight_addr := weight_addr + 4.U
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

                when (count_b < max_out) {
                    count_b := count_b + 4.U
                    out_addr := out_addr + 4.U

                    io.bram_rd_req := true.B
                    io.bram_rd_addr := out_addr + 4.U 
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