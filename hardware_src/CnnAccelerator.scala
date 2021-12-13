/*
A CNN accelerator: https://github.com/bossebandowski/nopu
Author: Bosse Bandowski (bosse.bandowski@outlook.com)
*/

package cop

import chisel3._
import chisel3.util._

import cnnacc.Config._
import cnnacc._

import ocp._
import patmos.Constants._

class CnnAccelerator() extends CoprocessorMemoryAccess() {
    
    val stateReg = RegInit(0.U(8.W))
    val mem_state = RegInit(mem_idle)

    val emulator = RegInit(true.B)

    // BRAM memory and default assignments
    val bram = Module(new BramControl())
    bram.io.wrEna := false.B
    bram.io.wrAddr := 0.U
    bram.io.wrData := 0.S
    bram.io.rdAddr := 0.U
    val layer_offset = scala.math.pow(2,14).toInt.U

    // auxiliary registers
    val addrReg = RegInit(0.U(DATA_WIDTH.W))
    val resReg = RegInit(10.U(DATA_WIDTH.W))
    val mem_w_buffer = Reg(Vec(BURST_LENGTH, UInt(DATA_WIDTH.W)))
    val mem_r_buffer = Reg(Vec(BURST_LENGTH, UInt(DATA_WIDTH.W)))
    val burst_count_reg = RegInit(0.U(3.W))
    val maxOut = RegInit(0.U(DATA_WIDTH.W))
    val outAddr = RegInit(0.U(DATA_WIDTH.W))
    val layer = RegInit(0.U(8.W))    
    
    // registers only needed to facilitate emulator
    val emulator_address = RegInit(0.U(DATA_WIDTH.W))
    val emulator_input = Reg(Vec(9, SInt(DATA_WIDTH.W)))
    val emulator_count = RegInit(0.U(3.W))

    val outs = Reg(Vec(BURST_LENGTH, SInt(DATA_WIDTH.W)))
    val idx = RegInit(0.U(DATA_WIDTH.W))
    val curMax = RegInit(ABS_MIN.S(DATA_WIDTH.W))

    val inCount = RegInit(0.U(DATA_WIDTH.W))
    val outCount = RegInit(0.U(DATA_WIDTH.W))

/* ================================================= NETWORK ============================================= */ 

    val num_layers = RegInit(0.U(DATA_WIDTH.W))
    val image_address = RegInit(0.U(DATA_WIDTH.W))
    val layer_meta_a = Reg(Vec(MAX_LAYERS, UInt(8.W)))          // layer activations
    val layer_meta_t = Reg(Vec(MAX_LAYERS, UInt(8.W)))          // layer types
    val layer_meta_w = Reg(Vec(MAX_LAYERS, UInt(32.W)))         // point to layer weight addresses
    val layer_meta_b = Reg(Vec(MAX_LAYERS, UInt(32.W)))         // point to layer biases
    val layer_meta_s_i = Reg(Vec(MAX_LAYERS, UInt(32.W)))       // layer sizes (for fc: number of nodes)
    val layer_meta_s_o = Reg(Vec(MAX_LAYERS, UInt(32.W)))       // layer sizes (for fc: number of nodes)
    val layer_meta_m = Reg(Vec(MAX_LAYERS, UInt(32.W)))

/* ================================================= LAYER HARDWARE INIT ============================================= */ 

    val conv_layer = Module(new LayerConv())
    
    // BRAM inputs
    conv_layer.io.bram_rd_data := bram.io.rdData

    // SRAM inputs
    conv_layer.io.sram_state := 0.U
    conv_layer.io.sram_rd_buffer := mem_r_buffer
    conv_layer.io.sram_idle := (mem_state === mem_idle)
    conv_layer.io.sram_done := (mem_state === mem_done)

    // CONFIG connections
    conv_layer.io.activation := 0.U
    conv_layer.io.weight_addr := 0.U
    conv_layer.io.bias_addr := 0.U
    conv_layer.io.shape_in := 0.U
    conv_layer.io.shape_out := 0.U
    conv_layer.io.m_factor := 0.U
    conv_layer.io.even := false.B

