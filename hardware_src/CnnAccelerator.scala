/*
A CNN accelerator: https://github.com/bossebandowski/nopu
Author: Bosse Bandowski (bosse.bandowski@outlook.com)
*/

package cop

import cnnacc._

import ocp._
import patmos.Constants._

import chisel3._
import chisel3.util._

class CnnAccelerator() extends CoprocessorMemoryAccess() {

    // coprocessor function definitions
    val FUNC_RESET            = "b00000".U(5.W)   // reset coprocessor
    val FUNC_POLL             = "b00001".U(5.W)   // get processor status
    val FUNC_RUN              = "b00010".U(5.W)   // init inference
    val FUNC_GET_RES          = "b00100".U(5.W)   // read result register

    val FUNC_CONFIG           = "b00011".U(5.W)   // receive a network configuration
    val FUNC_LOAD_IMG         = "b00110".U(5.W)   // write img pixel to BRAM
    val FUNC_MEM_R            = "b00101".U(5.W)   // TEST: read a value from memory

    // states COP control
    val idle :: start :: fc :: conv :: pool :: mem_r :: restart :: reset_memory :: next_layer :: layer_done :: read_output :: find_max :: save_max :: load_image :: write_bram :: clear_layer :: peek_bram :: set_offset :: config :: Nil = Enum(19)
    // states FC layer
    val fc_idle :: fc_done :: fc_init :: fc_load_input :: fc_load_weight :: fc_load_output :: fc_mac :: fc_write_output :: fc_load_bias :: fc_add_bias :: fc_apply_relu :: fc_write_bias :: fc_requantize :: fc_load_m :: Nil = Enum(14)
    // states CONV layer
    val conv_idle :: conv_done :: conv_init :: conv_load_filter :: conv_apply_filter :: conv_write_output :: conv_load_bias :: conv_add_bias :: conv_apply_relu :: conv_load_input :: conv_addr_set :: conv_out_address_set :: conv_load_output :: conv_load_m :: conv_requantize :: Nil = Enum(15)
    // states POOL layer
    val pool_idle :: pool_done :: pool_init :: pool_in_addr_set :: pool_find_max :: pool_write_output :: Nil = Enum(6)
    // states SRAM control
    val memIdle :: memDone :: memReadReq :: memRead :: memWriteReq :: memWrite :: Nil = Enum(6)
    
    val stateReg = RegInit(0.U(8.W))
    val fcState = RegInit(0.U(8.W))
    val convState = RegInit(0.U(8.W))
    val poolState = RegInit(0.U(8.W))
    val memState = RegInit(memIdle)

    val emulator = RegInit(true.B)
    val outputUsage = RegInit(0.U(8.W))

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
    val bram_count_reg = RegInit(0.U(3.W))
    val relu = Reg(Vec(BURST_LENGTH, SInt(DATA_WIDTH.W)))

    val maxIn = RegInit(0.U(DATA_WIDTH.W))
    val maxOut = RegInit(0.U(DATA_WIDTH.W))

    val inAddr = RegInit(0.U(DATA_WIDTH.W))
    val weightAddr = RegInit(0.U(DATA_WIDTH.W))
    val biasAddr = RegInit(0.U(DATA_WIDTH.W))
    val outAddr = RegInit(0.U(DATA_WIDTH.W))
    val layer = RegInit(0.U(8.W))    

    
    // requantization regs
    val ms = Reg(Vec(16, UInt(32.W)))
    val tmp64 = Reg(Vec(BURST_LENGTH, SInt(64.W)))

    // conv and pool registers
    val filter3x3 = Reg(Vec(9, SInt(DATA_WIDTH.W)))
    val in_map = Reg(Vec(9, SInt(DATA_WIDTH.W)))
    val conv_addr = RegInit(0.S(DATA_WIDTH.W))

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

    val img_chunk_size = 3072.U
    val abs_min = -2147483646

