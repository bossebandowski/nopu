/*
A CNN accelerator: https://github.com/bossebandowski/nopu
Author: Bosse Bandowski (bosse.bandowski@outlook.com)
*/

package cop

import util.MemorySInt
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

    val FUNC_MEM_W            = "b00011".U(5.W)   // TEST: write a value into memory
    val FUNC_MEM_R            = "b00101".U(5.W)   // TEST: read a value from memory

    // states COP control
    val idle :: start :: fc :: conv :: pool :: mem_r :: restart :: reset_memory :: next_layer :: layer_done :: read_output :: find_max :: save_max :: load_image :: write_bram :: clear_layer :: peek_bram :: set_offset :: Nil = Enum(18)
    // states FC layer
    val fc_idle :: fc_done :: fc_init :: fc_load_input :: fc_load_weight :: fc_load_output :: fc_mac :: fc_write_output :: fc_load_bias :: fc_add_bias :: fc_apply_relu :: fc_write_bias :: fc_requantize :: fc_type_cast :: Nil = Enum(14)
    // states CONV layer
    val conv_idle :: conv_done :: conv_init :: conv_load_filter :: conv_apply_filter :: conv_write_output :: conv_load_bias :: conv_add_bias :: conv_apply_relu :: conv_load_input :: conv_addr_set :: conv_out_address_set :: conv_load_output :: Nil = Enum(13)
    // states POOL layer
    val pool_idle :: pool_done :: pool_init :: pool_in_addr_set :: pool_find_max :: pool_write_output :: Nil = Enum(6)
    // states SRAM control
    val memIdle :: memDone :: memReadReq :: memRead :: memWriteReq :: memWrite :: Nil = Enum(6)
    
    val stateReg = RegInit(0.U(8.W))
    val fcState = RegInit(0.U(8.W))
    val convState = RegInit(0.U(8.W))
    val poolState = RegInit(0.U(8.W))
    val memState = RegInit(memIdle)

    val outputUsage = RegInit(0.U(8.W))

    // BRAM memory and default assignments
    val bram = Module(new MemorySInt())
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

    val num_layers = 2
    val layer_meta_a = Reg(Vec(num_layers, UInt(8.W)))          // layer activations
    val layer_meta_t = Reg(Vec(num_layers, UInt(8.W)))          // layer types
    val layer_meta_w = Reg(Vec(num_layers, UInt(32.W)))         // point to layer weight addresses
    val layer_meta_b = Reg(Vec(num_layers, UInt(32.W)))         // point to layer biases
    val layer_meta_s_i = Reg(Vec(num_layers, UInt(32.W)))       // layer sizes (for fc: number of nodes)
    val layer_meta_s_o = Reg(Vec(num_layers, UInt(32.W)))       // layer sizes (for fc: number of nodes)
    val m = RegInit(2756637.U(DATA_WIDTH.W))
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

    val img_chunk_size = 784.U
    val abs_min = -2147483646

    // fc registers
    val ws = Reg(Vec(BURST_LENGTH, SInt(8.W)))
    val outs = Reg(Vec(BURST_LENGTH, SInt(DATA_WIDTH.W)))
    val bs = Reg(Vec(BURST_LENGTH, SInt(DATA_WIDTH.W)))
    val idx = RegInit(0.U(DATA_WIDTH.W))
    val curMax = RegInit(abs_min.S(DATA_WIDTH.W))

    val inCount = RegInit(0.U(DATA_WIDTH.W))
    val outCount = RegInit(0.U(DATA_WIDTH.W))