    // OTHER
    conv_layer.io.run := false.B
    conv_layer.io.ack := false.B

    val pool_layer = Module(new LayerMaxPool())
    
    // BRAM inputs
    pool_layer.io.bram_rd_data := bram.io.rdData

    // SRAM inputs
    pool_layer.io.sram_state := 0.U
    pool_layer.io.sram_rd_buffer := Seq(0.U, 0.U, 0.U, 0.U)
    pool_layer.io.sram_idle := false.B
    pool_layer.io.sram_done := false.B

    // CONFIG connections
    pool_layer.io.activation := 0.U
    pool_layer.io.weight_addr := 0.U
    pool_layer.io.bias_addr := 0.U
    pool_layer.io.shape_in := 0.U
    pool_layer.io.shape_out := 0.U
    pool_layer.io.m_factor := 0.U
    pool_layer.io.even := false.B

    // OTHER
    pool_layer.io.run := false.B
    pool_layer.io.ack := false.B

    val fc_layer = Module(new LayerFc())

    // BRAM inputs
    fc_layer.io.bram_rd_data := bram.io.rdData

    // SRAM inputs
    fc_layer.io.sram_state := 0.U
    fc_layer.io.sram_rd_buffer := mem_r_buffer
    fc_layer.io.sram_idle := (mem_state === mem_idle)
    fc_layer.io.sram_done := (mem_state === mem_done)

    // CONFIG connections
    fc_layer.io.activation := 0.U
    fc_layer.io.weight_addr := 0.U
    fc_layer.io.bias_addr := 0.U
    fc_layer.io.shape_in := 0.U
    fc_layer.io.shape_out := 0.U
    fc_layer.io.m_factor := 0.U
    fc_layer.io.even := false.B

    // OTHER
    fc_layer.io.run := false.B
    fc_layer.io.ack := false.B

/* ============================================== CMD HANDLING ============================================ */ 


    // SRAM requests from layer components
    when (conv_layer.io.sram_wr_req) {
        mem_w_buffer := conv_layer.io.sram_wr_buffer
        mem_state := mem_write_req
        addrReg := conv_layer.io.sram_addr
    }
    .elsewhen (fc_layer.io.sram_wr_req) {
        mem_w_buffer := fc_layer.io.sram_wr_buffer
        mem_state := mem_write_req
        addrReg := fc_layer.io.sram_addr
    }

    when (conv_layer.io.sram_rd_req) {
        mem_state := mem_read_req
        addrReg := conv_layer.io.sram_addr
    }
    .elsewhen (fc_layer.io.sram_rd_req) {
        mem_state := mem_read_req
        addrReg := fc_layer.io.sram_addr
    }

    when (conv_layer.io.sram_free_up) {
        mem_state := mem_idle
    }
    .elsewhen (fc_layer.io.sram_free_up) {
        mem_state := mem_idle
    }

    // BRAM requests from layer components
    when (conv_layer.io.bram_rd_req) {
        bram.io.rdAddr := conv_layer.io.bram_rd_addr
    }
    .elsewhen (pool_layer.io.bram_rd_req) {
        bram.io.rdAddr := pool_layer.io.bram_rd_addr
    }
    .elsewhen (fc_layer.io.bram_rd_req) {
        bram.io.rdAddr := fc_layer.io.bram_rd_addr
    }

    when (conv_layer.io.bram_wr_req) {
        bram.io.wrEna := true.B
        bram.io.wrAddr := conv_layer.io.bram_wr_addr
        bram.io.wrData := conv_layer.io.bram_wr_data
    }
    .elsewhen(pool_layer.io.bram_wr_req) {
        bram.io.wrEna := true.B
        bram.io.wrAddr := pool_layer.io.bram_wr_addr
        bram.io.wrData := pool_layer.io.bram_wr_data
    }
    .elsewhen(fc_layer.io.bram_wr_req) {
        bram.io.wrEna := true.B
        bram.io.wrAddr := fc_layer.io.bram_wr_addr
        bram.io.wrData := fc_layer.io.bram_wr_data
    }