    // fc registers
    val ws = Reg(Vec(BURST_LENGTH, SInt(8.W)))
    val outs = Reg(Vec(BURST_LENGTH, SInt(DATA_WIDTH.W)))
    val bs = Reg(Vec(BURST_LENGTH, SInt(DATA_WIDTH.W)))
    val idx = RegInit(0.U(DATA_WIDTH.W))
    val curMax = RegInit(abs_min.S(DATA_WIDTH.W))

    val inCount = RegInit(0.U(DATA_WIDTH.W))
    val outCount = RegInit(0.U(DATA_WIDTH.W))

/* ================================================= NETWORK ============================================= */ 

    val num_layers_max = 20
    val num_layers = RegInit(20.U(DATA_WIDTH.W))
    val image_address = RegInit(0.U(DATA_WIDTH.W))
    val layer_meta_a = Reg(Vec(num_layers_max, UInt(8.W)))          // layer activations
    val layer_meta_t = Reg(Vec(num_layers_max, UInt(8.W)))          // layer types
    val layer_meta_w = Reg(Vec(num_layers_max, UInt(32.W)))         // point to layer weight addresses
    val layer_meta_b = Reg(Vec(num_layers_max, UInt(32.W)))         // point to layer biases
    val layer_meta_s_i = Reg(Vec(num_layers_max, UInt(32.W)))       // layer sizes (for fc: number of nodes)
    val layer_meta_s_o = Reg(Vec(num_layers_max, UInt(32.W)))       // layer sizes (for fc: number of nodes)
    val layer_meta_m = Reg(Vec(num_layers_max, UInt(32.W)))

/* ================================================= LAYER HARDWARE INIT ============================================= */ 

    val conv_layer = Module(new LayerConv())
    
    // BRAM inputs
    conv_layer.io.bram_rd_data := bram.io.rdData

    // SRAM inputs
    conv_layer.io.sram_state := 0.U
    conv_layer.io.sram_rd_buffer := mem_r_buffer
    conv_layer.io.sram_idle := (memState === memIdle)
    conv_layer.io.sram_done := (memState === memDone)

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

/* ============================================== CMD HANDLING ============================================ */ 


    // SRAM requests from layer components
    when (conv_layer.io.sram_wr_req) {
        mem_w_buffer := conv_layer.io.sram_wr_buffer
        memState := memWriteReq
        addrReg := conv_layer.io.sram_addr
    }

    when (conv_layer.io.sram_rd_req) {
        memState := memReadReq
        addrReg := conv_layer.io.sram_addr
    }

    when (conv_layer.io.sram_free_up) {
        memState := memIdle
    }