/* ================================================= CONSTANTS ============================================= */ 
/*
    // address constants
    layer_meta_t(0) := conv
    layer_meta_t(1) := pool
    layer_meta_t(2) := conv
    layer_meta_t(3) := pool
    layer_meta_t(4) := fc
    layer_meta_t(5) := fc

    layer_meta_a(0) := conv_apply_relu
    layer_meta_a(1) := 0.U
    layer_meta_a(2) := conv_apply_relu
    layer_meta_a(3) := 0.U
    layer_meta_a(4) := fc_apply_relu
    layer_meta_a(5) := fc_write_bias

    layer_meta_w(0) := 1000000.U
    layer_meta_w(1) := 0.U
    layer_meta_w(2) := 1100000.U
    layer_meta_w(3) := 0.U
    layer_meta_w(4) := 1200000.U
    layer_meta_w(5) := 1300000.U

    layer_meta_b(0) := 1500000.U
    layer_meta_b(1) := 0.U
    layer_meta_b(2) := 1501000.U
    layer_meta_b(3) := 0.U
    layer_meta_b(4) := 1502000.U
    layer_meta_b(5) := 1503000.U

    layer_meta_s_i(0) := Cat(28.U(8.W), 3.U(8.W), 1.U(8.W), 16.U(8.W))              // 2d width, filter width, input depth, output depth
    layer_meta_s_i(1) := Cat(26.U(8.W), 2.U(4.W), 2.U(4.W), 13.U(8.W), 16.U(8.W))   // 2d width, pool width, stride length, output width, output depth
    layer_meta_s_i(2) := Cat(13.U(8.W), 3.U(8.W), 16.U(8.W), 16.U(8.W))             // 2d width, filter width, input depth, output depth
    layer_meta_s_i(3) := Cat(11.U(8.W), 2.U(4.W), 2.U(4.W), 5.U(8.W), 16.U(8.W))    // 2d width, pool width, stride length, output width, output depth
    layer_meta_s_i(4) := 400.U                                                      // flattened input length for FC layer
    layer_meta_s_i(5) := 64.U                                                       // flattened input length for FC layer

    layer_meta_s_o(0) := 10816.U
    layer_meta_s_o(1) := 2704.U
    layer_meta_s_o(2) := 1936.U
    layer_meta_s_o(3) := 400.U
    layer_meta_s_o(4) := 64.U
    layer_meta_s_o(5) := 12.U
*/
    // address constants
    layer_meta_t(0) := fc
    layer_meta_t(1) := fc

    layer_meta_a(0) := fc_requantize
    layer_meta_a(1) := fc_write_bias

    layer_meta_w(0) := 1000000.U
    layer_meta_w(1) := 1100000.U

    layer_meta_b(0) := 1500000.U
    layer_meta_b(1) := 1501000.U

    layer_meta_s_i(0) := 784.U                                                      // flattened input length for FC layer
    layer_meta_s_i(1) := 100.U                                                       // flattened input length for FC layer

    layer_meta_s_o(0) := 100.U
    layer_meta_s_o(1) := 12.U