    // COP requests from patmos
    val isIdle = Wire(Bool())
    isIdle := stateReg === idle && mem_state === mem_idle

    // default values
    io.copOut.result := 10.U
    io.copOut.ena_out := false.B

    // start operation
    when(io.copIn.trigger && io.copIn.ena_in) {
        io.copOut.ena_out := true.B
        when(io.copIn.isCustom) {
        // no custom operations
        }.elsewhen(io.copIn.read) {
            switch(io.copIn.funcId) {
                is(FUNC_POLL) {
                    io.copOut.result := stateReg
                }
                is(FUNC_GET_RES) {
                    io.copOut.result := resReg
                }
            }
        }
        .otherwise {
            switch(io.copIn.funcId) {
                is(FUNC_RESET) {
                    when(isIdle) {
                        stateReg := restart
                    }
                }
                is(FUNC_RUN) {
                    when(isIdle) {
                        stateReg := start
                    }
                }
                is(FUNC_CONFIG) {
                    when(isIdle) {
                        layer := io.copIn.opData(0)(15, 8)
                        inCount := io.copIn.opData(0)(7, 0)
                        outCount := io.copIn.opData(1)
                        stateReg := config
                    }
                }
                is(FUNC_LOAD_IMG) {
                    when(isIdle) {
                        bram.io.wrEna := true.B
                        bram.io.wrAddr := io.copIn.opData(0).asUInt
                        bram.io.wrData := io.copIn.opData(1).asSInt
                        emulator := false.B
                    }
                }
                is(FUNC_MEM_R) {
                    when(isIdle) {
                        bram.io.rdAddr := io.copIn.opData(0)
                        stateReg := mem_r
                    }
                }
            }
        }
    }

/* ======================================= ACCELERATOR STATE MACHINE ====================================== */