    // BRAM requests from layer components
    when (conv_layer.io.bram_rd_req) {
        bram.io.rdAddr := conv_layer.io.bram_rd_addr
    }
    .elsewhen (pool_layer.io.bram_rd_req) {
        bram.io.rdAddr := pool_layer.io.bram_rd_addr
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



    // COP requests from patmos
    val isIdle = Wire(Bool())
    isIdle := stateReg === idle && memState === memIdle

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
            bram_count_reg := 0.U
            outAddr := 0.U
            when (emulator) {
                stateReg := load_image                          // start inference by moving image to bram
                inAddr := image_address
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

            when (memState === memIdle) {                   // wait until memory is ready
                addrReg := inAddr                           // load current input address
                memState := memReadReq                      // force memory into read request state
            }

            when (memState === memDone) {                   // wait until transaction is finished
                memState := memIdle                         // mark memory as idle
                stateReg := write_bram                      // load weights next
                in_map(0) := mem_r_buffer(0).asSInt          // load 4 px
                in_map(1) := mem_r_buffer(1).asSInt           
                in_map(2) := mem_r_buffer(2).asSInt           
                in_map(3) := mem_r_buffer(3).asSInt
            }
        }
        is(write_bram) {
            /*
            write image into bram
            */
            when (inCount === img_chunk_size) {
                stateReg := next_layer
            }
            .otherwise {
                when (bram_count_reg < BURST_LENGTH.U) {
                    bram.io.wrEna := true.B
                    bram.io.wrAddr := outAddr
                    bram.io.wrData := in_map(bram_count_reg)

                    bram_count_reg := bram_count_reg + 1.U
                    outAddr := outAddr + 1.U
                    stateReg := write_bram
                }
                .otherwise {
                    bram_count_reg := 0.U
                    inAddr := inAddr + 16.U
                    inCount := inCount + 4.U
                    stateReg := load_image
                }
            }
        }
        is(layer_done) {
            when (layer === num_layers - 1.U) {   // when we have reached the last layer
                stateReg := read_output             // get ready to extract max
                curMax := abs_min.S                 // set curmax to low value
                maxOut := 9.U                       // set number of output nodes (now only processing one at a time)
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
                    fcState := fc_init
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
            when(fcState === fc_done) {
                stateReg := set_offset
                fcState := fc_idle
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
            when (layer(0) === 0.U) {               // even layers (inverted here)
                outAddr := 0.U
            }
            .otherwise {                            // odd layers (inverted here)
                outAddr := layer_offset
            }
        }
        is(clear_layer) {
            when(outCount < layer_offset - 1.U) {
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
            when(outCount < layer_offset) {
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

            memState := memIdle
            stateReg := reset_memory
        }
    }

/* ================================================= FC LAYER ============================================ */

    switch(fcState) {
        is(fc_idle) {
            /*
            do nothing
            */
        }
        is(fc_init) {
            weightAddr := layer_meta_w(layer)                   // set first weight address
            biasAddr := layer_meta_b(layer)                     // set first bias address
            inCount := 0.U                                      // reset input count
            outCount := 0.U                                     // reset output count
            outputUsage := fc_mac                               // tell the load_output state where to transition next (mac or bias_add loop)
            fcState := fc_load_m
            maxIn := layer_meta_s_i(layer) - 1.U                // in input layer, we increment by 1
            maxOut := layer_meta_s_o(layer) - 4.U               // in output layer, we increment by 4
            bram_count_reg := 0.U


            when (layer(0) === 0.U) {                           // even layers
                outAddr := layer_offset
                inAddr := 0.U
            }
            .otherwise {                                        // odd layers
                outAddr := 0.U
                inAddr := layer_offset
            }
        }
        is(fc_load_m) {

            when (memState === memIdle) {                   // wait until memory is ready                   
                addrReg := layer_meta_m(layer)              // load current weight address
                memState := memReadReq                      // force memory into read request state
            }

            when (memState === memDone) {                   // wait until transaction is finished
                memState := memIdle                         // mark memory as idle
                fcState := fc_load_input                    // load outputs next
                ms(0) := mem_r_buffer(0)   
                bram.io.rdAddr := inAddr
            }
        }
        is(fc_load_input) {
            /*
            Read one input from bram
            */
            in_map(0) := bram.io.rdData                          // read single value from bram
            fcState := fc_load_weight                        // load weights next
        }
        is(fc_load_weight) {
            /*
            read one burst of weights from sram
            */

            when (memState === memIdle) {                   // wait until memory is ready                   
                addrReg := weightAddr                       // load current weight address
                memState := memReadReq                      // force memory into read request state
            }

            when (memState === memDone) {                   // wait until transaction is finished
                memState := memIdle                         // mark memory as idle
                fcState := fc_load_output                     // load outputs next

                ws(0) := mem_r_buffer(0)(31, 24).asSInt   
                ws(1) := mem_r_buffer(0)(23, 16).asSInt
                ws(2) := mem_r_buffer(0)(15, 8).asSInt
                ws(3) := mem_r_buffer(0)(7, 0).asSInt
                bram.io.rdAddr := outAddr
            }
        }
        is(fc_load_output) {
            /*
            Load four output nodes from bram. Legacy from ocpburst, could be more
            */
            when (bram_count_reg === BURST_LENGTH.U) {
                fcState := outputUsage                             // after loading four outputs, transition to mac
                bram_count_reg := 0.U                               // reset count
            }
            .otherwise {
                outs(bram_count_reg) := bram.io.rdData              // read data into out reg array
                bram_count_reg := bram_count_reg + 1.U              // increment "burst" count
                bram.io.rdAddr := outAddr + bram_count_reg + 1.U    // set address for next read
                fcState := fc_load_output                          // stay in this state
            }
        }
        is(fc_mac) {
            /*
            Do the multiply-accumulate ops. One input to four outputs.
            */
            outs(0) := outs(0) + (in_map(0) * ws(0))
            outs(1) := outs(1) + (in_map(0) * ws(1))
            outs(2) := outs(2) + (in_map(0) * ws(2))
            outs(3) := outs(3) + (in_map(0) * ws(3))

            fcState := fc_write_output                           // next state will be output writeback

        }
        is(fc_write_output) {
            /*
            Write four output nodes back to bram. Legacy from ocpburst, could be more
            */
            when (bram_count_reg < BURST_LENGTH.U) {
                bram.io.wrEna := true.B
                bram.io.wrAddr := outAddr + bram_count_reg
                bram.io.wrData := outs(bram_count_reg)
                bram_count_reg := bram_count_reg + 1.U
                fcState := fc_write_output
            }
            .otherwise {
                bram_count_reg := 0.U

                when (inCount === maxIn && outCount === maxOut) {   // biases
                    outCount := 0.U                                 // reset output count
                    fcState := fc_load_output                       // next state will be output load

                    outputUsage := fc_load_bias                     // after that, transition to bias load
                    when (layer(0) === 0.U) {                           // even layers
                        outAddr := layer_offset
                        bram.io.rdAddr := layer_offset
                    }
                    .otherwise {                                        // odd layers
                        outAddr := 0.U
                        bram.io.rdAddr := 0.U
                    }
                }
                .elsewhen(inCount < maxIn && outCount === maxOut) { // next input

                    inCount := inCount + 1.U                        // increment input count
                    outCount := 0.U                                 // reset output count
                    fcState := fc_load_input                        // load next input
                    inAddr := inAddr + 1.U                          // increment input address by 1
                    bram.io.rdAddr := inAddr + 1.U

                    weightAddr := weightAddr + 4.U                  // increment weight address by 1 word

                    when (layer(0) === 0.U) {                           // even layers
                        outAddr := layer_offset
                    }
                    .otherwise {                                        // odd layers
                        outAddr := 0.U
                    }

                }
                .otherwise {
                    /*
                    when the current input has not yet been mac'ed to all outputs, just continue with the next 4...
                    */
                    outCount := outCount + 4.U              // increment node count by 4
                    outAddr := outAddr + 4.U                // increment node address by 4
                    weightAddr := weightAddr + 4.U          // increment weight address by 4 bytes
                    fcState := fc_load_weight               // transition to load weight (same input, different outputs, different weights)
                }
            }
        }
        is(fc_load_bias) {
            /*
            load bias from memory. Biases are 32 bit wide, so we can read four at a time
            */

            when (memState === memIdle) {                   // wait until memory is ready
                addrReg := biasAddr                         // load current bias address
                memState := memReadReq                      // force memory into read request state
            }

            when (memState === memDone) {                   // wait until transaction is finished
                memState := memIdle                         // mark memory as idle
                fcState := fc_add_bias                       // transition to layer's corresponding activation state

                bs(0) := mem_r_buffer(0).asSInt             // get biases from memory read buffer
                bs(1) := mem_r_buffer(1).asSInt
                bs(2) := mem_r_buffer(2).asSInt
                bs(3) := mem_r_buffer(3).asSInt
            }
        }
        is(fc_add_bias) {
            /*
            add biases to nodes without applying relu
            */
            fcState := layer_meta_a(layer)


            relu(0) := bs(0) + outs(0)
            relu(1) := bs(1) + outs(1)
            relu(2) := bs(2) + outs(2)
            relu(3) := bs(3) + outs(3)

            bram_count_reg := 0.U
        }
        is(fc_requantize) {
            fcState := fc_apply_relu
            tmp64(0) := (relu(0) * ms(0).asSInt) >> 32.U
            tmp64(1) := (relu(1) * ms(0).asSInt) >> 32.U
            tmp64(2) := (relu(2) * ms(0).asSInt) >> 32.U
            tmp64(3) := (relu(3) * ms(0).asSInt) >> 32.U
        }
        is(fc_apply_relu) {
            /*
            finish relu activation by setting negative nodes equal to 0
            */
            when (tmp64(0)(31, 0).asSInt < 0.S) {
                relu(0) := 0.S
            }
            .elsewhen(tmp64(0)(31, 0).asSInt > 255.S) {
                relu(0) := 255.S
            }
            .otherwise {
                relu(0) := tmp64(0)(31, 0).asSInt
            }

            when (tmp64(1)(31, 0).asSInt < 0.S) {
                relu(1) := 0.S
            }
            .elsewhen(tmp64(1)(31, 0).asSInt > 255.S) {
                relu(1) := 255.S
            }
            .otherwise {
                relu(1) := tmp64(1)(31, 0).asSInt
            }

            when (tmp64(2)(31, 0).asSInt < 0.S) {
                relu(2) := 0.S
            }
            .elsewhen(tmp64(2)(31, 0).asSInt > 255.S) {
                relu(2) := 255.S
            }
            .otherwise {
                relu(2) := tmp64(2)(31, 0).asSInt
            }

            when (tmp64(3)(31, 0).asSInt < 0.S) {
                relu(3) := 0.S
            }
            .elsewhen(tmp64(3)(31, 0).asSInt > 255.S) {
                relu(3) := 255.S
            }
            .otherwise {
                relu(3) := tmp64(3)(31, 0).asSInt
            }

            fcState := fc_write_bias                                // get ready to write back outputs
        }
        is(fc_write_bias) {
            /*
            write results of bias_add + activation back to memory. Do some state transition magic
            */
            when (bram_count_reg < BURST_LENGTH.U) {
                bram.io.wrEna := true.B
                bram.io.wrAddr := outAddr + bram_count_reg
                bram.io.wrData := relu(bram_count_reg)
                bram_count_reg := bram_count_reg + 1.U
            }
            .otherwise {

                bram_count_reg := 0.U

                when (outCount < maxOut) {                  // when there are still biases to be added
                    outCount := outCount + 4.U              // increment node count by 4
                    outAddr := outAddr + 4.U                // increment node address by 4
                    bram.io.rdAddr := outAddr + 4.U
                    biasAddr := biasAddr + 16.U             // increment bias address by 4 words
                    fcState := fc_load_output               // continue with loading the next 4 nodes
                }
                .otherwise {
                    fcState := fc_done                      // done with layer
                }
            }
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
    switch(memState) {
        is(memReadReq) {
            io.memPort.M.Cmd := OcpCmd.RD
            io.memPort.M.Addr := addrReg
            burst_count_reg := 0.U
            when(io.memPort.S.CmdAccept === 1.U) {
                memState := memRead
            }
        }
        is(memRead) {
            mem_r_buffer(burst_count_reg) := io.memPort.S.Data
            when(io.memPort.S.Resp === OcpResp.DVA) {
                burst_count_reg := burst_count_reg + 1.U
            }
            when (burst_count_reg + 1.U === BURST_LENGTH.U) {
                memState := memDone
            }
        }
        is(memWriteReq) {
            io.memPort.M.Cmd := OcpCmd.WR
            io.memPort.M.Addr := addrReg
            io.memPort.M.Data := mem_w_buffer(0)
            io.memPort.M.DataValid := 1.U
            when(io.memPort.S.CmdAccept === 1.U && io.memPort.S.DataAccept === 1.U) {
                burst_count_reg := 1.U
                memState := memWrite
            }
        }
        is(memWrite) {
            io.memPort.M.Data := mem_w_buffer(burst_count_reg);
            io.memPort.M.DataValid := 1.U
            when(io.memPort.S.DataAccept === 1.U) {
                burst_count_reg := burst_count_reg + 1.U
            }

            when(io.memPort.S.Resp === OcpResp.DVA) {
                memState := memDone
            }
        }
    }
}

object CnnAccelerator extends CoprocessorObject {

    def init(params: Map[String, String]) = {}
    
    def create(params: Map[String, String]): CnnAccelerator = Module(new CnnAccelerator())
}