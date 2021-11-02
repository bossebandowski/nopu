/*
A CNN accelerator: https://github.com/bossebandowski/nopu
Author: Bosse Bandowski (bosse.bandowski@outlook.com)
*/

package cop

import chisel3._
import chisel3.util._
import patmos.Constants._
import util._
import ocp._

object CnnAccelerator extends CoprocessorObject {
    
  def init(params: Map[String, String]) = {}

  def create(params: Map[String, String]): CnnAccelerator = Module(new CnnAccelerator())
}

class CnnAccelerator() extends CoprocessorMemoryAccess() {

    // coprocessor function definitions
    val FUNC_RESET            = "b00000".U(5.W)   // reset coprocessor
    val FUNC_POLL             = "b00001".U(5.W)   // get processor status
    val FUNC_RUN              = "b00010".U(5.W)   // init inference
    val FUNC_GET_RES          = "b00100".U(5.W)   // read result register

    val FUNC_MEM_W            = "b00011".U(5.W)   // TEST: write a value into memory
    val FUNC_MEM_R            = "b00101".U(5.W)   // TEST: read a value from memory

    // states COP
    val idle :: start :: fc :: conv :: pool :: mem_w :: mem_r :: restart :: reset_memory :: next_layer :: layer_done :: read_output :: find_max :: save_max :: Nil = Enum(14)
    val fc_idle :: fc_done :: fc_init :: fc_load_input :: fc_load_weight :: fc_load_output :: fc_mac :: fc_write_output :: fc_load_bias :: fc_add_bias :: fc_apply_relu :: fc_write_bias :: Nil = Enum(12)
    val conv_idle :: conv_done :: conv_init :: conv_load_filter :: conv_load_input :: conv_apply_filter :: conv_write_output :: conv_load_bias :: conv_add_bias :: conv_apply_relu :: Nil = Enum(10)
    val memIdle :: memDone :: memReadReq :: memRead :: memWriteReq :: memWrite :: Nil = Enum(6)
    
    val stateReg = RegInit(0.U(8.W))
    val fcState = RegInit(0.U(8.W))
    val convState = RegInit(0.U(8.W))
    val poolState = RegInit(0.U(8.W))
    val memState = RegInit(memIdle)

    val outputUsage = RegInit(0.U(8.W))
    val layerType = RegInit(0.U(8.W))

    // state MEM

    // auxiliary registers
    val addrReg = RegInit(0.U(DATA_WIDTH.W))
    val resReg = RegInit(10.U(DATA_WIDTH.W))
    val mem_w_buffer = Reg(Vec(BURST_LENGTH, UInt(DATA_WIDTH.W)))
    val mem_r_buffer = Reg(Vec(BURST_LENGTH, UInt(DATA_WIDTH.W)))
    val burst_count_reg = RegInit(0.U(3.W))
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
    val layer_meta_i = Reg(Vec(num_layers, UInt(32.W)))         // point to intermediate results (nodes)
    val layer_meta_o = Reg(Vec(num_layers, UInt(32.W)))         // point to intermediate results (nodes)
    val layer_meta_s_i = Reg(Vec(num_layers, UInt(32.W)))       // layer sizes (for fc: number of nodes)
    val layer_meta_s_o = Reg(Vec(num_layers, UInt(32.W)))       // layer sizes (for fc: number of nodes)


    val ws = Reg(Vec(BURST_LENGTH, SInt(8.W)))
    val ins32 = Reg(Vec(BURST_LENGTH, SInt(32.W)))
    val outs = Reg(Vec(BURST_LENGTH, SInt(32.W)))
    val bs = Reg(Vec(BURST_LENGTH, SInt(32.W)))
    val idx = RegInit(0.U(DATA_WIDTH.W))
    val curMax = RegInit(-2147483646.S(DATA_WIDTH.W))

    val inCount = RegInit(0.U(32.W))
    val outCount = RegInit(0.U(32.W))

/* ================================================= CONSTANTS ============================================= */ 