    switch(stateReg) {
        is(idle) {
            /*
            Don't do anything until asked to
            */
        }
        is(config) {
            stateReg := idle
            switch(inCount) {
                is(0.U) {
                    layer_meta_t(layer) := outCount
                }
                is(1.U) {
                    layer_meta_a(layer) := outCount
                }
                is(2.U) {
                    layer_meta_w(layer) := outCount
                }
                is(3.U) {
                    layer_meta_b(layer) := outCount
                }
                is(4.U) {
                    layer_meta_s_i(layer) := outCount
                }
                is(5.U) {
                    layer_meta_s_o(layer) := outCount
                }
                is(6.U) {
                    layer_meta_m(layer) := outCount
                }
                is(7.U) {
                    image_address := outCount
                }
                is(8.U) {
                    num_layers := outCount
                }
            }
        }
        is(start) {
            /*
            Prepare for inference. Assumes that model and image are in the defined memory locations.
            If that is not the case by this state, the inference will go horribly wrong.
            */
            layer := 0.U                                    // reset layer count
            idx := 0.U                                      // reset idx which is used to figure out the index of the maximum value in the output layer
            inCount := 0.U
            emulator_count := 0.U
            outAddr := 0.U
            when (emulator) {
                stateReg := load_image                          // start inference by moving image to bram
                emulator_address := image_address
            }
            .otherwise {
                stateReg := next_layer
            }
        }
        is(load_image) {
            /*
            Read one burst of inputs
            For full word inputs only
            */

            when (mem_state === mem_idle) {                   // wait until memory is ready
                addrReg := emulator_address                           // load current input address
                mem_state := mem_read_req                      // force memory into read request state
            }

            when (mem_state === mem_done) {                   // wait until transaction is finished
                mem_state := mem_idle                         // mark memory as idle
                stateReg := write_bram                      // load weights next
                emulator_input(0) := mem_r_buffer(0).asSInt          // load 4 px
                emulator_input(1) := mem_r_buffer(1).asSInt           
                emulator_input(2) := mem_r_buffer(2).asSInt           
                emulator_input(3) := mem_r_buffer(3).asSInt
            }
        }
        is(write_bram) {
            /*
            write image into bram
            */
            when (inCount === IMG_CHUNK_SIZE) {
                stateReg := next_layer
            }
            .otherwise {
                when (emulator_count < BURST_LENGTH.U) {
                    bram.io.wrEna := true.B
                    bram.io.wrAddr := outAddr
                    bram.io.wrData := emulator_input(emulator_count)

                    emulator_count := emulator_count + 1.U
                    outAddr := outAddr + 1.U
                    stateReg := write_bram
                }
                .otherwise {
                    emulator_count := 0.U
                    emulator_address := emulator_address + 16.U
                    inCount := inCount + 4.U
                    stateReg := load_image
                }
            }
        }
        is(layer_done) {
            when (layer === num_layers - 1.U) {   // when we have reached the last layer
                stateReg := read_output             // get ready to extract max
                curMax := ABS_MIN.S                 // set curmax to low value
                maxOut := layer_meta_s_o(layer)     // set number of output nodes (now only processing one at a time)
                outCount := 0.U                     // reset node count
                when (layer(0) === 0.U) {           // even last layer
                    outAddr := layer_offset
                    bram.io.rdAddr := layer_offset
                }
                .otherwise {                        // odd last layer
                    outAddr := 0.U
                    bram.io.rdAddr := 0.U
                }
            }
            .otherwise {
                stateReg := next_layer                   // get ready to load nodes as inputs
                layer := layer + 1.U
            }
        }
        is(next_layer) {
            switch(layer_meta_t(layer)) {
                is (fc) {
                    stateReg := fc
                    fc_layer.io.run := true.B
                }
                is (conv) {
                    stateReg := conv
                    conv_layer.io.run := true.B
                }
                is (pool) {
                    stateReg := pool
                    pool_layer.io.run := true.B
                }
            }
        }
        is(fc) {
            fc_layer.io.activation := layer_meta_a(layer)
            fc_layer.io.weight_addr := layer_meta_w(layer)
            fc_layer.io.bias_addr := layer_meta_b(layer)
            fc_layer.io.shape_in := layer_meta_s_i(layer)
            fc_layer.io.shape_out := layer_meta_s_o(layer)
            fc_layer.io.m_factor := layer_meta_m(layer)
            fc_layer.io.even := ~layer(0)
            
            when(fc_layer.io.state === fc_done) {
                stateReg := set_offset
                fc_layer.io.ack := true.B
            }
        }
        is(conv) {
            conv_layer.io.activation := layer_meta_a(layer)
            conv_layer.io.weight_addr := layer_meta_w(layer)
            conv_layer.io.bias_addr := layer_meta_b(layer)
            conv_layer.io.shape_in := layer_meta_s_i(layer)
            conv_layer.io.shape_out := layer_meta_s_o(layer)
            conv_layer.io.m_factor := layer_meta_m(layer)
            conv_layer.io.even := ~layer(0)

            when(conv_layer.io.state === conv_done) {
                stateReg := set_offset
                conv_layer.io.ack := true.B
            }
        }
        is(pool) {
            pool_layer.io.shape_in := layer_meta_s_i(layer)
            pool_layer.io.shape_out := layer_meta_s_o(layer)
            pool_layer.io.m_factor := layer_meta_m(layer)
            pool_layer.io.even := ~layer(0)

            when(pool_layer.io.state === pool_done) {
                stateReg := set_offset
                pool_layer.io.ack := true.B
            }
        }
        is(set_offset) {
            stateReg := clear_layer
            outCount := 0.U

            when (layer === 0.U) {
                maxOut := IMG_CHUNK_SIZE
            }
            .otherwise {
                maxOut := layer_meta_s_o(layer - 1.U)
            }

            when (layer(0) === 0.U) {               // even layers (inverted here)
                outAddr := 0.U
            }
            .otherwise {                            // odd layers (inverted here)
                outAddr := layer_offset
            }
        }
        is(clear_layer) {

            when(outCount < maxOut + 16.U) {
                bram.io.wrEna := true.B
                bram.io.wrAddr := outAddr
                bram.io.wrData := 0.S
                
                outCount := outCount + 1.U
                outAddr := outAddr + 1.U
            }
            .otherwise {
                stateReg := layer_done
            }
        }
        is(read_output) {
            /*
            read a single node from the output layer
            */
            outs(0) := bram.io.rdData
            stateReg := find_max
        }
        is(find_max) {
            /*
            check if the current output node is larger than all previously seen ones
            */
            outAddr := outAddr + 1.U                        // increment node address by 1 word
            outCount := outCount + 1.U                      // increment node count by 1

            when (outs(0) > curMax) {                       // if output nodes is larger than current maximum output
                curMax := outs(0)                           // update current maximum output
                idx := outCount                             // update index of maximum output
            }

            when (outCount === maxOut) {                    // when we have cycled though all output nodes, exit loop                       
                stateReg := save_max
            }
            .otherwise {                                    // otherwise continue with next
                bram.io.rdAddr := outAddr + 1.U
                stateReg := read_output
            }
        }
        is(save_max) {
            /*
            copy index of maximum output into result register and set cop to idle (process finished)
            */
            resReg := idx
            stateReg := reset_memory

            outCount := 0.U
            when (layer(0) === 0.U) {                           // even layers
                outAddr := 0.U
            }
            .otherwise {                                        // odd layers
                outAddr := layer_offset
            }
        }
        is(reset_memory) {
            /*
            reset network nodes in last layer to 0 to be ready for next inference
            */
            when(outCount < layer_meta_s_o(layer) + 16.U) {
                bram.io.wrEna := true.B
                bram.io.wrAddr := outAddr
                bram.io.wrData := 0.S
                
                outCount := outCount + 1.U
                outAddr := outAddr + 1.U
            }
            .otherwise {
                stateReg := idle
            }
        }
        is(mem_r) {
            stateReg := idle
            resReg := bram.io.rdData.asUInt
        }
        is(restart) {
            burst_count_reg := 0.U
            addrReg := 0.U

            resReg := 10.U
            mem_r_buffer := Seq(0.U, 0.U, 0.U, 0.U)
            mem_w_buffer := Seq(0.U, 0.U, 0.U, 0.U)

            mem_state := mem_idle
            stateReg := reset_memory
        }
    }


/* =================================================== Memory ================================================== */