/* ============================================== CMD HANDLING ============================================ */ 

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
                    io.copOut.result := Cat(0.U((DATA_WIDTH-8).W), stateReg)
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
        is(start) {
            /*
            Prepare for inference. Assumes that model and image are in the defined memory locations.
            If that is not the case by this state, the inference will go horribly wrong.
            */
            layer := 0.U                                    // reset layer count
            idx := 0.U                                      // reset idx which is used to figure out the index of the maximum value in the output layer
            inCount := 0.U
            bram_count_reg := 0.U
            stateReg := load_image                          // start inference by moving image to bram
            inAddr := 40.U
            outAddr := 0.U
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
            when (layer === num_layers.U - 1.U) {   // when we have reached the last layer
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
                    convState := conv_init
                }
                is (pool) {
                    stateReg := pool
                    poolState := pool_init
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
            when(convState === conv_done) {


                stateReg := set_offset
                convState := conv_idle
            }
        }
        is(pool) {
            when(poolState === pool_done) {
                stateReg := set_offset
                poolState := pool_idle
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
            fcState := fc_load_input
            maxIn := layer_meta_s_i(layer) - 1.U                // in input layer, we increment by 1
            maxOut := layer_meta_s_o(layer) - 4.U               // in output layer, we increment by 4
            bram_count_reg := 0.U

            when (layer(0) === 0.U) {                           // even layers
                outAddr := layer_offset
                inAddr := 0.U
                bram.io.rdAddr := 0.U
            }
            .otherwise {                                        // odd layers
                outAddr := 0.U
                inAddr := layer_offset
                bram.io.rdAddr := layer_offset
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
            tmp64(0) := (relu(0) * m.asSInt) >> 32.U
            tmp64(1) := (relu(1) * m.asSInt) >> 32.U
            tmp64(2) := (relu(2) * m.asSInt) >> 32.U
            tmp64(3) := (relu(3) * m.asSInt) >> 32.U
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

            fcState := fc_type_cast                                // get ready to write back outputs
        }
        is(fc_type_cast) {
            fcState := fc_write_bias
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

/* ================================================= CONV LAYER ============================================ */

    switch(convState) {
        is(conv_idle) {
            /*
            do nothing
            */
        }
        is(conv_init) {
            weightAddr := layer_meta_w(layer)                   // set first weight address
            biasAddr := layer_meta_b(layer)                     // set first bias address
            w := layer_meta_s_i(layer)(31, 24).asSInt
            filter_size := layer_meta_s_i(layer)(23, 16).asSInt
            input_depth := layer_meta_s_i(layer)(15, 8)
            output_depth := layer_meta_s_i(layer)(7, 0)

            inCount := 0.U                                      // reset input count
            outCount := 0.U                                     // reset output count
            x := 1.S
            y := 1.S
            z := 0.U
            dx := -1.S
            dy := -1.S

            convState := conv_load_bias

            when (layer(0) === 0.U) {                           // even layers
                outAddr := layer_offset
                conv_addr := (w + 1.S) * input_depth
            }
            .otherwise {                                        // odd layers
                outAddr := 0.U
                conv_addr := layer_offset.asSInt + (w + 1.S) * input_depth
            }
        }
        is(conv_load_bias) {
            when (memState === memIdle) {                       // wait until memory is ready
                addrReg := biasAddr                             // load current input address
                memState := memReadReq                          // force memory into read request state
            }

            when (memState === memDone) {                       // wait until transaction is finished
                memState := memIdle                             // mark memory as idle
                convState := conv_load_filter                   // load bias next (1:1 match with filters)
                bs(0) := mem_r_buffer(0).asSInt                 // load single bias for current filter
            }
        }
        is(conv_load_filter) {
            when (memState === memIdle) {                       // wait until memory is ready
                addrReg := weightAddr                           // load current input address
                memState := memReadReq                          // force memory into read request state
            }

            when (memState === memDone) {                       // wait until transaction is finished
                memState := memIdle                             // mark memory as idle
                convState := conv_addr_set                      // load bias next (1:1 match with filters)
                filter3x3(0) := mem_r_buffer(0)(31, 24).asSInt         // load filter
                filter3x3(1) := mem_r_buffer(0)(23, 16).asSInt
                filter3x3(2) := mem_r_buffer(0)(15, 8).asSInt
                filter3x3(3) := mem_r_buffer(1)(31, 24).asSInt
                filter3x3(4) := mem_r_buffer(1)(23, 16).asSInt
                filter3x3(5) := mem_r_buffer(1)(15, 8).asSInt
                filter3x3(6) := mem_r_buffer(2)(31, 24).asSInt
                filter3x3(7) := mem_r_buffer(2)(23, 16).asSInt
                filter3x3(8) := mem_r_buffer(2)(15, 8).asSInt
            }
        }

        is(conv_addr_set) {
            bram.io.rdAddr := (conv_addr + dx * input_depth.asSInt + dy * w * input_depth.asSInt).asUInt
            convState := conv_load_input
        }
        is(conv_load_input) {
            in_map(((dx + 1.S) + (dy + 1.S) * filter_size).asUInt) := bram.io.rdData
            when(dx === 1.S && dy === 1.S) {
                convState := conv_load_output
                bram.io.rdAddr := outAddr
                dx := -1.S
                dy := -1.S
            }
            .elsewhen (dx === 1.S && dy < 1.S) {
                dx := -1.S
                dy := dy + 1.S
                convState := conv_addr_set
            }
            .otherwise {
                dx := dx + 1.S
                convState := conv_addr_set
            }
        }
        is(conv_load_output) {
            convState := conv_apply_filter
            outs(0) := bram.io.rdData
        }
        is(conv_apply_filter) {
            outs(0) := outs(0) + filter3x3(((dx + 1.S) + (dy + 1.S) * filter_size).asUInt) * in_map(((dx + 1.S) + (dy + 1.S) * filter_size).asUInt)

            when(dx === 1.S && dy === 1.S) {
                dx := -1.S
                dy := -1.S
                when(z < input_depth - 1.U) {
                    convState := conv_write_output
                }
                .otherwise {
                    convState := conv_add_bias
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
            convState := conv_apply_relu
        }
        is(conv_apply_relu) {
            when (outs(0) < 0.S) {
                outs(0) := 0.S
            }
            convState := conv_write_output
        }
        is(conv_write_output) {
            bram.io.wrEna := true.B
            bram.io.wrAddr := outAddr
            bram.io.wrData := outs(0)

            // transitions
            when (x === w - filter_size + 1.S && y === w - filter_size + 1.S) {
                // reset x and y
                x := 1.S
                y := 1.S
                weightAddr := weightAddr + (filter_size * 4.S).asUInt
                
                when (z < input_depth - 1.U) {
                    z := z + 1.U         
                    // next input channel, same filter (but different filter layer)
                    when (layer(0) === 0.U) {
                        conv_addr := (w + 1.S) * input_depth.asSInt + z.asSInt + 1.S
                        outAddr := layer_offset + inCount 
                    }
                    .otherwise {
                        conv_addr := layer_offset.asSInt + (w + 1.S) * input_depth.asSInt + z.asSInt + 1.S
                        outAddr := inCount
                    }

                    convState := conv_load_filter
                }
                .otherwise {
                    z := 0.U
                 
                    when (inCount < output_depth - 1.U) {
                        // when done with a filter
                        // get ready to load the next filter and corresponding bias
                        biasAddr := biasAddr + 4.U

                        // increment filter count
                        inCount := inCount + 1.U

                        // reset in address
                        when (layer(0) === 0.U) {
                            conv_addr := (w + 1.S) * input_depth.asSInt
                            outAddr := layer_offset + inCount + 1.U
                        }
                        .otherwise {
                            conv_addr := layer_offset.asSInt + (w + 1.S) * input_depth.asSInt
                            outAddr := inCount + 1.U
                        }
                        
                        // state transition
                        convState := conv_load_bias
                    }
                    .otherwise {
                        // when done with all filters
                        convState := conv_done                  // done with layer
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
                when (layer(0) === 0.U) {
                    conv_addr := (y + 1.S) * w * input_depth.asSInt + input_depth.asSInt + z.asSInt
                }
                .otherwise {
                    conv_addr := layer_offset.asSInt + (y + 1.S) * w * input_depth.asSInt + input_depth.asSInt + z.asSInt
                }
                // state transition
                convState := conv_out_address_set
            }
            // standard case: done with an input map
            .otherwise {
                // increment x
                x := x + 1.S

                // set center of next in map
                when (layer(0) === 0.U) {
                    conv_addr := y * w * input_depth.asSInt + (x + 1.S) * input_depth.asSInt + z.asSInt
                }
                .otherwise {
                    conv_addr := layer_offset.asSInt + y * w * input_depth.asSInt + (x + 1.S) * input_depth.asSInt + z.asSInt
                }
                // state transition
                convState := conv_out_address_set
            }
        }
        is(conv_out_address_set) {
            when (layer(0) === 0.U) {
                outAddr := ((y - 1.S) * output_depth.asSInt * (w - filter_size + 1.S) + (x - 1.S) * output_depth.asSInt + inCount.asSInt + layer_offset.asSInt).asUInt
            }
            .otherwise {
                outAddr := ((y - 1.S) * output_depth.asSInt * (w - filter_size + 1.S) + (x - 1.S) * output_depth.asSInt + inCount.asSInt).asUInt
            }
            convState := conv_addr_set
        }
    }

/* ================================================= POOL LAYER ============================================ */

    switch(poolState) {
        is(pool_idle) {
            
        }
        is(pool_init) {
            x := 0.S
            y := 0.S
            dx := 0.S
            dy := 0.S
            curMax := abs_min.S
            inCount := 0.U
            
            w := layer_meta_s_i(layer)(31, 24).asSInt
            filter_size := layer_meta_s_i(layer)(23, 20).asSInt
            stride_length := layer_meta_s_i(layer)(19, 16).asSInt
            output_depth := layer_meta_s_i(layer)(15, 8)
            input_depth := layer_meta_s_i(layer)(7, 0)
            
            poolState := pool_in_addr_set

        }
        is(pool_in_addr_set) {

            when (layer(0) === 0.U) {
                bram.io.rdAddr := ((stride_length * y + dy) * input_depth.asSInt * w + (stride_length * x + dx) * input_depth.asSInt + inCount.asSInt).asUInt
            }
            .otherwise {
                bram.io.rdAddr := (layer_offset.asSInt + (stride_length * y + dy) * input_depth.asSInt * w + (stride_length * x + dx) * input_depth.asSInt + inCount.asSInt).asUInt
            }
                
            poolState := pool_find_max
        }
        is(pool_find_max) {
            when(bram.io.rdData > curMax) {
                curMax := bram.io.rdData
            }

            when (dx === filter_size - 1.S && dy === filter_size - 1.S) {
                dx := 0.S
                dy := 0.S                
                poolState := pool_write_output
            }
            .elsewhen (dx === filter_size - 1.S && dy < filter_size - 1.S) {
                dx := 0.S
                dy := dy + 1.S

                poolState := pool_in_addr_set
            }
            .otherwise {
                dx := dx + 1.S

                poolState := pool_in_addr_set
            }
        }
        is(pool_write_output) {
            when (layer(0) === 0.U) {                           // even layers
                bram.io.wrAddr := (y * input_depth.asSInt * output_depth.asSInt + x * input_depth.asSInt + inCount.asSInt + layer_offset.asSInt).asUInt
            }
            .otherwise {                                        // odd layers
                bram.io.wrAddr := (y * input_depth.asSInt * output_depth.asSInt + x * input_depth.asSInt + inCount.asSInt).asUInt
            }

            bram.io.wrEna := true.B
            bram.io.wrData := curMax
            curMax := abs_min.S

            when (x === output_depth.asSInt && y === output_depth.asSInt) {
                when (inCount < input_depth - 1.U) {
                    x := 0.S
                    y := 0.S
                    inCount := inCount + 1.U
                    poolState := pool_in_addr_set
                }
                .otherwise {
                    poolState := pool_done
                }
            }
            .elsewhen(x === output_depth.asSInt && y < output_depth.asSInt) {
                x := 0.S
                y := y + 1.S
                poolState := pool_in_addr_set
            }
            .otherwise {
                x := x + 1.S
                poolState := pool_in_addr_set
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