    // address constants
    layer_meta_t(0) := fc
    layer_meta_t(1) := fc

    layer_meta_a(0) := fc_apply_relu
    layer_meta_a(1) := fc_write_bias

    layer_meta_w(0) := 1000000.U
    layer_meta_w(1) := 1100000.U

    layer_meta_b(0) := 1300000.U
    layer_meta_b(1) := 1310000.U

    layer_meta_i(0) := 40.U
    layer_meta_i(1) := 10000.U

    layer_meta_o(0) := 10000.U
    layer_meta_o(1) := 20000.U

    layer_meta_s_i(0) := 784.U
    layer_meta_s_i(1) := 100.U

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
                is(FUNC_MEM_W) {
                    when(isIdle) {
                        addrReg := io.copIn.opData(0)
                        mem_w_buffer(0) := io.copIn.opData(1)
                        stateReg := mem_w
                    }
                }
                is(FUNC_MEM_R) {
                    when(isIdle) {
                        addrReg := io.copIn.opData(0)
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
            layerType := layer_meta_t(0)                    // set first layer type 
            layer := 0.U                                    // reset layer count
            
            curMax := -2147483646.S                         // set curmax to low value
            idx := 0.U                                      // reset idx which is used to figure out the index of the maximum value in the output layer

            stateReg := next_layer                          // start inference by loading pixels
        }
        is (layer_done) {
            when (layer === num_layers.U - 1.U) {   // when we have reached the last layer
                stateReg := read_output             // get ready to extract max
                maxOut := 9.U                       // set number of output nodes (now only processing one at a time)
                outAddr := layer_meta_o(layer)      // set node address
                outCount := 0.U                     // reset node count
            }
            .otherwise {
                stateReg := next_layer                   // get ready to load nodes as inputs
                layer := layer + 1.U
            }
        }
        is (next_layer) {
            switch(layerType) {
                is (fc) {
                    stateReg := fc
                    fcState := fc_init
                }
                is (conv) {
                    stateReg := conv
                    convState := conv_init
                }
            }
        }
        is(fc) {
            when(fcState === fc_done) {
                stateReg := layer_done
                fcState := fc_idle
            }
        }
        is(conv) {
            when(convState === conv_done) {
                stateReg := layer_done
                convState := conv_idle
            }
        }
        is(read_output) {
            /*
            read a single node from the output layer
            */
            when (memState === memIdle) {                   // wait for memory to be ready
                addrReg := outAddr                          // set node address
                memState := memReadReq                      // force memory in ready request state
            }

            when (memState === memDone) {                   // wait until the transaction is finished
                memState := memIdle                         // mark memory as idle
                stateReg := find_max                    // continue to next state in max extract loop
                outs(0) := mem_r_buffer(0).asSInt           // get first output from memory read buffer
            }
        }
        is(find_max) {
            /*
            check if the current output node is larger than all previously seen ones
            */
            outAddr := outAddr + 4.U                        // increment node address by 1 word
            outCount := outCount + 1.U                      // increment node count by 1

            when (outs(0) > curMax) {                       // if output nodes is larger than current maximum output
                curMax := outs(0)                           // update current maximum output
                idx := outCount                             // update index of maximum output
            }

            when (outCount === maxOut) {                    // when we have cycled though all output nodes, exit loop                       
                stateReg := save_max
            }
            .otherwise {                                    // otherwise continue with next
                stateReg := read_output
            }
        }
        is(save_max) {
            /*
            copy index of maximum output into result register and set cop to idle (process finished)
            */
            resReg := idx
            stateReg := reset_memory

            // prepare for clearing intermediate nodes
            mem_w_buffer := Seq(0.U, 0.U, 0.U, 0.U)
            layer := 0.U
            inCount := 0.U
            inAddr := layer_meta_o(0)
            maxIn := layer_meta_s_o(0)
        }
        is(reset_memory) {
            /*
            reset network nodes to 0 to be ready for next inference
            */
            when (memState === memIdle) {                   // wait until memory is ready
                when (inCount >= maxIn) {                   // when done with current layer, transition to next
                    when (layer < num_layers.U - 1.U) {       // iterate through all intermediate layer
                        layer := layer + 1.U                // increment layer
                        maxIn := layer_meta_s_o(layer + 1.U)  // get layer size 
                        inCount := 0.U                      // reset count
                        inAddr := layer_meta_o(layer + 1.U) // update address space
                    }
                    .otherwise {
                        stateReg := idle                    // when done with last layer, quit
                    }
                }
                .otherwise {                                // overwrite layer memory segment with 0s
                    inCount := inCount + 4.U
                    inAddr := inAddr + 16.U
                    addrReg := inAddr
                    memState := memWriteReq
                }
            }

            when (memState === memDone) {                   // wait until transition is finished
                memState := memIdle                         // mark memory as idle
            }
        }
        is(mem_w) {
            when (memState === memIdle) {
                memState := memWriteReq
            }

            when (memState === memDone) {
                memState := memIdle
                stateReg := idle
            }
        }
        is(mem_r) {
            when (memState === memIdle) {
                memState := memReadReq
            }

            when (memState === memDone) {
                memState := memIdle
                stateReg := idle
                resReg := mem_r_buffer(0)
            }
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
            outAddr := layer_meta_o(layer)                      // set first node address
            inAddr := layer_meta_i(layer)
            inCount := 0.U                                  // reset input count
            outCount := 0.U                                 // reset output count
            outputUsage := fc_mac                           // tell the load_output state where to transition next (mac or bias_add loop)
            fcState := fc_load_input
            maxIn := layer_meta_s_i(layer) - 1.U          // in input layer, we increment by 1
            maxOut := layer_meta_s_o(layer) - 4.U   // in output layer, we increment by 4
        }
        is(fc_load_input) {
            /*
            Read one burst of inputs and only keep the first one.
            For full word inputs only
            */

            when (memState === memIdle) {                   // wait until memory is ready
                addrReg := inAddr                           // load current input address
                memState := memReadReq                      // force memory into read request state
            }

            when (memState === memDone) {                   // wait until transaction is finished
                memState := memIdle                         // mark memory as idle
                fcState := fc_load_weight                        // load weights next
                ins32(0) := mem_r_buffer(0).asSInt          // load first input from read buffer (and discard the rest for now)
            }
        }
        is(fc_load_weight) {
            /*
            Read one burst of weights of 4 words. If weights are stored as full words, then this will amount to 4 weights
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

            }
        }
        is(fc_load_output) {
            /*
            Load intermediate network nodes. Since the activations are 32 bits, a single burst returns
            4 intermediate network nodes. 
            */
            when (memState === memIdle) {                   // wait until memory is ready
                addrReg := outAddr                          // load current output address
                memState := memReadReq                      // force memory into read request state
            }

            when (memState === memDone) {                   // wait until transaction is finished
                memState := memIdle                         // mark memory as idle
                fcState := outputUsage                     // next state could be mac or bias add, depending on progress

                outs(0) := mem_r_buffer(0).asSInt           // load 4 outputs from read buffer
                outs(1) := mem_r_buffer(1).asSInt
                outs(2) := mem_r_buffer(2).asSInt
                outs(3) := mem_r_buffer(3).asSInt
            }      
        }
        is(fc_mac) {
            /*
            Do the multiply-accumulate ops. One input to four outputs.
            */
            outs(0) := outs(0) + (ins32(0) * ws(0))
            outs(1) := outs(1) + (ins32(0) * ws(1))
            outs(2) := outs(2) + (ins32(0) * ws(2))
            outs(3) := outs(3) + (ins32(0) * ws(3))

            fcState := fc_write_output                           // next state will be output writeback

        }
        is(fc_write_output) {
            /*
            write result back to memory (this could potentially be moved to on-chip memory).
            Further, defines how to continue based on which inputs and outputs have just been processed
            */

            when (memState === memIdle) {                   // wait until memory is ready
                mem_w_buffer(0) := outs(0).asUInt           // load outputs into write buffer
                mem_w_buffer(1) := outs(1).asUInt
                mem_w_buffer(2) := outs(2).asUInt
                mem_w_buffer(3) := outs(3).asUInt

                addrReg := outAddr                          // make sure that the right output address is still in addrReg
                memState := memWriteReq                     // force memory into write request state
            }

            when (memState === memDone) {                   // wait until transition is finished
                memState := memIdle                         // mark memory as idle

                // transitions
                when (inCount === maxIn && outCount === maxOut) {
                    /*
                    when all inputs have been processed and added to all outputs, the mac loop of the current layer is done.
                    Continue by resetting counts and addresses and move on to adding biases
                    */
                    outCount := 0.U                         // reset output count
                    fcState := fc_load_output                 // next state will be output load
                    outputUsage := fc_load_bias                   // after that, transition to bias load
                    outAddr := layer_meta_o(layer)
                }
                .elsewhen(inCount < maxIn && outCount === maxOut) {
                    /*
                    when the current input has been mac'ed to all outputs, continue to the next input
                    */
                    outCount := 0.U                         // reset output count
                    inCount := inCount + 1.U                // increment input count
                    inAddr := inAddr + 4.U                  // increment input address by one word
                    fcState := fc_load_input               // in the future, the input layer will map to a different loop because the inputs are less wide
                    weightAddr := weightAddr + 4.U          // increment weight address by four bytes
                    outAddr := layer_meta_o(layer)         
                }
                .otherwise {
                    /*
                    when the current input has not yet been mac'ed to all outputs, just continue with the next 4...
                    */
                    outCount := outCount + 4.U              // increment node count by 4
                    outAddr := outAddr + 16.U               // increment node address by 4 words
                    weightAddr := weightAddr + 4.U          // increment weight address by 4 bytes
                    fcState := fc_load_weight                    // transition to load weight (same input, different outputs, different weights)
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

            relu(0) := bs(0) + outs(0)
            relu(1) := bs(1) + outs(1)
            relu(2) := bs(2) + outs(2)
            relu(3) := bs(3) + outs(3)

            fcState := layer_meta_a(layer)
        }
        is(fc_apply_relu) {
            /*
            finish relu activation by setting negative nodes equal to 0
            */
            when (relu(0) < 0.S) {
                relu(0) := 0.S
            }
            when (relu(1) < 0.S) {
                relu(1) := 0.S
            }
            when (relu(2) < 0.S) {
                relu(2) := 0.S
            }
            when (relu(3) < 0.S) {
                relu(3) := 0.S
            }

            fcState := fc_write_bias                                // get ready to write back outputs
        }
        is(fc_write_bias) {
            /*
            write results of bias_add + activation back to memory. Do some state transition magic
            */
            when (memState === memIdle) {                   // wait for memory to be ready
                mem_w_buffer(0) := relu(0).asUInt           // load outputs into write buffer
                mem_w_buffer(1) := relu(1).asUInt
                mem_w_buffer(2) := relu(2).asUInt
                mem_w_buffer(3) := relu(3).asUInt

                addrReg := outAddr                          // set output address
                memState := memWriteReq                     // request write transaction
            }
            when (memState === memDone) {                   // wait until transaction is finished
                memState := memIdle                         // mark memory as idle

                when (outCount < maxOut) {                  // when there are still biases to be added
                    outCount := outCount + 4.U              // increment node count by 4
                    outAddr := outAddr + 16.U               // increment node address by 4 words
                    biasAddr := biasAddr + 16.U             // increment bias address by 4 words
                    fcState := fc_load_output                 // continue with loading the next 4 nodes
                }
                .otherwise {
                    fcState := fc_done             // get ready to extract max
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