    // defaults
    io.memPort.M.Cmd := OcpCmd.IDLE
    io.memPort.M.Addr := 0.U
    io.memPort.M.Data := 0.U
    io.memPort.M.DataValid := 0.U
    io.memPort.M.DataByteEn := "b1111".U

    // memory state machine
    switch(mem_state) {
        is(mem_read_req) {
            io.memPort.M.Cmd := OcpCmd.RD
            io.memPort.M.Addr := addrReg
            burst_count_reg := 0.U
            when(io.memPort.S.CmdAccept === 1.U) {
                mem_state := mem_read
            }
        }
        is(mem_read) {
            mem_r_buffer(burst_count_reg) := io.memPort.S.Data
            when(io.memPort.S.Resp === OcpResp.DVA) {
                burst_count_reg := burst_count_reg + 1.U
            }
            when (burst_count_reg + 1.U === BURST_LENGTH.U) {
                mem_state := mem_done
            }
        }
        is(mem_write_req) {
            io.memPort.M.Cmd := OcpCmd.WR
            io.memPort.M.Addr := addrReg
            io.memPort.M.Data := mem_w_buffer(0)
            io.memPort.M.DataValid := 1.U
            when(io.memPort.S.CmdAccept === 1.U && io.memPort.S.DataAccept === 1.U) {
                burst_count_reg := 1.U
                mem_state := mem_write
            }
        }
        is(mem_write) {
            io.memPort.M.Data := mem_w_buffer(burst_count_reg);
            io.memPort.M.DataValid := 1.U
            when(io.memPort.S.DataAccept === 1.U) {
                burst_count_reg := burst_count_reg + 1.U
            }

            when(io.memPort.S.Resp === OcpResp.DVA) {
                mem_state := mem_done
            }
        }
    }
}

object CnnAccelerator extends CoprocessorObject {

    def init(params: Map[String, String]) = {}
    
    def create(params: Map[String, String]): CnnAccelerator = Module(new CnnAccelerator())
}